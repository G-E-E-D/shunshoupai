#include "window.h"
#include "connection.h"
#include "modern_calendar.h"
#include "qrcodegen.hpp"
#include "draft_state.h"
#include <QApplication>
#include <QButtonGroup>
#include <QComboBox>
#include <QCryptographicHash>
#include <QDateEdit>
#include <QDateTimeEdit>
#include <QDialog>
#include <QFrame>
#include <QGraphicsDropShadowEffect>
#include <QHeaderView>
#include <QHostAddress>
#include <QImage>
#include <QJsonDocument>
#include <QLabel>
#include <QLineEdit>
#include <QMouseEvent>
#include <QPainter>
#include <QPushButton>
#include <QScreen>
#include <QSignalBlocker>
#include <QScrollArea>
#include <QStyle>
#include <QStyledItemDelegate>
#include <QTableWidget>
#include <QTextEdit>
#include <QTimer>
#include <QToolButton>
#include <QUuid>
#include <QVBoxLayout>
#include <QWindow>
#include <stdexcept>

namespace {
QString text(const char *value) { return QString::fromUtf8(value); }

QLabel *copy(const QString &value, const QString &name = {}) {
    auto *label = new QLabel(value);
    label->setObjectName(name);
    label->setWordWrap(true);
    label->setTextFormat(Qt::PlainText);
    return label;
}

QPushButton *action(const QString &value, const QString &name = {}) {
    auto *button = new QPushButton(value);
    button->setObjectName(name);
    button->setCursor(Qt::PointingHandCursor);
    button->setMinimumHeight(44);
    button->setAutoDefault(false);
    return button;
}

// Move the native dialog by its custom header while keeping every field and
// button in Qt's normal focus/accessibility system.
class DialogHeader final : public QWidget {
protected:
    void mousePressEvent(QMouseEvent *event) override {
        if (event->button() == Qt::LeftButton && window()->windowHandle())
            window()->windowHandle()->startSystemMove();
        QWidget::mousePressEvent(event);
    }
};

struct DialogUi {
    QVBoxLayout *body;
    QLabel *title;
    QLabel *subtitle;
};

DialogUi prepare(QDialog &dialog, const QString &name, const QString &title,
                 const QString &subtitle, int width) {
    dialog.setObjectName(name);
    dialog.setProperty("modern", true);
    dialog.setWindowTitle(title);
    dialog.setWindowFlags(Qt::Dialog | Qt::FramelessWindowHint);
    dialog.setWindowModality(Qt::WindowModal);
    dialog.setAttribute(Qt::WA_TranslucentBackground);
    dialog.setFixedWidth(width);
    dialog.setSizeGripEnabled(false);
    auto *outer = new QVBoxLayout(&dialog);
    outer->setContentsMargins(14, 14, 14, 14);
    // A minimum-size layout would overwrite QDialog's fixed width with its
    // content minimum when adjustSize runs, collapsing forms into narrow cards.
    outer->setSizeConstraint(QLayout::SetNoConstraint);
    auto *surface = new QFrame;
    surface->setObjectName("dialogSurface");
    auto *shadow = new QGraphicsDropShadowEffect(surface);
    shadow->setBlurRadius(26);
    shadow->setOffset(0, 5);
    shadow->setColor(QColor(32, 33, 36, 28));
    surface->setGraphicsEffect(shadow);
    outer->addWidget(surface);
    auto *surfaceLayout = new QVBoxLayout(surface);
    surfaceLayout->setContentsMargins(28, 24, 28, 28);
    surfaceLayout->setSpacing(18);
    auto *header = new DialogHeader;
    auto *top = new QHBoxLayout(header);
    top->setContentsMargins(0, 0, 0, 0);
    top->setSpacing(12);
    auto *heading = new QVBoxLayout;
    heading->setSpacing(8);
    auto *eyebrow = copy(text("顺手排"), "dialogEyebrow");
    heading->addWidget(eyebrow);
    auto *titleLabel = copy(title, "dialogTitle");
    heading->addWidget(titleLabel);
    top->addLayout(heading, 1);
    auto *close = new QToolButton;
    close->setObjectName("dialogClose");
    close->setText(QString::fromUtf8("×"));
    close->setFixedSize(40, 40);
    close->setCursor(Qt::PointingHandCursor);
    close->setAccessibleName(text("关闭弹窗"));
    close->setToolTip(text("关闭"));
    close->setAutoRaise(true);
    top->addWidget(close, 0, Qt::AlignTop);
    QObject::connect(close, &QToolButton::clicked, &dialog, &QDialog::reject);
    surfaceLayout->addWidget(header);
    auto *description = copy(subtitle, "dialogSubtitle");
    description->setVisible(!subtitle.isEmpty());
    surfaceLayout->addWidget(description);
    auto *scroll = new QScrollArea;
    scroll->setObjectName("dialogScroll");
    scroll->setFrameShape(QFrame::NoFrame);
    scroll->setWidgetResizable(true);
    scroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scroll->viewport()->setObjectName("dialogViewport");
    auto *content = new QWidget;
    content->setObjectName("dialogContent");
    auto *body = new QVBoxLayout(content);
    body->setContentsMargins(0, 0, 0, 0);
    body->setSpacing(18);
    body->setSizeConstraint(QLayout::SetMinimumSize);
    scroll->setWidget(content);
    surfaceLayout->addWidget(scroll);
    auto *fixedFooter = new QVBoxLayout;
    fixedFooter->setContentsMargins(0, 0, 0, 0);
    surfaceLayout->addLayout(fixedFooter);
    body->setProperty("footerTarget", QVariant::fromValue<QObject *>(fixedFooter));
    // Fit small laptop screens without compressing fields or hiding actions.
    // The title and footer stay fixed; only long form content scrolls.
    QTimer::singleShot(0, &dialog, [&dialog, scroll, content, surface, surfaceLayout, fixedFooter, width] {
        dialog.setFixedWidth(width);
        dialog.layout()->activate();
        surfaceLayout->activate();
        content->layout()->activate();
        const auto bounds = dialog.screen()->availableGeometry().adjusted(16, 16, -16, -16);
        const int wanted = content->sizeHint().height();
        const int fixed = surface->sizeHint().height() - scroll->sizeHint().height()
            + 28 + (fixedFooter->count() == 0 ? -surfaceLayout->spacing() : 0);
        const int room = qMax(120, bounds.height() - fixed);
        const int height = qMin(wanted, room);
        scroll->setFixedHeight(height);
        dialog.adjustSize();
        dialog.setFixedWidth(width);
        dialog.resize(width, dialog.sizeHint().height());
        auto position = dialog.frameGeometry();
        position.moveCenter(dialog.parentWidget() ? dialog.parentWidget()->frameGeometry().center() : bounds.center());
        position.moveLeft(qBound(bounds.left(), position.left(), qMax(bounds.left(), bounds.right() - position.width() + 1)));
        position.moveTop(qBound(bounds.top(), position.top(), qMax(bounds.top(), bounds.bottom() - position.height() + 1)));
        dialog.move(position.topLeft());
    });
    return {body, titleLabel, description};
}

void field(QVBoxLayout *layout, const QString &title, QWidget *editor,
           const QString &hint = {}) {
    auto *group = new QVBoxLayout;
    group->setSpacing(8);
    auto *caption = copy(title, "fieldLabel");
    caption->setBuddy(editor);
    group->addWidget(caption);
    editor->setMinimumHeight(46);
    group->addWidget(editor);
    if (!hint.isEmpty()) group->addWidget(copy(hint, "fieldHint"));
    layout->addLayout(group);
}

QLabel *messageArea(QVBoxLayout *layout) {
    auto *message = copy({}, "inlineMessage");
    message->hide();
    layout->addWidget(message);
    return message;
}

void message(QLabel *label, const QString &value, bool error = false) {
    label->setText(value);
    label->setObjectName(error ? "errorMessage" : "inlineMessage");
    label->setVisible(!value.isEmpty());
    label->style()->unpolish(label);
    label->style()->polish(label);
    label->updateGeometry();
}

void footer(QVBoxLayout *layout, QPushButton *primary, QPushButton *secondary,
            QWidget *leading = nullptr) {
    auto *row = new QHBoxLayout;
    row->setContentsMargins(0, 6, 0, 0);
    row->setSpacing(10);
    if (leading) row->addWidget(leading);
    row->addStretch();
    if (secondary) row->addWidget(secondary);
    row->addWidget(primary);
    auto *target = static_cast<QVBoxLayout *>(layout->property("footerTarget").value<QObject *>());
    (target ? target : layout)->addLayout(row);
}

bool confirm(QWidget *parent, const QString &title, const QString &description,
             const QString &affirmative = text("确认")) {
    QDialog dialog(parent);
    auto ui = prepare(dialog, "confirmDialog", title, description, 480);
    auto *cancel = action(text("取消"), "textButton");
    auto *proceed = action(affirmative, "primary");
    proceed->setDefault(true);
    QObject::connect(cancel, &QPushButton::clicked, &dialog, &QDialog::reject);
    QObject::connect(proceed, &QPushButton::clicked, &dialog, &QDialog::accept);
    footer(ui.body, proceed, cancel);
    return dialog.exec() == QDialog::Accepted;
}

void information(QWidget *parent, const QString &title, const QString &description) {
    QDialog dialog(parent);
    auto ui = prepare(dialog, "informationDialog", title, description, 500);
    auto *ok = action(text("知道了"), "primary");
    ok->setDefault(true);
    QObject::connect(ok, &QPushButton::clicked, &dialog, &QDialog::accept);
    footer(ui.body, ok, nullptr);
    dialog.exec();
}

// 0 = decide later, 1 = use the remote state, 2 = explicitly keep local edits.
int resolveEventConflict(QWidget *parent, const QJsonObject &remote, const QJsonObject &mine, bool deleted) {
    QDialog dialog(parent);
    auto ui = prepare(dialog, deleted ? "eventDeletedDialog" : "eventConflictDialog",
        deleted ? text("这条日程已在另一端删除") : text("这条日程在另一端更新了"),
        text("你的修改仍保留在本机。核对后再选择如何处理。"), 660);
    auto addVersion = [&](const QString &heading, const QString &title, const QString &start, const QString &end, const QString &state) {
        ui.body->addWidget(copy(heading, "fieldLabel"));
        ui.body->addWidget(copy(title, "eventTitle"));
        const auto startTime = QDateTime::fromString(start, Qt::ISODate);
        const auto endTime = QDateTime::fromString(end, Qt::ISODate);
        ui.body->addWidget(copy(startTime.toString("yyyy-MM-dd HH:mm") + " – " + endTime.toString("yyyy-MM-dd HH:mm")
            + text(" · 上海时区") + state, "fieldHint"));
    };
    if (!deleted) addVersion(text("另一端的最新日程"), remote.value("title").toString(),
        QDateTime::fromString(remote.value("start_at").toString(), Qt::ISODate).toTimeZone(QTimeZone("Asia/Shanghai")).toString(Qt::ISODate),
        QDateTime::fromString(remote.value("end_at").toString(), Qt::ISODate).toTimeZone(QTimeZone("Asia/Shanghai")).toString(Qt::ISODate),
        remote.value("status") == "completed" ? text(" · 已完成") : text(" · 待进行"));
    addVersion(text("你保留的修改"), mine.value("title").toString(), mine.value("start_local").toString(), mine.value("end_local").toString(), {});
    auto *keep = action(deleted ? text("保留为新日程") : text("保留我的修改并保存"), "primary");
    keep->setProperty("controlId", "eventKeepMine");
    auto *useRemote = action(deleted ? text("放弃本机修改") : text("使用另一端版本"), "textButton");
    useRemote->setProperty("controlId", "eventUseRemote");
    auto *later = action(text("稍后处理"), "textButton");
    later->setProperty("controlId", "eventResolveLater");
    int choice = 0;
    QObject::connect(keep, &QPushButton::clicked, &dialog, [&] { choice = 2; dialog.accept(); });
    QObject::connect(useRemote, &QPushButton::clicked, &dialog, [&] { choice = 1; dialog.accept(); });
    QObject::connect(later, &QPushButton::clicked, &dialog, &QDialog::reject);
    footer(ui.body, keep, later, useRemote);
    return dialog.exec() == QDialog::Accepted ? choice : 0;
}

QStringList strings(const QJsonArray &values) {
    QStringList result;
    for (const auto &value : values) result << value.toString();
    return result;
}

QString requestId() { return QUuid::createUuid().toString(QUuid::WithoutBraces); }
QDateTime localTime(const QString &value) {
    return QDateTime::fromString(value, Qt::ISODate).toTimeZone(QTimeZone("Asia/Shanghai"));
}
QString pendingKey(const QString &origin, const QString &owner) {
    return DraftState::key("pending", origin, owner);
}

bool usableOrigin(const QString &value, QUrl &url, bool phone = false) {
    try {
        url = QUrl(QString::fromStdString(shunshoupai::parseConnection(value.toStdString(), false).origin), QUrl::StrictMode);
    } catch (const std::invalid_argument &) { return false; }
    auto host = url.host().toLower();
    if (host.endsWith('.')) host.chop(1);
    const QHostAddress ip(host);
    if (phone && (host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.")
        || ip.isLoopback() || ip == QHostAddress::AnyIPv6 || ip == QHostAddress::AnyIPv4)) return false;
    url.setPath("");
    return true;
}

class DraftTitleDelegate final : public QStyledItemDelegate {
public:
    using QStyledItemDelegate::QStyledItemDelegate;
    QWidget *createEditor(QWidget *parent, const QStyleOptionViewItem &, const QModelIndex &) const override {
        auto *editor = new QLineEdit(parent);
        auto *delegate = const_cast<DraftTitleDelegate *>(this);
        QObject::connect(editor, &QLineEdit::textChanged, delegate, [delegate, editor] { emit delegate->commitData(editor); });
        return editor;
    }
    void setEditorData(QWidget *editor, const QModelIndex &index) const override {
        const QSignalBlocker blocked(editor);
        auto *title = static_cast<QLineEdit *>(editor);
        const auto value = index.data(Qt::EditRole).toString();
        if (title->text() != value) title->setText(value);
    }
    void setModelData(QWidget *editor, QAbstractItemModel *model, const QModelIndex &index) const override {
        model->setData(index, static_cast<QLineEdit *>(editor)->text(), Qt::EditRole);
    }
};

class DraftTimeDelegate final : public QStyledItemDelegate {
public:
    using QStyledItemDelegate::QStyledItemDelegate;
    QWidget *createEditor(QWidget *parent, const QStyleOptionViewItem &, const QModelIndex &) const override {
        auto *editor = new QDateTimeEdit(parent);
        editor->setMinimumDate(QDate(2000, 1, 1));
        editor->setMaximumDate(QDate(2100, 12, 31));
        editor->setDisplayFormat("yyyy-MM-dd HH:mm");
        installModernDateTimeEdit(editor);
        auto *delegate = const_cast<DraftTimeDelegate *>(this);
        QObject::connect(editor, &QDateTimeEdit::dateTimeChanged, delegate, [delegate, editor] { emit delegate->commitData(editor); });
        return editor;
    }
    void setEditorData(QWidget *editor, const QModelIndex &index) const override {
        auto *date = static_cast<QDateTimeEdit *>(editor);
        const QSignalBlocker blocked(date);
        const auto value = QDateTime::fromString(index.data(Qt::EditRole).toString(), Qt::ISODate);
        if (date->dateTime() != value) date->setDateTime(value);
    }
    void setModelData(QWidget *editor, QAbstractItemModel *model, const QModelIndex &index) const override {
        auto *date = static_cast<QDateTimeEdit *>(editor);
        model->setData(index, date->dateTime().toString("yyyy-MM-ddTHH:mm:ss"), Qt::EditRole);
    }
    QString displayText(const QVariant &value, const QLocale &) const override {
        const auto date = QDateTime::fromString(value.toString(), Qt::ISODate);
        return date.isValid() ? date.toString(QString::fromUtf8("MM月dd日 HH:mm")) : value.toString();
    }
};
}

