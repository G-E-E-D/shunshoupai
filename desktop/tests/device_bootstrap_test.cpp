#include "api.h"
#include <QCoreApplication>
#include <QRegularExpression>
#include <QTcpServer>
#include <QTcpSocket>
#include <iostream>
#include <memory>
#include <stdexcept>

namespace {
void require(bool condition, const char *message) { if (!condition) throw std::runtime_error(message); }
QByteArray header(const QByteArray &request, const QByteArray &name) {
    for (const auto &line : request.split('\n')) {
        const auto separator = line.indexOf(':');
        if (separator >= 0 && line.left(separator).trimmed().toLower() == name.toLower()) return line.mid(separator + 1).trimmed();
    }
    return {};
}
void serve(QTcpServer &server, QList<QByteArray> &requests, QString &redirect) {
    QObject::connect(&server, &QTcpServer::newConnection, &server, [&server, &requests, &redirect] {
        auto *socket = server.nextPendingConnection();
        auto received = std::make_shared<QByteArray>();
        QObject::connect(socket, &QTcpSocket::readyRead, socket, [socket, received, &requests, &redirect] {
            received->append(socket->readAll());
            if (!received->contains("\r\n\r\n")) return;
            requests.append(*received);
            const QByteArray response = redirect.isEmpty() ? QByteArray("HTTP/1.1 200 OK\r\n")
                : QByteArray("HTTP/1.1 302 Found\r\nLocation: ") + redirect.toUtf8() + "\r\n";
            socket->write(response + "Content-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");
            socket->disconnectFromHost();
            QObject::disconnect(socket, &QTcpSocket::readyRead, socket, nullptr);
        });
        QObject::connect(socket, &QTcpSocket::disconnected, socket, &QTcpSocket::deleteLater);
    });
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    try {
        const auto key = newDeviceBootstrapKey();
        require(QRegularExpression("^[A-Za-z0-9_-]{43}$").match(QString::fromLatin1(key)).hasMatch(), "bootstrap key must contain 256 bits in base64url form");
        require(newDeviceBootstrapKey() != key, "bootstrap key reused across launches");
        require(isDeviceBootstrapOrigin("http://127.0.0.1:43127") && isDeviceBootstrapOrigin("http://[::1]:43127"), "literal loopback not accepted");
        for (const auto &address : {"http://localhost:43127", "http://192.168.1.20:43127", "https://example.com", "http://127.0.0.1@remote.test"})
            require(!isDeviceBootstrapOrigin(address), "bootstrap secret must not be sent to a network or ambiguous host");
        QTcpServer first, second;
        require(first.listen(QHostAddress::LocalHost) && second.listen(QHostAddress::LocalHost), "test listeners unavailable");
        const QString firstOrigin = "http://127.0.0.1:" + QString::number(first.serverPort());
        const QString secondOrigin = "http://127.0.0.1:" + QString::number(second.serverPort());
        QList<QByteArray> firstRequests, secondRequests;
        QString firstRedirect, secondRedirect;
        serve(first, firstRequests, firstRedirect); serve(second, secondRequests, secondRedirect);
        ApiClient local(firstOrigin, false, key);
        require(local.canBootstrap() && local.call("POST", "/api/devices/bootstrap").ok(), "owned backend bootstrap failed");
        require(header(firstRequests.last(), "X-Device-Bootstrap") == key, "bootstrap request omitted secret");
        require(local.call("GET", "/api/auth/session").ok(), "normal session request failed");
        require(header(firstRequests.last(), "X-Device-Bootstrap").isEmpty(), "secret leaked to an ordinary API");
        require(local.call("GET", "/api/devices/bootstrap").ok(), "non-bootstrap method request failed");
        require(header(firstRequests.last(), "X-Device-Bootstrap").isEmpty(), "secret leaked to another HTTP method");
        local.reset(secondOrigin);
        require(!local.canBootstrap() && local.call("POST", "/api/devices/bootstrap").ok(), "untrusted origin request failed");
        require(header(secondRequests.last(), "X-Device-Bootstrap").isEmpty(), "secret crossed backend ports");
        const auto remoteCount = secondRequests.size();
        local.reset(firstOrigin); firstRedirect = secondOrigin + "/api/devices/bootstrap";
        require(local.call("POST", "/api/devices/bootstrap").status == 302, "bootstrap redirect was followed");
        require(secondRequests.size() == remoteCount, "bootstrap secret followed a redirect");
        ApiClient unconfigured(firstOrigin);
        require(!unconfigured.canBootstrap(), "loopback or user-agent alone enabled bootstrap");
        ApiClient remote("http://192.168.1.20:43127", false, key);
        require(!remote.canBootstrap(), "network origin accepted bootstrap key");
        std::cout << "PASS: per-launch random bootstrap, literal-loopback binding, no ordinary-request/origin/port/redirect leakage\n";
        return 0;
    } catch (const std::exception &error) { std::cerr << error.what() << '\n'; return 1; }
}
