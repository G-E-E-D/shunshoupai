#include "window.h"
#include "design.h"
#include "modern_calendar.h"
#include "qrcodegen.hpp"
#include "connection.h"
#include "draft_state.h"
#include <QApplication>
#include <QCheckBox>
#include <QButtonGroup>
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
#include <QFontMetrics>
#include <QGridLayout>
#include <QHeaderView>
#include <QImage>
#include <QJsonArray>
#include <QLabel>
#include <QLineEdit>
#include <QMessageBox>
#include <QPainter>
#include <QPushButton>
#include <QResizeEvent>
#include <QSignalBlocker>
#include <QShortcut>
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
class AccountSummary final : public QLabel {
public:
    explicit AccountSummary(QWidget *parent = nullptr) : QLabel(parent) {
        setTextFormat(Qt::PlainText);
        setWordWrap(false);
        setFixedHeight(24);
        setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Fixed);
    }
    void setFullText(const QString &value) {
        fullText_ = value;
        setToolTip(value);
        updateElision();
    }
protected:
    void resizeEvent(QResizeEvent *event) override {
        QLabel::resizeEvent(event);
        updateElision();
    }
private:
    QString fullText_;
    void updateElision() {
        setText(fontMetrics().elidedText(fullText_, Qt::ElideMiddle, qMax(0, contentsRect().width())));
    }
};

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
    return DraftState::key("pending", origin, owner);
}
void clearLayout(QLayout *layout) {
    while (auto *item = layout->takeAt(0)) {
        if (item->widget()) { item->widget()->hide(); item->widget()->deleteLater(); }
        if (item->layout()) clearLayout(item->layout());
        delete item;
    }
}
}

