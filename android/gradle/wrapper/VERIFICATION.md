# Gradle wrapper verification

The wrapper scripts and JAR are copied from the official Gradle `v8.13.0` tag.

- Distribution: `https://services.gradle.org/distributions/gradle-8.13-bin.zip`
- Distribution SHA-256: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`
- Wrapper JAR SHA-256: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`

Both hashes were compared against the official `gradle-8.13-bin.zip.sha256` and
`gradle-8.13-wrapper.jar.sha256` endpoints on 2026-10-27. The distribution checksum
is enforced by `gradle-wrapper.properties`; the CI also checks the wrapper JAR.

Compatibility source: [Android Gradle Plugin 8.13 release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
AGP 8.13.2 requires Gradle 8.13, JDK 17, and supports API level 36.1. This project
uses Java 17, compile/target SDK 36 and Build Tools 35.0.0.