void MainWindow::auth() {
    if (!owner().isEmpty()) {
        if (!confirm(this, text("退出当前账号？"), text("未提交的草稿与待确认保存会按账号保留，下次登录后可以继续。"), text("退出账号"))) return;
        const auto result = api.call("POST", "/api/auth/logout");
        api.forgetSession();
        setSession({});
        status->setText(result.ok() ? text("已退出登录。") : text("已退出本机登录。服务暂时不可用，远端会话将在到期后失效。"));
        return;
    }
    QDialog dialog(this);
    auto ui = prepare(dialog, "authDialog", text("欢迎回来"), text("登录一次，下次打开会自动接着使用。"), 508);
    auto *tabs = new QFrame;
    tabs->setObjectName("segmentedBar");
    auto *tabLayout = new QHBoxLayout(tabs);
    tabLayout->setContentsMargins(4, 4, 4, 4);
    tabLayout->setSpacing(4);
    auto *login = action(text("登录"), "segmentButton");
    auto *registration = action(text("创建账号"), "segmentButton");
    login->setProperty("controlId", "authLoginTab");
    registration->setProperty("controlId", "authRegisterTab");
    login->setAccessibleName(text("登录账号"));
    registration->setAccessibleName(text("创建新账号"));
    login->setCheckable(true);
    registration->setCheckable(true);
    auto *group = new QButtonGroup(&dialog);
    group->addButton(login, 0);
    group->addButton(registration, 1);
    login->setChecked(true);
    tabLayout->addWidget(login);
    tabLayout->addWidget(registration);
    ui.body->addWidget(tabs);
    QLineEdit email, password;
    email.setObjectName("authEmail");
    email.setPlaceholderText("you@example.com");
    email.setClearButtonEnabled(true);
    email.setInputMethodHints(Qt::ImhEmailCharactersOnly | Qt::ImhNoAutoUppercase);
    const auto emailKey = "last_email/" + QString::fromLatin1(QCryptographicHash::hash(api.origin.toUtf8(), QCryptographicHash::Sha256).toHex());
    email.setText(settings.value(emailKey).toString());
    password.setObjectName("authPassword");
    password.setEchoMode(QLineEdit::Password);
    password.setPlaceholderText(text("输入你的密码"));
    field(ui.body, text("邮箱地址"), &email);
    field(ui.body, text("密码"), &password);
    auto *notice = messageArea(ui.body);
    auto *submit = action(text("登录"), "primary");
    submit->setProperty("controlId", "authSubmit");
    submit->setDefault(true);
    submit->setMinimumHeight(48);
    ui.body->addWidget(submit);
    ui.body->addWidget(copy(text("你的日程与模型配置仅对自己的账号可见。"), "fieldHint"));
    connect(group, &QButtonGroup::idClicked, &dialog, [&](int index) {
        const bool registering = index == 1;
        ui.title->setText(registering ? text("让安排有个新家") : text("欢迎回来"));
        ui.subtitle->setText(registering ? text("创建自己的账号，开始整理每一天。") : text("登录一次，下次打开会自动接着使用。"));
        submit->setText(registering ? text("创建账号") : text("登录"));
        password.setPlaceholderText(registering ? text("设置至少 8 位密码") : text("输入你的密码"));
        password.clear();
        message(notice, {});
    });
    connect(submit, &QPushButton::clicked, &dialog, [&] {
        const auto result = api.call("POST", group->checkedId() == 0 ? "/api/auth/login" : "/api/auth/register",
            {{"email", email.text().trimmed()}, {"password", password.text()}});
        if (!result.ok()) { message(notice, result.error, true); return; }
        password.clear();
        if (result.data.value("user").isNull()) { message(notice, result.data.value("message").toString()); return; }
        settings.setValue(emailKey, email.text().trimmed());
        setSession(result.data);
        dialog.accept();
    });
    connect(&password, &QLineEdit::returnPressed, submit, &QPushButton::click);
    if (email.text().isEmpty()) email.setFocus(); else password.setFocus();
    if (dialog.exec() == QDialog::Accepted) refresh();
    password.clear();
}

