#include "window.h"
#include "java_capture_bridge.h"
#include "java_capture_state.h"
#include <QApplication>
#include <QDialog>
#include <QDir>
#include <QElapsedTimer>
#include <QFile>
#include <QJsonDocument>
#include <QLabel>
#include <QMessageBox>
#include <QTableWidget>
#include <QTextEdit>
#include <QThread>
#include <QTimer>
#include <iostream>

int MainWindow::javaCaptureSmoke(const QString &directory) {
    auto fail = [](const char *reason) { std::cerr << "Java capture handoff: " << reason << '\n'; return 150; };
    QDir output(directory); if (!QDir().mkpath(output.absolutePath())) return fail("Cannot create evidence directory");
    show(); initialize(); if (owner().isEmpty()) return fail("Local calendar unavailable");
    const QDateTime start(currentDay, QTime(0, 0), zone);
    auto count = [&] {
        const auto reply = api.call("GET", "/api/events?from=" + start.toUTC().toString(Qt::ISODate) + "&to=" + start.addDays(7).toUTC().toString(Qt::ISODate));
        return reply.ok() ? reply.data.value("events").toArray().size() : -1;
    };
    const auto before = count(); if (before < 0) return fail("Cannot count isolated schedules");
    int imports = 0, reviews = 0, notices = 0; bool invalid = false; QJsonObject original;
    const auto receiver = javaCapture->received;
    javaCapture->received = [&](const QJsonObject &message) {
        if (message.value("type") == "recognized-text") {
            ++imports; if (original.isEmpty()) original = message;
            else if (original != message) { invalid = true; std::cerr << "Retry payload differs\n"; }
        }
        receiver(message);
    };
    QElapsedTimer elapsed; elapsed.start();
    QTimer driver; driver.setInterval(60);
    connect(&driver, &QTimer::timeout, this, [&] {
        auto *dialog = qobject_cast<QDialog *>(QApplication::activeModalWidget());
        if (!dialog) return;
        if (dialog->objectName() == "draftsDialog") {
            auto *table = dialog->findChild<QTableWidget *>("draftTable");
            if (!table || table->rowCount() != 2) { invalid = true; std::cerr << "Draft row count: " << (table ? table->rowCount() : -1) << '\n'; }
            ++reviews; if (!dialog->grab().save(output.filePath("java-handoff-drafts.png"))) { invalid = true; std::cerr << "Draft evidence image failed\n"; }
            QTimer::singleShot(0, dialog, &QDialog::reject);
        } else if (auto *notice = qobject_cast<QMessageBox *>(dialog);
                   notice && notice->windowTitle() == QStringLiteral("整理提示") && notice->icon() == QMessageBox::Information && notices++ == 0) {
            // The real rules parser explains inferred end times before showing the drafts.
            QTimer::singleShot(0, notice, &QDialog::accept);
        } else {
            invalid = true; std::cerr << "Unexpected modal: " << dialog->objectName().toStdString() << " / " << dialog->windowTitle().toStdString() << '\n';
            QTimer::singleShot(0, dialog, &QDialog::reject);
        }
    });
    driver.start();
    if (!javaCapture->start({"--ipc-memory-smoke", "--output", output.absolutePath()}, output.filePath("isolated-localdata"))) return fail("Packaged Java module missing");
    while (elapsed.elapsed() < 90000 && !invalid && (javaCapture->running() || reviews == 0)) {
        QApplication::processEvents(); QThread::msleep(10);
    }
    driver.stop(); javaCapture->received = receiver;
    if (invalid) { javaCapture->stop(); return fail("Handoff assertion failed; see diagnostic above"); }
    if (javaCapture->running()) { javaCapture->stop(); return fail("Handoff timed out"); }
    if (imports != 2 || reviews != 1 || original.isEmpty()) return fail("Expected one real review and an identical retry without reparse");
    QFile reportFile(output.filePath("java-ipc-memory-smoke.json"));
    if (!reportFile.open(QIODevice::ReadOnly)) return fail("Java memory OCR report missing");
    const auto report = QJsonDocument::fromJson(reportFile.readAll()).object();
    if (!report.value("ok").toBool() || !report.value("real_local_ocr").toBool() || report.value("accepted_count").toInt() != 2)
        return fail("Java memory OCR or acknowledgement verification failed");
    const auto scope = javaCaptureScope();
    if (JavaCaptureState::receipt(settings, scope, original.value("id").toString()) != JavaCaptureState::hash(original)) return fail("Receipt not durable");
    if (count() != before) return fail("Unconfirmed screenshot wrote formal schedules");
    const auto reviewKey = DraftState::key("review", api.origin, owner());
    const auto storedReview = settings.value(reviewKey);
    const auto text = composer->toPlainText();
    settings.remove(reviewKey); updateJavaCaptureContext();
    auto stale = original; stale.insert("id", QUuid::createUuid().toString(QUuid::WithoutBraces)); stale.insert("context_id", "previous-process:2"); stale.insert("text", "old unacknowledged OCR");
    receiveJavaCapture(stale);
    if (composer->toPlainText() != text || !JavaCaptureState::receipt(settings, scope, stale.value("id").toString()).isEmpty()) return fail("Old process payload overwrote current input or obtained a receipt");
    settings.setValue(reviewKey, storedReview); updateJavaCaptureContext();
    resize(1060, 720); QApplication::processEvents(); grab().save(output.filePath("java-handoff-desktop.png"));
    std::cout << "PASS: packaged Java real OCR and Swing confirmation -> private pipe -> existing two-row schedule review; identical retry acknowledged once, stale process rejected, no automatic event save\n";
    return 0;
}
