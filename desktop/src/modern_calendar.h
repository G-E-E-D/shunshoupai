#pragma once

#include <QCalendarWidget>
#include <QDate>
#include <array>

class QDateEdit;
class QDateTimeEdit;
class QComboBox;
class QFrame;
class QGridLayout;
class QKeyEvent;
class QLabel;
class QShowEvent;
class QToolButton;
class QVBoxLayout;

// A native date picker with its own Material-inspired month controls and date
// buttons. QCalendarWidget remains the model and public signal contract, so it
// also works inside QDateTimeEdit's standard keyboard-aware popup container.
class ModernCalendarWidget final : public QCalendarWidget {
public:
    explicit ModernCalendarWidget(QWidget *parent = nullptr);
    void setCompact(bool compact);
    bool isCompact() const { return compact_; }
    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void resizeEvent(QResizeEvent *event) override;
    void showEvent(QShowEvent *event) override;
    void keyPressEvent(QKeyEvent *event) override;
    bool eventFilter(QObject *object, QEvent *event) override;

private:
    QFrame *surface_;
    QVBoxLayout *surfaceLayout_;
    QToolButton *monthTitle_;
    QToolButton *previous_;
    QToolButton *next_;
    QToolButton *today_;
    QWidget *daysPanel_;
    QWidget *monthsPanel_;
    QWidget *footer_;
    QGridLayout *daysLayout_;
    std::array<QToolButton *, 42> days_{};
    std::array<QToolButton *, 12> months_{};
    std::array<QDate, 42> visibleDates_{};
    bool compact_ = false;
    bool choosingMonth_ = false;
    int yearCursor_ = 2000;
    int monthCursor_ = 1;
    QDate openingDate_;

    void refreshControls();
    void navigate(int direction);
    void chooseDate(QDate date);
    void chooseMonth(int month);
    bool handleNavigationKey(QKeyEvent *event);
    void updateSizing();
};

// Idempotent installers; they do not change the editor's date/time value,
// display format, minimum/maximum range, or wall-clock time semantics.
void installModernDateEdit(QDateEdit *editor);
void installModernDateTimeEdit(QDateTimeEdit *editor);
void installModernComboBox(QComboBox *editor);
