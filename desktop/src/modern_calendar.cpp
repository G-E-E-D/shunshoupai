#include "modern_calendar.h"

#include <QAbstractSpinBox>
#include <QDateEdit>
#include <QDateTimeEdit>
#include <QComboBox>
#include <QDateTime>
#include <QEvent>
#include <QFrame>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QIcon>
#include <QKeyEvent>
#include <QLabel>
#include <QLineEdit>
#include <QLocale>
#include <QPainter>
#include <QPixmap>
#include <QResizeEvent>
#include <QShowEvent>
#include <QStyle>
#include <QTimeZone>
#include <QToolButton>
#include <QVBoxLayout>
#include <algorithm>

namespace {
class CalendarIndicator final : public QWidget {
public:
    explicit CalendarIndicator(QWidget *editor, bool calendar = true) : QWidget(editor), calendar_(calendar) {
        setObjectName(calendar ? "modernDateDropdownIcon" : "modernComboDropdownIcon");
        setAttribute(Qt::WA_TransparentForMouseEvents);
        setAttribute(Qt::WA_TranslucentBackground);
        setFocusPolicy(Qt::NoFocus);
        setStyleSheet("background:transparent;border:0;padding:0;");
        editor->installEventFilter(this);
        align();
    }
protected:
    bool eventFilter(QObject *object, QEvent *event) override {
        if (object == parentWidget() && (event->type() == QEvent::Resize || event->type() == QEvent::Show)) align();
        if (object == parentWidget() && event->type() == QEvent::EnabledChange) update();
        return QWidget::eventFilter(object, event);
    }
    void paintEvent(QPaintEvent *) override {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setPen(QPen(QColor(isEnabled() ? "#5f6368" : "#dadce0"), 1.5, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
        if (!calendar_) {
            painter.drawLine(QPointF(5, 7), QPointF(9, 11));
            painter.drawLine(QPointF(9, 11), QPointF(13, 7));
            return;
        }
        painter.setBrush(Qt::NoBrush);
        painter.drawRoundedRect(QRectF(2, 4, 14, 12), 2, 2);
        painter.drawLine(QPointF(2, 8), QPointF(16, 8));
        painter.drawLine(QPointF(6, 2), QPointF(6, 5));
        painter.drawLine(QPointF(12, 2), QPointF(12, 5));
        painter.setPen(Qt::NoPen);
        painter.setBrush(QColor(isEnabled() ? "#5f6368" : "#dadce0"));
        for (int y : {10, 13}) for (int x : {6, 10}) painter.drawEllipse(QPointF(x, y), 0.7, 0.7);
    }
private:
    bool calendar_;
    void align() {
        setGeometry(parentWidget()->width() - 29, (parentWidget()->height() - 18) / 2, 18, 18);
        raise();
        show();
    }
};

QDate shanghaiToday() {
    return QDateTime::currentDateTimeUtc().toTimeZone(QTimeZone("Asia/Shanghai")).date();
}

QIcon chevron(bool right) {
    QPixmap pixels(20, 20);
    pixels.fill(Qt::transparent);
    QPainter painter(&pixels);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setPen(QPen(QColor("#5f6368"), 1.8, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
    const qreal outside = right ? 8.0 : 12.0;
    const qreal inside = right ? 12.0 : 8.0;
    painter.drawLine(QPointF(outside, 5), QPointF(inside, 10));
    painter.drawLine(QPointF(inside, 10), QPointF(outside, 15));
    return QIcon(pixels);
}

QIcon dropdownIcon() {
    QPixmap pixels(16, 16);
    pixels.fill(Qt::transparent);
    QPainter painter(&pixels);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setPen(QPen(QColor("#5f6368"), 1.6, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
    painter.drawLine(QPointF(4, 6), QPointF(8, 10));
    painter.drawLine(QPointF(8, 10), QPointF(12, 6));
    return QIcon(pixels);
}

void refreshStyle(QWidget *widget) {
    widget->style()->unpolish(widget);
    widget->style()->polish(widget);
    widget->update();
}

bool validMonth(int year, int month, const QDate &minimum, const QDate &maximum) {
    const QDate begin(year, month, 1);
    return begin.isValid() && begin <= maximum && begin.addMonths(1).addDays(-1) >= minimum;
}
}

ModernCalendarWidget::ModernCalendarWidget(QWidget *parent) : QCalendarWidget(parent) {
    setObjectName("modernCalendar");
    setAccessibleName(QString::fromUtf8("选择日期"));
    setAccessibleDescription(QString::fromUtf8("使用方向键选择日期，Page Up 和 Page Down 切换月份，回车确认。"));
    setFirstDayOfWeek(Qt::Monday);
    setNavigationBarVisible(false);
    setHorizontalHeaderFormat(QCalendarWidget::NoHorizontalHeader);
    setVerticalHeaderFormat(QCalendarWidget::NoVerticalHeader);
    setGridVisible(false);
    setDateEditEnabled(false);
    // QCalendarWidget normally proxies keyboard focus to its built-in table,
    // which is hidden in this implementation. Give the visible native picker
    // itself focus so real keyboard events reach our navigation handler.
    setFocusProxy(nullptr);
    setFocusPolicy(Qt::StrongFocus);
    // Keep Qt's date model for range/selection semantics, while replacing its
    // native navigation bar and table with our own accessible native controls.
    for (auto *child : findChildren<QWidget *>(QString(), Qt::FindDirectChildrenOnly)) child->hide();

    setStyleSheet(R"(
        QCalendarWidget#modernCalendar {background:transparent;border:0;}
        QFrame#modernCalendarSurface {background:#ffffff;border:1px solid #e8eaed;border-radius:20px;}
        QWidget#modernCalendarDays,QWidget#modernCalendarMonths,QWidget#modernCalendarFooter {background:transparent;border:0;}
        QLabel[calendarRole="weekday"] {background:transparent;color:#5f6368;font-size:12px;font-weight:500;border:0;}
        QLabel[calendarRole="hint"] {background:transparent;color:#80868b;font-size:11px;border:0;}
        QToolButton {background:transparent;border:0;color:#202124;font-family:'Microsoft YaHei UI';font-size:13px;padding:0;}
        QToolButton[calendarRole="nav"] {border-radius:20px;}
        QToolButton[calendarRole="nav"]:hover {background:#f1f3f4;}
        QToolButton[calendarRole="nav"]:focus {background:#edf3fe;border:2px solid #1a73e8;}
        QToolButton[calendarRole="nav"]:disabled {color:#dadce0;}
        QToolButton#popupMonthLabel {text-align:left;font-size:15px;font-weight:600;border-radius:8px;padding:0 4px;}
        QToolButton#popupMonthLabel:hover {background:#f1f3f4;}
        QToolButton#popupMonthLabel:focus {background:#edf3fe;border:2px solid #1a73e8;}
        QToolButton[calendarRole="day"] {border-radius:20px;}
        QToolButton[calendarRole="day"][calendarCompact="true"] {border-radius:15px;}
        QToolButton[calendarRole="day"]:hover {background:#edf3fe;}
        QToolButton[calendarRole="day"][outsideMonth="true"] {color:#9aa0a6;}
        QToolButton[calendarRole="day"][selectedDate="true"] {background:#d3e3fd;color:#0b57d0;font-weight:600;}
        QToolButton[calendarRole="day"][today="true"] {background:#1a73e8;color:#ffffff;font-weight:600;}
        QToolButton[calendarRole="day"]:disabled {color:#dadce0;background:transparent;}
        QToolButton[calendarRole="day"]:focus {border:2px solid #1a73e8;}
        QToolButton[calendarRole="month"] {border-radius:18px;padding:0 8px;}
        QToolButton[calendarRole="month"]:hover {background:#edf3fe;}
        QToolButton[calendarRole="month"][selectedMonth="true"] {background:#d3e3fd;color:#0b57d0;font-weight:600;}
        QToolButton[calendarRole="month"]:disabled {color:#dadce0;}
        QToolButton[calendarRole="month"]:focus {border:2px solid #1a73e8;}
        QToolButton#calendarToday {color:#0b57d0;font-size:13px;font-weight:600;border-radius:18px;padding:0 14px;}
        QToolButton#calendarToday:hover {background:#edf3fe;}
        QToolButton#calendarToday:focus {border:2px solid #1a73e8;}
        QToolButton#calendarToday:disabled {color:#dadce0;}
    )");

    surface_ = new QFrame(this);
    surface_->setObjectName("modernCalendarSurface");
    surfaceLayout_ = new QVBoxLayout(surface_);
    surfaceLayout_->setContentsMargins(16, 14, 16, 12);
    surfaceLayout_->setSpacing(4);
    auto *header = new QHBoxLayout;
    header->setSpacing(0);
    monthTitle_ = new QToolButton(surface_);
    monthTitle_->setObjectName("popupMonthLabel");
    monthTitle_->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    monthTitle_->setIcon(dropdownIcon());
    monthTitle_->setIconSize(QSize(16, 16));
    monthTitle_->setLayoutDirection(Qt::RightToLeft);
    monthTitle_->setCursor(Qt::PointingHandCursor);
    monthTitle_->setMinimumHeight(40);
    monthTitle_->setAccessibleName(QString::fromUtf8("选择月份和年份"));
    header->addWidget(monthTitle_, 1);
    previous_ = new QToolButton(surface_);
    previous_->setObjectName("calendarPreviousMonth");
    previous_->setProperty("calendarRole", "nav");
    previous_->setIcon(chevron(false));
    next_ = new QToolButton(surface_);
    next_->setObjectName("calendarNextMonth");
    next_->setProperty("calendarRole", "nav");
    next_->setIcon(chevron(true));
    for (auto *nav : {previous_, next_}) {
        nav->setFixedSize(40, 40);
        nav->setIconSize(QSize(20, 20));
        nav->setCursor(Qt::PointingHandCursor);
        header->addWidget(nav);
    }
    surfaceLayout_->addLayout(header);

    daysPanel_ = new QWidget(surface_);
    daysPanel_->setObjectName("modernCalendarDays");
    auto *daysOuter = new QVBoxLayout(daysPanel_);
    daysOuter->setContentsMargins(0, 0, 0, 0);
    daysOuter->setSpacing(0);
    auto *weekdays = new QGridLayout;
    weekdays->setContentsMargins(0, 0, 0, 0);
    weekdays->setHorizontalSpacing(2);
    const QStringList names{QString::fromUtf8("一"), QString::fromUtf8("二"), QString::fromUtf8("三"), QString::fromUtf8("四"), QString::fromUtf8("五"), QString::fromUtf8("六"), QString::fromUtf8("日")};
    for (int column = 0; column < 7; ++column) {
        auto *dayName = new QLabel(names[column], daysPanel_);
        dayName->setProperty("calendarRole", "weekday");
        dayName->setAlignment(Qt::AlignCenter);
        dayName->setFixedHeight(28);
        weekdays->addWidget(dayName, 0, column);
        weekdays->setColumnStretch(column, 1);
    }
    daysOuter->addLayout(weekdays);
    daysLayout_ = new QGridLayout;
    daysLayout_->setContentsMargins(0, 0, 0, 0);
    daysLayout_->setSpacing(2);
    for (int index = 0; index < int(days_.size()); ++index) {
        auto *day = new QToolButton(daysPanel_);
        day->setProperty("calendarRole", "day");
        day->setCursor(Qt::PointingHandCursor);
        day->setFocusPolicy(Qt::NoFocus);
        day->installEventFilter(this);
        days_[index] = day;
        daysLayout_->addWidget(day, index / 7, index % 7, Qt::AlignCenter);
        daysLayout_->setColumnStretch(index % 7, 1);
        connect(day, &QToolButton::clicked, this, [this, index] { chooseDate(visibleDates_[index]); });
    }
    daysOuter->addLayout(daysLayout_);
    surfaceLayout_->addWidget(daysPanel_, 1);

    monthsPanel_ = new QWidget(surface_);
    monthsPanel_->setObjectName("modernCalendarMonths");
    auto *monthGrid = new QGridLayout(monthsPanel_);
    monthGrid->setContentsMargins(0, 14, 0, 14);
    monthGrid->setSpacing(10);
    for (int index = 0; index < int(months_.size()); ++index) {
        auto *month = new QToolButton(monthsPanel_);
        month->setObjectName("calendarMonth_" + QString::number(index + 1));
        month->setProperty("calendarRole", "month");
        month->setProperty("calendarMonthNumber", index + 1);
        month->setText(QString::number(index + 1) + QString::fromUtf8("月"));
        month->setMinimumHeight(40);
        month->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
        month->setCursor(Qt::PointingHandCursor);
        month->installEventFilter(this);
        months_[index] = month;
        monthGrid->addWidget(month, index / 3, index % 3);
        connect(month, &QToolButton::clicked, this, [this, index] { chooseMonth(index + 1); });
    }
    surfaceLayout_->addWidget(monthsPanel_, 1);
    monthsPanel_->hide();

    footer_ = new QWidget(surface_);
    footer_->setObjectName("modernCalendarFooter");
    auto *footerLayout = new QHBoxLayout(footer_);
    footerLayout->setContentsMargins(0, 4, 0, 0);
    auto *hint = new QLabel(QString::fromUtf8("方向键选择 · 回车确认"), footer_);
    hint->setProperty("calendarRole", "hint");
    footerLayout->addWidget(hint);
    footerLayout->addStretch();
    today_ = new QToolButton(footer_);
    today_->setObjectName("calendarToday");
    today_->setText(QString::fromUtf8("今天"));
    today_->setMinimumHeight(36);
    today_->setCursor(Qt::PointingHandCursor);
    footerLayout->addWidget(today_);
    surfaceLayout_->addWidget(footer_);

    connect(previous_, &QToolButton::clicked, this, [this] { navigate(-1); });
    connect(next_, &QToolButton::clicked, this, [this] { navigate(1); });
    connect(monthTitle_, &QToolButton::clicked, this, [this] {
        choosingMonth_ = !choosingMonth_;
        yearCursor_ = yearShown();
        monthCursor_ = monthShown();
        refreshControls();
    });
    connect(today_, &QToolButton::clicked, this, [this] { chooseDate(shanghaiToday()); });
    connect(this, &QCalendarWidget::selectionChanged, this, [this] { refreshControls(); });
    connect(this, &QCalendarWidget::currentPageChanged, this, [this] { refreshControls(); });
    updateSizing();
    refreshControls();
    surface_->show();
}

void ModernCalendarWidget::setCompact(bool compact) {
    if (compact_ == compact) return;
    compact_ = compact;
    setProperty("compact", compact);
    updateSizing();
    refreshControls();
}

QSize ModernCalendarWidget::sizeHint() const { return compact_ ? QSize(280, 290) : QSize(336, 386); }
QSize ModernCalendarWidget::minimumSizeHint() const { return compact_ ? QSize(252, 280) : QSize(320, 380); }

void ModernCalendarWidget::updateSizing() {
    const int diameter = compact_ ? 30 : 40;
    const int margin = compact_ ? 10 : 16;
    surfaceLayout_->setContentsMargins(margin, compact_ ? 8 : 14, margin, compact_ ? 8 : 12);
    daysLayout_->setSpacing(compact_ ? 1 : 2);
    for (auto *day : days_) {
        day->setProperty("calendarCompact", compact_);
        day->setFixedSize(diameter, diameter);
    }
    footer_->setVisible(!compact_);
    setMinimumSize(minimumSizeHint());
    setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Fixed);
    setMaximumHeight(sizeHint().height());
    updateGeometry();
}

void ModernCalendarWidget::refreshControls() {
    const QDate today = shanghaiToday();
    const QDate first(yearShown(), monthShown(), 1);
    const QDate begin = first.addDays(-(first.dayOfWeek() - 1));
    const QLocale locale(QLocale::Chinese, QLocale::China);
    monthTitle_->setText(choosingMonth_
        ? QString::number(yearCursor_) + QString::fromUtf8("年")
        : QString::number(yearShown()) + QString::fromUtf8("年 ") + QString::number(monthShown()) + QString::fromUtf8("月"));
    monthTitle_->setAccessibleName(monthTitle_->text() + QString::fromUtf8("，选择月份和年份"));
    monthTitle_->setAccessibleDescription(choosingMonth_ ? QString::fromUtf8("点击返回日期选择") : QString::fromUtf8("点击快速选择月份和年份"));
    const auto previousMonth = first.addMonths(-1);
    const auto nextMonth = first.addMonths(1);
    previous_->setEnabled(choosingMonth_ ? yearCursor_ > minimumDate().year() : validMonth(previousMonth.year(), previousMonth.month(), minimumDate(), maximumDate()));
    next_->setEnabled(choosingMonth_ ? yearCursor_ < maximumDate().year() : validMonth(nextMonth.year(), nextMonth.month(), minimumDate(), maximumDate()));
    previous_->setAccessibleName(choosingMonth_ ? QString::fromUtf8("上一年") : QString::fromUtf8("上个月"));
    next_->setAccessibleName(choosingMonth_ ? QString::fromUtf8("下一年") : QString::fromUtf8("下个月"));
    previous_->setToolTip(previous_->accessibleName());
    next_->setToolTip(next_->accessibleName());
    for (int index = 0; index < int(days_.size()); ++index) {
        const QDate value = begin.addDays(index);
        auto *day = days_[index];
        visibleDates_[index] = value;
        day->setText(QString::number(value.day()));
        day->setObjectName("calendarDay_" + value.toString(Qt::ISODate));
        day->setAccessibleName(locale.toString(value, "yyyy年M月d日 dddd"));
        day->setToolTip(locale.toString(value, "yyyy年M月d日 dddd"));
        day->setProperty("outsideMonth", value.month() != monthShown());
        day->setProperty("selectedDate", value == selectedDate());
        day->setProperty("today", value == today);
        day->setEnabled(value >= minimumDate() && value <= maximumDate() && selectionMode() != QCalendarWidget::NoSelection);
        refreshStyle(day);
    }
    for (int index = 0; index < int(months_.size()); ++index) {
        auto *month = months_[index];
        month->setEnabled(validMonth(yearCursor_, index + 1, minimumDate(), maximumDate()));
        month->setProperty("selectedMonth", yearCursor_ == yearShown() && index + 1 == monthShown());
        month->setAccessibleName(QString::number(yearCursor_) + QString::fromUtf8("年") + QString::number(index + 1) + QString::fromUtf8("月"));
        refreshStyle(month);
    }
    today_->setEnabled(today >= minimumDate() && today <= maximumDate() && selectionMode() != QCalendarWidget::NoSelection);
    daysPanel_->setVisible(!choosingMonth_);
    monthsPanel_->setVisible(choosingMonth_);
}

void ModernCalendarWidget::navigate(int direction) {
    if (choosingMonth_) {
        yearCursor_ = std::clamp(yearCursor_ + direction, minimumDate().year(), maximumDate().year());
        refreshControls();
    } else {
        const auto target = QDate(yearShown(), monthShown(), 1).addMonths(direction);
        if (validMonth(target.year(), target.month(), minimumDate(), maximumDate())) setCurrentPage(target.year(), target.month());
    }
    setFocus(Qt::OtherFocusReason);
}

void ModernCalendarWidget::chooseDate(QDate date) {
    if (!date.isValid() || date < minimumDate() || date > maximumDate() || selectionMode() == QCalendarWidget::NoSelection) return;
    choosingMonth_ = false;
    setSelectedDate(date);
    refreshControls();
    emit clicked(date);
}

void ModernCalendarWidget::chooseMonth(int month) {
    if (!validMonth(yearCursor_, month, minimumDate(), maximumDate())) return;
    choosingMonth_ = false;
    setCurrentPage(yearCursor_, month);
    refreshControls();
    setFocus(Qt::OtherFocusReason);
}

void ModernCalendarWidget::resizeEvent(QResizeEvent *event) {
    QCalendarWidget::resizeEvent(event);
    surface_->setGeometry(rect());
    surface_->raise();
}

void ModernCalendarWidget::showEvent(QShowEvent *event) {
    QCalendarWidget::showEvent(event);
    openingDate_ = selectedDate();
    choosingMonth_ = false;
    refreshControls();
    surface_->setGeometry(rect());
    surface_->raise();
    if (parentWidget() && parentWidget()->windowFlags().testFlag(Qt::Popup)) {
        // QDateTimeEdit owns the popup and its positioning/cancel behavior.
        // Remove the old palette frame while retaining Qt's native popup shadow.
        parentWidget()->setStyleSheet("background:transparent;border:0;padding:0;");
    }
}

bool ModernCalendarWidget::handleNavigationKey(QKeyEvent *event) {
    if (event->key() == Qt::Key_Escape && choosingMonth_) {
        choosingMonth_ = false;
        refreshControls();
        setFocus();
        return true;
    }
    if (choosingMonth_) {
        int change = 0;
        if (event->key() == Qt::Key_Left) change = -1;
        else if (event->key() == Qt::Key_Right) change = 1;
        else if (event->key() == Qt::Key_Up) change = -3;
        else if (event->key() == Qt::Key_Down) change = 3;
        else if (event->key() == Qt::Key_PageUp) { navigate(-1); return true; }
        else if (event->key() == Qt::Key_PageDown) { navigate(1); return true; }
        else if (event->key() == Qt::Key_Return || event->key() == Qt::Key_Enter) { chooseMonth(monthCursor_); return true; }
        else return false;
        monthCursor_ = std::clamp(monthCursor_ + change, 1, 12);
        months_[monthCursor_ - 1]->setFocus(Qt::OtherFocusReason);
        return true;
    }
    QDate target = selectedDate();
    switch (event->key()) {
    case Qt::Key_Left: target = target.addDays(-1); break;
    case Qt::Key_Right: target = target.addDays(1); break;
    case Qt::Key_Up: target = target.addDays(-7); break;
    case Qt::Key_Down: target = target.addDays(7); break;
    case Qt::Key_PageUp: target = event->modifiers().testFlag(Qt::ControlModifier) ? target.addYears(-1) : target.addMonths(-1); break;
    case Qt::Key_PageDown: target = event->modifiers().testFlag(Qt::ControlModifier) ? target.addYears(1) : target.addMonths(1); break;
    case Qt::Key_Home: target = QDate(target.year(), target.month(), 1); break;
    case Qt::Key_End: target = QDate(target.year(), target.month(), target.daysInMonth()); break;
    case Qt::Key_Return:
    case Qt::Key_Enter:
    case Qt::Key_Space:
        if (selectionMode() != QCalendarWidget::NoSelection) emit activated(selectedDate());
        return true;
    default: return false;
    }
    if (selectionMode() != QCalendarWidget::NoSelection) {
        if (target < minimumDate()) target = minimumDate();
        if (target > maximumDate()) target = maximumDate();
        setSelectedDate(target);
    }
    return true;
}

void ModernCalendarWidget::keyPressEvent(QKeyEvent *event) {
    if (event->key() == Qt::Key_Escape && !choosingMonth_ && openingDate_.isValid()
        && parentWidget() && parentWidget()->windowType() == Qt::Popup) {
        // Qt feeds keyboard preview dates back into the edit immediately. Its
        // popup may therefore replace its own oldDate; retain our opening date
        // explicitly so Escape has genuine cancel behavior.
        setSelectedDate(openingDate_);
    }
    if (handleNavigationKey(event)) { event->accept(); return; }
    // In particular, let Escape reach QDateTimeEdit's popup: it restores the
    // original field value rather than committing keyboard preview changes.
    QCalendarWidget::keyPressEvent(event);
}

bool ModernCalendarWidget::eventFilter(QObject *object, QEvent *event) {
    if (event->type() == QEvent::FocusIn && object->property("calendarRole").toString() == "month")
        monthCursor_ = object->property("calendarMonthNumber").toInt();
    if (event->type() == QEvent::KeyPress && handleNavigationKey(static_cast<QKeyEvent *>(event))) return true;
    return QCalendarWidget::eventFilter(object, event);
}

void installModernDateTimeEdit(QDateTimeEdit *editor) {
    if (!editor || editor->property("modernCalendarInstalled").toBool()) return;
    editor->setProperty("modernCalendarInstalled", true);
    editor->setCalendarPopup(true);
    auto *calendar = new ModernCalendarWidget;
    calendar->setDateRange(editor->minimumDate(), editor->maximumDate());
    calendar->setSelectedDate(editor->date());
    editor->setCalendarWidget(calendar);
    editor->setKeyboardTracking(false);
    editor->setButtonSymbols(QAbstractSpinBox::NoButtons);
    editor->setMinimumHeight(44);
    editor->setStyleSheet(R"(
        QDateTimeEdit,QDateEdit {background:#ffffff;color:#202124;border:1px solid #dadce0;border-radius:10px;padding:10px 38px 10px 12px;selection-background-color:#d3e3fd;selection-color:#174ea6;}
        QDateTimeEdit:hover,QDateEdit:hover {border-color:#9aa0a6;}
        QDateTimeEdit:focus,QDateEdit:focus {border:2px solid #1a73e8;padding:9px 37px 9px 11px;}
        QDateTimeEdit::drop-down,QDateEdit::drop-down {subcontrol-origin:border;subcontrol-position:top right;width:34px;background:transparent;border:0;border-radius:9px;}
        QDateTimeEdit::drop-down:hover,QDateEdit::drop-down:hover {background:#edf3fe;}
        QDateTimeEdit::down-arrow,QDateEdit::down-arrow {image:none;width:0;height:0;border:0;}
    )");
    new CalendarIndicator(editor);
}

void installModernDateEdit(QDateEdit *editor) { installModernDateTimeEdit(editor); }

void installModernComboBox(QComboBox *editor) {
    if (!editor || editor->property("modernComboInstalled").toBool()) return;
    editor->setProperty("modernComboInstalled", true);
    editor->setMinimumHeight(44);
    editor->setStyleSheet(R"(
        QComboBox {background:#ffffff;color:#202124;border:1px solid #c4c7c5;border-radius:10px;padding:10px 38px 10px 12px;}
        QComboBox:hover {border-color:#747775;}
        QComboBox:focus {border:2px solid #0b57d0;padding:9px 37px 9px 11px;}
        QComboBox::drop-down {subcontrol-origin:border;subcontrol-position:top right;width:34px;background:transparent;border:0;border-radius:9px;}
        QComboBox::drop-down:hover {background:#edf3fe;}
        QComboBox::down-arrow {image:none;width:0;height:0;border:0;}
        QComboBox QAbstractItemView {border:1px solid #e3e7ed;background:white;color:#202124;selection-background-color:#e8f0fe;selection-color:#174ea6;outline:0;padding:8px;}
    )");
    new CalendarIndicator(editor, false);
}
