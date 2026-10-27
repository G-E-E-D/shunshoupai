#pragma once
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSettings>

namespace DraftState {
inline QString key(const QString &kind, const QString &origin, const QString &owner) {
    // Preserve the existing pending-key format so upgrades can recover an
    // already-submitted request with its original idempotency identifier.
    return kind + "/" + QString::fromLatin1(QCryptographicHash::hash((origin + owner).toUtf8(), QCryptographicHash::Sha256).toHex());
}
inline QJsonObject read(const QSettings &settings, const QString &key) {
    return QJsonDocument::fromJson(settings.value(key).toString().toUtf8()).object();
}
inline void write(QSettings &settings, const QString &key, const QJsonObject &value) {
    settings.setValue(key, QString::fromUtf8(QJsonDocument(value).toJson(QJsonDocument::Compact)));
    settings.sync();
}
}
