# AGENTS.md — Batch Image Resizer (Android)

Instructions for any coding agent working in this repository. The full product spec is
[`application-plan.md`](application-plan.md); this file is the operating manual for implementing it.
When the two disagree, the spec wins, except for the **project facts** below, which reflect the real repo.

## Project facts (override the spec's placeholders)

| Item | Value |
|---|---|
| Package / namespace / applicationId | `pl.fehu.imageresizer` (spec's `com.example.batchresizer` is a placeholder — **do not use it**) |
| Root project name | `Image Resizer`, single module `:app` |
| App display name | "Batch Resizer" (`app_name` in `strings.xml`) |
| Build | AGP 9.4.1 (built-in Kotlin support — no separate `kotlin-android` plugin), Gradle 9.6 wrapper, Kotlin DSL |
| SDK | `minSdk 28`, `compileSdk`/`targetSdk 37` |
| Dependencies | Version catalog only: `gradle/libs.versions.toml`. No inline versions in `build.gradle.kts`. |
| Configuration cache | Enabled (`gradle.properties`) — keep build logic compatible with it |
| Host OS | Windows; use `.\gradlew.bat` (or `./gradlew` from Git Bash) |

Starting state: an Android Studio template with no activity, no Compose and View-based `appcompat`/`material`
dependencies. M0 replaces it with a Compose setup and removes template leftovers
(`ExampleUnitTest`, `ExampleInstrumentedTest`, unused View dependencies) when they are no longer needed.

## Hard rules (never break these)

1. **No network.** Never add `android.permission.INTERNET`; the manifest must contain
   `<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />`.
   No networking, analytics, ads or crash-reporting SDKs. Check the merged manifest after every milestone.
2. **Never modify originals**, except when the policy is OVERWRITE *and* output folder = source folder *and* the user confirmed it.
3. **No broad storage permissions**: no `READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES` or `MANAGE_EXTERNAL_STORAGE`. All file access goes through the photo picker or SAF.
4. **Only the dependencies listed in spec §2.** Anything else needs an entry in `DECISIONS.md` before it is added.
5. **`domain/` is pure Kotlin**: no `android.*` imports (URIs are `String`, use a domain `Rect`/`Size` type rather than Android's).
6. **Never swallow exceptions.** Convert them to `ItemResult.Failed`/`Skipped` or a UI error state with a readable message. One bad image must never crash a batch; catch `OutOfMemoryError` per item (spec §7.2).
7. **Memory safety**: always decode with `setTargetSize` + `ALLOCATOR_SOFTWARE`; `recycle()` bitmaps in `finally`; never hold more than the semaphore-limited number of bitmaps at once.

## Workflow

- Work through milestones **M0 → M7 in order** (spec §10). Do not start a milestone until the previous one builds, passes its tests and meets its acceptance criteria.
- Keep the build green after every task.
- Track status in **`PROGRESS.md`** (create it in M0): one line per task (`T1.2`), done/in-progress, plus a short note covering what was done and anything deferred.
- Record deviations from the spec, new dependencies and platform surprises in **`DECISIONS.md`** (date, decision, reason). If the spec conflicts with how the platform actually behaves, choose correctness and data safety and write it down.
- Keep changes scoped to the current task. Don't refactor unrelated code or implement later milestones early.

## Commands

```powershell
.\gradlew.bat assembleDebug                 # build
.\gradlew.bat test                          # JVM unit tests (domain/ lives here)
.\gradlew.bat lint                          # Android lint
.\gradlew.bat connectedDebugAndroidTest     # instrumented tests (needs emulator/device)
.\gradlew.bat :app:testDebugUnitTest --tests "pl.fehu.imageresizer.domain.DimensionCalculatorTest"   # single test class
.\gradlew.bat assembleRelease               # R8-minified release (M7)
```

Milestone gate: `.\gradlew.bat lint test` must pass, plus `connectedDebugAndroidTest` from M2 on.

INTERNET check: inspect `app/build/intermediates/merged_manifests/<variant>/.../AndroidManifest.xml`
(or `aapt dump permissions <apk>` for release) and confirm `INTERNET` is absent.

## Architecture

Single activity, Jetpack Compose + Material 3, Navigation Compose, MVVM (`ViewModel` + `StateFlow`),
manual DI through `AppContainer` (created in `App`). No Hilt/Koin.

```
pl.fehu.imageresizer
├── App.kt, AppContainer.kt, MainActivity.kt   // MainActivity also handles ACTION_SEND(_MULTIPLE)
├── domain/   model/, DimensionCalculator, FileNamer, FormatResolver, QualitySearch   — pure, unit-tested
├── engine/   ImageProcessor, Decoder, Encoder, FormatSupport, MetadataCopier         — ImageDecoder / Bitmap.compress / ExifInterface
├── storage/  InputResolver, OutputWriter, UriPermissionManager                       — SAF, picker, persisted grants
├── work/     JobStore, ResizeWorker, JobScheduler                                    — WorkManager foreground CoroutineWorker
├── data/     SettingsRepository, PresetRepository                                    — DataStore Preferences + kotlinx.serialization
└── ui/       navigation/, home/, options/, progress/, results/, theme/
```

Dependency direction: `ui → data/work → engine/storage → domain`. `domain` depends on nothing.

Key mechanics to get right (details in the spec):
- **Dimensions** come from `ImageDecoder` (already EXIF-oriented); output always gets orientation tag 1 (§5.1, §5.4).
- **Format mapping**: `KEEP_ORIGINAL` turns HEIC/AVIF/GIF/BMP/DNG into JPEG; the extension always follows the output format (§5.5).
- **Input format detection**: MIME first, then extension (case-insensitive), because HEIC often arrives as `application/octet-stream` (§6.1).
- **WebP encode**: `WEBP_LOSSY`/`WEBP_LOSSLESS` on API 30+, legacy `WEBP` on 28–29 (§5.2).
- **JPEG from alpha sources**: composite onto white first.
- **Job data**: WorkManager `Data` carries only `jobId`; the job lives in `filesDir/jobs/{jobId}.json`, results are appended as items finish, and the last 10 jobs are kept (§7.1).
- **Folder enumeration**: a single `DocumentsContract` children cursor query, not `DocumentFile.listFiles()` (§6.1).
- **Output conflicts**: list the existing names once, then resolve in memory. Read the actual name back after `createDocument` and delete partial files on failure (§6.3).
- **Persisted URI grants**: take them when a job starts and release input grants when it ends, because the platform caps them (§6.2).

## Code conventions

- Kotlin official style (`kotlin.code.style=official`), 4-space indent, no wildcard imports.
- Every public function in `engine/`, `storage/` and `work/` has KDoc that states its **threading expectations** (e.g. "Call on `Dispatchers.IO`", "main-safe").
- Decode/encode run on `Dispatchers.Default`, I/O on `Dispatchers.IO`. Parallelism is `Semaphore(2)`, or 1 on low-RAM devices.
- Domain models are `@Serializable` and use reflection-free kotlinx.serialization, so they survive R8 (M7).
- All user-visible text goes in `res/values/strings.xml` (English) with a matching entry in `res/values-pl/strings.xml` (Polish). No hard-coded UI strings.
- Compose: content descriptions on every icon, touch targets ≥ 48dp, dark mode + dynamic color. Selection state survives process death via `SavedStateHandle`.

## Testing

- `domain/`: JVM unit tests (JUnit4 + kotlinx-coroutines-test), coverage ≥ 90 %. Cover the edge cases listed in M1 AC
  (1×1, 20000×20000, portrait/landscape/square, upscale on/off, FILL crop rect, `.HEIC` uppercase, octet-stream HEIC, every token, illegal chars, empty names).
- `QualitySearch` takes an injectable `encode(quality, scale) -> Int` so it can be tested without bitmaps.
- `engine/` and `storage/`: instrumented tests with fixtures in `app/src/androidTest/assets/` (spec M2 AC lists the required files).
  If a fixture such as an iPhone HEIC can't be produced, stop, ask the user for it and note the gap in `PROGRESS.md`. Do not fake it.
- `work/`: use a fake `ImageProcessor` to simulate OOM and failures.
- UI: Compose UI tests for the M5 AC cases.
- Don't weaken or delete a failing test to make the build pass. Fix the code, or record why in `DECISIONS.md`.
