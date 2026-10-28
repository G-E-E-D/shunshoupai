#include "window.h"
#include "modern_calendar.h"
#include "draft_state.h"

#include <QAbstractButton>
#include <QApplication>
#include <QComboBox>
#include <QDateEdit>
#include <QDateTimeEdit>
#include <QDialog>
#include <QDir>
#include <QElapsedTimer>
#include <QEvent>
#include <QFrame>
#include <QFontMetrics>
#include <QJsonArray>
#include <QKeyEvent>
#include <QLineEdit>
#include <QLabel>
#include <QMouseEvent>
#include <QPointer>
#include <QPushButton>
#include <QScrollArea>
#include <QTableWidget>
#include <QTextEdit>
#include <QTimer>
#include <QToolButton>
#include <QUuid>
#include <functional>
#include <iostream>

namespace {
QAbstractButton *control(QWidget *parent, const QString &identifier) {
    if (!parent) return nullptr;
    for (auto *button : parent->findChildren<QAbstractButton *>())
        if (button->objectName() == identifier || button->property("controlId").toString() == identifier) return button;
    return nullptr;
}

void queueClick(QAbstractButton *button) {
    if (!button) return;
    QPointer<QAbstractButton> pointer(button);
    QTimer::singleShot(0, button, [pointer] { if (pointer) pointer->click(); });
}

void queueReject(QDialog *dialog) {
    if (dialog) QTimer::singleShot(0, dialog, &QDialog::reject);
}

void sendKey(QWidget *target, int key) {
    QKeyEvent down(QEvent::KeyPress, key, Qt::NoModifier);
    QApplication::sendEvent(target, &down);
    QKeyEvent up(QEvent::KeyRelease, key, Qt::NoModifier);
    QApplication::sendEvent(target, &up);
    QApplication::processEvents();
}

bool openDatePopup(QDateEdit *date) {
    date->setFocus();
    const QPointF point(date->width() - 17, date->height() / 2);
    QMouseEvent down(QEvent::MouseButtonPress, point, date->mapToGlobal(point.toPoint()), Qt::LeftButton, Qt::LeftButton, Qt::NoModifier);
    QApplication::sendEvent(date, &down);
    QMouseEvent up(QEvent::MouseButtonRelease, point, date->mapToGlobal(point.toPoint()), Qt::LeftButton, Qt::NoButton, Qt::NoModifier);
    QApplication::sendEvent(date, &up);
    QApplication::processEvents();
    return date->calendarWidget() && date->calendarWidget()->isVisible();
}
}