MainWindow::MainWindow(const QString &origin, bool rememberSession) : api(origin, rememberSession) {
    setWindowTitle(trText("顺手排 · 我的日程"));
    resize(1320, 880);
    setMinimumSize(1060, 720);
    auto *root = new QWidget;
    root->setObjectName("appRoot");
    auto *layout = new QVBoxLayout(root);
    layout->setContentsMargins(16, 8, 16, 8);
    layout->setSpacing(8);
    auto *bar = new QFrame;
    bar->setObjectName("appBar");
    auto *header = new QHBoxLayout(bar);
    header->setContentsMargins(12, 10, 4, 10);
    header->setSpacing(8);
    auto *brandIcon = new QLabel;
    brandIcon->setPixmap(Design::icon("calendar", QColor("#1a73e8")).pixmap(32, 32));
    header->addWidget(brandIcon);
    auto *brandText = label(trText("顺手排"), "brand");
    brandText->setMinimumWidth(180);
    header->addWidget(brandText);
    auto *todayButton = button(trText("今天"));
    todayButton->setToolTip(trText("回到今天"));
    header->addWidget(todayButton);
    auto *previous = button(""), *next = button("");
    for (auto *nav : {previous, next}) { nav->setObjectName("iconButton"); nav->setFixedSize(40, 40); }
    previous->setIcon(Design::icon("previous")); next->setIcon(Design::icon("next"));
    previous->setToolTip(trText("上一天")); next->setToolTip(trText("下一天"));
    header->addWidget(previous); header->addWidget(next);
    dateHeading = label("", "pageTitle");
    dateHeading->setMinimumWidth(165);
    header->addWidget(dateHeading);
    header->addStretch();
    modeLabel = label(trText("规则体验"), "modeChip");
    modeLabel->setWordWrap(false);
    modeLabel->setMinimumWidth(76);
    modeLabel->setFixedHeight(40);
    modeLabel->setSizePolicy(QSizePolicy::Minimum, QSizePolicy::Fixed);
    header->addWidget(modeLabel);
    modelButton = button(trText("模型"));
    modelButton->setObjectName("textButton"); modelButton->setIcon(Design::icon("sparkle", QColor("#0b57d0")));
    phoneButton = button(trText("手机登录"));
    phoneButton->setObjectName("textButton"); phoneButton->setIcon(Design::icon("qr", QColor("#0b57d0")));
    auto *service = button("");
    service->setObjectName("iconButton"); service->setFixedSize(40, 40); service->setIcon(Design::icon("settings"));
    service->setToolTip(trText("服务设置")); service->setAccessibleName(trText("服务设置"));
    accountButton = button(trText("登录"));
    accountButton->setObjectName("tonalButton"); accountButton->setIcon(Design::icon("person", QColor("#0b57d0")));
    header->addWidget(modelButton); header->addWidget(phoneButton); header->addWidget(service); header->addWidget(accountButton);
    layout->addWidget(bar);

    auto *body = new QHBoxLayout;
    body->setSpacing(16);
    auto *sidebar = new QWidget;
    sidebar->setObjectName("sidebarContent");
    auto *side = new QVBoxLayout(sidebar);
    side->setContentsMargins(4, 8, 4, 16);
    side->setSpacing(16);
    auto *create = button(trText("创建日程"));
    create->setObjectName("tonalButton");
    create->setIcon(Design::icon("plus", QColor("#041e49"))); create->setIconSize(QSize(24, 24));
    create->setMinimumHeight(52);
    side->addWidget(create, 0, Qt::AlignLeft);
    currentDay = QDateTime::currentDateTimeUtc().toTimeZone(zone).date();
    miniCalendar = new ModernCalendarWidget;
    miniCalendar->setCompact(true);
    miniCalendar->setDateRange(QDate(2000, 1, 1), QDate(2100, 12, 31));
    miniCalendar->setSelectedDate(currentDay);
    side->addWidget(miniCalendar);
    auto *sideNote = new QFrame; sideNote->setObjectName("sidebarNote");
    auto *note = new QVBoxLayout(sideNote); note->setContentsMargins(16, 16, 16, 16); note->setSpacing(8);
    note->addWidget(label(trText("给重要的事，留个位置"), "fieldLabel"));
    note->addWidget(label(trText("先确认草稿，再安排进日程。手机扫码，就能接着用。"), "fieldHint"));
    side->addWidget(sideNote);
    side->addStretch();
    auto *reminder = new QCheckBox(trText("前台开始提醒"));
    reminders = settings.value("preferences/reminders", false).toBool();
    reminder->setChecked(reminders);
    side->addWidget(reminder);
    side->addWidget(label(trText("开始前 5 分钟提醒，应用保持打开时生效。"), "fieldHint"));
    auto *accountSummary = new AccountSummary;
    accountSummary->setObjectName("muted");
    accountSummary->setFullText(trText("登录后与手机同步"));
    accountLabel = accountSummary;
    accountLabel->setMaximumWidth(270);
    side->addWidget(accountLabel);
    auto *sidebarScroll = new QScrollArea;
    sidebarScroll->setObjectName("sidebarScroll"); sidebarScroll->viewport()->setObjectName("sidebarViewport");
    sidebarScroll->setWidgetResizable(true); sidebarScroll->setFixedWidth(296);
    sidebarScroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    sidebarScroll->setWidget(sidebar);
    body->addWidget(sidebarScroll);

    auto *surface = new QFrame; surface->setObjectName("calendarSurface");
    auto *content = new QVBoxLayout(surface); content->setContentsMargins(28, 24, 28, 22); content->setSpacing(18);
    auto *toolbar = new QHBoxLayout; toolbar->setSpacing(12);
    day = new QDateEdit(currentDay);
    day->setObjectName("selectedDay");
    day->setDisplayFormat(trText("yyyy年M月d日"));
    day->setDateRange(QDate(2000, 1, 1), QDate(2100, 12, 31));
    installModernDateEdit(day);
    day->setMinimumWidth(200);
    toolbar->addWidget(day);
    toolbar->addStretch();
    view = new QComboBox(this);
    view->addItems({trText("当天"), trText("七天"), trText("已完成")});
    view->setCurrentIndex(qBound(0, settings.value("preferences/view", 0).toInt(), 2));
    view->hide();
    auto *segments = new QFrame; segments->setObjectName("segmentedBar");
    auto *segmentLayout = new QHBoxLayout(segments); segmentLayout->setContentsMargins(4, 4, 4, 4); segmentLayout->setSpacing(0);
    auto *group = new QButtonGroup(this); group->setExclusive(true);
    for (int i = 0; i < 3; ++i) {
        auto *choice = button(QStringList{trText("日程"), trText("七天"), trText("已完成")}[i]);
        choice->setObjectName("segmentButton"); choice->setCheckable(true); choice->setChecked(i == view->currentIndex()); group->addButton(choice, i);
        segmentLayout->addWidget(choice);
        connect(choice, &QPushButton::clicked, this, [this, i] { view->setCurrentIndex(i); });
    }
    toolbar->addWidget(segments);
    auto *reload = button(""); reload->setObjectName("iconButton"); reload->setFixedSize(40, 40);
    reload->setIcon(Design::icon("refresh")); reload->setToolTip(trText("刷新日程")); reload->setAccessibleName(trText("刷新日程"));
    toolbar->addWidget(reload);
    content->addLayout(toolbar);
    auto *composeCard = new QFrame; composeCard->setObjectName("composerCard");
    auto *composeRow = new QHBoxLayout(composeCard); composeRow->setContentsMargins(16, 14, 16, 14); composeRow->setSpacing(16);
    auto *composeColumn = new QVBoxLayout; composeColumn->setSpacing(8);
    composeColumn->addWidget(label(trText("一句话安排"), "fieldLabel"));
    composer = new QTextEdit; composer->setObjectName("composer"); composer->setFixedHeight(92);
    composer->setPlaceholderText(trText("明天下午三点到四点面试，今晚七点改简历…"));
    composeColumn->addWidget(composer);
    composeRow->addLayout(composeColumn, 1);
    auto *composeActions = new QVBoxLayout; composeActions->setSpacing(5);
    composeActions->addStretch();
    auto *parseButton = button(trText("整理日程"), nullptr, true);
    parseButton->setIcon(Design::icon("sparkle", Qt::white)); parseButton->setMinimumWidth(138);
    parseButton->setToolTip(trText("整理日程（Ctrl+Enter）"));
    auto *example = button(trText("试试示例")); example->setObjectName("textButton");
    auto *resume = button(trText("恢复待确认保存")); resume->setObjectName("textButton");
    resumeButton = resume;
    resume->hide();
    composeActions->addWidget(parseButton); composeActions->addWidget(example); composeActions->addWidget(resume);
    composeRow->addLayout(composeActions);
    content->addWidget(composeCard);
    auto *filterRow = new QHBoxLayout;
    summary = label(trText("我的日程 · 上海时区"), "muted");
    summary->setWordWrap(false);
    filterRow->addWidget(summary);
    filterRow->addStretch();
    search = new QLineEdit; search->setObjectName("search"); search->setPlaceholderText(trText("搜索日程")); search->setMaximumWidth(270);
    search->addAction(Design::icon("search"), QLineEdit::LeadingPosition);
    filterRow->addWidget(search);
    content->addLayout(filterRow);
    auto *scroll = new QScrollArea; scroll->viewport()->setObjectName("agendaViewport"); scroll->setWidgetResizable(true);
    auto *list = new QWidget; list->setObjectName("agendaContent");
    agenda = new QVBoxLayout(list); agenda->setContentsMargins(0, 0, 8, 0); agenda->setSpacing(12);
    scroll->setWidget(list); content->addWidget(scroll, 1);
    body->addWidget(surface, 1);
    layout->addLayout(body, 1);
    status = label(trText("正在连接日程服务…"), "status"); layout->addWidget(status);
    setCentralWidget(root);
    connect(accountButton, &QPushButton::clicked, this, &MainWindow::auth);
    connect(modelButton, &QPushButton::clicked, this, &MainWindow::models);
    connect(phoneButton, &QPushButton::clicked, this, &MainWindow::phone);
    connect(service, &QPushButton::clicked, this, &MainWindow::connection);
    connect(create, &QPushButton::clicked, this, [this] { editEvent(); });
    connect(parseButton, &QPushButton::clicked, this, &MainWindow::parse);
    auto *quickParse = new QShortcut(QKeySequence(Qt::CTRL | Qt::Key_Return), composer);
    quickParse->setContext(Qt::WidgetShortcut);
    connect(quickParse, &QShortcut::activated, parseButton, &QPushButton::click);
    connect(composer, &QTextEdit::textChanged, this, [this] {
        if (!owner().isEmpty()) settings.setValue("composer/" + pendingKey(api.origin, owner()), composer->toPlainText());
    });
    connect(example, &QPushButton::clicked, this, [this] { composer->setPlainText(trText("明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。")); });
    connect(resume, &QPushButton::clicked, this, &MainWindow::restorePending);
    connect(reminder, &QCheckBox::toggled, this, [this](bool enabled) { reminders = enabled; settings.setValue("preferences/reminders", enabled); });
    connect(reload, &QPushButton::clicked, this, &MainWindow::refresh);
    connect(todayButton, &QPushButton::clicked, this, [this] { day->setDate(QDateTime::currentDateTimeUtc().toTimeZone(zone).date()); });
    connect(previous, &QPushButton::clicked, this, [this] { day->setDate(day->date().addDays(-1)); });
    connect(next, &QPushButton::clicked, this, [this] { day->setDate(day->date().addDays(1)); });
    connect(miniCalendar, &QCalendarWidget::clicked, this, [this](const QDate &date) { day->setDate(date); });
    connect(miniCalendar, &QCalendarWidget::activated, this, [this](const QDate &date) { day->setDate(date); });
    connect(day, &QDateEdit::dateChanged, this, [this](const QDate &date) {
        dateHeading->setText(date.toString(trText("yyyy年M月")));
        miniCalendar->setSelectedDate(date); miniCalendar->setCurrentPage(date.year(), date.month());
        refresh();
    });
    dateHeading->setText(day->date().toString(trText("yyyy年M月")));
    connect(view, &QComboBox::currentIndexChanged, this, [this, group](int index) {
        group->button(index)->setChecked(true); settings.setValue("preferences/view", index); renderEvents(); refresh();
    });
    connect(search, &QLineEdit::textChanged, this, [this] { renderEvents(); });
    poll = new QTimer(this); poll->setInterval(8000);
    connect(poll, &QTimer::timeout, this, [this] { if (!api.busy() && isActiveWindow() && !QApplication::activeModalWidget()) refresh(); });
    connect(qApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState state) {
        if (state == Qt::ApplicationActive) QTimer::singleShot(0, this, [this] { if (!api.busy() && !QApplication::activeModalWidget()) refresh(); });
    });
    poll->start(); modelButton->setEnabled(false); phoneButton->setEnabled(false);
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
    sessionChecked = true;
    static_cast<AccountSummary *>(accountLabel)->setFullText(owner().isEmpty() ? trText("登录后与手机同步") : data.value("user").toObject().value("email").toString());
    accountButton->setText(owner().isEmpty() ? trText("登录 / 注册") : trText("退出账号"));
    modelButton->setEnabled(!owner().isEmpty());
    phoneButton->setEnabled(!owner().isEmpty());
    modeLabel->setText(data.value("ai_provider").toString() == "model" ? trText("模型整理") : trText("规则体验"));
    if (changed) {
        events = {}; reminded.clear();
        const QSignalBlocker blocked(composer);
        if (!previousOwner.isEmpty()) composer->clear();
        if (!owner().isEmpty() && composer->toPlainText().isEmpty())
            composer->setPlainText(settings.value("composer/" + pendingKey(api.origin, owner())).toString());
        if (!owner().isEmpty()) settings.setValue("composer/" + pendingKey(api.origin, owner()), composer->toPlainText());
    }
    renderEvents();
}
void MainWindow::initialize() {
    const auto result = api.call("GET", "/api/auth/session");
    if (!result.ok()) { sessionChecked = false; status->setText(trText("暂时未连接，恢复后会自动重试。")); return; }
    if (result.data.value("user").toObject().isEmpty()) api.forgetSession();
    setSession(result.data);
    status->setText(trText("服务已就绪 · ") + api.origin);
    refresh();
}
void MainWindow::refresh() {
    if (api.busy()) return;
    updateRecoveryButton();
    if (!sessionChecked) { initialize(); return; }
    const auto today = QDateTime::currentDateTimeUtc().toTimeZone(zone).date();
    if (today != currentDay) {
        const bool followsToday = day->date() == currentDay;
        currentDay = today;
        if (followsToday) { const QSignalBlocker blocked(day); day->setDate(today); }
    }
    dateHeading->setText(day->date().toString(trText("yyyy年M月")));
    miniCalendar->setSelectedDate(day->date());
    miniCalendar->setCurrentPage(day->date().year(), day->date().month());
    if (owner().isEmpty()) { renderEvents(); return; }
    const QDateTime from(day->date(), QTime(0, 0), zone);
    const auto to = from.addDays(view->currentIndex() == 1 ? 7 : 1);
    const auto query = "?from=" + QString::fromLatin1(QUrl::toPercentEncoding(from.toUTC().toString(Qt::ISODate)))
        + "&to=" + QString::fromLatin1(QUrl::toPercentEncoding(to.toUTC().toString(Qt::ISODate)));
    const auto result = api.call("GET", "/api/events" + query);
    if (!result.ok()) {
        status->setText(trText("同步失败：") + result.error);
        if (result.status == 401) { api.forgetSession(); setSession({}); status->setText(trText("登录已过期，请重新登录。你的草稿已保留。")); }
        return;
    }
    const auto freshEvents = result.data.value("events").toArray();
    // An unchanged poll must not recreate focused controls or move the scroll.
    if (freshEvents != events) { events = freshEvents; renderEvents(); }
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
    updateRecoveryButton();
    clearLayout(agenda);
    int count = 0;
    QDate lastGroup;
    for (const auto &value : events) {
        auto event = value.toObject();
        const bool complete = event.value("status").toString() == "completed";
        if ((view->currentIndex() == 2 && !complete) || !event.value("title").toString().contains(search->text(), Qt::CaseInsensitive)) continue;
        ++count;
        const auto start = localTime(event.value("start_at").toString());
        const auto end = localTime(event.value("end_at").toString());
        if (view->currentIndex() == 1 && start.date() != lastGroup) {
            lastGroup = start.date();
            agenda->addWidget(label(start.toString(trText("M月d日")) + "  " + QStringList{trText("周一"),trText("周二"),trText("周三"),trText("周四"),trText("周五"),trText("周六"),trText("周日")}[start.date().dayOfWeek() - 1], "dayGroup"));
        }
        auto *entry = new QWidget;
        auto *entryLayout = new QHBoxLayout(entry);
        entryLayout->setContentsMargins(0, 0, 0, 0); entryLayout->setSpacing(14);
        auto *timeColumn = new QVBoxLayout; timeColumn->setSpacing(4);
        auto *time = label(start.toString("HH:mm"), "eventTime");
        time->setMinimumWidth(58);
        timeColumn->addWidget(time);
        timeColumn->addWidget(label(start.toString("MM.dd"), "eventDate"));
        timeColumn->addStretch();
        entryLayout->addLayout(timeColumn);
        auto *card = new QFrame; card->setObjectName("eventCard"); card->setProperty("completed", complete);
        auto *row = new QHBoxLayout(card); row->setContentsMargins(18, 15, 12, 15); row->setSpacing(12);
        auto *details = new QVBoxLayout; details->setSpacing(6);
        auto *title = label(event.value("title").toString(), "eventTitle");
        if (complete) { auto font = title->font(); font.setStrikeOut(true); title->setFont(font); }
        details->addWidget(title);
        details->addWidget(label(start.toString("HH:mm") + " – " + end.toString(start.date() == end.date() ? "HH:mm" : "MM.dd HH:mm") + (complete ? trText("  ·  已完成") : trText("  ·  待进行")), "eventMeta"));
        row->addLayout(details, 1);
        auto *toggle = button(complete ? trText("恢复") : trText("完成"));
        toggle->setObjectName("textButton"); toggle->setIcon(Design::icon("check", QColor("#0b57d0")));
        auto *edit = button(""), *remove = button("");
        for (auto *action : {edit, remove}) { action->setObjectName("iconButton"); action->setFixedSize(36, 36); }
        edit->setIcon(Design::icon("edit")); remove->setIcon(Design::icon("delete"));
        edit->setToolTip(trText("编辑日程")); edit->setAccessibleName(trText("编辑日程"));
        remove->setToolTip(trText("删除日程")); remove->setAccessibleName(trText("删除日程"));
        row->addWidget(toggle); row->addWidget(edit); row->addWidget(remove);
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
        entryLayout->addWidget(card, 1);
        agenda->addWidget(entry);
    }
    if (count == 0) {
        agenda->addStretch(1);
        auto *emptyIcon = label("", "emptyIcon"); emptyIcon->setFixedSize(72, 72); emptyIcon->setAlignment(Qt::AlignCenter);
        emptyIcon->setPixmap(Design::icon("calendar", QColor("#1a73e8")).pixmap(34, 34));
        agenda->addWidget(emptyIcon, 0, Qt::AlignHCenter);
        agenda->addWidget(label(owner().isEmpty() ? trText("登录，开始安排") : trText("给今天留一点空白"), "emptyTitle"), 0, Qt::AlignHCenter);
        agenda->addWidget(label(owner().isEmpty() ? trText("用自己的账号，让电脑和手机接着安排。") : trText("创建日程，或写下一句话，安排接下来的生活。"), "emptyCopy"), 0, Qt::AlignHCenter);
    }
    agenda->addStretch(2);
    summary->setText(trText("我的日程  ·  ") + QString::number(count) + trText(" 项  ·  上海时区"));
}
void MainWindow::parse() {
    if (owner().isEmpty()) { auth(); if (owner().isEmpty()) return; }
    if (composer->toPlainText().trimmed().isEmpty() || composer->toPlainText().size() > 2000) {
        QMessageBox::information(this, trText("写下你的安排"), trText("请填写 1–2000 字的日程描述。")); return;
    }
    if (!settings.value(pendingKey(api.origin, owner())).toString().isEmpty()
        || !DraftState::read(settings, DraftState::key("review", api.origin, owner())).isEmpty()) {
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
void MainWindow::restorePending() {
    if (owner().isEmpty()) return;
    const auto pending = DraftState::read(settings, pendingKey(api.origin, owner()));
    if (!pending.isEmpty()) { drafts(pending.value("events").toArray(), pending.value("request_id").toString()); return; }
    const auto review = DraftState::read(settings, DraftState::key("review", api.origin, owner()));
    if (!review.isEmpty()) { drafts(review.value("events").toArray()); return; }
    const auto prefix = DraftState::key("editor", api.origin, owner()) + "/";
    for (const auto &key : settings.allKeys()) {
        if (!key.startsWith(prefix)) continue;
        const auto saved = DraftState::read(settings, key);
        if (!saved.isEmpty()) { editEvent(saved.value("event").toObject()); return; }
    }
    status->setText(trText("没有尚未完成的草稿。"));
}
void MainWindow::updateRecoveryButton() {
    QString caption;
    if (!owner().isEmpty()) {
        if (!settings.value(pendingKey(api.origin, owner())).toString().isEmpty()) caption = trText("确认上次保存结果");
        else if (!DraftState::read(settings, DraftState::key("review", api.origin, owner())).isEmpty()) caption = trText("继续未提交草稿");
        else {
            const auto prefix = DraftState::key("editor", api.origin, owner()) + "/";
            for (const auto &key : settings.allKeys()) {
                if (key.startsWith(prefix) && !DraftState::read(settings, key).isEmpty()) { caption = trText("继续编辑日程"); break; }
            }
        }
    }
    resumeButton->setVisible(!caption.isEmpty());
    if (!caption.isEmpty()) resumeButton->setText(caption);
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
