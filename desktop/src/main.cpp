#include "backend.h"
#include "window.h"
#include "design.h"
#include "connection.h"
#include <QApplication>
#include <QCommandLineParser>
#include <QElapsedTimer>
#include <QLockFile>
#include <QMessageBox>
#include <QPalette>
#include <QSettings>
#include <QThread>
#include <QTemporaryDir>
#include <QTimer>
#include <memory>

int main(int argc, char **argv) {
    QApplication app(argc, argv);
    Design::apply(app);
    QCoreApplication::setOrganizationName("G-E-E-D");
    QCoreApplication::setApplicationName("Shunshoupai");
    QCoreApplication::setApplicationVersion(QStringLiteral(SHUNSHOUPAI_VERSION));
    QCommandLineParser parser;
    parser.setApplicationDescription(QString::fromUtf8("顺手排 · 原生 C++ / Qt 桌面客户端"));
    parser.addHelpOption(); parser.addVersionOption();
    parser.addOption({"backend", "Connect an existing backend instead of starting the bundled one.", "url"});
    parser.addOption({"smoke-test", "Exercise the native client against an isolated test backend."});
    parser.addOption({"visual-smoke", "Exercise native dialogs and save rendered UI evidence.", "directory"});
    parser.addOption({"screenshot", "Save the native widget screenshot during --smoke-test.", "path"});
    parser.process(app);
    const bool smoke = parser.isSet("smoke-test") || parser.isSet("visual-smoke");
    if (smoke && !parser.isSet("backend")) return 4;
    std::unique_ptr<QTemporaryDir> smokeSettings;
    if (smoke) {
        smokeSettings = std::make_unique<QTemporaryDir>();
        if (!smokeSettings->isValid()) return 5;
        QSettings::setDefaultFormat(QSettings::IniFormat);
        QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, smokeSettings->path());
    }
    QDir().mkpath(appDataDirectory());
    QLockFile lock(appDataDirectory() + "/desktop.lock");
    if (!smoke && !lock.tryLock(50)) {
        QMessageBox::information(nullptr, QString::fromUtf8("顺手排"), QString::fromUtf8("顺手排已经在运行。请打开已启动的窗口。"));
        return 1;
    }
    BackendHost backend;
    QSettings settings;
    QString origin = parser.value("backend");
    if (origin.isEmpty()) {
        origin = settings.value("service_origin", "http://127.0.0.1:43127").toString();
        if (origin == "http://127.0.0.1:43127") {
            const auto error = backend.start();
            if (!error.isEmpty()) { QMessageBox::critical(nullptr, QString::fromUtf8("启动失败"), error); return 2; }
            ApiClient readiness(origin);
            QElapsedTimer elapsed; elapsed.start();
            while (elapsed.elapsed() < 20000) {
                if (readiness.call("GET", "/api/auth/session").ok()) break;
                QThread::msleep(150);
            }
        }
    }
    QUrl validated;
    try { validated = QUrl(QString::fromStdString(shunshoupai::parseConnection(origin.toStdString(), false).origin)); }
    catch (const std::invalid_argument &) { return 3; }
    validated.setPath("");
    MainWindow window(validated.toString(), !smoke);
    if (parser.isSet("visual-smoke")) return window.visualSmoke(parser.value("visual-smoke"));
    if (smoke) {
        if (!parser.isSet("backend")) return 4;
        return window.smokeTest(parser.value("screenshot"));
    }
    window.show();
    QTimer::singleShot(0, &window, [&] { window.initialize(); });
    return app.exec();
}
