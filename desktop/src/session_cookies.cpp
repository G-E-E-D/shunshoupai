#include "session_cookies.h"
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkCookie>
#include <QSaveFile>
#ifdef Q_OS_WIN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <dpapi.h>
#endif

namespace {
QByteArray protect(const QByteArray &input, const QByteArray &origin, bool encrypt) {
#ifdef Q_OS_WIN
    DATA_BLOB source{DWORD(input.size()), reinterpret_cast<BYTE *>(const_cast<char *>(input.constData()))};
    DATA_BLOB entropy{DWORD(origin.size()), reinterpret_cast<BYTE *>(const_cast<char *>(origin.constData()))};
    DATA_BLOB output{};
    const bool ok = encrypt
        ? CryptProtectData(&source, L"Shunshoupai session", &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output)
        : CryptUnprotectData(&source, nullptr, &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output);
    if (!ok) return {};
    QByteArray result(reinterpret_cast<const char *>(output.pbData), output.cbData);
    SecureZeroMemory(output.pbData, output.cbData);
    LocalFree(output.pbData);
    return result;
#else
    Q_UNUSED(input); Q_UNUSED(origin); Q_UNUSED(encrypt);
    return {}; // Never fall back to plaintext credential storage.
#endif
}
QUrl originUrl(const QString &origin) {
    QUrl url(origin);
    url.setPath(QString{}); url.setQuery(QString{}); url.setFragment(QString{});
    if ((url.scheme() == "http" && url.port() == 80) || (url.scheme() == "https" && url.port() == 443)) url.setPort(-1);
    return url;
}
}

SessionCookieJar::SessionCookieJar(const QString &origin, QObject *parent, const QString &directory)
    : QNetworkCookieJar(parent), service(originUrl(origin)) {
#ifdef Q_OS_WIN
    const QString root = directory.isEmpty() ? qEnvironmentVariable("LOCALAPPDATA") + "/Shunshoupai/sessions" : directory;
    if (!directory.isEmpty() || !qEnvironmentVariable("LOCALAPPDATA").isEmpty()) {
        const auto key = QCryptographicHash::hash(service.toEncoded(), QCryptographicHash::Sha256).toHex();
        filename = QDir(root).filePath(QString::fromLatin1(key) + ".bin");
        restore();
    }
#else
    Q_UNUSED(directory);
#endif
}

void SessionCookieJar::restore() {
    QFile file(filename);
    if (!file.open(QIODevice::ReadOnly) || file.size() > 1024 * 1024) return;
    const auto decoded = protect(file.readAll(), service.toEncoded(), false);
    const auto data = QJsonDocument::fromJson(decoded).object();
    if (data.value("origin").toString() != service.toString()) return;
    restoring = true;
    for (const auto &value : data.value("cookies").toArray()) {
        for (const auto &cookie : QNetworkCookie::parseCookies(QByteArray::fromBase64(value.toString().toLatin1()))) {
            if (!cookie.isSessionCookie() && cookie.expirationDate() > QDateTime::currentDateTimeUtc())
                QNetworkCookieJar::setCookiesFromUrl({cookie}, service);
        }
    }
    restoring = false;
}

bool SessionCookieJar::persist() {
    if (filename.isEmpty()) return false;
    QJsonArray cookies;
    for (const auto &cookie : allCookies()) {
        if (!cookie.isSessionCookie() && cookie.expirationDate() > QDateTime::currentDateTimeUtc())
            cookies.append(QString::fromLatin1(cookie.toRawForm().toBase64()));
    }
    if (cookies.isEmpty()) { QFile::remove(filename); return true; }
    const auto data = QJsonDocument(QJsonObject{{"origin", service.toString()}, {"cookies", cookies}}).toJson(QJsonDocument::Compact);
    const auto encrypted = protect(data, service.toEncoded(), true);
    if (encrypted.isEmpty() || !QDir().mkpath(QFileInfo(filename).absolutePath())) return false;
    QSaveFile file(filename);
    if (!file.open(QIODevice::WriteOnly)) return false;
    file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    if (file.write(encrypted) != encrypted.size()) return false;
    return file.commit();
}

bool SessionCookieJar::setCookiesFromUrl(const QList<QNetworkCookie> &cookies, const QUrl &url) {
    if (originUrl(url.toString()) != service) return false;
    const bool accepted = QNetworkCookieJar::setCookiesFromUrl(cookies, url);
    // Qt may return false for an expired Set-Cookie even when it removed a
    // matching cookie. Persist deletions as well as newly accepted cookies.
    if (!restoring) persist();
    return accepted;
}

void SessionCookieJar::clear() {
    setAllCookies({});
    if (!filename.isEmpty()) QFile::remove(filename);
}