int MainWindow::visualSmoke(const QString &directory) {
    // The caller must supply an isolated backend, just like --smoke-test. This
    // exercises the actual native dialogs/buttons and their API handlers. Every
    // device/event below is an isolated documentation fixture, never user data.
    QDir output(QDir(directory).absolutePath());
    if (!QDir().mkpath(output.absolutePath())) return 30;
    auto fail = [](int code, const char *message) {
        std::cerr << "Native UI smoke " << code << ": " << message << '\n';
        return code;
    };
    auto capture = [&](QWidget *widget, const QString &filename) {
        if (!widget) return false;
        QApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
        QApplication::processEvents();
        widget->repaint();
        QApplication::processEvents();
        return widget->grab().save(output.filePath(filename));
    };
    auto dismissUnexpected = [] {
        queueReject(qobject_cast<QDialog *>(QApplication::activeModalWidget()));
    };
    show();
    QApplication::processEvents();
    initialize();
    if (owner().isEmpty() || !session.value("local_first").toBool() || session.value("role") != "owner")
        return fail(31, "native startup did not establish an authorized local device without login");
    const auto deviceName = session.value("device").toObject().value("name").toString();
    for (auto *action : findChildren<QAbstractButton *>())
        if (action->text().contains(QString::fromUtf8("登录")) || action->text().contains(QString::fromUtf8("注册")) || action->text().contains(QString::fromUtf8("退出账号")))
            return fail(72, "account login controls remain in the native workspace");

    // Open the real event editor, then its nested draft-confirmation dialog.
    // Clicks are queued outside the controller callback: eventSave opens a
    // nested exec(), so synchronously clicking here would block this timer.
    const auto eventDay = QDateTime::currentDateTimeUtc().toTimeZone(zone).date().addDays(1);
    day->setDate(eventDay);
    bool eventFailed = false;
    int eventPhase = 0;
    QTimer eventController;
    eventController.setInterval(60);
    QElapsedTimer eventDeadline;
    eventDeadline.start();
    connect(&eventController, &QTimer::timeout, this, [&] {
        if (api.busy()) return;
        if (eventDeadline.elapsed() > 20000) { eventFailed = true; dismissUnexpected(); return; }
        auto *dialog = qobject_cast<QDialog *>(QApplication::activeModalWidget());
        if (!dialog) return;
        if (eventPhase == 0 && dialog->objectName() == "eventDialog") {
            eventPhase = 10;
            auto *title = dialog->findChild<QLineEdit *>("eventTitle");
            auto *start = dialog->findChild<QDateTimeEdit *>("eventStart");
            auto *end = dialog->findChild<QDateTimeEdit *>("eventEnd");
            auto *save = control(dialog, "eventSave");
            if (!title || !start || !end || !save) { eventFailed = true; queueReject(dialog); return; }
            title->setText(QString::fromUtf8("设计评审"));
            start->setDateTime(QDateTime(eventDay, QTime(9, 30)));
            end->setDateTime(QDateTime(eventDay, QTime(10, 30)));
            if (!capture(dialog, "event-editor.png")) { eventFailed = true; queueReject(dialog); return; }
            eventPhase = 1;
            queueClick(save);
        } else if (eventPhase == 1 && dialog->objectName() == "draftsDialog") {
            eventPhase = 11;
            auto *table = dialog->findChild<QTableWidget *>("draftTable");
            auto *save = control(dialog, "draftSave");
            if (!table || table->rowCount() != 1 || !save || !capture(dialog, "drafts.png")) { eventFailed = true; queueReject(dialog); return; }
            eventPhase = 2;
            queueClick(save);
        }
    });
    eventController.start();
    editEvent();
    eventController.stop();
    refresh();
    bool savedEventFound = false;
    for (const auto &value : events) if (value.toObject().value("title").toString() == QString::fromUtf8("设计评审")) savedEventFound = true;
    if (eventFailed || eventPhase != 2 || !savedEventFound) return fail(32, "native editor -> draft confirmation -> real saved event failed");

    auto inspectDialog = [&](const QString &name, const QString &file, const std::function<void()> &launch,
                             const std::function<bool(QDialog *)> &prepare = {}) {
        bool visited = false;
        bool failed = false;
        QTimer controller;
        controller.setInterval(60);
        QElapsedTimer deadline;
        deadline.start();
        connect(&controller, &QTimer::timeout, this, [&] {
            if (api.busy()) return;
            if (deadline.elapsed() > 15000) { failed = true; dismissUnexpected(); return; }
            auto *dialog = qobject_cast<QDialog *>(QApplication::activeModalWidget());
            if (visited || !dialog || dialog->objectName() != name) return;
            visited = true;
            if (prepare && !prepare(dialog)) failed = true;
            if (!failed && !capture(dialog, file)) failed = true;
            queueReject(dialog);
        });
        controller.start();
        launch();
        controller.stop();
        return visited && !failed;
    };
    // Review edits, including an editor that still has focus at close, must
    // survive without being mistaken for an already-submitted request.
    const auto reviewKey = DraftState::key("review", api.origin, owner());
    const auto pendingKey = DraftState::key("pending", api.origin, owner());
    const auto editorPrefix = DraftState::key("editor", api.origin, owner()) + "/";
    const QJsonObject reviewRow{{"title", QString::fromUtf8("尚未提交")},
        {"start_local", "2030-10-28T09:00:00"}, {"end_local", "2030-10-28T10:00:00"},
        {"timezone", "Asia/Shanghai"}, {"time_source", "explicit"}, {"assumptions", QJsonArray{}}};
    if (!inspectDialog("draftsDialog", "review-edited.png", [this, reviewRow] { drafts({reviewRow, reviewRow}); }, [](QDialog *dialog) {
        auto *table = dialog->findChild<QTableWidget *>("draftTable");
        auto *remove = control(dialog, "draftRemove");
        if (!table || !remove || table->rowCount() != 2) return false;
        table->setCurrentCell(1, 0); remove->click();
        table->setCurrentCell(0, 1); table->editItem(table->item(0, 1));
        auto *date = table->findChild<QDateTimeEdit *>();
        if (!date) return false;
        date->setDateTime(QDateTime::fromString("2030-10-28T09:15:00", Qt::ISODate));
        table->setCurrentCell(0, 0); table->editItem(table->item(0, 0));
        auto *title = qobject_cast<QLineEdit *>(QApplication::focusWidget());
        if (!title) return false;
        title->setText(QString::fromUtf8("已经手工调整的草稿"));
        return table->rowCount() == 1 && table->item(0, 0)->text() == title->text();
    })) return fail(53, "review edits did not survive active editor changes");
    const auto rememberedReview = DraftState::read(settings, reviewKey).value("events").toArray();
    if (rememberedReview.size() != 1 || rememberedReview.at(0).toObject().value("title") != QString::fromUtf8("已经手工调整的草稿")
        || rememberedReview.at(0).toObject().value("start_local") != "2030-10-28T09:15:00"
        || settings.contains(pendingKey) || events.size() != 1)
        return fail(54, "closing review lost edits or incorrectly submitted them");
    if (!inspectDialog("draftsDialog", "review-restored.png", [this] { restorePending(); }, [](QDialog *dialog) {
        auto *table = dialog->findChild<QTableWidget *>("draftTable");
        return table && table->rowCount() == 1 && table->editTriggers() != QAbstractItemView::NoEditTriggers
            && table->item(0, 0)->text() == QString::fromUtf8("已经手工调整的草稿")
            && control(dialog, "draftSave")->text() == QString::fromUtf8("确认保存");
    })) return fail(55, "unsubmitted review was not restored as editable content");
    // A pending result always wins and retains its exact original payload.
    const QJsonObject pendingFixture{{"request_id", QUuid::createUuid().toString(QUuid::WithoutBraces)}, {"events", QJsonArray{reviewRow}}};
    DraftState::write(settings, pendingKey, pendingFixture);
    if (!inspectDialog("draftsDialog", "pending-restored.png", [this] { restorePending(); }, [](QDialog *dialog) {
        auto *table = dialog->findChild<QTableWidget *>("draftTable");
        return table && table->editTriggers() == QAbstractItemView::NoEditTriggers
            && table->item(0, 0)->text() == QString::fromUtf8("尚未提交")
            && !control(dialog, "draftRemove")->isEnabled();
    })) return fail(56, "pending recovery lost immutability or original content");
    if (DraftState::read(settings, pendingKey) != pendingFixture || events.size() != 1)
        return fail(57, "merely restoring a pending result altered or submitted it");
    settings.remove(reviewKey); settings.remove(pendingKey); settings.sync();
    if (!inspectDialog("eventDialog", "manual-draft.png", [this] { editEvent(); }, [](QDialog *dialog) {
        auto *title = dialog->findChild<QLineEdit *>("eventTitle");
        auto *start = dialog->findChild<QDateTimeEdit *>("eventStart");
        auto *end = dialog->findChild<QDateTimeEdit *>("eventEnd");
        if (!title || !start || !end) return false;
        title->setText(QString::fromUtf8("手动输入尚未提交"));
        start->setDateTime(QDateTime::fromString("2030-10-29T11:00:00", Qt::ISODate));
        end->setDateTime(QDateTime::fromString("2030-10-29T12:00:00", Qt::ISODate));
        return true;
    })) return fail(58, "manual new-event draft could not be edited");
    if (!inspectDialog("eventDialog", "manual-draft-restored.png", [this] { restorePending(); }, [](QDialog *dialog) {
        auto *title = dialog->findChild<QLineEdit *>("eventTitle");
        auto *start = dialog->findChild<QDateTimeEdit *>("eventStart");
        return title && start && title->text() == QString::fromUtf8("手动输入尚未提交")
            && start->dateTime().toString("yyyy-MM-ddTHH:mm:ss") == "2030-10-29T11:00:00";
    })) return fail(59, "manual editor contents did not restore after close");
    if (events.size() != 1 || settings.contains(pendingKey)) return fail(60, "manual draft was unexpectedly submitted");
    settings.remove(editorPrefix + "new"); settings.sync();
    const auto originalEvent = events.first().toObject();
    if (!inspectDialog("eventDialog", "edit-draft.png", [this, originalEvent] { editEvent(originalEvent); }, [](QDialog *dialog) {
        auto *title = dialog->findChild<QLineEdit *>("eventTitle");
        if (!title) return false;
        title->setText(QString::fromUtf8("现有日程的未保存修改"));
        return true;
    })) return fail(61, "existing event draft could not be edited");
    const auto rememberedEditor = DraftState::read(settings, editorPrefix + originalEvent.value("id").toString());
    if (rememberedEditor.value("event").toObject() != originalEvent || rememberedEditor.value("title") != QString::fromUtf8("现有日程的未保存修改"))
        return fail(62, "existing editor did not retain the original version alongside edits");
    auto newerEvent = originalEvent;
    newerEvent.insert("version", originalEvent.value("version").toInt() + 1);
    newerEvent.insert("title", QString::fromUtf8("模拟另一台设备的修改"));
    if (!inspectDialog("eventDialog", "edit-draft-restored.png", [this, newerEvent] { editEvent(newerEvent); }, [](QDialog *dialog) {
        auto *title = dialog->findChild<QLineEdit *>("eventTitle");
        return title && title->text() == QString::fromUtf8("现有日程的未保存修改");
    }) || DraftState::read(settings, editorPrefix + originalEvent.value("id").toString()).value("event").toObject() != originalEvent)
        return fail(63, "reopening a draft silently adopted a different base version");
    settings.remove(editorPrefix + originalEvent.value("id").toString()); settings.sync();
    // Real stale writes must offer an explicit resolution, not trap a retained
    // draft on its old version forever. These fixtures live on another day.
    const QJsonObject conflictDraft{{"title", QString::fromUtf8("冲突测试原始日程")},
        {"start_local", "2030-10-30T09:00:00"}, {"end_local", "2030-10-30T10:00:00"},
        {"timezone", "Asia/Shanghai"}, {"time_source", "explicit"}, {"assumptions", QJsonArray{}}};
    auto conflictCreated = api.call("POST", "/api/events/commit", {{"request_id", QUuid::createUuid().toString(QUuid::WithoutBraces)}, {"events", QJsonArray{conflictDraft}}});
    if (!conflictCreated.ok()) return fail(64, "conflict fixture creation failed");
    auto conflictOriginal = conflictCreated.data.value("events").toArray().first().toObject();
    const auto conflictPath = "/api/events/" + conflictOriginal.value("id").toString();
    auto remoteChanged = api.call("PATCH", conflictPath, {{"base_version", conflictOriginal.value("version")}, {"title", QString::fromUtf8("另一端已经修改")}});
    if (!remoteChanged.ok()) return fail(65, "remote conflict fixture update failed");
    auto exerciseResolution = [&](const QJsonObject &original, const QString &mine, const QString &dialogName,
                                  const QString &choice, bool clone, const QString &screenshotName) {
        bool failed = false;
        int phase = 0;
        QTimer controller;
        controller.setInterval(60);
        QElapsedTimer deadline;
        deadline.start();
        connect(&controller, &QTimer::timeout, this, [&] {
            if (api.busy()) return;
            if (deadline.elapsed() > 20000) { failed = true; dismissUnexpected(); return; }
            auto *dialog = qobject_cast<QDialog *>(QApplication::activeModalWidget());
            if (!dialog) return;
            if (phase == 0 && dialog->objectName() == "eventDialog") {
                auto *title = dialog->findChild<QLineEdit *>("eventTitle");
                if (!title || !control(dialog, "eventSave")) { failed = true; queueReject(dialog); return; }
                title->setText(mine);
                phase = 1; queueClick(control(dialog, "eventSave"));
            } else if (phase == 1 && dialog->objectName() == dialogName) {
                bool localVisible = false;
                for (auto *label : dialog->findChildren<QLabel *>()) if (label->text() == mine) localVisible = true;
                if (!localVisible || !control(dialog, choice) || !capture(dialog, screenshotName)) { failed = true; queueReject(dialog); return; }
                phase = 2; queueClick(control(dialog, choice));
            } else if (clone && phase == 2 && dialog->objectName() == "draftsDialog") {
                auto *table = dialog->findChild<QTableWidget *>("draftTable");
                if (!table || table->rowCount() != 1 || table->item(0, 0)->text() != mine || !control(dialog, "draftSave")) { failed = true; queueReject(dialog); return; }
                phase = 3; queueClick(control(dialog, "draftSave"));
            }
        });
        controller.start();
        editEvent(original);
        controller.stop();
        return !failed && phase == (clone ? 3 : 2);
    };
    if (!exerciseResolution(conflictOriginal, QString::fromUtf8("明确选择保留本机修改"), "eventConflictDialog", "eventKeepMine", false, "conflict-keep-mine.png"))
        return fail(66, "stale edit could not explicitly save against reviewed latest version");
    auto resolved = api.call("GET", conflictPath);
    if (!resolved.ok() || resolved.data.value("event").toObject().value("title") != QString::fromUtf8("明确选择保留本机修改")
        || resolved.data.value("event").toObject().value("version").toInt() != 3)
        return fail(67, "explicit keep-mine conflict resolution did not save once");
    conflictOriginal = resolved.data.value("event").toObject();
    remoteChanged = api.call("PATCH", conflictPath, {{"base_version", conflictOriginal.value("version")}, {"title", QString::fromUtf8("明确采用另一端日程")}});
    if (!remoteChanged.ok() || !exerciseResolution(conflictOriginal, QString::fromUtf8("不应覆盖另一端的修改"), "eventConflictDialog", "eventUseRemote", false, "conflict-use-remote.png"))
        return fail(68, "use-remote conflict resolution failed");
    resolved = api.call("GET", conflictPath);
    if (!resolved.ok() || resolved.data.value("event").toObject().value("title") != QString::fromUtf8("明确采用另一端日程")
        || resolved.data.value("event").toObject().value("version").toInt() != 4
        || settings.contains(editorPrefix + conflictOriginal.value("id").toString()))
        return fail(69, "use-remote choice unexpectedly wrote or retained conflicting draft");
    conflictOriginal = resolved.data.value("event").toObject();
    const auto removedRemote = api.call("DELETE", conflictPath, {{"base_version", conflictOriginal.value("version")}});
    if (!removedRemote.ok() || !exerciseResolution(conflictOriginal, QString::fromUtf8("另存恢复的本机日程"), "eventDeletedDialog", "eventKeepMine", true, "conflict-deleted.png"))
        return fail(70, "remote deletion could not preserve local edits as a new event");
    const auto cloned = api.call("GET", "/api/events?from=2030-10-30T00:00:00%2B08:00&to=2030-10-31T00:00:00%2B08:00");
    const auto clonedRows = cloned.data.value("events").toArray();
    if (!cloned.ok() || clonedRows.size() != 1 || clonedRows.first().toObject().value("title") != QString::fromUtf8("另存恢复的本机日程")
        || clonedRows.first().toObject().value("id") == conflictOriginal.value("id"))
        return fail(71, "deleted event recovery did not create exactly one new event");
    const auto pairing = api.call("POST", "/api/pairing/issue");
    if (!pairing.ok()) return fail(73, "device pairing could not be issued");
    ApiClient pairedPhone(api.origin);
    const auto paired = pairedPhone.call("POST", "/api/pairing/redeem", {{"token", pairing.data.value("token")},
        {"device_id", QUuid::createUuid().toString(QUuid::WithoutBraces)}, {"device_name", QString::fromUtf8("测试手机")}, {"platform", "android"}});
    const auto pairingId = paired.data.value("pairing_id").toString();
    if (!paired.ok() || pairingId.isEmpty()) return fail(74, "device pairing redemption failed");
    if (!inspectDialog("devicesDialog", "paired-devices.png", [this] { devices(); }, [pairingId](QDialog *dialog) {
        auto *scroll = dialog->findChild<QScrollArea *>("dialogScroll");
        auto *remove = control(dialog, "unpair-" + pairingId);
        return remove && control(dialog, "devicesPair") && scroll && scroll->height() >= 120
            && scroll->viewport()->rect().intersects(QRect(remove->mapTo(scroll->viewport(), QPoint(0, 0)), remove->size()));
    })) return fail(75, "paired-device manager did not expose authorized device removal");
    bool unpairFailed = false;
    int unpairPhase = 0;
    QTimer unpairController;
    unpairController.setInterval(60);
    QElapsedTimer unpairDeadline;
    unpairDeadline.start();
    connect(&unpairController, &QTimer::timeout, this, [&] {
        if (api.busy()) return;
        if (unpairDeadline.elapsed() > 20000) { unpairFailed = true; dismissUnexpected(); return; }
        auto *dialog = qobject_cast<QDialog *>(QApplication::activeModalWidget());
        if (!dialog) return;
        if (unpairPhase == 0 && dialog->objectName() == "devicesDialog") {
            auto *remove = control(dialog, "unpair-" + pairingId);
            if (!remove) { unpairFailed = true; queueReject(dialog); return; }
            unpairPhase = 1; queueClick(remove);
        } else if (unpairPhase == 1 && dialog->objectName() == "confirmDialog") {
            auto *proceed = control(dialog, "primary");
            if (!proceed) { unpairFailed = true; queueReject(dialog); return; }
            unpairPhase = 2; queueClick(proceed);
        } else if (unpairPhase == 2 && dialog->objectName() == "devicesDialog") {
            auto *remove = control(dialog, "unpair-" + pairingId);
            if (remove && remove->isVisible()) { unpairFailed = true; queueReject(dialog); return; }
            unpairPhase = 3; queueReject(dialog);
        }
    });
    unpairController.start(); devices(); unpairController.stop();
    if (unpairFailed || unpairPhase != 3 || events.size() != 1
        || pairedPhone.call("GET", "/api/events?from=2030-01-01T00:00:00Z&to=2030-01-02T00:00:00Z").status != 401)
        return fail(76, "unpairing did not revoke peer access while preserving local events");
    bool migrationShown = false;
    QTimer migrationController;
    migrationController.setInterval(60);
    connect(&migrationController, &QTimer::timeout, this, [&] {
        auto *dialog = qobject_cast<QDialog *>(QApplication::activeModalWidget());
        if (migrationShown || !dialog || dialog->objectName() != "legacySpaceDialog") return;
        migrationShown = true;
        auto *choice = dialog->findChild<QComboBox *>("legacySpaceChoice");
        if (!choice || choice->count() != 2 || !control(dialog, "legacySpaceContinue")) { queueReject(dialog); return; }
        choice->setCurrentIndex(1);
        capture(dialog, "legacy-space-selection.png");
        queueClick(control(dialog, "legacySpaceContinue"));
    });
    migrationController.start();
    const auto selectedLegacy = chooseLegacySpace({QJsonObject{{"id", "11111111-1111-4111-8111-111111111111"}, {"label", QString::fromUtf8("旧日程一")}},
        QJsonObject{{"id", "22222222-2222-4222-8222-222222222222"}, {"label", QString::fromUtf8("旧日程二")}}});
    migrationController.stop();
    if (!migrationShown || selectedLegacy != "22222222-2222-4222-8222-222222222222") return fail(77, "one-time legacy calendar choice failed");
    if (!inspectDialog("modelDialog", "model-settings.png", [this] { models(); }, [](QDialog *dialog) {
        return dialog->findChild<QLineEdit *>("modelBaseUrl") && dialog->findChild<QLineEdit *>("modelApiKey");
    })) return fail(33, "native model settings dialog failed");
    if (!inspectDialog("phoneDialog", "device-pairing.png", [this] { phone(); }, [](QDialog *dialog) {
        auto *address = dialog->findChild<QComboBox *>("phoneAddress");
        if (!address) return false;
        address->setEditText("http://192.168.1.20:43127");
        // Deliberately do not issue a real authentication capability for an image.
        return control(dialog, "phoneGenerate") != nullptr;
    })) return fail(34, "native phone pairing dialog failed");
    if (!inspectDialog("serviceDialog", "service-settings.png", [this] { connection(); }, [](QDialog *dialog) {
        auto *address = dialog->findChild<QLineEdit *>("serviceAddress");
        if (!address) return false;
        address->setText("https://schedule.example.com");
        return true;
    })) return fail(35, "native service settings dialog failed");

    // Actual mouse/keyboard interaction with the date editor's popup. Mouse
    // selection must update the field and close; Enter must commit navigation.
    const QDate original = day->date();
    if (!openDatePopup(day)) return fail(36, "date editor popup did not open from its calendar icon");
    auto *calendar = day->calendarWidget();
    if (!dynamic_cast<ModernCalendarWidget *>(calendar) || !capture(calendar->window(), "date-picker.png")) return fail(37, "date popup did not use modern native calendar");
    auto *nextDate = calendar->findChild<QToolButton *>("calendarDay_" + original.addDays(1).toString(Qt::ISODate));
    if (!nextDate) return fail(38, "calendar date control missing");
    nextDate->click();
    QApplication::processEvents();
    if (day->date() != original.addDays(1) || calendar->isVisible()) return fail(39, "calendar mouse selection did not commit and close");
    if (!openDatePopup(day)) return fail(40, "date popup did not reopen");
    auto *focused = QApplication::focusWidget();
    if (!focused || (focused != calendar && !calendar->isAncestorOf(focused))) return fail(45, "calendar did not receive actual keyboard focus");
    sendKey(focused, Qt::Key_Left);
    sendKey(QApplication::focusWidget(), Qt::Key_Return);
    if (day->date() != original || calendar->isVisible()) return fail(41, "calendar keyboard selection did not commit and close");

    // Additional API-backed documentation fixtures make the main calendar show
    // separate real timeslots, while the first event above proves native input.
    auto draft = [&](const QString &title, const QString &start, const QString &end) {
        return QJsonObject{{"title", title}, {"start_local", eventDay.toString(Qt::ISODate) + "T" + start},
            {"end_local", eventDay.toString(Qt::ISODate) + "T" + end}, {"timezone", "Asia/Shanghai"},
            {"time_source", "explicit"}, {"assumptions", QJsonArray{}}};
    };
    const auto populated = api.call("POST", "/api/events/commit", {{"request_id", QUuid::createUuid().toString(QUuid::WithoutBraces)},
        {"events", QJsonArray{draft(QString::fromUtf8("产品原型讨论"), "14:00:00", "15:00:00"), draft(QString::fromUtf8("整理灵感与笔记"), "18:00:00", "19:00:00")}}});
    if (!populated.ok()) return fail(42, "documentation fixture events failed to save");
    refresh();
    if (events.size() != 3) return fail(43, "saved native/API fixtures were not visible in the real calendar query");
    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    QPointer<QLabel> stableTitle = findChild<QLabel *>("eventTitle");
    if (!stableTitle) return fail(51, "event control missing before unchanged refresh");
    refresh();
    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    if (!stableTitle) return fail(52, "unchanged background refresh replaced existing controls");
    composer->setPlainText(QString::fromUtf8("明天下午三点到四点讨论产品方案，晚上七点读书一小时。"));
    if (!capture(this, "main-desktop.png")) return fail(44, "native main window screenshot failed");
    resize(1060, 720);
    QApplication::processEvents();
    if (!capture(this, "main-desktop-compact.png")) return fail(46, "compact native screenshot failed");
    if (width() != 1060 || modeLabel->wordWrap()
        || modeLabel->fontMetrics().horizontalAdvance(modeLabel->text()) > modeLabel->contentsRect().width())
        return fail(47, "compact window must retain a fully visible single-line rules/model badge");
    auto *bar = findChild<QFrame *>("appBar");
    if (!bar) return fail(48, "native app bar missing");
    for (auto *action : bar->findChildren<QAbstractButton *>()) {
        if (!action->isVisible()) continue;
        const QRect bounds(action->mapTo(this, QPoint(0, 0)), action->size());
        if (!rect().contains(bounds)) return fail(49, "compact header action extends beyond the native window");
    }
    if (accountLabel->wordWrap() || accountLabel->height() < accountLabel->fontMetrics().height()
        || accountLabel->fontMetrics().horizontalAdvance(accountLabel->text()) > accountLabel->contentsRect().width()
        || accountLabel->toolTip() != deviceName)
        return fail(50, "compact device summary must fit and retain the full device name in its tooltip");
    std::cout << "PASS: 1060px header actions and single-line provider badge fit; account summary elides with full tooltip\n";
    std::cout << "PASS: local device opens without login/register; event editor -> editable drafts -> saved event; model/pairing/device dialogs; calendar mouse and keyboard; rendered main calendar\n";
    std::cout << "PASS: unchanged background refresh preserves native controls and focus\n";
    std::cout << "PASS: edited review rows/removals, active cell editor, pending immutability, manual new/existing drafts persist without auto-submit\n";
    std::cout << "PASS: explicit keep-local/use-remote conflict resolution and remote-deletion recovery as one new event\n";
    std::cout << "PASS: native paired-device list and explicit revocation preserve local events; one-time legacy calendar selection\n";
    return 0;
}
