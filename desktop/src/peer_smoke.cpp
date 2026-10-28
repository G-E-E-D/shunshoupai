#include "window.h"
#include "qrcodegen.hpp"
#include "pairing_input.h"
#include <QAbstractButton>
#include <QApplication>
#include <QClipboard>
#include <QComboBox>
#include <QDialog>
#include <QDateEdit>
#include <QDir>
#include <QElapsedTimer>
#include <QFile>
#include <QLineEdit>
#include <QMimeData>
#include <QPainter>
#include <QPointer>
#include <QRadioButton>
#include <QTimer>
#include <QUuid>
#include <functional>
#include <iostream>
#include <memory>

namespace {
QAbstractButton *control(QDialog *dialog, const QString &id) {
    for (auto *button : dialog->findChildren<QAbstractButton *>())
        if (button->objectName() == id || button->property("controlId") == id) return button;
    return nullptr;
}
void clickLater(QAbstractButton *button) {
    if (!button) return;
    QPointer<QAbstractButton> target(button);
    QTimer::singleShot(0, button, [target] { if (target) target->click(); });
}
void rejectLater(QDialog *dialog) { if (dialog) QTimer::singleShot(0, dialog, &QDialog::reject); }
bool waitFor(const std::function<bool()> &ready, int timeout = 30000) {
    QElapsedTimer elapsed; elapsed.start();
    while (elapsed.elapsed() < timeout) {
        if (ready()) return true;
        QEventLoop loop; QTimer::singleShot(200, &loop, &QEventLoop::quit); loop.exec();
    }
    return false;
}
QString uuid() { return QUuid::createUuid().toString(QUuid::WithoutBraces); }
QJsonObject draft(const QString &title, const QString &date, const QString &start, const QString &end) {
    return {{"title", title}, {"start_local", date + "T" + start}, {"end_local", date + "T" + end},
        {"timezone", "Asia/Shanghai"}, {"time_source", "explicit"}, {"assumptions", QJsonArray{}}};
}
QImage pairingImage(const QString &url) {
    const auto qr = qrcodegen::QrCode::encodeText(url.toUtf8().constData(), qrcodegen::QrCode::Ecc::MEDIUM);
    constexpr int border = 4, scale = 6;
    QImage image((qr.getSize() + 2 * border) * scale, (qr.getSize() + 2 * border) * scale, QImage::Format_RGB32);
    image.fill(Qt::white); QPainter painter(&image); painter.setPen(Qt::NoPen); painter.setBrush(Qt::black);
    for (int y = 0; y < qr.getSize(); ++y) for (int x = 0; x < qr.getSize(); ++x)
        if (qr.getModule(x, y)) painter.drawRect((x + border) * scale, (y + border) * scale, scale, scale);
    return image;
}
struct ClipboardRestore {
    std::unique_ptr<QMimeData> previous = std::make_unique<QMimeData>();
    ClipboardRestore() { if (const auto *data = QApplication::clipboard()->mimeData()) for (const auto &format : data->formats()) previous->setData(format, data->data(format)); }
    ~ClipboardRestore() { if (QApplication::clipboard()->ownsClipboard()) QApplication::clipboard()->setMimeData(previous.release()); }
};
}

