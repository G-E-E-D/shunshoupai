#pragma once
#include "draft_state.h"
#include <QJsonDocument>
#include <QSettings>
#include <QUuid>

// The single composer envelope is authoritative after an OCR handoff. Its last
// receipt is journalled with the text; a separate per-id receipt keeps retries
// safe without rewriting an ever-growing receipt list on every keystroke.
namespace JavaCaptureState {
inline QString scope(const QString &origin, const QString &owner) {
    return QString::fromLatin1(QCryptographicHash::hash((origin + "\n" + owner).toUtf8(), QCryptographicHash::Sha256).toHex());
}
inline QString key(const QString &scope) { return "java-capture-composer/" + scope; }
inline QString receiptKey(const QString &scope, const QString &id) { return "java-capture-receipts/" + scope + "/" + id; }
inline QJsonObject state(const QSettings &settings, const QString &scope) { return DraftState::read(settings, key(scope)); }
inline QString text(const QSettings &settings, const QString &scope, const QString &fallback) {
    const auto value = state(settings, scope); return value.value("composer").isString() ? value.value("composer").toString() : fallback;
}
inline void edit(QSettings &settings, const QString &scope, const QString &text) {
    auto value = state(settings, scope); value.insert("composer", text);
    settings.setValue(key(scope), QString::fromUtf8(QJsonDocument(value).toJson(QJsonDocument::Compact)));
}
inline bool validId(const QString &id) { return !QUuid(id).isNull() && QUuid(id).toString(QUuid::WithoutBraces) == id; }
inline QString hash(const QJsonObject &message) {
    QJsonObject value; for (const auto &key : {"scope", "context_id", "id", "text", "mode"}) value.insert(key, message.value(key));
    return QString::fromLatin1(QCryptographicHash::hash(QJsonDocument(value).toJson(QJsonDocument::Compact), QCryptographicHash::Sha256).toHex());
}
inline QString receipt(const QSettings &settings, const QString &scope, const QString &id) {
    const auto durable = settings.value(receiptKey(scope, id)).toString(); if (!durable.isEmpty()) return durable;
    const auto value = state(settings, scope); return value.value("last_id").toString() == id ? value.value("last_hash").toString() : QString{};
}
inline bool flushLast(QSettings &settings, const QString &scope) {
    const auto value = state(settings, scope); const auto id = value.value("last_id").toString();
    if (!id.isEmpty()) {
        if (!validId(id) || value.value("last_hash").toString().size() != 64) return false;
        const auto digest = value.value("last_hash").toString(), previous = settings.value(receiptKey(scope, id)).toString();
        if (!previous.isEmpty() && previous != digest) return false;
        settings.setValue(receiptKey(scope, id), digest);
    }
    settings.sync(); return settings.status() == QSettings::NoError;
}
inline bool accept(QSettings &settings, const QString &scope, const QString &id, const QString &digest,
                   const QString &combined, const QString &legacyComposerKey) {
    if (!validId(id) || combined.size() > 2000 || !flushLast(settings, scope)) return false;
    QJsonObject value{{"composer", combined}, {"last_id", id}, {"last_hash", digest}};
    DraftState::write(settings, key(scope), value);
    if (settings.status() != QSettings::NoError) return false;
    settings.setValue(legacyComposerKey, combined);
    return flushLast(settings, scope);
}
}
