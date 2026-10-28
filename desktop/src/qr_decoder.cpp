#include "qr_decoder.h"
#include "connection.h"
#include <QImageReader>
#include <QPainter>
#include <QSet>
#include <ReadBarcode.h>
#include <stdexcept>

namespace {
QString failed(QString *error, const char *message) {
    if (error) *error = QString::fromUtf8(message);
    return {};
}
bool reasonableSize(const QSize &size) {
    return size.width() > 0 && size.height() > 0 && qint64(size.width()) * size.height() <= 16'777'216;
}
}

QString decodePairingQr(const QImage &source, QString *error) {
    if (error) error->clear();
    if (source.isNull()) return failed(error, "图片无法读取，请选择二维码截图或粘贴配对链接。");
    if (!reasonableSize(source.size())) return failed(error, "图片过大，请裁剪到二维码附近后重试。");
    // Flatten transparent screenshots onto white before converting to luma.
    const auto size = source.size().scaled(QSize(4096, 4096), Qt::KeepAspectRatio);
    const auto bounded = source.width() > 4096 || source.height() > 4096 ? size : source.size();
    QImage image(bounded, QImage::Format_RGB32);
    image.fill(Qt::white);
    { QPainter painter(&image); painter.drawImage(image.rect(), source); }
    image = image.convertToFormat(QImage::Format_Grayscale8);
    try {
        const ZXing::ImageView pixels(image.constBits(), image.width(), image.height(), ZXing::ImageFormat::Lum, image.bytesPerLine());
        const auto options = ZXing::ReaderOptions().formats(ZXing::BarcodeFormat::QRCode).tryHarder(true).tryRotate(true).tryInvert(true).maxNumberOfSymbols(4);
        QSet<QString> links;
        for (const auto &code : ZXing::ReadBarcodes(pixels, options)) {
            if (!code.isValid()) continue;
            try {
                const auto parsed = shunshoupai::parseConnection(code.text(), true);
                if (!parsed.pairingToken.empty()) links.insert(QString::fromStdString(parsed.origin + "#pair=" + parsed.pairingToken));
            } catch (const std::invalid_argument &) { }
        }
        if (links.size() > 1) return failed(error, "图片里有多个配对码，请只保留要连接的那台设备。");
        if (links.isEmpty()) return failed(error, "没有识别到顺手排配对码，请换一张清晰截图，或直接粘贴配对链接。");
        return *links.constBegin();
    } catch (const std::exception &) {
        return failed(error, "二维码识别失败，请换一张清晰截图或粘贴配对链接。");
    }
}

QString readPairingQrImage(const QString &path, QString *error) {
    QImageReader reader(path);
    reader.setAutoTransform(true);
    if (!reasonableSize(reader.size())) return failed(error, "图片无法读取或尺寸过大，请选择二维码截图。");
    return decodePairingQr(reader.read(), error);
}
