#pragma once
#include "session_cookies.h"
#include "device_bootstrap.h"
#include <QEventLoop>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkCookieJar>
#include <QNetworkReply>
#include <QTimer>
#include <QUrl>

struct ApiResult {
    QJsonObject data;
    QString error;
    int status = 0;
    bool ok() const { return error.isEmpty(); }
};

class ApiClient {
    QNetworkAccessManager manager;
    bool working = false;
    bool rememberSession = false;
    QByteArray bootstrapKey;
    QString bootstrapOrigin;
public:
    QString origin;
    explicit ApiClient(QString base, bool remember = false, QByteArray localBootstrapKey = {})
        : rememberSession(remember), origin(std::move(base)) {
        if (!localBootstrapKey.isEmpty() && isDeviceBootstrapOrigin(origin)) {
            bootstrapKey = std::move(localBootstrapKey);
            bootstrapOrigin = origin;
        }
        reset(origin);
    }
    bool busy() const { return working; }
    bool canBootstrap() const { return !bootstrapKey.isEmpty() && origin == bootstrapOrigin; }
    void reset(const QString &base) {
        origin = base;
        manager.setCookieJar(rememberSession ? new SessionCookieJar(origin, &manager) : new QNetworkCookieJar(&manager));
    }
    void forgetSession() {
        if (auto *jar = dynamic_cast<SessionCookieJar *>(manager.cookieJar())) jar->clear();
        else manager.setCookieJar(new QNetworkCookieJar(&manager));
    }
    ApiResult call(const QString &method, const QString &path, const QJsonObject &body = {}) {
        if (working) return {{}, QStringLiteral("上一项请求尚未完成，请稍候。"), 0};
        working = true;
        QNetworkRequest request{QUrl(origin + path)};
        request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
        request.setRawHeader("Origin", origin.toUtf8());
        request.setRawHeader("User-Agent", "ShunshoupaiQt/" SHUNSHOUPAI_VERSION);
        if (canBootstrap() && method == "POST" && path == "/api/devices/bootstrap")
            request.setRawHeader("X-Device-Bootstrap", bootstrapKey);
        request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
        request.setTransferTimeout(35000);
        auto *reply = manager.sendCustomRequest(request, method.toUtf8(), method == "GET" ? QByteArray{} : QJsonDocument(body).toJson(QJsonDocument::Compact));
        QEventLoop loop;
        QTimer timer;
        timer.setSingleShot(true);
        bool timedOut = false;
        QObject::connect(&timer, &QTimer::timeout, reply, [&] { timedOut = true; reply->abort(); });
        QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
        timer.start(36000);
        loop.exec(QEventLoop::ExcludeUserInputEvents);
        timer.stop();
        ApiResult result;
        result.status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const auto bytes = reply->readAll();
        const auto document = QJsonDocument::fromJson(bytes);
        result.data = document.object();
        if (timedOut || result.status == 0)
            result.error = QStringLiteral("网络请求未能确认结果。若正在保存，请保留原请求重试，或刷新日程核实。");
        else if (result.status < 200 || result.status >= 300)
            result.error = result.data.value("error").toObject().value("message").toString(QStringLiteral("服务拒绝了请求，请刷新后重试。"));
        else if (!document.isObject()) result.error = QStringLiteral("服务返回了无法识别的数据。");
        reply->deleteLater();
        working = false;
        return result;
    }
};
