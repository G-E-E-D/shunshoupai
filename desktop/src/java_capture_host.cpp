#include "window.h"
#include "java_capture_bridge.h"
#include "java_capture_state.h"
#include <QApplication>
#include <QJsonDocument>
#include <QLabel>
#include <QPushButton>
#include <QSignalBlocker>
#include <QTextEdit>
#include <QTimer>

MainWindow::~MainWindow() { if (javaCapture) { javaCapture->started = {}; javaCapture->received = {}; javaCapture->failed = {}; javaCapture->stop(); } }
QString MainWindow::javaCaptureScope() const { return owner().isEmpty() ? QString{} : JavaCaptureState::scope(api.origin, owner()); }
QString MainWindow::javaCaptureContextToken() const { return javaCaptureRunId + ":" + QString::number(javaCaptureContextId); }
void MainWindow::setupJavaCapture(bool automatic) {
    javaCaptureRunId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    javaCaptureAutomatic = automatic;
    javaCapture = new JavaCaptureBridge(this);
    javaCapture->started = [this] {
        const auto starting = ++javaCaptureStartEpoch;
        javaCaptureReady = false; updateJavaCaptureContext();
        if (owner().isEmpty() || session.value("role") != "owner") { javaCapture->stop(); return; }
        auto init = javaCaptureContext; init.insert("type", "init"); init.insert("protocol", 1);
        init.insert("version", QCoreApplication::applicationVersion()); init.insert("context_id", javaCaptureContextToken()); javaCapture->send(init);
        QTimer::singleShot(10000, this, [this, starting] {
            if (starting == javaCaptureStartEpoch && !javaCaptureReady && javaCapture->running()) {
                javaCapture->stop(); captureHint->setText(QStringLiteral("截图组件初始化超时，可再次点击截图重试。"));
            }
        });
    };
    javaCapture->received = [this](const QJsonObject &message) { receiveJavaCapture(message); };
    javaCapture->failed = [this](const QString &message) {
        if (captureWindowHidden) { show(); captureWindowHidden = false; }
        captureRequestId.clear(); javaCaptureReady = false; captureHint->setText(message);
    };
    auto *timer = new QTimer(this); timer->setInterval(500);
    connect(timer, &QTimer::timeout, this, [this] { updateJavaCaptureContext(); }); timer->start();
}
void MainWindow::javaCaptureCommand(const QString &type) {
    if (owner().isEmpty() || session.value("role") != "owner") { status->setText(QStringLiteral("请先等待本机日程准备好，再使用截图整理。")); return; }
    if (!javaCapture->running()) {
        javaCaptureAttempted = true; javaCaptureReady = false;
        if (!javaCapture->start()) return;
    }
    if (!javaCaptureReady) { pendingJavaCommand = type; return; }
    updateJavaCaptureContext(true); javaCapture->send({{"type", type}});
}
void MainWindow::updateJavaCaptureContext(bool force) {
    if (!javaCapture) return;
    if (owner().isEmpty() || session.value("role") != "owner") {
        if (javaCapture->running()) javaCapture->stop();
        javaCaptureReady = false; javaCaptureAttempted = false; javaCaptureContext = {}; return;
    }
    if (javaCaptureAutomatic && !javaCaptureAttempted) { javaCaptureAttempted = true; javaCapture->start(); }
    const auto scope = javaCaptureScope();
    QJsonObject context{{"scope", scope}, {"prior_text", composer->toPlainText()},
        {"has_review", !DraftState::read(settings, DraftState::key("review", api.origin, owner())).isEmpty()},
        {"has_pending", !DraftState::read(settings, DraftState::key("pending", api.origin, owner())).isEmpty()}};
    if (context != javaCaptureContext) { javaCaptureContext = context; ++javaCaptureContextId; force = true; }
    if (!javaCaptureReady || !force) return;
    context.insert("type", "context"); context.insert("context_id", javaCaptureContextToken()); javaCapture->send(context);
}
void MainWindow::receiveJavaCapture(const QJsonObject &message) {
    const auto type = message.value("type").toString();
    if (type == "ready") {
        if (message.value("protocol").toInt() != 1) { javaCapture->stop(); return; }
        if (owner().isEmpty() || session.value("role") != "owner") { javaCapture->stop(); return; }
        javaCaptureReady = true;
        updateJavaCaptureContext(true);
        const auto command = pendingJavaCommand; pendingJavaCommand.clear(); if (!command.isEmpty()) javaCapture->send({{"type", command}});
    } else if (type == "status") {
        captureHint->setText(message.value("hotkey_available").toBool() ? QStringLiteral("Ctrl+Alt+S 框选文字 · Java 本机识别") : QStringLiteral("快捷键不可用，可点击“截图”或在截图设置中使用浮窗。"));
        captureResumeButton->setVisible(message.value("pending_count").toInt() > 0);
    } else if (type == "capture-request") {
        const auto id = message.value("id").toString(); if (!JavaCaptureState::validId(id)) return;
        if (!captureRequestId.isEmpty() || QApplication::activeModalWidget() || session.value("role") != "owner") {
            javaCapture->send({{"type", "capture-rejected"}, {"id", id}, {"message", QStringLiteral("请先关闭当前编辑窗口，草稿会保留。")}}); return;
        }
        captureRequestId = id; captureWindowHidden = isVisible() && !isMinimized(); if (captureWindowHidden) hide();
        javaCapture->send({{"type", "capture-ready"}, {"id", id}});
        QTimer::singleShot(130000, this, [this, id] { if (captureRequestId == id) { if (captureWindowHidden) show(); captureWindowHidden = false; captureRequestId.clear(); } });
    } else if (type == "capture-finished") {
        if (message.value("id").toString() != captureRequestId) return;
        if (captureWindowHidden) show();
        captureWindowHidden = false; captureRequestId.clear();
    } else if (type == "error") {
        if (captureWindowHidden) show();
        captureWindowHidden = false; captureRequestId.clear();
        status->setText(message.value("message").toString().left(500));
        if (!javaCaptureReady) javaCapture->stop();
    } else if (type == "recognized-text") {
        const auto id = message.value("id").toString(), scope = message.value("scope").toString(), text = message.value("text").toString(), mode = message.value("mode").toString();
        auto reject = [&](const QString &reason) { javaCapture->send({{"type", "rejected"}, {"id", id}, {"message", reason}}); updateJavaCaptureContext(true); };
        if (!JavaCaptureState::validId(id) || scope != javaCaptureScope() || session.value("role") != "owner" ||
            !message.value("text").isString() || text.trimmed().isEmpty() || text.size() > 2000 || (mode != "append" && mode != "replace")) { reject(QStringLiteral("文字或日程位置无效，请回到当前日程重新确认。")); return; }
        const auto digest = JavaCaptureState::hash(message), previous = JavaCaptureState::receipt(settings, scope, id);
        if (!previous.isEmpty()) {
            if (previous != digest) reject(QStringLiteral("这次文字编号已使用，请重新确认识别文字。"));
            else if (JavaCaptureState::flushLast(settings, scope)) javaCapture->send({{"type", "accepted"}, {"id", id}});
            return;
        }
        if (api.busy()) { reject(QStringLiteral("主窗口正在处理上一项请求，请稍后再确认；识别文字仍保留。")); return; }
        updateJavaCaptureContext();
        if (message.value("context_id").toString() != javaCaptureContextToken()) { reject(QStringLiteral("原输入或草稿已改变，请核对后重新确认。")); return; }
        if (javaCaptureContext.value("has_review").toBool() || javaCaptureContext.value("has_pending").toBool() || QApplication::activeModalWidget()) { reject(QStringLiteral("请先回主窗口处理已有草稿或上次保存结果；识别文字会保留。")); return; }
        const auto old = composer->toPlainText(); const auto combined = mode == "append" && !old.trimmed().isEmpty() ? old + "\n" + text : text;
        if (combined.size() > 2000) { reject(QStringLiteral("追加后超过2000字，请删减或分次整理，内容没有截断。")); return; }
        const auto persisted = JavaCaptureState::accept(settings, scope, id, digest, combined, "composer/" + DraftState::key("pending", api.origin, owner()));
        const auto authoritative = JavaCaptureState::text(settings, scope, old);
        { const QSignalBlocker block(composer); composer->setPlainText(authoritative); }
        if (!persisted) { status->setText(QStringLiteral("尚未确认文字已持久保存，请保留截图组件中的文字，重启后重试。")); return; }
        javaCapture->send({{"type", "accepted"}, {"id", id}}); updateJavaCaptureContext(true);
        showNormal(); raise(); activateWindow();
        QTimer::singleShot(0, this, [this, scope, combined] { if (javaCaptureScope() == scope && composer->toPlainText() == combined) parse(); });
    }
}
