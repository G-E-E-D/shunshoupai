#pragma once
#include "api.h"
#include <QMainWindow>
#include <QDate>
#include <QJsonArray>
#include <QSettings>
#include <QSet>
#include <QTimeZone>
class QLabel;
class QPushButton;
class QTextEdit;
class QLineEdit;
class QDateEdit;
class QComboBox;
class QVBoxLayout;
class QTimer;
class ModernCalendarWidget;

class MainWindow : public QMainWindow {
    ApiClient api;
    QSettings settings;
    QJsonObject session;
    QJsonArray events;
    QLabel *accountLabel, *modeLabel, *status, *summary;
    QLabel *dateHeading;
    ModernCalendarWidget *miniCalendar;
    QPushButton *accountButton, *modelButton, *phoneButton;
    QTextEdit *composer;
    QLineEdit *search;
    QDateEdit *day;
    QComboBox *view;
    QVBoxLayout *agenda;
    QTimer *poll;
    QSet<QString> reminded;
    bool reminders = false;
    QTimeZone zone{"Asia/Shanghai"};
    QDate currentDay;
    QString owner() const;
    void showError(const ApiResult &result);
    void setSession(const QJsonObject &data);
    void renderEvents();
    void parse();
    void drafts(QJsonArray rows, QString request = {});
    void editEvent(QJsonObject event = {});
    void models();
    void phone();
    void connection();
    void auth();
    void refresh();
    void restorePending();
public:
    explicit MainWindow(const QString &origin);
    void initialize();
    int smokeTest(const QString &screenshot);
    int visualSmoke(const QString &directory);
};
