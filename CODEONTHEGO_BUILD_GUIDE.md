# CodeOnTheGo build guide

This project is intentionally kept buildable inside **CodeOnTheGo** on an
Android phone. Verified compatibility profile:

| Setting | Value |
|---|---|
| Gradle wrapper | 8.9 (`gradle/wrapper/gradle-wrapper.properties`) |
| Android Gradle Plugin | 7.x line (root `build.gradle.kts`) |
| Kotlin plugin | 1.9.x line |
| compileSdk / targetSdk | 34 |
| minSdk | 24 |
| Java | 17 |
| UI | XML layouts + Material 3 (no Jetpack Compose) |

## What keeps it compatible

- **No annotation processing / kapt / Compose compiler** — the heaviest parts
  of modern Android builds are absent on purpose.
- Persistence is hand-rolled SQLite (`SQLiteOpenHelper`), so no Room compiler.
- JSON uses the platform `org.json`; HTTP uses `HttpURLConnection`; PDF uses
  Android's `android.graphics.pdf`. Zero extra SDKs for those features.
- Dependencies are plain AndroidX libraries plus zxing (pure JVM/AAR packages).

## Opening the project in CodeOnTheGo

1. Extract the project ZIP so that `settings.gradle.kts` sits at the folder
   root.
2. In CodeOnTheGo, open the extracted folder as a Gradle project.
3. The first build downloads AndroidX/Material/zxing artifacts from Maven —
   keep internet on for that; after caching, builds work offline.
4. Run/debug as usual, or use **assembleDebug** to produce
   `app/build/outputs/apk/debug/app-debug.apk` and install it.

## If a build runs out of memory on the phone

Free as much RAM as possible (close other apps) and retry. If your COTG
version allows editing `gradle.properties`, lower to:

```
org.gradle.jvmargs=-Xmx1536m
```

The project already avoids R8 (`isMinifyEnabled = false`) so release builds
fit in modest memory too.

## Editing code on the phone

- Kotlin sources live in `app/src/main/java/com/hisabbaki/app/`.
- Layouts in `app/src/main/res/layout/`, user-visible text in
  `app/src/main/res/values*/strings_phase*.xml` — `values/` (English),
  `values-bn/` (Bengali), `values-hi/` (Hindi). If you add a UI string, add
  it to all three folders or the app shows the English text.
- Adding a screen: create the Activity and register it in
  `app/src/main/AndroidManifest.xml`, following any existing entry as the
  pattern.

## External services

- The **AI assistant** works fully offline (answers computed from your
  database). Connecting an AI API is optional and configured in the app
  (Settings → AI business assistant → toolbar menu): base URL, model, your
  own API key. Nothing is hardcoded; the key only travels to the URL you
  enter.
- No Firebase, no payment gateway, no backend is required for any feature.
