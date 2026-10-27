#include "window.h"
#include "qrcodegen.hpp"
#include "connection.h"
#include <QApplication>
#include <QCheckBox>
#include <QClipboard>
#include <QComboBox>
#include <QCryptographicHash>
#include <QDateEdit>
#include <QDateTimeEdit>
#include <QDialog>
#include <QDialogButtonBox>
#include <QDir>
#include <QEvent>
#include <QFile>
#include <QFormLayout>
#include <QFrame>
#include <QGridLayout>
#include <QHeaderView>
#include <QImage>
#include <QJsonArray>
#include <QLabel>
#include <QLineEdit>
#include <QMessageBox>
#include <QPainter>
#include <QPushButton>
#include <QSignalBlocker>
#include <QScrollArea>
#include <QSpinBox>
#include <QSplitter>
#include <QTableWidget>
#include <QTextEdit>
#include <QTimer>
#include <QUuid>
#include <QVBoxLayout>
#include <functional>

namespace {
QString trText(const char *text) { return QString::fromUtf8(text); }
QPushButton *button(const QString &text, QWidget *parent = nullptr, bool primary = false) {
    auto *value = new QPushButton(text, parent);
    value->setCursor(Qt::PointingHandCursor);
    if (primary) value->setObjectName("primary");
    return value;
}
QLabel *label(const QString &text, const QString &name = {}) {
    auto *value = new QLabel(text);
    value->setObjectName(name);
    value->setWordWrap(true);
    value->setTextFormat(Qt::PlainText);
    return value;
}
QStringList strings(const QJsonArray &values) {
    QStringList result;
    for (const auto &value : values) result << value.toString();
    return result;
}
QString newId() { return QUuid::createUuid().toString(QUuid::WithoutBraces); }
QDateTime localTime(const QString &value) {
    return QDateTime::fromString(value, Qt::ISODate).toTimeZone(QTimeZone("Asia/Shanghai"));
}
QString pendingKey(const QString &origin, const QString &owner) {
    return "pending/" + QString::fromLatin1(QCryptographicHash::hash((origin + owner).toUtf8(), QCryptographicHash::Sha256).toHex());
}
bool usableOrigin(const QString &value, QUrl &url, bool phone = false) {
    try {
        const auto parsed = shunshoupai::parseConnection(value.toStdString(), false);
        url = QUrl(QString::fromStdString(parsed.origin), QUrl::StrictMode);
    } catch (const std::invalid_argument &) { return false; }
    auto host = url.host().toLower();
    if (host.endsWith('.')) host.chop(1);
    const QHostAddress ip(host);
    if (phone && (host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.")
        || ip.isLoopback() || ip == QHostAddress::AnyIPv6 || ip == QHostAddress::AnyIPv4)) return false;
    url.setPath("");
    return true;
}
void clearLayout(QLayout *layout) {
    while (auto *item = layout->takeAt(0)) {
        if (item->widget()) { item->widget()->hide(); item->widget()->deleteLater(); }
        if (item->layout()) clearLayout(item->layout());
        delete item;
    }
}
}

