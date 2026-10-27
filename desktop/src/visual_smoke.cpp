#include "window.h"
#include "modern_calendar.h"

#include <QAbstractButton>
#include <QApplication>
#include <QComboBox>
#include <QDateEdit>
#include <QDateTimeEdit>
#include <QDialog>
#include <QDir>
#include <QElapsedTimer>
#include <QEvent>
#include <QJsonArray>
#include <QKeyEvent>
#include <QLineEdit>
#include <QMouseEvent>
#include <QPointer>
#include <QPushButton>
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
    // account/event below is a unique documentation fixture, never user data.
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
    const QString email = "design-" + QUuid::createUuid().toString(QUuid::WithoutBraces) + "@example.com";
    show();
    QApplication::processEvents();

    bool authFailed = false;
    int authPhase = 0;
    QTimer authController;
    authController.setInterval(60);
    QElapsedTimer authDeadline;
    authDeadline.start();
    connect(&authController, &QTimer::timeout, this, [&] {
        if (api.busy()) return;
        if (authDeadline.elapsed() > 20000) { authFailed = true; dismissUnexpected(); return; }
        auto *dialog = qobject_cast<QDialog *>(QApplication::activeModalWidget());
        if (!dialog || dialog->objectName() != "authDialog") return;
        if (authPhase == 0) {
            authPhase = 10;
            if (!capture(dialog, "auth-login.png") || !control(dialog, "authRegisterTab")) { authFailed = true; queueReject(dialog); return; }
            authPhase = 1;
            queueClick(control(dialog, "authRegisterTab"));
        } else if (authPhase == 1) {
            authPhase = 11;
            auto *emailInput = dialog->findChild<QLineEdit *>("authEmail");
            auto *password = dialog->findChild<QLineEdit *>("authPassword");
            auto *submit = control(dialog, "authSubmit");
            if (!emailInput || !password || !submit || !capture(dialog, "auth-register.png")) { authFailed = true; queueReject(dialog); return; }
            emailInput->setText(email);
            password->setText("Native-design-test-only-2026!");
            authPhase = 2;
            queueClick(submit);
        }
    });
    authController.start();
    auth();
    authController.stop();
    if (authFailed || authPhase != 2 || owner().isEmpty()) return fail(31, "native registration dialog did not create an authenticated account");

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
    if (!inspectDialog("modelDialog", "model-settings.png", [this] { models(); }, [](QDialog *dialog) {
        return dialog->findChild<QLineEdit *>("modelBaseUrl") && dialog->findChild<QLineEdit *>("modelApiKey");
    })) return fail(33, "native model settings dialog failed");
    if (!inspectDialog("phoneDialog", "phone-login.png", [this] { phone(); }, [](QDialog *dialog) {
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
    composer->setPlainText(QString::fromUtf8("明天下午三点到四点讨论产品方案，晚上七点读书一小时。"));
    if (!capture(this, "main-desktop.png")) return fail(44, "native main window screenshot failed");
    std::cout << "PASS: actual native login/register; event editor -> editable drafts -> saved event; model/phone/service dialogs; calendar mouse and keyboard; rendered main calendar\n";
    return 0;
}
