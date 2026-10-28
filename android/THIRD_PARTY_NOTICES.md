# Android third-party components

The original application code is covered by the repository's MIT license.
Third-party libraries and models retain their own terms.

## On-device text recognition

The APK bundles `com.google.mlkit:text-recognition-chinese:16.0.1` and its
transitive SDK dependencies, including
`com.google.mlkit:text-recognition-bundled-common:17.0.0`. The Chinese recognizer
also includes Latin-script recognition. Models and precompiled native libraries
ship with the APK; the app does not download a model before first recognition.

ML Kit and its models are governed by the
[ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) and the
[Google APIs Terms of Service](https://developers.google.com/terms), rather than
this application's MIT license. The Apache license shown for Google's sample
code does not relicense the SDK or its models.

According to the ML Kit privacy statement, input images, text and recognition
outputs are processed on the device and are not sent to Google. The SDK may
contact Google for updates and compatibility information, and sends performance
and usage metrics. This is also disclosed in the screen-capture feature's
introduction. See the linked terms for the current details.

## Android UI and QR scanning

The app uses [AndroidX](https://android.googlesource.com/platform/frameworks/support/)
and [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded),
including [ZXing](https://github.com/zxing/zxing). Their source and license notices
are available in those upstream repositories; exact dependency declarations are
in `app/build.gradle`.

No screen-capture permission, account credential or API key is included in the
application package.
