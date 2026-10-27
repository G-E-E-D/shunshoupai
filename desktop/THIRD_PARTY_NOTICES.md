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

**Node.js 24** and its bundled third-party notices are in `licenses/Node-LICENSE`.
Node.js source: https://github.com/nodejs/node .

The bundled API server contains Next.js, React, and dependencies. Their available
package license files are retained under `resources/backend/node_modules/` and
copied into `licenses/backend/` during packaging. Full application source and
build scripts: https://github.com/G-E-E-D/shunshoupai .

The Microsoft Visual C++ runtime DLLs are redistributed app-locally under the
Microsoft Visual Studio redistribution terms. No system-wide runtime installer
or administrator permission is needed.
