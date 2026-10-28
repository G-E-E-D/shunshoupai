#include "qr_decoder.h"
#include "qrcodegen.hpp"
#include <QCoreApplication>
#include <QPainter>
#include <QTemporaryDir>
#include <QTransform>
#include <iostream>
#include <stdexcept>

namespace {
void require(bool condition, const char *message) { if (!condition) throw std::runtime_error(message); }
QImage codeImage(const QString &text, bool transparent = false) {
    const auto qr = qrcodegen::QrCode::encodeText(text.toUtf8().constData(), qrcodegen::QrCode::Ecc::MEDIUM);
    constexpr int border = 4, scale = 6;
    const int side = (qr.getSize() + 2 * border) * scale;
    QImage result(side, side, QImage::Format_ARGB32);
    result.fill(transparent ? Qt::transparent : Qt::white);
    QPainter painter(&result);
    painter.setPen(Qt::NoPen); painter.setBrush(Qt::black);
    for (int y = 0; y < qr.getSize(); ++y) for (int x = 0; x < qr.getSize(); ++x)
        if (qr.getModule(x, y)) painter.drawRect((x + border) * scale, (y + border) * scale, scale, scale);
    return result;
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    try {
        const QString token(43, 'A');
        const QString url = "http://192.168.1.20:43127/#pair=" + token;
        QString error;
        const auto plain = codeImage(url);
        require(decodePairingQr(plain, &error) == url && error.isEmpty(), "generated pairing QR did not decode");
        require(decodePairingQr(plain.transformed(QTransform().rotate(90)), &error) == url, "rotated QR did not decode");
        require(decodePairingQr(codeImage(url, true), &error) == url, "transparent screenshot did not decode");
        auto inverted = plain; inverted.invertPixels();
        require(decodePairingQr(inverted, &error) == url, "inverted QR did not decode");
        QTemporaryDir directory;
        require(directory.isValid(), "temporary directory unavailable");
        const auto path = directory.filePath(QString::fromUtf8("配对截图.png"));
        require(plain.save(path) && readPairingQrImage(path, &error) == url, "selected PNG file did not decode");
        require(decodePairingQr(codeImage("https://example.com"), &error).isEmpty() && !error.isEmpty(), "unrelated QR was accepted as a device grant");
        QImage blank(320, 320, QImage::Format_RGB32); blank.fill(Qt::white);
        require(decodePairingQr(blank, &error).isEmpty(), "blank image produced a pairing link");
        require(decodePairingQr(QImage(), &error).isEmpty(), "empty image accepted");
        const auto second = codeImage("http://192.168.1.21:43127/#pair=" + QString(43, 'B'));
        QImage multiple(plain.width() + second.width() + 40, qMax(plain.height(), second.height()), QImage::Format_RGB32);
        multiple.fill(Qt::white);
        { QPainter painter(&multiple); painter.drawImage(0, 0, plain); painter.drawImage(plain.width() + 40, 0, second); }
        require(decodePairingQr(multiple, &error).isEmpty() && error.contains(QString::fromUtf8("多个")), "ambiguous QR image silently selected a device");
        std::cout << "PASS: selected PNG, rotation, transparency and inversion decode locally; invalid/blank/multiple-device images rejected\n";
        return 0;
    } catch (const std::exception &error) { std::cerr << error.what() << '\n'; return 1; }
}
