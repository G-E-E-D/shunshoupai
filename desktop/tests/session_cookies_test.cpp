#include "session_cookies.h"
#include <QCoreApplication>
#include <QDateTime>
#include <QFile>
#include <QNetworkCookie>
#include <QProcess>
#include <QTemporaryDir>
#include <iostream>
#include <stdexcept>

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    try {
        if (app.arguments().size() == 3 && app.arguments().at(1) == "--restore-session") {
            SessionCookieJar child("http://127.0.0.1:43127", nullptr, app.arguments().at(2));
            const auto cookies = child.cookiesForUrl(QUrl("http://127.0.0.1:43127"));
            require(cookies.size() == 1 && cookies.at(0).value() == "private-test-token-never-store-in-plaintext", "fresh process did not restore session");
            return 0;
        }
        QTemporaryDir directory;
        require(directory.isValid(), "temporary directory unavailable");
        const QUrl source("http://127.0.0.1:43127");
        QNetworkCookie auth("shunshoupai_session", "private-test-token-never-store-in-plaintext");
        auth.setPath("/"); auth.setHttpOnly(true);
        auth.setExpirationDate(QDateTime::currentDateTimeUtc().addDays(30));
        QString file;
        {
            SessionCookieJar first(source.toString(), nullptr, directory.path());
            require(first.setCookiesFromUrl({auth}, source), "valid login cookie rejected");
            require(first.cookiesForUrl(source).size() == 1, "login cookie missing");
            require(!first.setCookiesFromUrl({auth}, QUrl("http://127.0.0.1:43128")), "cookie from another port accepted");
            file = first.storagePath();
        }
#ifdef Q_OS_WIN
        QFile encrypted(file);
        require(encrypted.open(QIODevice::ReadOnly), "encrypted file missing");
        const auto stored = encrypted.readAll(); encrypted.close();
        require(!stored.isEmpty() && !stored.contains(auth.value()), "credential file is plaintext");
        QProcess child;
        child.start(app.applicationFilePath(), {"--restore-session", directory.path()});
        require(child.waitForFinished(10000) && child.exitStatus() == QProcess::NormalExit && child.exitCode() == 0, "new process failed to restore encrypted session");
        {
            SessionCookieJar restarted(source.toString(), nullptr, directory.path());
            require(restarted.cookiesForUrl(source).size() == 1, "login not restored on restart");
            require(restarted.cookiesForUrl(source).at(0).value() == auth.value(), "restored token changed");
            SessionCookieJar other("http://127.0.0.1:43128", nullptr, directory.path());
            require(other.cookiesForUrl(QUrl("http://127.0.0.1:43128")).isEmpty(), "different backend inherited login");
            require(QFile::copy(file, other.storagePath()), "cannot arrange cross-origin copy test");
            SessionCookieJar tampered("http://127.0.0.1:43128", nullptr, directory.path());
            require(tampered.cookiesForUrl(QUrl("http://127.0.0.1:43128")).isEmpty(), "copied credential crossed origins");
            restarted.clear();
        }
        SessionCookieJar loggedOut(source.toString(), nullptr, directory.path());
        require(loggedOut.cookiesForUrl(source).isEmpty() && !QFile::exists(file), "logout survived restart");
        QNetworkCookie ephemeral("temporary", "session-only"); ephemeral.setPath("/");
        require(loggedOut.setCookiesFromUrl({ephemeral}, source), "temporary cookie rejected");
        SessionCookieJar noSessionCookie(source.toString(), nullptr, directory.path());
        require(noSessionCookie.cookiesForUrl(source).isEmpty(), "session-only cookie persisted");
        loggedOut.setCookiesFromUrl({auth}, source);
        auth.setExpirationDate(QDateTime::currentDateTimeUtc().addDays(-1));
        loggedOut.setCookiesFromUrl({auth}, source);
        SessionCookieJar expired(source.toString(), nullptr, directory.path());
        require(expired.cookiesForUrl(source).isEmpty(), "expired cookie restored");
        QFile damaged(file); require(damaged.open(QIODevice::WriteOnly), "cannot arrange corruption test");
        damaged.write("damaged-file"); damaged.close();
        SessionCookieJar corrupted(source.toString(), nullptr, directory.path());
        require(corrupted.cookiesForUrl(source).isEmpty(), "corrupt credential was accepted");
#endif
        std::cout << "PASS: encrypted restart, per-origin isolation, expiry, corruption and logout\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
