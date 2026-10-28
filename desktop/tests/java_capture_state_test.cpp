#include "java_capture_state.h"
#include <QCoreApplication>
#include <QTemporaryDir>
#include <iostream>

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv); QTemporaryDir directory;
    auto fail = [](const char *reason) { std::cerr << reason << '\n'; return 1; };
    if (!directory.isValid()) return fail("No isolated settings directory");
    const auto path = directory.filePath("state.ini"), scope = JavaCaptureState::scope("http://127.0.0.1:43127", "calendar-a");
    const auto other = JavaCaptureState::scope("http://127.0.0.1:43128", "calendar-a");
    const auto id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QJsonObject input{{"scope", scope}, {"id", id}, {"context_id", "run-one:2"}, {"text", "OCR text"}, {"mode", "append"}};
    const auto digest = JavaCaptureState::hash(input);
    {
        QSettings settings(path, QSettings::IniFormat);
        if (!JavaCaptureState::accept(settings, scope, id, digest, "original\nOCR text", "legacy")) return fail("Handoff not committed");
        JavaCaptureState::edit(settings, scope, "edited after OCR"); settings.sync();
    }
    {
        QSettings settings(path, QSettings::IniFormat);
        if (JavaCaptureState::text(settings, scope, "fallback") != "edited after OCR") return fail("User edits did not survive restart");
        if (JavaCaptureState::receipt(settings, scope, id) != digest) return fail("Retry receipt missing");
        if (!JavaCaptureState::receipt(settings, other, id).isEmpty()) return fail("Receipt crossed origin boundary");
        // Emulate a crash after the atomic composer envelope but before receipt mirroring.
        settings.remove(JavaCaptureState::receiptKey(scope, id)); settings.sync();
        if (JavaCaptureState::receipt(settings, scope, id) != digest || !JavaCaptureState::flushLast(settings, scope)) return fail("Uncertain delivery recovery lost receipt");
        if (JavaCaptureState::text(settings, scope, "fallback") != "edited after OCR") return fail("Receipt recovery reverted later edits");
        input.insert("text", "changed retry");
        if (JavaCaptureState::hash(input) == digest) return fail("Changed payload reused old receipt");
        const auto second = QUuid::createUuid().toString(QUuid::WithoutBraces);
        if (!JavaCaptureState::accept(settings, scope, second, JavaCaptureState::hash(input), "second OCR", "legacy")) return fail("Next handoff failed");
        if (JavaCaptureState::receipt(settings, scope, id) != digest) return fail("Old acknowledgement was discarded");
        if (JavaCaptureState::accept(settings, scope, "../bad", digest, "bad", "legacy")) return fail("Invalid request id accepted");
    }
    std::cout << "PASS: durable text and receipt, unknown-result retry, later edits, previous receipts and origin isolation\n"; return 0;
}