void MainWindow::drafts(QJsonArray rows, QString request) {
    QDialog dialog(this);
    auto ui = prepare(dialog, "draftsDialog", text("确认你的安排"), text("修改会自动保留。只有点击确认保存，才会加入正式日程。"), 960);
    auto *zoneHint = copy(text("上海时区 · UTC+8"), "fieldHint");
    ui.body->addWidget(zoneHint);
    QTableWidget table(rows.size(), 4);
    table.setObjectName("draftTable");
    table.setHorizontalHeaderLabels({text("安排"), text("开始"), text("结束"), text("时间说明")});
    table.horizontalHeader()->setDefaultAlignment(Qt::AlignLeft | Qt::AlignVCenter);
    table.horizontalHeader()->setSectionResizeMode(QHeaderView::Stretch);
    table.verticalHeader()->hide();
    table.verticalHeader()->setDefaultSectionSize(58);
    table.setShowGrid(false);
    table.setAlternatingRowColors(false);
    table.setSelectionBehavior(QAbstractItemView::SelectRows);
    table.setSelectionMode(QAbstractItemView::SingleSelection);
    table.setMinimumHeight(qBound(180, rows.size() * 58 + 50, 360));
    table.setItemDelegateForColumn(0, new DraftTitleDelegate(&table));
    table.setItemDelegateForColumn(1, new DraftTimeDelegate(&table));
    table.setItemDelegateForColumn(2, new DraftTimeDelegate(&table));
    for (int i = 0; i < rows.size(); ++i) {
        auto event = rows[i].toObject();
        const QStringList values{event.value("title").toString(), event.value("start_local").toString(), event.value("end_local").toString(), strings(event.value("assumptions").toArray()).join(text("；"))};
        for (int j = 0; j < 4; ++j) {
            auto *item = new QTableWidgetItem(values[j]);
            if (j == 3) {
                item->setFlags(item->flags() & ~Qt::ItemIsEditable);
                if (values[j].isEmpty()) item->setText(text("时间已明确"));
            }
            table.setItem(i, j, item);
        }
    }
    ui.body->addWidget(&table);
    auto *notice = messageArea(ui.body);
    auto *save = action(text("确认保存"), "primary");
    save->setProperty("controlId", "draftSave");
    save->setDefault(true);
    auto *remove = action(text("移除选中项"), "destructiveButton");
    remove->setProperty("controlId", "draftRemove");
    auto *cancel = action(text("暂不保存"), "textButton");
    cancel->setProperty("controlId", "draftCancel");
    auto *discard = action(text("放弃草稿"), "textButton");
    discard->setProperty("controlId", "draftDiscard");
    auto *leading = new QWidget;
    auto *leadingLayout = new QHBoxLayout(leading);
    leadingLayout->setContentsMargins(0, 0, 0, 0);
    leadingLayout->addWidget(remove); leadingLayout->addWidget(discard);
    footer(ui.body, save, cancel, leading);
    const QString key = pendingKey(api.origin, owner());
    const QString reviewKey = DraftState::key("review", api.origin, owner());
    bool finished = false;
    auto collectRows = [&] {
        for (int i = 0; i < rows.size(); ++i) {
            auto event = rows[i].toObject();
            event.insert("title", table.item(i, 0)->text());
            event.insert("start_local", table.item(i, 1)->text());
            event.insert("end_local", table.item(i, 2)->text());
            rows[i] = event;
        }
    };
    auto preserveReview = [&] {
        if (finished || !request.isEmpty()) return;
        collectRows();
        if (rows.isEmpty()) { settings.remove(reviewKey); settings.sync(); }
        else DraftState::write(settings, reviewKey, {{"events", rows}});
        if (settings.status() != QSettings::NoError)
            message(notice, text("草稿暂时无法写入本机，请保留窗口并检查磁盘空间。"), true);
    };
    if (!request.isEmpty()) {
        table.setEditTriggers(QAbstractItemView::NoEditTriggers);
        remove->setEnabled(false);
        discard->hide();
        save->setText(text("确认上次保存结果"));
        message(notice, text("上次保存结果待确认，将使用原编号与内容核实，避免重复创建。"));
    } else preserveReview();
    connect(&table, &QTableWidget::itemChanged, &dialog, [&](QTableWidgetItem *) { preserveReview(); });
    connect(remove, &QPushButton::clicked, &dialog, [&] {
        const int selected = table.currentRow();
        if (selected >= 0) {
            const QSignalBlocker blocked(&table);
            table.removeRow(selected); rows.removeAt(selected);
            preserveReview();
        }
    });
    connect(discard, &QPushButton::clicked, &dialog, [&] {
        if (!request.isEmpty()) return;
        if (!confirm(&dialog, text("放弃这份草稿？"), text("这会删除尚未提交的修改，正式日程不受影响。"), text("放弃草稿"))) return;
        finished = true;
        settings.remove(reviewKey); settings.sync();
        dialog.reject();
    });
    connect(cancel, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(save, &QPushButton::clicked, &dialog, [&] {
        if (table.rowCount() == 0) { message(notice, text("至少保留一项草稿。"), true); return; }
        table.clearFocus();
        const bool recoveringPending = !request.isEmpty();
        if (request.isEmpty()) {
            collectRows();
            for (int i = 0; i < rows.size(); ++i) {
                auto event = rows[i].toObject();
                event.insert("title", table.item(i, 0)->text().trimmed());
                event.insert("start_local", table.item(i, 1)->text().trimmed());
                event.insert("end_local", table.item(i, 2)->text().trimmed());
                rows[i] = event;
            }
            request = requestId();
        }
        const QJsonObject payload{{"request_id", request}, {"events", rows}};
        settings.setValue(key, QString::fromUtf8(QJsonDocument(payload).toJson(QJsonDocument::Compact)));
        settings.sync();
        if (settings.status() != QSettings::NoError) {
            if (!recoveringPending) {
                request.clear();
                settings.remove(key);
                preserveReview();
            }
            message(notice, text("暂时无法保存请求编号，请保留窗口并检查磁盘空间后重试。"), true);
            return;
        }
        table.setEditTriggers(QAbstractItemView::NoEditTriggers);
        remove->setEnabled(false);
        discard->hide();
        auto result = api.call("POST", "/api/events/commit", payload);
        if (result.ok()) {
            finished = true;
            settings.remove(key); settings.remove(reviewKey); settings.sync();
            status->setText(result.data.value("replayed").toBool() ? text("已确认上次保存成功，没有重复创建。") : text("日程已保存。"));
            dialog.accept();
        } else {
            message(notice, result.error, true);
            const auto code = result.data.value("error").toObject().value("code").toString();
            if (code == "VALIDATION_ERROR" || code == "TIME_CONFLICT") {
                settings.remove(key);
                request.clear();
                preserveReview();
                table.setEditTriggers(QAbstractItemView::DoubleClicked | QAbstractItemView::EditKeyPressed);
                remove->setEnabled(true);
                discard->show();
                save->setText(text("确认保存"));
            } else {
                message(notice, result.error + text(" 使用原编号重试，或稍后从主界面恢复。"), true);
                save->setText(text("确认上次保存结果"));
            }
        }
    });
    dialog.exec();
    preserveReview();
    refresh();
}

void MainWindow::editEvent(QJsonObject event) {
    if (owner().isEmpty()) { auth(); if (owner().isEmpty()) return; }
    if (event.isEmpty() && (!DraftState::read(settings, pendingKey(api.origin, owner())).isEmpty()
        || !DraftState::read(settings, DraftState::key("review", api.origin, owner())).isEmpty())) {
        restorePending(); return;
    }
    const QString editorKey = DraftState::key("editor", api.origin, owner()) + "/" + (event.isEmpty() ? "new" : event.value("id").toString());
    const auto saved = DraftState::read(settings, editorKey);
    // Keep the version that the user actually edited. Reopening must not turn
    // stale text into an unnoticed overwrite of another device's newer event.
    if (!saved.isEmpty()) event = saved.value("event").toObject();
    QDialog dialog(this);
    auto ui = prepare(dialog, "eventDialog", event.isEmpty() ? text("添加一项安排") : text("编辑日程"), text("修改会自动保留，关闭后可继续编辑。"), 590);
    QLineEdit title(saved.isEmpty() ? event.value("title").toString() : saved.value("title").toString());
    title.setObjectName("eventTitle");
    title.setPlaceholderText(text("为这项安排取个名字"));
    title.setMaxLength(120);
    title.setClearButtonEnabled(true);
    field(ui.body, text("日程标题"), &title);
    QDateTimeEdit start, end;
    start.setObjectName("eventStart");
    end.setObjectName("eventEnd");
    for (auto *edit : {&start, &end}) {
        edit->setDisplayFormat("yyyy-MM-dd HH:mm");
        edit->setMinimumDate(QDate(2000, 1, 1));
        edit->setMaximumDate(QDate(2100, 12, 31));
        installModernDateTimeEdit(edit);
    }
    start.setDateTime(event.isEmpty() ? QDateTime(day->date(), QTime(9, 0)) : QDateTime::fromString(localTime(event.value("start_at").toString()).toString("yyyy-MM-ddTHH:mm:ss"), Qt::ISODate));
    end.setDateTime(event.isEmpty() ? start.dateTime().addSecs(3600) : QDateTime::fromString(localTime(event.value("end_at").toString()).toString("yyyy-MM-ddTHH:mm:ss"), Qt::ISODate));
    const auto originalStart = start.dateTime();
    const auto originalEnd = end.dateTime();
    if (!saved.isEmpty()) {
        start.setDateTime(QDateTime::fromString(saved.value("start_local").toString(), Qt::ISODate));
        end.setDateTime(QDateTime::fromString(saved.value("end_local").toString(), Qt::ISODate));
    }
    field(ui.body, text("开始时间"), &start);
    field(ui.body, text("结束时间"), &end, text("上海时区（UTC+8）"));
    auto *notice = messageArea(ui.body);
    auto *cancel = action(text("稍后继续"), "textButton");
    cancel->setProperty("controlId", "eventCancel");
    auto *discard = action(text("放弃修改"), "textButton");
    discard->setProperty("controlId", "eventDiscard");
    auto *save = action(event.isEmpty() ? text("检查草稿") : text("保存更改"), "primary");
    save->setProperty("controlId", "eventSave");
    save->setDefault(true);
    footer(ui.body, save, cancel, discard);
    bool finished = false;
    auto preserveEditor = [&] {
        if (finished) return;
        const bool changed = title.text() != event.value("title").toString()
            || start.dateTime() != originalStart || end.dateTime() != originalEnd;
        discard->setEnabled(changed);
        if (!changed) { settings.remove(editorKey); settings.sync(); return; }
        DraftState::write(settings, editorKey, {{"event", event}, {"title", title.text()},
            {"start_local", start.dateTime().toString("yyyy-MM-ddTHH:mm:ss")},
            {"end_local", end.dateTime().toString("yyyy-MM-ddTHH:mm:ss")}});
        if (settings.status() != QSettings::NoError)
            message(notice, text("修改暂时无法写入本机，请保留窗口并检查磁盘空间。"), true);
    };
    connect(&title, &QLineEdit::textChanged, &dialog, [&](const QString &) { preserveEditor(); });
    connect(&start, &QDateTimeEdit::dateTimeChanged, &dialog, [&](const QDateTime &) { preserveEditor(); });
    connect(&end, &QDateTimeEdit::dateTimeChanged, &dialog, [&](const QDateTime &) { preserveEditor(); });
    connect(discard, &QPushButton::clicked, &dialog, [&] {
        if (!confirm(&dialog, text("放弃这次修改？"), text("正式日程会保持原样，这次尚未保存的修改将被删除。"), text("放弃修改"))) return;
        finished = true;
        settings.remove(editorKey); settings.sync();
        dialog.reject();
    });
    preserveEditor();
    connect(cancel, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(save, &QPushButton::clicked, &dialog, [&] {
        QJsonObject patch{{"title", title.text().trimmed()}, {"start_local", start.dateTime().toString("yyyy-MM-ddTHH:mm:ss")}, {"end_local", end.dateTime().toString("yyyy-MM-ddTHH:mm:ss")}};
        auto keepAsNew = [&] {
            if (!DraftState::read(settings, pendingKey(api.origin, owner())).isEmpty()) {
                message(notice, text("还有一份保存结果待确认。你的修改已保留，请先确认上次保存结果，再保留为新日程。"), true);
                return;
            }
            auto draft = patch;
            draft.remove("base_version");
            draft.insert("timezone", "Asia/Shanghai"); draft.insert("time_source", "explicit"); draft.insert("assumptions", QJsonArray{});
            const auto reviewKey = DraftState::key("review", api.origin, owner());
            auto reviewRows = DraftState::read(settings, reviewKey).value("events").toArray();
            reviewRows.append(draft);
            DraftState::write(settings, reviewKey, {{"events", reviewRows}});
            if (settings.status() != QSettings::NoError) {
                message(notice, text("暂时无法保存草稿，请保留窗口并检查磁盘空间。"), true); return;
            }
            finished = true;
            settings.remove(editorKey); settings.sync();
            dialog.accept();
            drafts(reviewRows);
        };
        if (event.isEmpty()) { keepAsNew(); return; }
        patch.insert("base_version", event.value("version"));
        auto result = api.call("PATCH", "/api/events/" + event.value("id").toString(), patch);
        if (!result.ok()) {
            const auto code = result.data.value("error").toObject().value("code").toString();
            if (code != "VERSION_CONFLICT" && code != "NOT_FOUND") { message(notice, result.error, true); return; }
            const auto latest = api.call("GET", "/api/events/" + event.value("id").toString());
            if (!latest.ok() && latest.status != 404) {
                message(notice, text("暂时无法核对另一端的最新日程。你的修改已保留，恢复连接后点击保存即可继续核对。"), true); return;
            }
            const auto remote = latest.data.value("event").toObject();
            if (latest.ok() && (remote.value("id") != event.value("id") || remote.value("version").toInt() < 1)) {
                message(notice, text("服务未返回可核对的最新日程。你的修改已保留。"), true); return;
            }
            const bool deleted = latest.status == 404 || !remote.value("deleted_at").toString().isEmpty();
            const int choice = resolveEventConflict(&dialog, remote, patch, deleted);
            if (choice == 0) return;
            if (choice == 1) {
                finished = true;
                settings.remove(editorKey); settings.sync();
                dialog.accept();
                return;
            }
            if (deleted) { keepAsNew(); return; }
            // Only this explicit choice authorizes using the displayed newer
            // version. A further concurrent edit must still produce a conflict.
            patch.insert("base_version", remote.value("version"));
            result = api.call("PATCH", "/api/events/" + event.value("id").toString(), patch);
            if (!result.ok()) {
                message(notice, result.error + text(" 你的修改仍保留，再次点击保存可重新核对最新版本。"), true); return;
            }
        }
        finished = true;
        settings.remove(editorKey); settings.sync();
        dialog.accept();
    });
    title.setFocus();
    dialog.exec();
    preserveEditor();
    refresh();
}

void MainWindow::models() {
    if (owner().isEmpty()) return;
    auto loaded = api.call("GET", "/api/model-settings");
    if (!loaded.ok()) { showError(loaded); return; }
    auto config = loaded.data.value("settings").toObject();
    QDialog dialog(this);
    auto ui = prepare(dialog, "modelDialog", text("连接你的模型"), text("使用自己的模型接口，电脑与手机共用一份配置。"), 640);
    auto *note = new QFrame;
    note->setObjectName("tonalCard");
    auto *noteLayout = new QVBoxLayout(note);
    noteLayout->setContentsMargins(16, 14, 16, 14);
    noteLayout->addWidget(copy(text("日程提示词已由应用统一维护"), "fieldLabel"));
    noteLayout->addWidget(copy(text("你只需填写接口与密钥，无需编写提示词。请使用自己信任的服务。"), "fieldHint"));
    ui.body->addWidget(note);
    QLineEdit base(config.value("base_url").toString()), model(config.value("model").toString()), key;
    base.setObjectName("modelBaseUrl");
    model.setObjectName("modelName");
    key.setObjectName("modelApiKey");
    base.setPlaceholderText("https://api.example.com/v1");
    model.setPlaceholderText(text("服务商提供的模型 ID"));
    key.setEchoMode(QLineEdit::Password);
    key.setPlaceholderText(config.value("has_api_key").toBool() ? text("留空保留密钥 · ") + config.value("key_hint").toString() : text("填写你的 API Key"));
    field(ui.body, "Base URL", &base, text("兼容 Chat Completions 的接口根地址"));
    field(ui.body, text("模型名称"), &model);
    field(ui.body, "API Key", &key, text("密钥加密保存在当前服务，界面只显示掩码。"));
    auto *notice = messageArea(ui.body);
    auto *test = action(text("测试连接"), "tonalButton");
    auto *save = action(text("保存配置"), "primary");
    save->setDefault(true);
    auto *remove = action(text("移除配置"), "destructiveButton");
    test->setProperty("controlId", "modelTest");
    save->setProperty("controlId", "modelSave");
    remove->setProperty("controlId", "modelRemove");
    footer(ui.body, save, test, remove);
    auto payload = [&] {
        QJsonObject data{{"base_url", base.text().trimmed()}, {"model", model.text().trimmed()}};
        if (!key.text().isEmpty()) data.insert("api_key", key.text());
        return data;
    };
    connect(test, &QPushButton::clicked, &dialog, [&] {
        auto result = api.call("POST", "/api/model-settings/test", payload());
        message(notice, result.ok() ? text("连接成功。点击保存，之后的日程整理会使用这份配置。") : result.error, !result.ok());
    });
    connect(save, &QPushButton::clicked, &dialog, [&] {
        auto data = payload(); data.insert("base_version", config.value("version"));
        auto result = api.call("PUT", "/api/model-settings", data);
        key.clear();
        if (!result.ok()) { message(notice, result.error + text(" 版本冲突或结果不明时，请关闭后重新打开核实。"), true); return; }
        config = result.data.value("settings").toObject();
        key.setPlaceholderText(text("留空保留密钥 · ") + config.value("key_hint").toString());
        modeLabel->setText(text("个人模型"));
        message(notice, text("配置已保存，手机同账号也会使用这份配置。"));
    });
    connect(remove, &QPushButton::clicked, &dialog, [&] {
        if (!confirm(&dialog, text("移除个人模型？"), text("移除后将使用服务端默认模型；没有默认模型时，使用规则解析。"), text("移除配置"))) return;
        auto result = api.call("DELETE", "/api/model-settings", {{"base_version", config.value("version")}});
        key.clear();
        if (!result.ok()) { message(notice, result.error, true); return; }
        dialog.accept();
        auto fresh = api.call("GET", "/api/auth/session");
        if (fresh.ok()) setSession(fresh.data);
    });
    dialog.exec();
    key.clear();
}

void MainWindow::phone() {
    if (owner().isEmpty()) return;
    auto info = api.call("GET", "/api/connection-info");
    if (!info.ok()) { showError(info); return; }
    QDialog dialog(this);
    auto ui = prepare(dialog, "phoneDialog", text("手机接着安排"), text("打开安卓应用，点击“扫码连接”，即可登录同一账号。"), 560);
    ui.body->addWidget(copy(session.value("user").toObject().value("email").toString(), "accountPill"));
    QComboBox address;
    address.setObjectName("phoneAddress");
    address.setEditable(true);
    installModernComboBox(&address);
    for (const auto &value : info.data.value("candidates").toArray()) address.addItem(value.toString());
    if (address.count() == 0) address.setEditText(info.data.value("current_origin").toString());
    field(ui.body, text("手机可访问的服务地址"), &address);
    auto *qrCard = new QFrame;
    qrCard->setObjectName("qrCard");
    auto *qrLayout = new QVBoxLayout(qrCard);
    qrLayout->setContentsMargins(18, 18, 18, 18);
    QLabel image;
    image.setObjectName("phoneQr");
    image.setAlignment(Qt::AlignCenter);
    image.setText(text("生成登录码后，在手机上扫描"));
    image.setMinimumHeight(250);
    qrLayout->addWidget(&image);
    ui.body->addWidget(qrCard);
    auto *notice = messageArea(ui.body);
    message(notice, text("同一 Wi-Fi 下选择电脑局域网地址。登录码有效 90 秒，仅可使用一次，请勿转发。"));
    auto *generate = action(text("生成登录二维码"), "primary");
    generate->setDefault(true);
    generate->setProperty("controlId", "phoneGenerate");
    auto *close = action(text("完成"), "textButton");
    footer(ui.body, generate, close);
    connect(close, &QPushButton::clicked, &dialog, &QDialog::reject);
    QDateTime expiry;
    QTimer timer;
    timer.setInterval(1000);
    connect(&timer, &QTimer::timeout, &dialog, [&] {
        if (!expiry.isValid()) return;
        const auto seconds = QDateTime::currentDateTimeUtc().secsTo(expiry);
        if (seconds <= 0) { image.clear(); image.setText(text("登录码已过期，请重新生成。")); expiry = {}; }
        else message(notice, text("剩余 ") + QString::number(seconds) + text(" 秒 · 仅限本人扫码 · 使用一次即失效，请勿转发。"));
    });
    connect(&address, &QComboBox::currentTextChanged, &dialog, [&] {
        image.clear(); image.setText(text("生成登录码后，在手机上扫描")); expiry = {};
    });
    connect(generate, &QPushButton::clicked, &dialog, [&] {
        QUrl target;
        if (!usableOrigin(address.currentText(), target, true)) { image.clear(); message(notice, text("请填写手机可访问的 HTTP(S) 服务根地址，不能包含 localhost、账号密码、路径或参数。"), true); return; }
        auto result = api.call("POST", "/api/pairing/issue");
        if (!result.ok()) { image.clear(); message(notice, result.error, true); return; }
        const auto token = result.data.value("token").toString();
        const auto value = (target.toString(QUrl::RemovePath) + "/#pair=" + token).toUtf8();
        try {
            const auto qr = qrcodegen::QrCode::encodeText(value.constData(), qrcodegen::QrCode::Ecc::MEDIUM);
            const int border = 4, scale = 6, size = (qr.getSize() + 2 * border) * scale;
            QImage bitmap(size, size, QImage::Format_RGB32);
            bitmap.fill(Qt::white);
            QPainter painter(&bitmap);
            painter.setPen(Qt::NoPen); painter.setBrush(QColor("#202124"));
            for (int y = 0; y < qr.getSize(); ++y) for (int x = 0; x < qr.getSize(); ++x)
                if (qr.getModule(x, y)) painter.drawRect((x + border) * scale, (y + border) * scale, scale, scale);
            painter.end();
            image.setPixmap(QPixmap::fromImage(bitmap).scaled(250, 250, Qt::KeepAspectRatio, Qt::FastTransformation));
            if (auto *scroll = dialog.findChild<QScrollArea *>("dialogScroll")) scroll->ensureWidgetVisible(&image, 8, 8);
            expiry = QDateTime::fromString(result.data.value("expires_at").toString(), Qt::ISODate);
            if (result.data.value("pairing_limited").toBool())
                information(&dialog, text("云端临时授权"), text("本次手机登录授权有效至 ") + result.data.value("session_expires_at").toString() + text("。到期后重新扫码或使用密码登录。"));
            message(notice, text("登录码已生成。请在 90 秒内扫描，仅限本人使用。"));
        } catch (const std::exception &) { image.clear(); message(notice, text("二维码无法生成，请缩短服务地址后重试。"), true); }
    });
    timer.start();
    dialog.exec();
    image.clear();
}

void MainWindow::connection() {
    QDialog dialog(this);
    auto ui = prepare(dialog, "serviceDialog", text("连接日程服务"), text("电脑与手机访问同一服务，才能共享账号和日程。"), 570);
    QLineEdit address(api.origin);
    address.setObjectName("serviceAddress");
    address.setPlaceholderText("http://192.168.1.20:43127");
    address.setClearButtonEnabled(true);
    field(ui.body, text("服务地址"), &address, text("安装版已自动启动本地服务，也可连接你已部署的 HTTPS 服务。"));
    auto *notice = messageArea(ui.body);
    auto *save = action(text("连接服务"), "primary");
    save->setProperty("controlId", "serviceConnect");
    save->setDefault(true);
    auto *cancel = action(text("取消"), "textButton");
    footer(ui.body, save, cancel);
    connect(cancel, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(save, &QPushButton::clicked, &dialog, [&] {
        QUrl value;
        if (!usableOrigin(address.text(), value)) { message(notice, text("请填写不含账号、路径或参数的 HTTP(S) 服务根地址。"), true); return; }
        if (value.toString() != api.origin && !confirm(&dialog, text("切换到另一个服务？"), text("每个服务的账号分别保留。连接后会自动恢复该服务的登录与日程。"), text("切换并连接"))) return;
        api.reset(value.toString());
        setSession({});
        settings.setValue("service_origin", api.origin);
        dialog.accept();
    });
    if (dialog.exec() == QDialog::Accepted) initialize();
}
