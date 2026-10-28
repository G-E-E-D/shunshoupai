#include "window.h"
#include "connection.h"
#include "modern_calendar.h"
#include "qrcodegen.hpp"
#include "draft_state.h"
#include "qr_decoder.h"
#include "pairing_input.h"
#include <QApplication>
#include <QButtonGroup>
#include <QComboBox>
#include <QClipboard>
#include <QCryptographicHash>
#include <QDateEdit>
#include <QDateTimeEdit>
#include <QDialog>
#include <QFrame>
#include <QFileDialog>
#include <QGraphicsDropShadowEffect>
#include <QHeaderView>
#include <QHostAddress>
#include <QImage>
#include <QJsonDocument>
#include <QLabel>
#include <QLineEdit>
#include <QMouseEvent>
#include <QMimeData>
#include <QPainter>
#include <QPushButton>
#include <QRadioButton>
#include <QScreen>
#include <QSignalBlocker>
#include <QScrollArea>
#include <QStyle>
#include <QStyledItemDelegate>
#include <QSysInfo>
#include <QTableWidget>
#include <QTextEdit>
#include <QTimer>
#include <QToolButton>
#include <QUuid>
#include <QVBoxLayout>
#include <QWindow>
#include <functional>
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
    std::function<void()> fit;
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
    auto fit = [&dialog, scroll, content, surface, surfaceLayout, fixedFooter, width] {
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
    };
    QTimer::singleShot(0, &dialog, fit);
    return {body, titleLabel, description, fit};
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

QString MainWindow::chooseLegacySpace(const QJsonArray &spaces) {
    if (spaces.isEmpty()) return {};
    QDialog dialog(this);
    auto ui = prepare(dialog, "legacySpaceDialog", text("继续使用哪份旧日程？"),
        text("本机保存了多份旧日程。选择一份继续使用，其他数据仍保留，不需要登录。"), 570);
    QComboBox choice;
    choice.setObjectName("legacySpaceChoice");
    installModernComboBox(&choice);
    for (const auto &entry : spaces) {
        const auto space = entry.toObject();
        choice.addItem(space.value("label").toString(text("旧日程")), space.value("id").toString());
    }
    field(ui.body, text("已有日程"), &choice);
    auto *proceed = action(text("继续使用这份日程"), "primary");
    proceed->setProperty("controlId", "legacySpaceContinue");
    auto *later = action(text("稍后选择"), "textButton");
    connect(proceed, &QPushButton::clicked, &dialog, &QDialog::accept);
    connect(later, &QPushButton::clicked, &dialog, &QDialog::reject);
    footer(ui.body, proceed, later);
    return dialog.exec() == QDialog::Accepted ? choice.currentData().toString() : QString{};
}

