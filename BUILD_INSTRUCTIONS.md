# Build instructions

## Requirements

- JDK 17
- Android SDK with platform 34 (compileSdk 34, minSdk 24, targetSdk 34)
- Gradle 8.9 (wrapper included — `gradlew` downloads it automatically on a PC;
  CodeOnTheGo ships its own Gradle, see CODEONTHEGO_BUILD_GUIDE.md)

Toolchain pins (do not upgrade casually — they are chosen for CodeOnTheGo):

- Gradle wrapper: 8.9
- Android Gradle Plugin / Kotlin: see the root `build.gradle.kts`
- compileSdk 34, minSdk 24, targetSdk 34
- Dependencies: AndroidX core/appcompat/recyclerview/constraintlayout, Material 3,
  splashscreen, biometric 1.1.0, zxing core 3.5.3 + zxing-android-embedded 4.3.0.
  No Compose, no kapt, no annotation processors.

## Build on a PC (Android Studio or command line)

```bash
./gradlew assembleDebug          # debug APK  -> app/build/outputs/apk/debug/
./gradlew assembleRelease        # release APK (unsigned)
```

In Android Studio: open the project folder, wait for Gradle sync, then
Build ▸ Generate Signed Bundle / APK.

## Signed release (to install over the existing app)

Use the same keystore every time; Android refuses an update when the signing
key changes, which is how your existing data is protected against accidental
loss.

```bash
keytool -genkeypair -v -keystore hisabbaki.keystore -alias hisabbaki \
  -keyalg RSA -keysize 2048 -validity 10000

apksigner sign --ks hisabbaki.keystore \
  --out Hishabbaki-v2.0-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

Install the signed APK over the existing Hishabbaki installation. The database
migrates in place (version 1 → 2); **no user data is deleted by the update**.

## Verify a build

1. `./gradlew assembleDebug assembleRelease` must finish with `BUILD SUCCESSFUL`
   (verified for v2.0: debug + release).
2. Install the debug APK on a phone, create a ledger entry, an invoice, a
   product, an expense and a backup; then check Reports numbers match.

## Project layout

```
app/src/main/java/com/hisabbaki/app/
  data/        SQLite layer (DatabaseHelper + one repo class per domain)
  util/        Money, PdfExport, CsvExport, UpiQr, ReportBuilder, BackupManager…
  *.kt         Activities and fragments (XML layouts, findViewById, Material 3)
app/src/main/res/
  values/ values-bn/ values-hi/   strings_phase1..5 (all three kept in parity)
```
