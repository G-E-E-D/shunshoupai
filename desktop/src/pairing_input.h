#pragma once
#include "qr_decoder.h"
#include <QLineEdit>

// The file-picker and clipboard paths share the same validated image import.
class PairingAddressInput final : public QLineEdit {
public:
    using QLineEdit::QLineEdit;
    bool loadQrImage(const QImage &image, QString *error = nullptr) {
        const auto value = decodePairingQr(image, error);
        if (value.isEmpty()) return false;
        setText(value); setCursorPosition(0); return true;
    }
    bool loadQrFile(const QString &path, QString *error = nullptr) {
        const auto value = readPairingQrImage(path, error);
        if (value.isEmpty()) return false;
        setText(value); setCursorPosition(0); return true;
    }
};
