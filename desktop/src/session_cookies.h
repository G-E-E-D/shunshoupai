#pragma once
#include <QNetworkCookieJar>
#include <QUrl>

// Persistent cookies belong to one complete service origin, including its port.
// Windows DPAPI protects the file for the signed-in Windows user. Other systems
// keep an in-memory jar until a platform credential store is implemented.
class SessionCookieJar final : public QNetworkCookieJar {
    QUrl service;
    QString filename;
    bool restoring = false;
    bool persist();
    void restore();
public:
    explicit SessionCookieJar(const QString &origin, QObject *parent = nullptr,
                              const QString &directory = {});
    bool setCookiesFromUrl(const QList<QNetworkCookie> &cookies, const QUrl &url) override;
    void clear();
    QString storagePath() const { return filename; }
};
