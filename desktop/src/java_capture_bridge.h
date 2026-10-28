#pragma once
#include <QJsonObject>
#include <QObject>
#include <QProcess>
#include <functional>

// Host adapter only. Screen capture, hotkeys, floating UI and OCR live in Java.
class JavaCaptureBridge final : public QObject {
    QProcess process;
    QByteArray buffer;
public:
    std::function<void(const QJsonObject &)> received;
    std::function<void()> started;
    std::function<void(const QString &)> failed;
    explicit JavaCaptureBridge(QObject *parent = nullptr);
    ~JavaCaptureBridge() override;
    bool start(const QStringList &extraArguments = {}, const QString &isolatedData = {});
    bool running() const;
    void send(const QJsonObject &message);
    void stop();
};
