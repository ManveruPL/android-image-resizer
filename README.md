# Batch Resizer

An offline, on-device Android app for resizing/converting batches of images (JPEG, PNG, WebP,
HEIC, AVIF, GIF, BMP, DNG) with no network access of any kind. See
[`application-plan.md`](application-plan.md) for the full product spec, [`PROGRESS.md`](PROGRESS.md)
for milestone status, and [`DECISIONS.md`](DECISIONS.md) for deviations from the spec and
platform-specific findings.

## Requirements

- JDK 17+ (matches Android Studio's bundled JBR; `java -version` to check)
- Android SDK with `compileSdk`/`targetSdk` 37 installed, plus platform-tools and at least one
  build-tools version
- An emulator or device running API 28+ to actually run the app or its instrumented tests

The project builds from the command line; Android Studio is optional.

## Building

```powershell
.\gradlew.bat assembleDebug       # debug APK, unsigned
.\gradlew.bat assembleRelease     # release APK, R8-minified, signed (see "Release signing" below)
```

## Testing

```powershell
.\gradlew.bat lint test                          # Android lint + JVM unit tests (domain/, work/)
.\gradlew.bat connectedDebugAndroidTest           # instrumented tests; needs a running emulator/device
.\gradlew.bat :app:testDebugUnitTest --tests "pl.fehu.imageresizer.domain.DimensionCalculatorTest"
```

The milestone gate (what must pass before any milestone is considered done) is `lint test`, plus
`connectedDebugAndroidTest` from M2 onward. Run instrumented tests on both a low and a high API
level emulator (this project was developed and verified against API 28 and API 37 AVDs) — some
platform behavior (notably `ImageDecoder` decode paths) differs meaningfully between them; see
`DECISIONS.md` for a concrete example that only reproduced on API 28.

## Release signing

`app/build.gradle.kts` reads signing credentials from `local.properties` (gitignored, never
checked in):

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=release
RELEASE_KEY_PASSWORD=...
```

A local-only keystore already exists at `keystore/release.jks` (gitignored) purely so
`assembleRelease` produces an installable, signed APK for local testing. **It is not a
distribution-ready signing identity** — generate your own key before any real release:

```powershell
keytool -genkeypair -v -keystore keystore/release.jks -alias release `
  -keyalg RSA -keysize 2048 -validity 10000
```

Then update `local.properties` to match. Losing a release key (once an app is actually published)
means you can never publish an update under the same `applicationId` again, so treat the real one
accordingly (password manager or secrets vault, not a plaintext file) once you have one.

## Project structure

```
pl.fehu.imageresizer
├── App.kt, AppContainer.kt, MainActivity.kt   // manual DI container; single activity
├── domain/   pure Kotlin: models, DimensionCalculator, FileNamer, FormatResolver, QualitySearch, ResizeModeValidation
├── engine/   ImageDecoder/Bitmap.compress/ExifInterface: Decoder, Encoder, ImageProcessor, FormatSupport, MetadataCopier
├── storage/  SAF/picker I/O: InputResolver, OutputWriter, UriPermissionManager
├── work/     WorkManager foreground job: JobStore, BatchRunner, ResizeWorker, JobScheduler, JobNotifications
├── data/     DataStore-backed: SettingsRepository, PresetRepository
└── ui/       navigation/, home/, options/, progress/, results/, theme/ (Jetpack Compose + Material 3)
```

Dependency direction: `ui → data/work → engine/storage → domain`. `domain/` has no Android
dependency and is unit-tested on the JVM; everything touching `ImageDecoder`, SAF, WorkManager or
DataStore is instrumented-tested (`app/src/androidTest`).

## Hard rules

See `AGENTS.md` for the full list enforced throughout development (no network access ever, never
modify originals without explicit confirmation, no broad storage permissions, memory-safe decode).
The release manifest has no `INTERNET` permission — verify with:

```powershell
& "$env:ANDROID_HOME\build-tools\<version>\aapt2.exe" dump permissions app\build\outputs\apk\release\app-release.apk
```
