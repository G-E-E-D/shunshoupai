#include "design.h"
#include <QApplication>
#include <QFont>
#include <QPainter>
#include <QPainterPath>
#include <QPalette>
#include <QPixmap>

QIcon Design::icon(const QString &name, const QColor &color) {
    QPixmap bitmap(48, 48);
    bitmap.fill(Qt::transparent);
    QPainter p(&bitmap);
    p.setRenderHint(QPainter::Antialiasing);
    p.scale(2, 2);
    p.setPen(QPen(color, 1.7, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
    p.setBrush(Qt::NoBrush);
    if (name == "calendar") {
        p.drawRoundedRect(QRectF(3, 5, 18, 16), 3, 3);
        p.drawLine(QPointF(3, 10), QPointF(21, 10));
        p.drawLine(QPointF(8, 3), QPointF(8, 7)); p.drawLine(QPointF(16, 3), QPointF(16, 7));
        p.setPen(Qt::NoPen); p.setBrush(color);
        for (int row = 0; row < 2; ++row) for (int col = 0; col < 3; ++col) p.drawEllipse(QPointF(7 + col * 5, 14 + row * 4), .8, .8);
    } else if (name == "plus") {
        p.drawLine(QPointF(5, 12), QPointF(19, 12)); p.drawLine(QPointF(12, 5), QPointF(12, 19));
    } else if (name == "previous" || name == "next") {
        QPainterPath path;
        path.moveTo(name == "previous" ? 14 : 10, 6); path.lineTo(name == "previous" ? 8 : 16, 12); path.lineTo(name == "previous" ? 14 : 10, 18); p.drawPath(path);
    } else if (name == "search") {
        p.drawEllipse(QRectF(4, 4, 12, 12)); p.drawLine(QPointF(14, 14), QPointF(20, 20));
    } else if (name == "refresh") {
        p.drawArc(QRectF(4, 4, 16, 16), 35 * 16, 290 * 16); p.drawLine(QPointF(20, 4), QPointF(20, 10)); p.drawLine(QPointF(20, 10), QPointF(14, 10));
    } else if (name == "settings") {
        for (int i = 0; i < 3; ++i) { p.drawLine(QPointF(4, 6 + 6 * i), QPointF(20, 6 + 6 * i)); p.setBrush(Qt::white); p.drawEllipse(QPointF(i == 1 ? 9 : 15, 6 + 6 * i), 2, 2); }
    } else if (name == "person") {
        p.drawEllipse(QRectF(8, 3, 8, 8)); p.drawArc(QRectF(4, 13, 16, 14), 0, 180 * 16);
    } else if (name == "desktop") {
        p.drawRoundedRect(QRectF(3, 4, 18, 13), 2, 2);
        p.drawLine(QPointF(12, 17), QPointF(12, 21)); p.drawLine(QPointF(8, 21), QPointF(16, 21));
    } else if (name == "qr") {
        for (const QPointF at : {QPointF(3, 3), QPointF(14, 3), QPointF(3, 14)}) { p.drawRoundedRect(QRectF(at, QSizeF(7, 7)), 1, 1); p.drawPoint(at + QPointF(3.5, 3.5)); }
        p.drawLine(QPointF(15, 15), QPointF(20, 15)); p.drawLine(QPointF(15, 15), QPointF(15, 20)); p.drawPoint(QPointF(20, 20));
    } else if (name == "edit") {
        QPainterPath path; path.moveTo(5, 15); path.lineTo(16, 4); path.lineTo(20, 8); path.lineTo(9, 19); path.lineTo(4, 20); path.closeSubpath(); p.drawPath(path); p.drawLine(QPointF(13, 7), QPointF(17, 11));
    } else if (name == "delete") {
        p.drawLine(QPointF(4, 6), QPointF(20, 6)); p.drawRoundedRect(QRectF(6, 6, 12, 15), 2, 2); p.drawLine(QPointF(9, 3), QPointF(15, 3)); p.drawLine(QPointF(10, 10), QPointF(10, 17)); p.drawLine(QPointF(14, 10), QPointF(14, 17));
    } else if (name == "check") {
        QPainterPath path; path.moveTo(5, 12); path.lineTo(10, 17); path.lineTo(20, 6); p.drawPath(path);
    } else if (name == "sparkle") {
        QPainterPath path; path.moveTo(12, 3); path.lineTo(14.5, 9.5); path.lineTo(21, 12); path.lineTo(14.5, 14.5); path.lineTo(12, 21); path.lineTo(9.5, 14.5); path.lineTo(3, 12); path.lineTo(9.5, 9.5); path.closeSubpath(); p.drawPath(path);
    }
    p.end();
    return QIcon(bitmap);
}

QString Design::styleSheet() {
    return QString::fromUtf8(R"(
        QWidget {color:#202124;font-family:'Microsoft YaHei UI';font-size:14px;selection-background-color:#d3e3fd;selection-color:#041e49;}
        QMainWindow {background:#f8fafd;}
        QWidget#appRoot,QWidget#sidebarViewport,QWidget#sidebarContent {background:#f8fafd;}
        QFrame#appBar {background:#f8fafd;border:0;}
        QLabel#brand {font-size:22px;font-weight:600;color:#3c4043;}
        QLabel#pageTitle {font-size:26px;font-weight:500;color:#202124;}
        QLabel#sectionTitle {font-size:18px;font-weight:600;}
        QLabel#title {font-size:23px;font-weight:600;}
        QLabel#muted,QLabel#fieldHint,QLabel#dialogSubtitle {color:#5f6368;line-height:1.5;}
        QLabel#fieldHint {font-size:12px;}
        QLabel#fieldLabel {font-size:13px;font-weight:500;color:#3c4043;}
        QLabel#eyebrow,QLabel#dialogEyebrow {color:#0b57d0;font-size:12px;font-weight:600;}
        QLabel#modeChip {background:#e8f0fe;color:#174ea6;border-radius:12px;padding:5px 10px;font-size:12px;}
        QLabel#status {font-size:12px;color:#5f6368;background:transparent;padding:5px 12px;}
        QLabel#avatar {background:#d3e3fd;color:#0b57d0;border-radius:18px;font-size:16px;font-weight:600;}
        QFrame#calendarSurface {background:white;border:1px solid #e8edf3;border-radius:24px;}
        QFrame#sidebarSection {background:transparent;border:0;}
        QFrame#sidebarNote {background:#edf2fa;border:0;border-radius:16px;}
        QFrame#composerCard {background:#f8fafd;border:0;border-radius:18px;}
        QFrame#tonalCard,QFrame#qrCard {background:#f8fafd;border:0;border-radius:16px;}
        QLabel#accountPill {background:#e8f0fe;color:#174ea6;border-radius:14px;padding:8px 12px;}
        QWidget#dialogViewport,QWidget#dialogContent {background:white;}
        QScrollArea#dialogScroll {background:transparent;border:0;}
        QFrame#eventCard {background:#e8f0fe;border:0;border-radius:14px;}
        QFrame#eventCard[completed="true"] {background:#f1f3f4;}
        QLabel#eventTitle {font-size:16px;font-weight:600;color:#174ea6;}
        QLabel#eventMeta {font-size:12px;color:#5f6368;}
        QLabel#eventTime {font-size:14px;font-weight:500;color:#3c4043;}
        QLabel#eventDate {font-size:11px;color:#5f6368;}
        QLabel#dayGroup {font-size:14px;font-weight:600;color:#3c4043;padding:14px 0 8px 0;}
        QLabel#emptyTitle {font-size:20px;font-weight:500;color:#3c4043;}
        QLabel#emptyCopy {font-size:14px;color:#5f6368;}
        QLabel#emptyIcon {background:#e8f0fe;border-radius:36px;}
        QPushButton {background:white;border:1px solid #dadce0;border-radius:20px;padding:9px 17px;min-height:20px;font-weight:500;color:#3c4043;}
        QPushButton:hover {background:#f1f5fb;border-color:#c4c7c5;}
        QPushButton:pressed {background:#dfe7f4;}
        QPushButton:focus {border:1px solid #0b57d0;}
        QPushButton:disabled {background:#f1f3f4;border-color:transparent;color:#9aa0a6;}
        QPushButton#primary {background:#0b57d0;border:1px solid #0b57d0;color:white;border-radius:20px;}
        QPushButton#primary:hover {background:#084bb5;border-color:#084bb5;}
        QPushButton#primary:pressed {background:#0842a0;}
        QPushButton#primary:disabled {background:#e0e4eb;border-color:#e0e4eb;color:#8a929e;}
        QPushButton#tonalButton {background:#d3e3fd;border-color:transparent;color:#041e49;}
        QPushButton#tonalButton:hover {background:#c2d7f7;}
        QPushButton#textButton {border:1px solid transparent;background:transparent;color:#0b57d0;padding:8px 10px;}
        QPushButton#textButton:hover {background:#e8f0fe;}
        QPushButton#destructiveButton {border:1px solid transparent;background:transparent;color:#b3261e;}
        QPushButton#destructiveButton:hover {background:#fce8e6;}
        QPushButton#iconButton {border:0;background:transparent;border-radius:18px;padding:9px;min-width:20px;max-width:20px;min-height:20px;}
        QPushButton#iconButton:hover {background:#e8edf5;}
        QPushButton#segmentButton {border:1px solid transparent;background:transparent;border-radius:17px;color:#5f6368;padding:7px 17px;}
        QPushButton#segmentButton:checked {background:white;color:#0b57d0;border-color:#e0e7f2;}
        QFrame#segmentedBar {background:#edf2fa;border-radius:21px;border:0;}
        QLineEdit,QTextEdit,QDateEdit,QDateTimeEdit,QComboBox {background:#fff;border:1px solid #c4c7c5;border-radius:10px;padding:10px 12px;min-height:20px;color:#202124;}
        QLineEdit:hover,QTextEdit:hover,QDateEdit:hover,QDateTimeEdit:hover,QComboBox:hover {border-color:#747775;}
        QLineEdit:focus,QTextEdit:focus,QDateEdit:focus,QDateTimeEdit:focus,QComboBox:focus {border:2px solid #0b57d0;padding:9px 11px;}
        QTextEdit#composer {background:white;border-color:#dde4ed;border-radius:16px;padding:13px;font-size:14px;}
        QLineEdit#search {background:#edf2fa;border:1px solid transparent;border-radius:20px;padding-left:16px;}
        QLineEdit#search:focus {background:white;border-color:#0b57d0;}
        QDateEdit::drop-down,QDateTimeEdit::drop-down,QComboBox::drop-down {border:0;width:28px;subcontrol-origin:padding;subcontrol-position:top right;border-top-right-radius:10px;border-bottom-right-radius:10px;}
        QDateEdit::down-arrow,QDateTimeEdit::down-arrow,QComboBox::down-arrow {width:0;height:0;border-left:4px solid transparent;border-right:4px solid transparent;border-top:5px solid #5f6368;}
        QComboBox QAbstractItemView {border:1px solid #e3e7ed;background:white;selection-background-color:#e8f0fe;selection-color:#174ea6;outline:0;padding:8px;}
        QAbstractItemView::item {padding:8px;min-height:24px;}
        QCheckBox {spacing:10px;color:#5f6368;}
        QCheckBox::indicator {width:18px;height:18px;border:1px solid #747775;border-radius:4px;background:white;}
        QCheckBox::indicator:checked {background:#0b57d0;border-color:#0b57d0;}
        QScrollArea {background:transparent;border:0;}
        QWidget#agendaViewport,QWidget#agendaContent {background:white;}
        QScrollBar:vertical {background:transparent;width:7px;margin:2px;border-radius:3px;}
        QScrollBar::handle:vertical {background:#d7dce2;min-height:32px;border-radius:3px;}
        QScrollBar::handle:vertical:hover {background:#b8c1ce;}
        QScrollBar::add-line:vertical,QScrollBar::sub-line:vertical {height:0;}
        QScrollBar::add-page:vertical,QScrollBar::sub-page:vertical {background:transparent;}
        QScrollBar:horizontal {background:transparent;height:7px;}
        QScrollBar::handle:horizontal {background:#d7dce2;min-width:32px;border-radius:3px;}
        QScrollBar::add-line:horizontal,QScrollBar::sub-line:horizontal {width:0;}
        QSplitter::handle {background:#f8fafd;}
        QDialog[modern="true"] {background:transparent;}
        QFrame#dialogSurface {background:white;border:1px solid #e6eaf0;border-radius:24px;}
        QLabel#dialogTitle {font-size:25px;font-weight:600;color:#202124;}
        QLabel#inlineMessage {font-size:12px;color:#5f6368;}
        QLabel#errorMessage {font-size:13px;color:#b3261e;background:#fce8e6;padding:10px 12px;border-radius:10px;}
        QToolButton#dialogClose {background:transparent;border:0;border-radius:20px;color:#5f6368;font-size:22px;width:40px;height:40px;}
        QToolButton#dialogClose:hover {background:#f1f3f4;}
        QTableWidget {background:white;border:1px solid #e5eaf1;border-radius:12px;gridline-color:#edf1f7;selection-background-color:#e8f0fe;selection-color:#202124;}
        QHeaderView::section {background:#f8fafd;border:0;border-bottom:1px solid #e5eaf1;padding:13px 10px;color:#5f6368;font-size:12px;font-weight:500;}
        QTableWidget::item {padding:8px;}
        QDialogButtonBox QPushButton {min-width:80px;}
        QMessageBox {background:#fff;}
        QToolTip {background:#303134;color:white;border:0;border-radius:5px;padding:6px 9px;font-size:12px;}
    )");
}

void Design::apply(QApplication &app) {
    app.setStyle("Fusion");
    QFont font("Microsoft YaHei UI", 10);
    font.setStyleStrategy(QFont::PreferAntialias);
    app.setFont(font);
    QPalette palette;
    palette.setColor(QPalette::Window, QColor("#f8fafd"));
    palette.setColor(QPalette::Base, Qt::white);
    palette.setColor(QPalette::AlternateBase, QColor("#f8fafd"));
    palette.setColor(QPalette::WindowText, QColor("#202124"));
    palette.setColor(QPalette::Text, QColor("#202124"));
    palette.setColor(QPalette::Button, Qt::white);
    palette.setColor(QPalette::ButtonText, QColor("#3c4043"));
    palette.setColor(QPalette::Highlight, QColor("#d3e3fd"));
    palette.setColor(QPalette::HighlightedText, QColor("#041e49"));
    palette.setColor(QPalette::Disabled, QPalette::Text, QColor("#9aa0a6"));
    palette.setColor(QPalette::Disabled, QPalette::ButtonText, QColor("#9aa0a6"));
    app.setPalette(palette);
    app.setStyleSheet(styleSheet());
    app.setWindowIcon(icon("calendar", QColor("#1a73e8")));
}