int MainWindow::peerSmoke(const QString &directory, bool resumed) {
    auto fail = [](int code, const char *message) { std::cerr << "Peer smoke " << code << ": " << message << '\n'; return code; };
    const auto remoteOrigin = qEnvironmentVariable("SHUNSHOUPAI_TEST_PEER_ORIGIN");
    if (!api.canBootstrap() || !isDeviceBootstrapOrigin(remoteOrigin)) return fail(100, "isolated owned backend configuration required");
    ApiClient remote(remoteOrigin, false, qgetenv("SHUNSHOUPAI_TEST_PEER_BOOTSTRAP_KEY"));
    if (!remote.canBootstrap()) return fail(101, "isolated remote bootstrap key missing");
    QDir output(QDir(directory).absolutePath()); QDir().mkpath(output.absolutePath());
    show(); QApplication::processEvents(); initialize();
    poll->stop(); disconnect(qApp, nullptr, this, nullptr);
    const auto localOrigin = api.origin, localSpace = owner();
    const auto localId = session.value("device").toObject().value("id").toString();
    const auto remoteSession = remote.call("POST", "/api/devices/bootstrap", {{"name", QString::fromUtf8("第二台电脑")}, {"platform", "windows"}});
    const auto remoteId = remoteSession.data.value("device").toObject().value("id").toString();
    if (localSpace.isEmpty() || !remoteSession.ok() || remoteId.isEmpty() || remoteId == localId || remoteSession.data.value("space_id") == localSpace)
        return fail(102, "devices did not keep independent local identities/calendars");
    auto event = [](ApiClient &client, const QString &id) { return client.call("GET", "/api/events/" + id).data.value("event").toObject(); };
    auto commit = [](ApiClient &client, const QJsonObject &row) { return client.call("POST", "/api/events/commit", {{"request_id", uuid()}, {"events", QJsonArray{row}}}); };
    auto contains = [&](ApiClient &client, const QString &id, const QString &title) { return event(client, id).value("title") == title; };
    QFile stateFile(output.filePath("peer-smoke-state.json"));
    QJsonObject state;
    if (resumed) {
        if (!stateFile.open(QIODevice::ReadOnly)) return fail(103, "previous test state missing");
        state = QJsonDocument::fromJson(stateFile.readAll()).object(); stateFile.close();
        if (state.value("local_id") != localId || state.value("remote_id") != remoteId || state.value("local_space") != localSpace)
            return fail(104, "device identity/calendar changed after process restart");
        const auto a = commit(api, draft(QString::fromUtf8("电脑A重启后独立安排"), "2032-05-12", "09:00:00", "10:00:00"));
        const auto b = commit(remote, draft(QString::fromUtf8("电脑B重启后独立安排"), "2032-05-12", "11:00:00", "12:00:00"));
        if (!a.ok() || !b.ok()) return fail(105, "post-restart local creation failed");
        const auto ar = a.data.value("events").toArray().first().toObject(), br = b.data.value("events").toArray().first().toObject();
        api.call("POST", "/api/peers/sync"); remote.call("POST", "/api/peers/sync");
        if (!waitFor([&] { return contains(remote, ar.value("id").toString(), ar.value("title").toString()) && contains(api, br.value("id").toString(), br.value("title").toString()); }))
            return fail(106, "persisted peers did not reconnect and merge new independent events");
        const auto id = state.value("event_id").toString(), title = state.value("resolved_title").toString();
        if (!contains(api, id, title) || !contains(remote, id, title) || !event(api, id).value("replica_conflicts").toArray().isEmpty())
            return fail(107, "explicit conflict resolution did not survive restart");
        if (!api.call("DELETE", "/api/peers/" + remoteId).ok()) return fail(108, "peer removal failed");
        auto unpaired = [](ApiClient &client, const QString &id) {
            const auto result = client.call("GET", "/api/devices");
            if (!result.ok()) return false;
            for (const auto &peer : result.data.value("peers").toArray()) if (peer.toObject().value("id") == id) return false;
            return true;
        };
        if (!waitFor([&] { return unpaired(api, remoteId) && unpaired(remote, localId); }, 15000)) return fail(121, "reachable reciprocal peer relation was not revoked");
        if (!contains(api, id, title) || !contains(remote, id, title)) return fail(109, "unpairing removed local calendar copies");
        if (api.origin != localOrigin || owner() != localSpace) return fail(110, "desktop switched to remote storage");
        std::cout << "PASS: both Node processes restart with persistent identities/peers; independent post-restart events merge; resolved versions and local copies survive unpairing\n";
        return 0;
    }
    const auto a = commit(api, draft(QString::fromUtf8("电脑A独立安排"), "2032-05-10", "09:00:00", "10:00:00"));
    const auto b = commit(remote, draft(QString::fromUtf8("电脑B独立安排"), "2032-05-10", "11:00:00", "12:00:00"));
    if (!a.ok() || !b.ok()) return fail(111, "independent pre-pairing events failed");
    const auto ar = a.data.value("events").toArray().first().toObject(), br = b.data.value("events").toArray().first().toObject();
    const auto id = ar.value("id").toString();
    const auto ticket = remote.call("POST", "/api/pairing/issue");
    if (!ticket.ok()) return fail(112, "remote pairing QR not issued");
    const auto url = remoteOrigin + "/#pair=" + ticket.data.value("token").toString();
    ClipboardRestore restoreClipboard;
    bool uiFailed = false; int phase = 0; QElapsedTimer deadline; deadline.start();
    QTimer controller; controller.setInterval(60);
    connect(&controller, &QTimer::timeout, this, [&] {
        if (api.busy()) return;
        auto *dialog = qobject_cast<QDialog *>(QApplication::activeModalWidget());
        if (!dialog) return;
        if (deadline.elapsed() > 30000) { uiFailed = true; rejectLater(dialog); return; }
        if (phase == 0 && dialog->objectName() == "serviceDialog") {
            auto *local = dialog->findChild<QComboBox *>("peerLocalAddress");
            if (!local || !control(dialog, "peerPaste")) { std::cerr << "Peer UI missing controls: local=" << bool(local) << " paste=" << bool(control(dialog, "peerPaste")) << '\n'; dialog->grab().save(output.filePath("peer-ui-failure.png")); uiFailed = true; rejectLater(dialog); return; }
            local->setEditText(localOrigin);
            const auto input = pairingImage(url);
            QApplication::clipboard()->setImage(input);
            QApplication::processEvents();
            phase = 1;
            if (!QApplication::clipboard()->image().isNull()) control(dialog, "peerPaste")->click();
            else {
                // Some Windows runner desktops prohibit clipboard ownership.
                // Exercise the same production file-image import in that case.
                const auto path = output.filePath("peer-pairing-image.png");
                auto *address = dynamic_cast<PairingAddressInput *>(dialog->findChild<QLineEdit *>("serviceAddress"));
                QString error;
                if (!input.save(path) || !address || !address->loadQrFile(path, &error)) { uiFailed = true; rejectLater(dialog); return; }
                std::cout << "INFO: system clipboard unavailable; verified the production QR image-file input instead\n";
            }
        } else if (phase == 1 && dialog->objectName() == "serviceDialog") {
            auto *address = dialog->findChild<QLineEdit *>("serviceAddress");
            if (!address || address->text() != url || !control(dialog, "serviceConnect")) { std::cerr << "Peer UI image decode: address=" << bool(address) << " matches=" << (address && address->text() == url) << " submit=" << bool(control(dialog, "serviceConnect")) << '\n'; dialog->grab().save(output.filePath("peer-ui-failure.png")); uiFailed = true; rejectLater(dialog); return; }
            dialog->grab().save(output.filePath("connect-peer-from-image.png"));
            phase = 2; clickLater(control(dialog, "serviceConnect"));
        } else if (phase == 2 && dialog->objectName() == "devicesDialog") { phase = 3; rejectLater(dialog); }
    });
    controller.start(); connection(); controller.stop();
    if (uiFailed || phase != 3 || api.origin != localOrigin || owner() != localSpace) { std::cerr << "Peer UI phase=" << phase << " origin_kept=" << (api.origin == localOrigin) << " calendar_kept=" << (owner() == localSpace) << '\n'; return fail(113, "native image pairing failed or changed local API/calendar"); }
    if (!waitFor([&] { return contains(remote, id, ar.value("title").toString()) && contains(api, br.value("id").toString(), br.value("title").toString()); }))
        return fail(114, "independent calendars did not merge bidirectionally");
    QJsonObject conflicted;
    QString localTitle;
    for (int attempt = 0; attempt < 3; ++attempt) {
        const auto left = event(api, id), right = event(remote, id);
        localTitle = QString::fromUtf8("电脑A离线修改 ") + QString::number(attempt + 1);
        const auto changedA = api.call("PATCH", "/api/events/" + id, {{"base_version", left.value("version")}, {"title", localTitle}});
        const auto changedB = remote.call("PATCH", "/api/events/" + id, {{"base_version", right.value("version")}, {"title", QString::fromUtf8("电脑B独立修改 ") + QString::number(attempt + 1)}});
        if (!changedA.ok()) return fail(115, "local independent edit failed");
        api.call("POST", "/api/peers/sync"); remote.call("POST", "/api/peers/sync");
        if (changedB.ok() && waitFor([&] { conflicted = event(api, id); return conflicted.value("replica_conflicts").toArray().size() >= 2; }, 10000)) break;
    }
    if (conflicted.value("replica_conflicts").toArray().size() < 2) return fail(116, "concurrent independent changes were overwritten instead of retained");
    QString selected;
    for (const auto &value : conflicted.value("replica_conflicts").toArray())
        if (value.toObject().value("payload").toObject().value("title") == localTitle) selected = value.toObject().value("revision_id").toString();
    if (selected.isEmpty()) return fail(117, "local conflict candidate missing");
    day->setDate(QDate(2032, 5, 10)); refresh(); QApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    QAbstractButton *resolveEntry = nullptr;
    for (auto *button : findChildren<QAbstractButton *>())
        if (button->property("controlId") == "resolveReplica-" + id && button->isVisible()) resolveEntry = button;
    if (!resolveEntry) return fail(122, "calendar did not display the multiple-version resolution entry");
    grab().save(output.filePath("calendar-with-conflict.png"));
    phase = 0; deadline.restart();
    disconnect(&controller, nullptr, this, nullptr);
    connect(&controller, &QTimer::timeout, this, [&] {
        if (api.busy()) return;
        auto *dialog = qobject_cast<QDialog *>(QApplication::activeModalWidget());
        if (!dialog || dialog->objectName() != "replicaDialog") return;
        if (deadline.elapsed() > 20000) { uiFailed = true; rejectLater(dialog); return; }
        auto *save = control(dialog, "replicaResolve");
        if (phase == 0) {
            if (!save || save->isEnabled()) { uiFailed = true; rejectLater(dialog); return; }
            QRadioButton *choice = nullptr;
            for (auto *radio : dialog->findChildren<QRadioButton *>()) {
                if (radio->isChecked()) { uiFailed = true; rejectLater(dialog); return; }
                if (radio->property("revisionId") == selected) choice = radio;
            }
            if (!choice) { uiFailed = true; rejectLater(dialog); return; }
            phase = 1; clickLater(choice);
        } else if (phase == 1) {
            if (!save || !save->isEnabled()) { uiFailed = true; rejectLater(dialog); return; }
            dialog->grab().save(output.filePath("choose-replica-version.png"));
            phase = 2; clickLater(save);
        }
    });
    controller.start(); resolveEntry->click(); controller.stop();
    const bool resolved = event(api, id).value("replica_conflicts").toArray().isEmpty();
    if (uiFailed || phase != 2 || !resolved) return fail(118, "explicit replica-choice UI failed");
    api.call("POST", "/api/peers/sync"); remote.call("POST", "/api/peers/sync");
    if (!waitFor([&] { return contains(api, id, localTitle) && contains(remote, id, localTitle)
        && event(api, id).value("replica_conflicts").toArray().isEmpty() && event(remote, id).value("replica_conflicts").toArray().isEmpty(); }))
        return fail(119, "explicit resolution did not converge on both devices");
    state = {{"local_id", localId}, {"remote_id", remoteId}, {"local_space", localSpace}, {"event_id", id}, {"resolved_title", localTitle}};
    if (!stateFile.open(QIODevice::WriteOnly) || stateFile.write(QJsonDocument(state).toJson()) < 0) return fail(120, "cannot retain non-secret restart test state");
    stateFile.close();
    std::cout << "PASS: QR image pairing keeps local API and calendar; independent PC calendars merge both ways; concurrent variants stay intact until explicit native choice then converge\n";
    return 0;
}
