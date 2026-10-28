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
class JavaCaptureBridge;

class MainWindow : public QMainWindow {
    ApiClient api;
    QSettings settings;
    QJsonObject session;
    QJsonArray events;
    QLabel *accountLabel, *modeLabel, *status, *summary;
    QLabel *dateHeading;
    ModernCalendarWidget *miniCalendar;
    QPushButton *accountButton, *modelButton, *phoneButton, *resumeButton;
    QTextEdit *composer;
    QLineEdit *search;
    QDateEdit *day;
    QComboBox *view;
    QVBoxLayout *agenda;
    QTimer *poll;
    QSet<QString> reminded;
    bool reminders = false;
    bool sessionChecked = false;
    QString requestedDeviceName;
    QTimeZone zone{"Asia/Shanghai"};
    QDate currentDay;
    JavaCaptureBridge *javaCapture = nullptr;
    QLabel *captureHint = nullptr;
    QPushButton *captureResumeButton = nullptr;
    QJsonObject javaCaptureContext;
    quint64 javaCaptureContextId = 0, javaCaptureStartEpoch = 0;
    bool javaCaptureAutomatic = false, javaCaptureAttempted = false, javaCaptureReady = false, captureWindowHidden = false;
    QString pendingJavaCommand, captureRequestId, javaCaptureRunId;
    QString javaCaptureContextToken() const;
    QString javaCaptureScope() const;
    void setupJavaCapture(bool automatic);
    void javaCaptureCommand(const QString &type);
    void updateJavaCaptureContext(bool force = false);
    void receiveJavaCapture(const QJsonObject &message);
    QString owner() const;
    void showError(const ApiResult &result);
    void setSession(const QJsonObject &data);
    void renderEvents();
    void parse();
    void drafts(QJsonArray rows, QString request = {});
    void editEvent(QJsonObject event = {});
    bool resolveReplica(const QJsonObject &event);
    void models();
    void phone();
    void connection();
    void devices();
    QString chooseLegacySpace(const QJsonArray &spaces);
    void refresh();
    void restorePending();
    void updateRecoveryButton();
public:
    explicit MainWindow(const QString &origin, bool rememberSession = true, const QByteArray &bootstrapKey = {}, const QString &deviceName = {});
    ~MainWindow() override;
    void initialize();
    int smokeTest(const QString &screenshot);
    int visualSmoke(const QString &directory);
    int javaCaptureSmoke(const QString &directory);
    int peerSmoke(const QString &directory, bool resumed = false);
};