void MainWindow::devices() {
    if (owner().isEmpty()) { initialize(); return; }
    const auto initial = api.call("GET", "/api/devices");
    if (!initial.ok()) { showError(initial); return; }
    QDialog dialog(this);
    auto ui = prepare(dialog, "devicesDialog", text("已配对设备"),
        text("配对后在同一局域网自动合并日程。解除配对只停止后续同步，两端已有日程都会保留。"), 650);
    auto *list = new QVBoxLayout;
    list->setSpacing(12);
    ui.body->addLayout(list);
    auto *notice = messageArea(ui.body);
    bool firstLoad = true;
    QJsonObject lastSnapshot;
    std::function<void()> reload;
    reload = [&] {
        const auto result = firstLoad ? initial : api.call("GET", "/api/devices");
        firstLoad = false;
        if (!result.ok()) { message(notice, result.error, true); return; }
        if (result.data == lastSnapshot) return;
        lastSnapshot = result.data;
        while (auto *item = list->takeAt(0)) { if (item->widget()) { item->widget()->hide(); item->widget()->deleteLater(); } delete item; }
        const auto entries = result.data.value("devices").toArray();
        int paired = 0;
        QSet<QString> peers;
        for (const auto &entry : result.data.value("peers").toArray()) {
            const auto peer = entry.toObject();
            const auto id = peer.value("id").toString();
            peers.insert(id); ++paired;
            auto *card = new QFrame; card->setObjectName("tonalCard");
            auto *row = new QHBoxLayout(card);
            auto *details = new QVBoxLayout;
            const auto name = peer.value("name").toString(text("已配对设备"));
            details->addWidget(copy(name, "fieldLabel"));
            const auto synced = localTime(peer.value("last_sync_at").toString());
            const auto error = peer.value("error").toString();
            details->addWidget(copy(peer.value("pending").toBool() ? text("正在建立连接…")
                : !error.isEmpty() ? text("暂时无法同步，连接恢复后会自动继续")
                : synced.isValid() ? text("最近同步 ") + synced.toString("MM-dd HH:mm") : text("已配对，等待首次同步"), "fieldHint"));
            if (!error.isEmpty()) card->setToolTip(error.left(500));
            row->addLayout(details, 1);
            auto *remove = action(text("解除配对"), "textButton");
            remove->setProperty("controlId", "peerRemove-" + id);
            row->addWidget(remove);
            connect(remove, &QPushButton::clicked, &dialog, [&, id, name] {
                if (!confirm(&dialog, text("解除与“") + name + text("”的配对？"), text("停止后续交换。对方和其他设备已收到的日程副本不会删除或撤回。"), text("解除配对"))) return;
                const auto removed = api.call("DELETE", "/api/peers/" + id);
                if (!removed.ok()) { message(notice, removed.error, true); return; }
                reload(); message(notice, text("已解除配对，本机日程完整保留。"));
            });
            list->addWidget(card);
        }
        for (const auto &entry : entries) {
            const auto device = entry.toObject();
            const bool local = device.value("local").toBool();
            if (!local && peers.contains(device.value("client_id").toString())) continue;
            if (!local) ++paired;
            auto *card = new QFrame;
            card->setObjectName("tonalCard");
            auto *row = new QHBoxLayout(card);
            auto *details = new QVBoxLayout;
            const auto name = device.value("name").toString(text("已配对设备"));
            details->addWidget(copy(name, "fieldLabel"));
            const auto seen = localTime(device.value("last_seen_at").toString());
            const auto platform = device.value("platform") == "windows" ? text("电脑")
                : device.value("platform") == "android" ? text("手机") : text("设备");
            details->addWidget(copy(local ? text("本机日程 · 无需登录")
                : platform + (seen.isValid() ? text(" · 最近连接 ") + seen.toString("MM-dd HH:mm") : text(" · 等待连接")), "fieldHint"));
            row->addLayout(details, 1);
            if (!local && (session.value("role") == "owner" || device.value("current").toBool())) {
                auto *remove = action(text("解除配对"), "textButton");
                const auto id = device.value("id").toString();
                remove->setProperty("controlId", "unpair-" + id);
                row->addWidget(remove);
                connect(remove, &QPushButton::clicked, &dialog, [&, id, name, device] {
                    if (!confirm(&dialog, text("解除与“") + name + text("”的配对？"), text("后续不再同步；两端已经保存的日程都会保留。"), text("解除配对"))) return;
                    const auto removed = api.call("DELETE", "/api/devices/" + id);
                    if (!removed.ok()) { message(notice, removed.error, true); return; }
                    if (device.value("current").toBool()) { api.forgetSession(); setSession({}); dialog.accept(); return; }
                    reload();
                    message(notice, text("已解除配对，本机日程完整保留。"));
                });
            }
            list->addWidget(card);
        }
        if (paired == 0) list->addWidget(copy(text("还没有配对其他设备。手机也能独立使用，需要合并日程时再扫码配对。"), "fieldHint"));
        const auto connection = result.data.value("connection").toObject();
        if (connection.value("state") == "connecting") message(notice, text("正在连接对方设备，成功后自动合并日程…"));
        else if (connection.value("state") == "failed") message(notice, connection.value("error").toString(text("暂时无法配对，请检查两台设备的局域网连接后重试。")), true);
        else if (connection.value("state") == "connected") message(notice, text("设备已配对，日程会自动合并。"));
        QTimer::singleShot(0, &dialog, ui.fit);
    };
    auto *close = action(text("完成"), "textButton");
    auto *pair = action(text("配对新设备"), "primary");
    pair->setProperty("controlId", "devicesPair");
    pair->setVisible(session.value("role") == "owner");
    connect(close, &QPushButton::clicked, &dialog, &QDialog::accept);
    connect(pair, &QPushButton::clicked, &dialog, [&] { phone(); reload(); });
    footer(ui.body, pair, close);
    reload();
    QTimer updates;
    updates.setInterval(2000);
    connect(&updates, &QTimer::timeout, &dialog, [&] { if (!api.busy() && QApplication::activeModalWidget() == &dialog) reload(); });
    updates.start();
    dialog.exec();
    refresh();
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

bool MainWindow::resolveReplica(const QJsonObject &event) {
    const auto path = "/api/events/" + event.value("id").toString();
    auto loaded = api.call("GET", path);
    if (!loaded.ok()) { showError(loaded); return false; }
    QJsonObject latest = loaded.data.value("event").toObject();
    if (latest.value("replica_conflicts").toArray().size() < 2) {
        refresh(); status->setText(text("其他设备已处理这些版本，日程已刷新。")); return true;
    }
    QDialog dialog(this);
    auto ui = prepare(dialog, "replicaDialog", text("选择要保留的日程版本"),
        text("几台设备分别修改了这条日程。所有版本都已保留，请核对后明确选择，不会按修改时间自动覆盖。"), 710);
    auto *versions = new QVBoxLayout;
    versions->setSpacing(12); ui.body->addLayout(versions);
    auto *notice = messageArea(ui.body);
    auto *save = action(text("采用选中版本"), "primary");
    save->setProperty("controlId", "replicaResolve"); save->setEnabled(false);
    auto *later = action(text("稍后处理"), "textButton");
    auto *reload = action(text("刷新版本"), "textButton");
    reload->setProperty("controlId", "replicaRefresh");
    footer(ui.body, save, later, reload);
    QButtonGroup choices(&dialog);
    QString selected;
    bool resolved = false;
    auto render = [&] {
        selected.clear(); save->setEnabled(false);
        for (auto *button : choices.buttons()) choices.removeButton(button);
        while (auto *item = versions->takeAt(0)) { if (item->widget()) { item->widget()->hide(); item->widget()->deleteLater(); } delete item; }
        for (const auto &value : latest.value("replica_conflicts").toArray()) {
            const auto revision = value.toObject();
            const auto payload = revision.value("payload").toObject();
            auto *card = new QFrame; card->setObjectName("tonalCard");
            auto *column = new QVBoxLayout(card);
            auto *choice = new QRadioButton(payload.value("deleted").toBool() ? text("删除这条日程") : payload.value("title").toString());
            const auto revisionId = revision.value("revision_id").toString();
            choice->setProperty("revisionId", revisionId);
            choice->setMinimumHeight(36); choices.addButton(choice); column->addWidget(choice);
            const auto author = revision.value("author").toString(text("其他设备"));
            const auto changed = revision.value("changed_at").isDouble()
                ? QDateTime::fromMSecsSinceEpoch(revision.value("changed_at").toInteger(), QTimeZone("Asia/Shanghai")) : QDateTime{};
            column->addWidget(copy(author + (changed.isValid() ? text(" · 修改于 ") + changed.toString("MM-dd HH:mm") : ""), "fieldHint"));
            if (payload.value("deleted").toBool()) column->addWidget(copy(payload.value("title").toString(), "fieldHint"));
            else {
                const auto start = QDateTime::fromString(payload.value("start_local").toString(), Qt::ISODate);
                const auto end = QDateTime::fromString(payload.value("end_local").toString(), Qt::ISODate);
                column->addWidget(copy(start.toString("yyyy-MM-dd HH:mm") + " – " + end.toString("yyyy-MM-dd HH:mm")
                    + (payload.value("status") == "completed" ? text(" · 已完成") : text(" · 待进行")), "fieldHint"));
            }
            connect(choice, &QRadioButton::toggled, &dialog, [&, revisionId](bool checked) { if (checked) { selected = revisionId; save->setEnabled(true); } });
            versions->addWidget(card);
        }
        QTimer::singleShot(0, &dialog, ui.fit);
    };
    connect(later, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(reload, &QPushButton::clicked, &dialog, [&] {
        const auto fresh = api.call("GET", path);
        if (!fresh.ok()) { message(notice, fresh.error, true); return; }
        latest = fresh.data.value("event").toObject();
        if (latest.value("replica_conflicts").toArray().size() < 2) { resolved = true; dialog.accept(); return; }
        render(); message(notice, text("已取得最新版本，请重新选择要保留的内容。"));
    });
    connect(save, &QPushButton::clicked, &dialog, [&] {
        if (selected.isEmpty()) return;
        const auto result = api.call("POST", path + "/resolve", {{"base_version", latest.value("version")}, {"revision_id", selected}});
        if (!result.ok()) { message(notice, result.error + text(" 所有版本仍保留，可刷新版本后重新核对。"), true); return; }
        resolved = true; dialog.accept();
    });
    render(); dialog.exec(); refresh();
    return resolved;
}

void MainWindow::editEvent(QJsonObject event) {
    if (owner().isEmpty()) { initialize(); if (owner().isEmpty()) { if (!api.canBootstrap()) connection(); if (owner().isEmpty()) return; } }
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
            if (code == "REPLICA_CONFLICT") {
                const bool handled = resolveReplica(event);
                message(notice, handled ? text("版本已处理，你的编辑草稿仍保留。再次保存时会核对最新日程。") : text("各端版本和你的编辑草稿均已保留，可稍后继续处理。"));
                return;
            }
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
    auto ui = prepare(dialog, "modelDialog", text("连接你的模型"), text("设置这台电脑整理日程时使用的模型。"), 640);
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
        message(notice, text("模型配置已保存在本机。"));
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
    auto ui = prepare(dialog, "phoneDialog", text("显示我的配对码"), text("配对会合并全部日程；后续修改也可能经对方传播到其其他已配对设备。解除配对不会撤回已同步的副本。"), 620);
    ui.body->addWidget(copy(session.value("device").toObject().value("name").toString(text("这台电脑")), "accountPill"));
    QComboBox address;
    address.setObjectName("phoneAddress");
    address.setEditable(true);
    installModernComboBox(&address);
    for (const auto &value : info.data.value("candidates").toArray()) address.addItem(value.toString());
    if (address.count() == 0) address.setEditText(info.data.value("current_origin").toString());
    field(ui.body, text("本机局域网地址"), &address);
    auto *qrCard = new QFrame;
    qrCard->setObjectName("qrCard");
    auto *qrLayout = new QVBoxLayout(qrCard);
    qrLayout->setContentsMargins(18, 18, 18, 18);
    QLabel image;
    image.setObjectName("phoneQr");
    image.setAlignment(Qt::AlignCenter);
    image.setText(text("正在准备设备配对码"));
    image.setMinimumHeight(250);
    qrLayout->addWidget(&image);
    ui.body->addWidget(qrCard);
    QString pairingUrl;
    QImage pairingImage;
    auto *copyActions = new QHBoxLayout;
    auto *copyLink = action(text("复制链接"), "textButton");
    auto *copyImage = action(text("复制二维码"), "textButton");
    auto *saveImage = action(text("保存二维码图片"), "textButton");
    copyLink->setProperty("controlId", "pairCopyLink"); copyImage->setProperty("controlId", "pairCopyImage");
    copyLink->setEnabled(false); copyImage->setEnabled(false); saveImage->setEnabled(false);
    copyActions->addStretch(); copyActions->addWidget(copyLink); copyActions->addWidget(copyImage); copyActions->addWidget(saveImage); copyActions->addStretch();
    ui.body->addLayout(copyActions);
    auto *notice = messageArea(ui.body);
    message(notice, text("两台设备连接同一 Wi-Fi。配对码有效 90 秒，仅限自己的设备扫描。"));
    auto *generate = action(text("刷新配对码"), "primary");
    generate->setDefault(true);
    generate->setProperty("controlId", "phoneGenerate");
    auto *close = action(text("完成"), "textButton");
    auto *connectOther = action(text("扫描/粘贴对方配对码"), "textButton");
    connectOther->setProperty("controlId", "pairConnectOther");
    footer(ui.body, generate, close, connectOther);
    connect(close, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(connectOther, &QPushButton::clicked, &dialog, [&] { dialog.accept(); connection(); });
    connect(copyLink, &QPushButton::clicked, &dialog, [&] { if (!pairingUrl.isEmpty()) QApplication::clipboard()->setText(pairingUrl); });
    connect(copyImage, &QPushButton::clicked, &dialog, [&] { if (!pairingImage.isNull()) QApplication::clipboard()->setImage(pairingImage); });
    connect(saveImage, &QPushButton::clicked, &dialog, [&] {
        if (pairingImage.isNull()) return;
        const auto path = QFileDialog::getSaveFileName(&dialog, text("保存设备配对码"), text("顺手排设备配对.png"), "PNG (*.png)");
        if (path.isEmpty()) return;
        if (!pairingImage.save(path, "PNG")) message(notice, text("图片保存失败，请换一个可写的位置。"), true);
        else message(notice, text("图片已保存。配对码过期后，需要重新生成图片。"));
    });
    QDateTime expiry;
    QTimer timer;
    timer.setInterval(1000);
    connect(&timer, &QTimer::timeout, &dialog, [&] {
        if (!expiry.isValid()) return;
        const auto seconds = QDateTime::currentDateTimeUtc().secsTo(expiry);
        if (seconds <= 0) { image.clear(); image.setText(text("正在更新配对码…")); expiry = {}; if (!api.busy()) generate->click(); }
        else message(notice, text("剩余 ") + QString::number(seconds) + text(" 秒 · 仅限本人扫码 · 使用一次即失效，请勿转发。"));
    });
    connect(&address, &QComboBox::currentTextChanged, &dialog, [&] {
        image.clear(); image.setText(text("点击刷新，生成这个地址的配对码")); expiry = {};
        pairingUrl.clear(); pairingImage = {}; copyLink->setEnabled(false); copyImage->setEnabled(false); saveImage->setEnabled(false);
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
            pairingUrl = QString::fromUtf8(value); pairingImage = bitmap;
            copyLink->setEnabled(true); copyImage->setEnabled(true); saveImage->setEnabled(true);
            image.setPixmap(QPixmap::fromImage(bitmap).scaled(250, 250, Qt::KeepAspectRatio, Qt::FastTransformation));
            if (auto *scroll = dialog.findChild<QScrollArea *>("dialogScroll")) scroll->ensureWidgetVisible(&image, 8, 8);
            expiry = QDateTime::fromString(result.data.value("expires_at").toString(), Qt::ISODate);
            message(notice, text("在手机上扫描即可配对。首次连接会合并两端日程，不会删除已有安排。"));
        } catch (const std::exception &) { image.clear(); message(notice, text("二维码无法生成，请缩短服务地址后重试。"), true); }
    });
    timer.start();
    QTimer::singleShot(0, &dialog, [&] { QUrl target; if (usableOrigin(address.currentText(), target, true)) generate->click(); });
    dialog.exec();
    image.clear();
}

void MainWindow::connection() {
    if (owner().isEmpty()) { initialize(); if (owner().isEmpty()) return; }
    const auto info = api.call("GET", "/api/connection-info");
    QDialog dialog(this);
    auto ui = prepare(dialog, "serviceDialog", text("扫描或粘贴对方配对码"), text("连接任意手机或电脑后会合并全部日程；修改也可能经对方传播到其他已配对设备。解除配对不会撤回副本。"), 650);
    PairingAddressInput address;
    address.setObjectName("serviceAddress");
    address.setPlaceholderText("http://192.168.1.20:43127/#pair=…");
    address.setClearButtonEnabled(true);
    field(ui.body, text("对方的完整配对链接"), &address);
    auto *imageActions = new QHBoxLayout;
    auto *paste = action(text("粘贴链接/图片"), "textButton");
    auto *openImage = action(text("选择二维码图片"), "textButton");
    paste->setProperty("controlId", "peerPaste"); openImage->setProperty("controlId", "peerOpenImage");
    imageActions->addWidget(paste); imageActions->addWidget(openImage); imageActions->addStretch();
    ui.body->addLayout(imageActions);
    QComboBox localAddress(&dialog);
    localAddress.setObjectName("peerLocalAddress"); localAddress.setEditable(true); installModernComboBox(&localAddress);
    for (const auto &candidate : info.data.value("candidates").toArray()) localAddress.addItem(candidate.toString());
    if (localAddress.count() > 1) field(ui.body, text("对方可访问的本机地址"), &localAddress, text("通常保持默认即可；多张网卡时选择与对方同一局域网的地址。"));
    else localAddress.hide();
    auto *notice = messageArea(ui.body);
    message(notice, text("二维码图片在本机识别，不会上传。"));
    auto useImage = [&](const QImage &image) {
        QString error;
        if (!address.loadQrImage(image, &error)) { message(notice, error, true); return; }
        message(notice, text("已识别配对码，点击“配对并合并”即可连接。"));
    };
    connect(paste, &QPushButton::clicked, &dialog, [&] {
        auto *clipboard = QApplication::clipboard();
        const auto image = clipboard->image();
        if (!image.isNull()) useImage(image);
        else {
            const auto value = clipboard->text().trimmed();
            if (value.isEmpty()) { message(notice, text("剪贴板里没有链接或图片，请先复制对方的配对码。"), true); return; }
            address.setText(value); address.setCursorPosition(0); message(notice, text("已粘贴。请使用对方当前显示的完整配对链接。"));
        }
    });
    connect(openImage, &QPushButton::clicked, &dialog, [&] {
        const auto path = QFileDialog::getOpenFileName(&dialog, text("选择对方的二维码图片"), {}, text("图片 (*.png *.jpg *.jpeg *.bmp);;所有文件 (*)"));
        if (path.isEmpty()) return;
        QString error;
        if (!address.loadQrFile(path, &error)) { message(notice, error, true); return; }
        message(notice, text("已识别配对码，点击“配对并合并”即可连接。"));
    });
    auto *save = action(text("配对并合并"), "primary");
    save->setProperty("controlId", "serviceConnect");
    save->setDefault(true);
    auto *cancel = action(text("取消"), "textButton");
    footer(ui.body, save, cancel);
    connect(cancel, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(save, &QPushButton::clicked, &dialog, [&] {
        shunshoupai::Connection parsed;
        try { parsed = shunshoupai::parseConnection(address.text().toStdString(), true); }
        catch (const std::invalid_argument &) { message(notice, text("请粘贴对方显示的完整设备配对链接。"), true); return; }
        if (parsed.pairingToken.empty()) { message(notice, text("链接缺少配对码，单填设备地址不能配对。"), true); return; }
        QJsonObject payload{{"url", QString::fromStdString(parsed.origin + "#pair=" + parsed.pairingToken)}};
        QUrl ownAddress;
        if (usableOrigin(localAddress.currentText(), ownAddress)) payload.insert("local_origin", ownAddress.toString());
        const auto result = api.call("POST", "/api/peers/connect", payload);
        if (!result.ok()) { message(notice, result.error, true); return; }
        address.clear();
        status->setText(text("已开始配对，连接后会自动合并日程。"));
        dialog.accept();
    });
    if (dialog.exec() == QDialog::Accepted) { if (!QApplication::activeModalWidget()) devices(); refresh(); }
}
