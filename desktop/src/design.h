#pragma once
#include <QColor>
#include <QIcon>
#include <QString>
class QApplication;
namespace Design {
void apply(QApplication &app);
QString styleSheet();
QIcon icon(const QString &name, const QColor &color = QColor("#444746"));
}
