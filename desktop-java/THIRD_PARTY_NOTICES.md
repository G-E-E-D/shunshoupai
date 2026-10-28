# Java capture module: third-party components

The application source is Java. Its Windows OCR and integration dependencies include native libraries; it is not a pure-JVM implementation.

| Component | Pinned version/source | License and distribution notes |
| --- | --- | --- |
| Tess4J | 5.20.0, Maven Central `net.sourceforge.tess4j:tess4j` | Apache-2.0. Wraps the bundled Tesseract native OCR engine. [Project](https://github.com/nguyenq/tess4j/tree/tess4j-5.20.0) |
| Tesseract | 5.5.3, supplied by Tess4J | Apache-2.0. Windows library name `libtesseract553`. [Project](https://github.com/tesseract-ocr/tesseract) |
| Lept4J / Leptonica | Lept4J 1.24.0, Maven Central | Lept4J Apache-2.0; Leptonica BSD-2-Clause. Native image processing dependency. [Lept4J](https://github.com/nguyenq/lept4j), [Leptonica](https://github.com/DanBloomberg/leptonica) |
| JNA and JNA Platform | 5.19.1, Maven Central | Apache-2.0 or LGPL-2.1-or-later dual license; distributed under the Apache-2.0 option. [Project](https://github.com/java-native-access/jna) |
| Gson | 2.13.2, Maven Central | Apache-2.0. [Project](https://github.com/google/gson) |
| SLF4J | 2.0.18, Maven Central | MIT. The no-op provider avoids library messages on the application JSONL protocol. [Project](https://www.slf4j.org/) |
| OCR language data | `tesseract-ocr/tessdata_fast` commit `87416418657359cb625c412a48b6e1d6d41c29bd`, `chi_sim` and `eng` | Apache-2.0; exact SHA-256 values are in `scripts/models.lock.json`. [Source](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd) |
| Maven | 3.9.11, Apache Maven Central binary distribution | Apache-2.0. Build tool only; not shipped with the application. The published SHA-512 is pinned in `scripts/common.ps1`. [Source](https://maven.apache.org/) |
| Eclipse Temurin 21 | 21.0.12.1+1 x64 JRE; official release URL and SHA-256 in `scripts/runtime.lock.json` | GPL-2.0 with Classpath Exception. Preserve the runtime's `legal` directory. The build JDK is not redistributed. [License information](https://adoptium.net/docs/faq/) |
| JUnit Jupiter | 5.11.4, Maven Central | EPL-2.0; tests only, not shipped. [Project](https://junit.org/junit5/) |

The complete Maven dependency closure is recorded in `scripts/maven-dependencies.lock.json`. Distribution keeps the runtime JARs unchanged, including their embedded `META-INF/LICENSE` and `META-INF/NOTICE` files. Tess4J also brings image-format dependencies such as Apache PDFBox, Commons IO, JAI ImageIO, JBIG2 ImageIO and JBoss VFS; their original license/notice files remain inside those JARs.

Tess4J's Windows native DLLs require Microsoft Visual C++ v14 runtime support. The pinned Temurin runtime includes `msvcp140.dll`, `vcruntime140.dll` and `vcruntime140_1.dll` beside the worker's `javaw.exe`; preserve these vendor-supplied runtime files and notices. A clean-machine release test must still check the complete native dependency closure; the application shows a recoverable OCR-unavailable message if loading a native DLL fails. The scripts do not install a system runtime automatically. See the [pinned Tess4J dependency declaration](https://github.com/nguyenq/tess4j/blob/tess4j-5.20.0/README.md).

OCR runs locally. The native DLL cache that Tess4J may create under the user's temporary directory contains library binaries, not captured images. Screen images must remain in memory and the OCR worker pipe.
