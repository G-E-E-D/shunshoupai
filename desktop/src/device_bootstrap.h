#pragma once
#include <QByteArray>
#include <QHostAddress>
#include <QRandomGenerator>
#include <QUrl>
#include <array>

inline QByteArray newDeviceBootstrapKey() {
    std::array<quint32, 8> random{};
    QRandomGenerator::system()->fillRange(random.data(), random.size());
    return QByteArray(reinterpret_cast<const char *>(random.data()), sizeof(random))
        .toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals);
}

// This only restricts where a separately supplied secret may be sent. Being a
// loopback URL is never itself authorization to read or create a device.
inline bool isDeviceBootstrapOrigin(const QString &origin) {
    const QUrl url(origin, QUrl::StrictMode);
    return url.isValid() && (url.scheme() == "http" || url.scheme() == "https")
        && url.userInfo().isEmpty() && url.query().isEmpty() && url.fragment().isEmpty()
        && (url.path().isEmpty() || url.path() == "/")
        && QHostAddress(url.host()).isLoopback();
}
