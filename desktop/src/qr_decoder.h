#pragma once
#include <QImage>
#include <QString>

// Only returns a validated device-pairing URL. Images never leave this process.
QString decodePairingQr(const QImage &image, QString *error = nullptr);
QString readPairingQrImage(const QString &path, QString *error = nullptr);
