#pragma once
#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QProcess>
#include <QProcessEnvironment>
#include <QTcpServer>
#ifdef Q_OS_WIN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

inline QString appDataDirectory() {
#ifdef Q_OS_WIN
    return qEnvironmentVariable("LOCALAPPDATA") + "/Shunshoupai";
#else
    return QDir::homePath() + "/.local/share/Shunshoupai";
#endif
}

class BackendHost {
    QProcess process;
#ifdef Q_OS_WIN
    HANDLE job = nullptr;
#endif
public:
    QString start() {
        const auto root = QCoreApplication::applicationDirPath();
        const auto node = root + "/runtime/node.exe";
        const auto server = root + "/resources/backend/server.js";
        if (!QFileInfo::exists(node) || !QFileInfo::exists(server))
            return QStringLiteral("未找到内置服务。源码运行请使用 --backend http://电脑地址:端口；安装版请重新安装。");
        QTcpServer portCheck;
        if (!portCheck.listen(QHostAddress::AnyIPv4, 43127))
            return QStringLiteral("端口 43127 已被占用。请关闭占用程序后重试，或使用 --backend 连接已有服务。");
        portCheck.close();
        QDir().mkpath(appDataDirectory() + "/data");
        auto env = QProcessEnvironment::systemEnvironment();
        env.insert("NODE_ENV", "production");
        env.insert("HOSTNAME", "0.0.0.0");
        env.insert("PORT", "43127");
        env.insert("APP_STORAGE", "local");
        env.insert("APP_DB_PATH", appDataDirectory() + "/data/app.sqlite");
        env.insert("APP_ORIGIN", "");
        env.insert("COOKIE_SECURE", "false");
        process.setProcessEnvironment(env);
        process.setWorkingDirectory(appDataDirectory());
        // No request text, cookies or model credentials are written to a log.
        process.setStandardOutputFile(QProcess::nullDevice());
        process.setStandardErrorFile(QProcess::nullDevice());
#ifdef Q_OS_WIN
        process.setCreateProcessArgumentsModifier([](QProcess::CreateProcessArguments *args) {
            args->flags |= CREATE_NO_WINDOW;
        });
#endif
        process.start(node, {server});
        if (!process.waitForStarted(8000)) return QStringLiteral("内置日程服务未能启动，请检查安装文件。");
#ifdef Q_OS_WIN
        job = CreateJobObjectW(nullptr, nullptr);
        if (job) {
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION info{};
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            SetInformationJobObject(job, JobObjectExtendedLimitInformation, &info, sizeof(info));
            HANDLE child = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, FALSE, DWORD(process.processId()));
            if (child) { AssignProcessToJobObject(job, child); CloseHandle(child); }
        }
#endif
        return {};
    }
    ~BackendHost() {
        if (process.state() != QProcess::NotRunning) {
            process.terminate();
            if (!process.waitForFinished(2000)) { process.kill(); process.waitForFinished(2000); }
        }
#ifdef Q_OS_WIN
        if (job) CloseHandle(job);
#endif
    }
};