MainWindow::MainWindow(const QString &origin) : api(origin) {
    setWindowTitle(trText("顺手排 · 把想法排进生活"));
    resize(1180, 800);
    setMinimumSize(940, 640);
    setStyleSheet(R"(
        QMainWindow,QDialog {background:#f7f7f5;color:#252b35;font-family:'Microsoft YaHei UI';font-size:14px;}
        QWidget {color:#252b35;font-family:'Microsoft YaHei UI';font-size:14px;}
        QFrame#card {background:white;border:1px solid #e7e8eb;border-radius:14px;}
        QLabel#brand {font-size:25px;font-weight:700;color:#d46b3e;}
        QLabel#title {font-size:24px;font-weight:700;}
        QLabel#muted {color:#7b8290;}
        QLabel#status {color:#687384;padding:10px;background:#eef1f3;border-radius:8px;}
        QPushButton {background:white;border:1px solid #dfe3e8;border-radius:7px;padding:9px 14px;}
        QPushButton:hover {background:#f0f2f5;border-color:#bcc5cf;}
        QPushButton:disabled {color:#a1a7af;background:#f1f2f4;}
        QPushButton#primary {background:#dc7849;color:white;border:0;font-weight:600;}
        QPushButton#primary:hover {background:#c8673c;}
        QLineEdit,QTextEdit,QDateEdit,QDateTimeEdit,QComboBox {background:white;border:1px solid #dfe3e8;border-radius:7px;padding:9px;selection-background-color:#edc5b2;}
        QLineEdit:focus,QTextEdit:focus {border-color:#dc7849;}
        QScrollArea {border:0;background:transparent;}
        QWidget#agendaViewport,QWidget#agendaContent,QWidget#composerViewport {background:white;}
        QScrollBar:vertical {background:#f2f3f4;width:10px;margin:0;border-radius:5px;}
        QScrollBar::handle:vertical {background:#cbd1d8;min-height:28px;border-radius:5px;}
        QScrollBar::handle:vertical:hover {background:#abb5c0;}
        QScrollBar::add-line:vertical,QScrollBar::sub-line:vertical {height:0;}
        QScrollBar::add-page:vertical,QScrollBar::sub-page:vertical {background:transparent;}
        QScrollBar:horizontal {background:#f2f3f4;height:10px;margin:0;}
        QScrollBar::handle:horizontal {background:#cbd1d8;min-width:28px;border-radius:5px;}
        QScrollBar::add-line:horizontal,QScrollBar::sub-line:horizontal {width:0;}
        QScrollBar::add-page:horizontal,QScrollBar::sub-page:horizontal {background:transparent;}
        QSplitter::handle {background:#f7f7f5;}
        QTableWidget {background:white;border:1px solid #e1e5e9;gridline-color:#e8ebee;}
        QHeaderView::section {background:#f0f2f5;padding:10px;border:0;}
        QDialogButtonBox QPushButton {min-width:80px;}
    )");
    auto *root = new QWidget;
    auto *layout = new QVBoxLayout(root);
    layout->setContentsMargins(28, 24, 28, 18);
    layout->setSpacing(20);
    auto *header = new QHBoxLayout;
    header->addWidget(label(trText("▣ 顺手排 ."), "brand"));
    header->addStretch();
    modeLabel = label(trText("规则体验"), "muted");
    header->addWidget(modeLabel);
    modelButton = button(trText("模型设置"));
    phoneButton = button(trText("手机扫码登录"));
    auto *service = button(trText("服务设置"));
    accountButton = button(trText("登录 / 注册"));
    header->addWidget(modelButton);
    header->addWidget(phoneButton);
    header->addWidget(service);
    header->addWidget(accountButton);
    layout->addLayout(header);
    auto *greeting = new QHBoxLayout;
    auto *greetingText = new QVBoxLayout;
    greetingText->addWidget(label(trText("把想法排进生活"), "title"));
    greetingText->addWidget(label(trText("写下一段安排，整理、确认，再安心去做。"), "muted"));
    greeting->addLayout(greetingText, 1);
    accountLabel = label(trText("原生 Qt 桌面客户端"), "muted");
    accountLabel->setMaximumWidth(400);
    greeting->addWidget(accountLabel);
    layout->addLayout(greeting);
    auto *splitter = new QSplitter;
    splitter->setHandleWidth(12);
    auto *left = new QFrame;
    left->setObjectName("card");
    auto *leftLayout = new QVBoxLayout(left);
    leftLayout->setSizeConstraint(QLayout::SetMinimumSize);
    leftLayout->setContentsMargins(22, 22, 22, 22);
    leftLayout->setSpacing(12);
    leftLayout->addWidget(label(trText("安排些什么？"), "title"));
    leftLayout->addWidget(label(trText("可以一起写下几件事，时间不完整时会请你补充。"), "muted"));
    composer = new QTextEdit;
    composer->setObjectName("composer");
    composer->setPlaceholderText(trText("明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。"));
    composer->setMinimumHeight(140);
    leftLayout->addWidget(composer);
    auto *example = button(trText("试试示例"));
    connect(example, &QPushButton::clicked, this, [this] {
        composer->setPlainText(trText("明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。"));
    });
    leftLayout->addWidget(example, 0, Qt::AlignLeft);
    auto *parseButton = button(trText("✧ 帮我整理一下"), nullptr, true);
    connect(parseButton, &QPushButton::clicked, this, &MainWindow::parse);
    leftLayout->addWidget(parseButton);
    auto *resume = button(trText("恢复待确认的保存"));
    connect(resume, &QPushButton::clicked, this, &MainWindow::restorePending);
    leftLayout->addWidget(resume);
    leftLayout->addWidget(label(trText("提示词已固定在服务中。整理结果先成为草稿，确认后才保存到正式日程。"), "muted"));
    leftLayout->addStretch();
    auto *reminder = new QCheckBox(trText("开启前台开始提醒"));
    connect(reminder, &QCheckBox::toggled, this, [this](bool enabled) { reminders = enabled; });
    leftLayout->addWidget(reminder);
    leftLayout->addWidget(label(trText("当前应用保持打开时生效；锁屏或退出后不提供提醒。"), "muted"));
    auto *leftScroll = new QScrollArea;
    leftScroll->setWidgetResizable(true);
    leftScroll->setMinimumWidth(340);
    leftScroll->viewport()->setObjectName("composerViewport");
    leftScroll->setWidget(left);
    splitter->addWidget(leftScroll);
    auto *right = new QFrame;
    right->setObjectName("card");
    auto *rightLayout = new QVBoxLayout(right);
    rightLayout->setContentsMargins(22, 22, 22, 22);
    auto *tools = new QHBoxLayout;
    currentDay = QDateTime::currentDateTimeUtc().toTimeZone(zone).date();
    day = new QDateEdit(currentDay);
    day->setDisplayFormat("yyyy-MM-dd");
    day->setCalendarPopup(true);
    day->setMinimumDate(QDate(2000, 1, 1));
    day->setMaximumDate(QDate(2100, 12, 31));
    auto *previous = button("‹"), *next = button("›");
    connect(previous, &QPushButton::clicked, this, [this] { day->setDate(day->date().addDays(-1)); });
    connect(next, &QPushButton::clicked, this, [this] { day->setDate(day->date().addDays(1)); });
    tools->addWidget(previous);
    tools->addWidget(day);
    tools->addWidget(next);
    view = new QComboBox;
    view->addItems({trText("当天"), trText("七天"), trText("已完成")});
    tools->addWidget(view);
    tools->addStretch();
    auto *reload = button(trText("刷新")), *add = button(trText("＋ 新建"));
    tools->addWidget(reload);
    tools->addWidget(add);
    rightLayout->addLayout(tools);
    search = new QLineEdit;
    search->setPlaceholderText(trText("搜索已加载的日程"));
    rightLayout->addWidget(search);
    summary = label(trText("登录后查看自己的日程"), "muted");
    rightLayout->addWidget(summary);
    auto *scroll = new QScrollArea;
    scroll->viewport()->setObjectName("agendaViewport");
    scroll->setWidgetResizable(true);
    auto *list = new QWidget;
    list->setObjectName("agendaContent");
    agenda = new QVBoxLayout(list);
    agenda->setContentsMargins(0, 8, 0, 8);
    agenda->setSpacing(12);
    scroll->setWidget(list);
    rightLayout->addWidget(scroll);
    splitter->addWidget(right);
    splitter->setSizes({365, 700});
    layout->addWidget(splitter, 1);
    status = label(trText("正在连接服务…"), "status");
    status->setObjectName("status");
    layout->addWidget(status);
    setCentralWidget(root);
    connect(accountButton, &QPushButton::clicked, this, &MainWindow::auth);
    connect(modelButton, &QPushButton::clicked, this, &MainWindow::models);
    connect(phoneButton, &QPushButton::clicked, this, &MainWindow::phone);
    connect(service, &QPushButton::clicked, this, &MainWindow::connection);
    connect(add, &QPushButton::clicked, this, [this] { editEvent(); });
    connect(reload, &QPushButton::clicked, this, &MainWindow::refresh);
    connect(day, &QDateEdit::dateChanged, this, [this] { refresh(); });
    connect(view, &QComboBox::currentIndexChanged, this, [this] { refresh(); });
    connect(search, &QLineEdit::textChanged, this, [this] { renderEvents(); });
    poll = new QTimer(this);
    poll->setInterval(8000);
    connect(poll, &QTimer::timeout, this, [this] {
        if (!api.busy() && isActiveWindow() && !QApplication::activeModalWidget()) refresh();
    });
    connect(qApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState state) {
        if (state == Qt::ApplicationActive) QTimer::singleShot(0, this, [this] {
            if (!api.busy() && !QApplication::activeModalWidget()) refresh();
        });
    });
    poll->start();
    modelButton->setEnabled(false);
    phoneButton->setEnabled(false);
}

QString MainWindow::owner() const { return session.value("user").toObject().value("id").toString(); }
void MainWindow::showError(const ApiResult &result) {
    status->setText(result.error);
    QMessageBox::warning(this, trText("请检查一下"), result.error);
}
void MainWindow::setSession(const QJsonObject &data) {
    const auto previousOwner = owner();
    const bool changed = owner() != data.value("user").toObject().value("id").toString();
    session = data;
    accountLabel->setText(owner().isEmpty() ? trText("登录后与手机同步") : data.value("user").toObject().value("email").toString());
    accountButton->setText(owner().isEmpty() ? trText("登录 / 注册") : trText("退出账号"));
    modelButton->setEnabled(!owner().isEmpty());
    phoneButton->setEnabled(!owner().isEmpty());
    modeLabel->setText(data.value("ai_provider").toString() == "model" ? trText("模型整理") : trText("规则体验"));
    if (changed) { events = {}; reminded.clear(); if (!previousOwner.isEmpty()) composer->clear(); }
    renderEvents();
}
void MainWindow::initialize() {
    const auto result = api.call("GET", "/api/auth/session");
    if (!result.ok()) { status->setText(trText("未连接：") + result.error); return; }
    setSession(result.data);
    status->setText(trText("服务已就绪 · ") + api.origin);
    refresh();
}
void MainWindow::auth() {
    if (!owner().isEmpty()) {
        if (QMessageBox::question(this, trText("退出账号"), trText("确认退出当前账号？待确认的保存会按账号保留。")) != QMessageBox::Yes) return;
        const auto result = api.call("POST", "/api/auth/logout");
        if (!result.ok()) { showError(result); return; }
        api.reset(api.origin);
        setSession(result.data);
        status->setText(trText("已退出登录。"));
        return;
    }
    QDialog dialog(this);
    dialog.setWindowTitle(trText("欢迎使用顺手排"));
    dialog.setMinimumWidth(400);
    QFormLayout form(&dialog);
    auto *kind = new QComboBox;
    kind->addItems({trText("登录账号"), trText("注册新账号")});
    QLineEdit email, password;
    email.setPlaceholderText("you@example.com");
    password.setEchoMode(QLineEdit::Password);
    password.setPlaceholderText(trText("至少 8 位密码"));
    form.addRow(trText("操作"), kind);
    form.addRow(trText("邮箱"), &email);
    form.addRow(trText("密码"), &password);
    auto *message = label("");
    form.addRow(message);
    auto *submit = button(trText("继续"), nullptr, true);
    form.addRow(submit);
    connect(submit, &QPushButton::clicked, &dialog, [&] {
        const auto result = api.call("POST", kind->currentIndex() == 0 ? "/api/auth/login" : "/api/auth/register",
                                     {{"email", email.text().trimmed()}, {"password", password.text()}});
        password.clear();
        if (!result.ok()) { message->setText(result.error); return; }
        if (result.data.value("user").isNull()) { message->setText(result.data.value("message").toString()); return; }
        setSession(result.data);
        dialog.accept();
    });
    if (dialog.exec() == QDialog::Accepted) { refresh(); restorePending(); }
}
void MainWindow::refresh() {
    if (api.busy()) return;
    const auto today = QDateTime::currentDateTimeUtc().toTimeZone(zone).date();
    if (today != currentDay) {
        const bool followsToday = day->date() == currentDay;
        currentDay = today;
        if (followsToday) { const QSignalBlocker blocked(day); day->setDate(today); }
    }
    if (owner().isEmpty()) { renderEvents(); return; }
    const QDateTime from(day->date(), QTime(0, 0), zone);
    const auto to = from.addDays(view->currentIndex() == 1 ? 7 : 1);
    const auto query = "?from=" + QString::fromLatin1(QUrl::toPercentEncoding(from.toUTC().toString(Qt::ISODate)))
        + "&to=" + QString::fromLatin1(QUrl::toPercentEncoding(to.toUTC().toString(Qt::ISODate)));
    const auto result = api.call("GET", "/api/events" + query);
    if (!result.ok()) {
        status->setText(trText("同步失败：") + result.error);
        if (result.status == 401) { setSession({}); status->setText(trText("登录已过期，请重新登录。")); }
        return;
    }
    events = result.data.value("events").toArray();
    renderEvents();
    status->setText(trText("已同步 · ") + QTime::currentTime().toString("HH:mm:ss") + " · " + api.origin);
    for (const auto &value : events) {
        const auto event = value.toObject();
        const auto seconds = QDateTime::currentDateTimeUtc().secsTo(localTime(event.value("start_at").toString()));
        const auto id = event.value("id").toString();
        if (reminders && event.value("status") == "planned" && seconds >= 0 && seconds <= 300 && !reminded.contains(id)) {
            reminded.insert(id);
            status->setText(trText("即将开始：") + event.value("title").toString());
            QApplication::alert(this, 4000);
        }
    }
}
void MainWindow::renderEvents() {
    clearLayout(agenda);
    int count = 0;
    for (const auto &value : events) {
        auto event = value.toObject();
        const bool complete = event.value("status").toString() == "completed";
        if ((view->currentIndex() == 2 && !complete) || !event.value("title").toString().contains(search->text(), Qt::CaseInsensitive)) continue;
        ++count;
        auto *card = new QFrame;
        card->setObjectName("card");
        auto *row = new QVBoxLayout(card);
        auto *headline = new QHBoxLayout;
        const auto start = localTime(event.value("start_at").toString());
        const auto end = localTime(event.value("end_at").toString());
        auto *time = label(start.toString("MM-dd HH:mm") + " — " + end.toString(start.date() == end.date() ? "HH:mm" : "MM-dd HH:mm"), "muted");
        headline->addWidget(time);
        headline->addStretch();
        headline->addWidget(label(complete ? trText("已完成") : trText("待进行"), "muted"));
        row->addLayout(headline);
        row->addWidget(label(event.value("title").toString()));
        auto *actions = new QHBoxLayout;
        actions->addStretch();
        auto *edit = button(trText("修改")), *toggle = button(complete ? trText("恢复") : trText("完成")), *remove = button(trText("删除"));
        actions->addWidget(edit);
        actions->addWidget(toggle);
        actions->addWidget(remove);
        connect(edit, &QPushButton::clicked, this, [this, event] { editEvent(event); });
        connect(toggle, &QPushButton::clicked, this, [this, event, complete] {
            auto result = api.call("PATCH", "/api/events/" + event.value("id").toString(),
                {{"base_version", event.value("version")}, {"status", complete ? "planned" : "completed"}});
            if (!result.ok()) showError(result);
            refresh();
        });
        connect(remove, &QPushButton::clicked, this, [this, event] {
            if (QMessageBox::question(this, trText("删除日程"), trText("确认删除“") + event.value("title").toString() + trText("”？")) != QMessageBox::Yes) return;
            auto result = api.call("DELETE", "/api/events/" + event.value("id").toString(), {{"base_version", event.value("version")}});
            if (!result.ok()) showError(result);
            refresh();
        });
        row->addLayout(actions);
        agenda->addWidget(card);
    }
    if (count == 0) agenda->addWidget(label(owner().isEmpty() ? trText("登录自己的账号，日程就有了归处。") : trText("这里还没有安排。留一点空白，也很好。"), "muted"));
    agenda->addStretch();
    summary->setText(trText("上海时区 · ") + QString::number(count) + trText(" 项日程"));
}
void MainWindow::parse() {
    if (owner().isEmpty()) { auth(); if (owner().isEmpty()) return; }
    if (composer->toPlainText().trimmed().isEmpty() || composer->toPlainText().size() > 2000) {
        QMessageBox::information(this, trText("写下你的安排"), trText("请填写 1–2000 字的日程描述。")); return;
    }
    if (!settings.value(pendingKey(api.origin, owner())).toString().isEmpty()) {
        restorePending(); return;
    }
    status->setText(trText("正在整理，请稍候…"));
    const auto result = api.call("POST", "/api/ai/parse", {{"text", composer->toPlainText()}, {"timezone", "Asia/Shanghai"}});
    if (!result.ok()) { showError(result); return; }
    QStringList notices = strings(result.data.value("questions").toArray()) + strings(result.data.value("warnings").toArray());
    modeLabel->setText(result.data.value("provider") == "model" ? trText("模型整理") : trText("规则解析"));
    if (!notices.isEmpty()) QMessageBox::information(this, trText("整理提示"), notices.join("\n"));
    auto rows = result.data.value("events").toArray();
    if (!rows.isEmpty() && !result.data.value("needs_clarification").toBool()) drafts(rows);
    else status->setText(trText("请补充说明后再整理。"));
}
void MainWindow::drafts(QJsonArray rows, QString request) {
    QDialog dialog(this);
    dialog.setWindowTitle(trText("检查草稿，再确认保存"));
    dialog.resize(880, 390);
    QVBoxLayout layout(&dialog);
    layout.addWidget(label(trText("标题、开始和结束时间均可修改。时间使用上海时区，格式为 yyyy-MM-ddTHH:mm:ss。"), "muted"));
    QTableWidget table(rows.size(), 4);
    table.setHorizontalHeaderLabels({trText("标题"), trText("开始"), trText("结束"), trText("时间说明")});
    table.horizontalHeader()->setSectionResizeMode(QHeaderView::Stretch);
    for (int i = 0; i < rows.size(); ++i) {
        auto event = rows[i].toObject();
        const QStringList values{event.value("title").toString(), event.value("start_local").toString(), event.value("end_local").toString(), strings(event.value("assumptions").toArray()).join("；")};
        for (int j = 0; j < 4; ++j) {
            auto *item = new QTableWidgetItem(values[j]);
            if (j == 3) item->setFlags(item->flags() & ~Qt::ItemIsEditable);
            table.setItem(i, j, item);
        }
    }
    layout.addWidget(&table);
    auto *message = label("", "muted");
    layout.addWidget(message);
    QDialogButtonBox controls;
    auto *save = controls.addButton(trText("确认保存"), QDialogButtonBox::AcceptRole);
    save->setObjectName("primary");
    auto *remove = controls.addButton(trText("移除选中草稿"), QDialogButtonBox::ActionRole);
    auto *cancel = controls.addButton(trText("暂不保存"), QDialogButtonBox::RejectRole);
    layout.addWidget(&controls);
    const QString key = pendingKey(api.origin, owner());
    if (!request.isEmpty()) {
        table.setEditTriggers(QAbstractItemView::NoEditTriggers);
        remove->setEnabled(false);
        save->setText(trText("用原编号确认保存结果"));
        message->setText(trText("上次保存结果待确认：本次将使用相同编号和内容，防止重复创建。"));
    }
    connect(remove, &QPushButton::clicked, &dialog, [&] {
        const int selected = table.currentRow();
        if (selected >= 0) { table.removeRow(selected); rows.removeAt(selected); }
    });
    connect(cancel, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(save, &QPushButton::clicked, &dialog, [&] {
        if (table.rowCount() == 0) { message->setText(trText("至少保留一项草稿。")); return; }
        if (request.isEmpty()) {
            for (int i = 0; i < rows.size(); ++i) {
                auto event = rows[i].toObject();
                event.insert("title", table.item(i, 0)->text().trimmed());
                event.insert("start_local", table.item(i, 1)->text().trimmed());
                event.insert("end_local", table.item(i, 2)->text().trimmed());
                rows[i] = event;
            }
            request = newId();
        }
        const QJsonObject payload{{"request_id", request}, {"events", rows}};
        settings.setValue(key, QString::fromUtf8(QJsonDocument(payload).toJson(QJsonDocument::Compact)));
        settings.sync();
        table.setEditTriggers(QAbstractItemView::NoEditTriggers);
        remove->setEnabled(false);
        auto result = api.call("POST", "/api/events/commit", payload);
        if (result.ok()) {
            settings.remove(key);
            status->setText(result.data.value("replayed").toBool() ? trText("已确认上次保存成功，没有重复创建。") : trText("日程已保存。"));
            dialog.accept();
        } else {
            message->setText(result.error);
            const auto code = result.data.value("error").toObject().value("code").toString();
            if (code == "VALIDATION_ERROR" || code == "TIME_CONFLICT") {
                settings.remove(key);
                request.clear();
                table.setEditTriggers(QAbstractItemView::DoubleClicked | QAbstractItemView::EditKeyPressed);
                remove->setEnabled(true);
            } else {
                message->setText(result.error + trText(" 使用原编号重试，或稍后从主界面恢复。"));
                save->setText(trText("用原编号确认保存结果"));
            }
        }
    });
    dialog.exec();
    refresh();
}
void MainWindow::restorePending() {
    if (owner().isEmpty()) return;
    const auto pending = QJsonDocument::fromJson(settings.value(pendingKey(api.origin, owner())).toString().toUtf8()).object();
    if (!pending.isEmpty()) drafts(pending.value("events").toArray(), pending.value("request_id").toString());
    else status->setText(trText("没有待确认的保存。"));
}
void MainWindow::editEvent(QJsonObject event) {
    if (owner().isEmpty()) { auth(); if (owner().isEmpty()) return; }
    QDialog dialog(this);
    dialog.setMinimumWidth(440);
    dialog.setWindowTitle(event.isEmpty() ? trText("新建日程") : trText("修改日程"));
    QFormLayout form(&dialog);
    QLineEdit title(event.value("title").toString());
    title.setMaxLength(120);
    QDateTimeEdit start, end;
    for (auto *edit : {&start, &end}) {
        edit->setDisplayFormat("yyyy-MM-dd HH:mm");
        edit->setCalendarPopup(true);
        edit->setMinimumDate(QDate(2000, 1, 1));
        edit->setMaximumDate(QDate(2100, 12, 31));
    }
    start.setDateTime(event.isEmpty() ? QDateTime(day->date(), QTime(9, 0)) : QDateTime::fromString(localTime(event.value("start_at").toString()).toString("yyyy-MM-ddTHH:mm:ss"), Qt::ISODate));
    end.setDateTime(event.isEmpty() ? start.dateTime().addSecs(3600) : QDateTime::fromString(localTime(event.value("end_at").toString()).toString("yyyy-MM-ddTHH:mm:ss"), Qt::ISODate));
    form.addRow(trText("标题"), &title);
    form.addRow(trText("开始 · 上海时区"), &start);
    form.addRow(trText("结束 · 上海时区"), &end);
    auto *message = label("");
    form.addRow(message);
    auto *save = button(trText("保存日程"), nullptr, true);
    form.addRow(save);
    connect(save, &QPushButton::clicked, &dialog, [&] {
        QJsonObject patch{{"title", title.text().trimmed()}, {"start_local", start.dateTime().toString("yyyy-MM-ddTHH:mm:ss")}, {"end_local", end.dateTime().toString("yyyy-MM-ddTHH:mm:ss")}};
        if (event.isEmpty()) {
            patch.insert("timezone", "Asia/Shanghai"); patch.insert("time_source", "explicit"); patch.insert("assumptions", QJsonArray{});
            dialog.accept();
            drafts({patch});
            return;
        }
        patch.insert("base_version", event.value("version"));
        auto result = api.call("PATCH", "/api/events/" + event.value("id").toString(), patch);
        if (!result.ok()) { message->setText(result.error + trText(" 请刷新核实后再编辑。")); return; }
        dialog.accept();
    });
    dialog.exec();
    refresh();
}
void MainWindow::models() {
    if (owner().isEmpty()) return;
    auto loaded = api.call("GET", "/api/model-settings");
    if (!loaded.ok()) { showError(loaded); return; }
    auto config = loaded.data.value("settings").toObject();
    QDialog dialog(this);
    dialog.setWindowTitle(trText("模型设置"));
    dialog.setMinimumWidth(570);
    QFormLayout form(&dialog);
    form.addRow(label(trText("使用自己的模型，日程提示词由软件固定。配置按账号保存，电脑和手机共用。请只向可信服务填写 Key。"), "muted"));
    QLineEdit base(config.value("base_url").toString()), model(config.value("model").toString()), key;
    base.setPlaceholderText("https://api.example.com/v1");
    key.setEchoMode(QLineEdit::Password);
    key.setPlaceholderText(config.value("has_api_key").toBool() ? trText("留空保留现有密钥 · ") + config.value("key_hint").toString() : trText("填写自己的 API Key"));
    form.addRow("Base URL", &base);
    form.addRow(trText("模型名称"), &model);
    form.addRow("API Key", &key);
    auto *message = label(trText("密钥加密存储在服务端，界面不会读回原文。测试连接会消耗一次小型请求，不会自动保存。"), "muted");
    form.addRow(message);
    auto *actions = new QHBoxLayout;
    auto *test = button(trText("测试连接")), *save = button(trText("保存配置"), nullptr, true), *remove = button(trText("移除个人配置"));
    actions->addWidget(test); actions->addWidget(save); actions->addWidget(remove);
    form.addRow(actions);
    auto payload = [&] {
        QJsonObject data{{"base_url", base.text().trimmed()}, {"model", model.text().trimmed()}};
        if (!key.text().isEmpty()) data.insert("api_key", key.text());
        return data;
    };
    connect(test, &QPushButton::clicked, &dialog, [&] {
        auto result = api.call("POST", "/api/model-settings/test", payload());
        message->setText(result.ok() ? trText("连接测试成功；请点击保存配置。") : result.error);
    });
    connect(save, &QPushButton::clicked, &dialog, [&] {
        auto data = payload(); data.insert("base_version", config.value("version"));
        auto result = api.call("PUT", "/api/model-settings", data);
        key.clear();
        if (!result.ok()) { message->setText(result.error + trText(" 版本冲突或结果不明时请关闭后重新打开核实。")); return; }
        config = result.data.value("settings").toObject();
        key.setPlaceholderText(trText("留空保留现有密钥 · ") + config.value("key_hint").toString());
        modeLabel->setText(trText("个人模型"));
        message->setText(trText("配置已保存，手机同账号也会使用这份配置。"));
    });
    connect(remove, &QPushButton::clicked, &dialog, [&] {
        if (QMessageBox::question(&dialog, trText("移除个人模型"), trText("移除后使用服务端默认模型，或回到规则解析。确认继续？")) != QMessageBox::Yes) return;
        auto result = api.call("DELETE", "/api/model-settings", {{"base_version", config.value("version")}});
        key.clear();
        if (!result.ok()) { message->setText(result.error); return; }
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
    dialog.setWindowTitle(trText("手机扫码，一键登录"));
    dialog.setMinimumWidth(480);
    QVBoxLayout layout(&dialog);
    layout.addWidget(label(trText("用安卓应用首页的“扫码连接”扫描，即可登录当前电脑账号。")));
    layout.addWidget(label(trText("当前账号：") + session.value("user").toObject().value("email").toString(), "muted"));
    QComboBox address;
    address.setEditable(true);
    for (const auto &value : info.data.value("candidates").toArray()) address.addItem(value.toString());
    if (address.count() == 0) address.setEditText(info.data.value("current_origin").toString());
    layout.addWidget(label(trText("手机能够访问的可信顺手排服务地址")));
    layout.addWidget(&address);
    QLabel image;
    image.setAlignment(Qt::AlignCenter);
    image.setFixedHeight(285);
    layout.addWidget(&image);
    auto *message = label(trText("同一 Wi-Fi 下选择电脑局域网 IP，电脑保持运行。登录码有效 90 秒且仅能使用一次；请勿截图转发。"), "muted");
    layout.addWidget(message);
    auto *generate = button(trText("生成一次性登录码"), nullptr, true);
    layout.addWidget(generate);
    QDateTime expiry;
    QTimer timer;
    timer.setInterval(1000);
    connect(&timer, &QTimer::timeout, &dialog, [&] {
        if (!expiry.isValid()) return;
        const auto seconds = QDateTime::currentDateTimeUtc().secsTo(expiry);
        if (seconds <= 0) { image.clear(); image.setText(trText("登录码已过期，请重新生成。")); expiry = {}; }
        else message->setText(trText("剩余 ") + QString::number(seconds) + trText(" 秒 · 仅限本人扫码 · 使用一次即失效，请勿转发。"));
    });
    connect(&address, &QComboBox::currentTextChanged, &dialog, [&] { image.clear(); expiry = {}; });
    connect(generate, &QPushButton::clicked, &dialog, [&] {
        QUrl target;
        if (!usableOrigin(address.currentText(), target, true)) { image.clear(); message->setText(trText("请填写手机可访问的 HTTP(S) 服务根地址，不能是 localhost、账号密码、路径或参数。")); return; }
        auto result = api.call("POST", "/api/pairing/issue");
        if (!result.ok()) { image.clear(); message->setText(result.error); return; }
        const auto token = result.data.value("token").toString();
        const auto value = (target.toString(QUrl::RemovePath) + "/#pair=" + token).toUtf8();
        try {
            const auto qr = qrcodegen::QrCode::encodeText(value.constData(), qrcodegen::QrCode::Ecc::MEDIUM);
            const int border = 4, scale = 6, size = (qr.getSize() + 2 * border) * scale;
            QImage bitmap(size, size, QImage::Format_RGB32);
            bitmap.fill(Qt::white);
            QPainter painter(&bitmap);
            painter.setPen(Qt::NoPen); painter.setBrush(QColor("#303542"));
            for (int y = 0; y < qr.getSize(); ++y) for (int x = 0; x < qr.getSize(); ++x)
                if (qr.getModule(x, y)) painter.drawRect((x + border) * scale, (y + border) * scale, scale, scale);
            painter.end();
            image.setPixmap(QPixmap::fromImage(bitmap).scaled(280, 280, Qt::KeepAspectRatio, Qt::FastTransformation));
            expiry = QDateTime::fromString(result.data.value("expires_at").toString(), Qt::ISODate);
            if (result.data.value("pairing_limited").toBool())
                QMessageBox::information(&dialog, trText("云端临时授权"), trText("本次手机登录授权有效至 ") + result.data.value("session_expires_at").toString() + trText("。到期后重新扫码或使用密码登录。"));
            message->setText(trText("登录码已生成 · 90 秒内扫描 · 仅限本人使用，请勿转发。"));
        } catch (const std::exception &) { image.clear(); message->setText(trText("二维码无法生成，请缩短服务地址后重试。")); }
    });
    timer.start();
    dialog.exec();
    image.clear();
}
void MainWindow::connection() {
    QDialog dialog(this);
    dialog.setWindowTitle(trText("服务设置"));
    dialog.setMinimumWidth(480);
    QFormLayout form(&dialog);
    form.addRow(label(trText("安装版已自动启动本地服务。也可连接另一台电脑或已部署的 HTTPS 服务；两端使用同一服务才会共享数据。"), "muted"));
    QLineEdit address(api.origin);
    form.addRow(trText("服务地址"), &address);
    auto *message = label(""); form.addRow(message);
    auto *save = button(trText("连接服务"), nullptr, true); form.addRow(save);
    connect(save, &QPushButton::clicked, &dialog, [&] {
        QUrl value;
        if (!usableOrigin(address.text(), value)) { message->setText(trText("请填写不含账号、路径或参数的 HTTP(S) 服务根地址。")); return; }
        if (value.toString() != api.origin && QMessageBox::question(&dialog, trText("切换服务"), trText("切换后将清除当前内存中的登录状态，并显示目标服务的数据。确认继续？")) != QMessageBox::Yes) return;
        api.reset(value.toString());
        setSession({});
        settings.setValue("service_origin", api.origin);
        dialog.accept();
    });
    if (dialog.exec() == QDialog::Accepted) initialize();
}

int MainWindow::smokeTest(const QString &screenshot) {
    // This exercises the real API and renders this native QWidget tree. It does
    // not open a browser, call a paid model, or touch the user's local database.
    auto result = api.call("POST", "/api/auth/register", {{"email", "qt-" + newId() + "@example.com"}, {"password", "Qt-test-only-2026!"}});
    if (!result.ok() || result.data.value("user").isNull()) return 11;
    setSession(result.data);
    result = api.call("POST", "/api/ai/parse", {{"text", trText("明天下午三点到四点面试")}, {"timezone", "Asia/Shanghai"}});
    if (!result.ok() || result.data.value("events").toArray().size() != 1) return 12;
    const auto id = newId();
    const QJsonObject payload{{"request_id", id}, {"events", result.data.value("events")}};
    result = api.call("POST", "/api/events/commit", payload);
    if (!result.ok() || result.data.value("events").toArray().size() != 1) return 13;
    const auto saved = result.data.value("events").toArray().at(0).toObject();
    result = api.call("POST", "/api/events/commit", payload);
    if (!result.ok() || !result.data.value("replayed").toBool()) return 14;
    result = api.call("PATCH", "/api/events/" + saved.value("id").toString(), {{"base_version", saved.value("version")}, {"title", trText("Qt 原生日程")}});
    if (!result.ok()) return 15;
    result = api.call("GET", "/api/model-settings");
    if (!result.ok() || result.data.value("settings").toObject().value("configured").toBool()) return 16;
    auto pair = api.call("POST", "/api/pairing/issue");
    if (!pair.ok() || pair.data.value("token").toString().size() != 43) return 17;
    ApiClient phoneApi(api.origin);
    result = phoneApi.call("POST", "/api/pairing/redeem", {{"token", pair.data.value("token")}});
    if (!result.ok() || result.data.value("user").toObject().value("id").toString() != owner()) return 18;
    result = phoneApi.call("GET", "/api/auth/session");
    if (!result.ok() || result.data.value("user").toObject().value("id").toString() != owner()) return 19;
    result = phoneApi.call("POST", "/api/pairing/redeem", {{"token", pair.data.value("token")}});
    if (result.ok()) return 20;
    day->setDate(localTime(saved.value("start_at").toString()).date());
    refresh();
    if (events.size() != 1 || events.at(0).toObject().value("title") != trText("Qt 原生日程")) return 21;
    composer->setPlainText(trText("明天下午三点到四点面试，今天晚上七点到八点改简历。"));
    show();
    QApplication::processEvents();
    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    QApplication::processEvents();
    if (!screenshot.isEmpty() && !grab().save(screenshot)) return 22;
    result = api.call("DELETE", "/api/events/" + saved.value("id").toString(), {{"base_version", 2}});
    if (!result.ok()) return 23;
    return 0;
}
