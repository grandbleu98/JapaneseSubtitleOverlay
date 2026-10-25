# Japanese Subtitle Overlay (prototype 0.2.0)

Android/Kotlin prototype that captures visible screen frames, OCRs text, translates recognized Korean or English text into Japanese, and displays the translation in a floating overlay.

## Status

Experimental source only; not yet device-tested. OCR and overlay positioning are approximate, and some DRM-protected content may block capture. The app requires explicit screen-capture permission and may capture private on-screen content. Captured frames are processed on-device.

## Build

GitHub Actions builds a debug APK on pushes to `main` or `master`. Open **Actions → Android debug APK** and download the `JapaneseSubtitleOverlay-debug-apk` artifact after a successful run. This is a debug APK, not a Play Store release build.

Requirements for a local build: Android SDK 35, JDK 17, and Gradle 8.9.
