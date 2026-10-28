#include "java_capture_bridge.h"
#include <QCoreApplication>
#include <QFileInfo>
#include <QJsonDocument>
#include <QProcessEnvironment>
#include <QTimer>
#ifdef Q_OS_WIN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

JavaCaptureBridge::JavaCaptureBridge(QObject *parent) : QObject(parent) {
    connect(&process, &QProcess::started, this, [this] { if (started) started(); });
    process.setStandardErrorFile(QProcess::nullDevice());
#ifdef Q_OS_WIN
    process.setCreateProcessArgumentsModifier([](QProcess::CreateProcessArguments *args) { args->flags |= CREATE_NO_WINDOW; });
#endif
    connect(&process, &QProcess::readyReadStandardOutput, this, [this] {
        buffer += process.readAllStandardOutput();
        if (buffer.size() > 512 * 1024) { stop(); if (failed) failed(QStringLiteral("截图组件返回了过长的数据，请重新打开截图。")); return; }
        while (buffer.contains('\n')) {
            const auto end = buffer.indexOf('\n'); const auto line = buffer.left(end).trimmed(); buffer.remove(0, end + 1);
            if (line.isEmpty()) continue;
            const auto document = QJsonDocument::fromJson(line);
            if (!document.isObject() || !document.object().value("type").isString()) {
                stop(); if (failed) failed(QStringLiteral("截图组件响应无效，请检查安装文件。")); return;
            }
            if (received) received(document.object());
        }
    });
    connect(&process, &QProcess::errorOccurred, this, [this](QProcess::ProcessError error) {
        if (error == QProcess::FailedToStart && failed) failed(QStringLiteral("Java 截图组件未能启动，请检查安装文件。"));
    });
    connect(&process, &QProcess::finished, this, [this](int, QProcess::ExitStatus) {
        buffer.clear(); if (failed) failed(QStringLiteral("截图组件已退出，未确认文字仍在本机保留，可重新打开。"));
    });
}
JavaCaptureBridge::~JavaCaptureBridge() { started = {}; received = {}; failed = {}; stop(); }
bool JavaCaptureBridge::running() const { return process.state() != QProcess::NotRunning; }
bool JavaCaptureBridge::start(const QStringList &extraArguments, const QString &isolatedData) {
    if (running()) return true;
    const auto executable = QCoreApplication::applicationDirPath() + "/capture-java/ShunshoupaiCapture.exe";
    if (!QFileInfo::exists(executable)) { if (failed) failed(QStringLiteral("未找到 Java 截图组件，请使用完整安装包；本机日程仍可使用。")); return false; }
    const auto inherited = QProcessEnvironment::systemEnvironment();
    QProcessEnvironment environment;
    // This UI helper does not need server bootstrap secrets, model keys or
    // developer credentials from the host process environment.
    for (const auto &name : {"SystemRoot", "WINDIR", "PATH", "LOCALAPPDATA", "APPDATA", "USERPROFILE", "TEMP", "TMP", "COMSPEC", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE"}) {
        if (inherited.contains(name)) environment.insert(name, inherited.value(name));
    }
    if (!isolatedData.isEmpty()) environment.insert("LOCALAPPDATA", isolatedData);
    process.setProcessEnvironment(environment);
    QStringList arguments{"--ipc"}; arguments.append(extraArguments);
    buffer.clear(); process.start(executable, arguments); return true;
}
void JavaCaptureBridge::send(const QJsonObject &message) {
    if (running()) process.write(QJsonDocument(message).toJson(QJsonDocument::Compact) + '\n');
}
void JavaCaptureBridge::stop() {
    if (!running()) return;
    send({{"type", "shutdown"}}); process.closeWriteChannel();
    if (!process.waitForFinished(1200)) { process.kill(); process.waitForFinished(1000); }
    buffer.clear();
}
