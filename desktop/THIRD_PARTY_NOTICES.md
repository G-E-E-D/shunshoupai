# Third-party software in the Windows installer

The desktop application uses dynamically linked **Qt 6.8.3** libraries (Core, Gui,
Widgets, Network and their plugins). Qt is available under LGPL-3.0 and GPL-3.0;
this distribution uses the LGPL option. License texts are included in
`licenses/Qt/`. Users may replace the shipped Qt DLLs with compatible builds.
Qt's corresponding source is available at
https://download.qt.io/archive/qt/6.8/6.8.3/single/qt-everywhere-src-6.8.3.tar.xz .
Instructions for building Qt: https://doc.qt.io/qt-6.8/windows-building.html .

The native QR encoder is Project Nayuki's QR Code Generator v1.8.0, under the MIT
license included in `licenses/qrcodegen.hpp`. Its source is in this repository's
`desktop/vendor/` directory.

QR image decoding uses statically linked **ZXing-C++ 3.1.1**, under Apache-2.0.
Its license and upstream notices are included in `licenses/ZXing/`. Only QR
readers are enabled; camera access and barcode writing are not part of this
dependency. The complete upstream source archive is pinned by SHA-256 in
`desktop/CMakeLists.txt`:
https://github.com/zxing-cpp/zxing-cpp/releases/tag/v3.1.1 .
ZXing's bundled libzueci character conversion code is BSD-3-Clause; its
copyright/license and upstream README are also retained in `licenses/ZXing/`.

**Node.js 24** and its bundled third-party notices are in `licenses/Node-LICENSE`.
Node.js source: https://github.com/nodejs/node .

The bundled API server contains Next.js, React, and dependencies. Their available
package license files are retained under `resources/backend/node_modules/` and
copied into `licenses/backend/` during packaging. Full application source and
build scripts: https://github.com/G-E-E-D/shunshoupai .

The Microsoft Visual C++ runtime DLLs are redistributed app-locally under the
Microsoft Visual Studio redistribution terms. No system-wide runtime installer
or administrator permission is needed.

The Java capture helper is bundled under `capture-java/`, including Eclipse
Temurin, Tess4J/Tesseract, Leptonica, JNA, and Chinese/English OCR models. Its
complete notices and license locations are documented in
`capture-java/THIRD_PARTY_NOTICES.md`; source and pinned build inputs are in the
repository's `desktop-java/` directory.
