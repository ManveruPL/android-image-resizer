# Decisions

Deviations from `application-plan.md`, dependency additions and platform findings. Newest last.

## 2026-10-17 — Package name `pl.fehu.imageresizer`
The spec's `com.example.batchresizer` is a placeholder. The project was created as `pl.fehu.imageresizer`, and that name is used for the namespace, applicationId and packages.

## 2026-10-17 — Kotlin 2.4.21 over AGP's bundled 2.2.10
AGP 9.4.1 uses built-in Kotlin and depends on KGP 2.2.10. Declaring the Compose and serialization compiler plugins at 2.4.21 (latest stable) in the root build makes Gradle resolve KGP 2.4.21 for the whole build (verified with `buildEnvironment`). There is one `kotlin` version in the catalog; keep the compiler plugins on it.

## 2026-10-17 — Extra libraries beyond spec §2
- `kotlinx-coroutines-android`: `Dispatchers.Main` for Android. It is part of the "Kotlin coroutines" choice in spec §2.
- `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `activity-compose`, Compose `ui-tooling(-preview)`, `ui-test-manifest`, `androidx.test:runner`: standard companions of Compose, MVVM and the AndroidX Test stack listed in spec §2.
- No `material-icons` artifact: icons are vector drawables in `res/drawable` (e.g. `ic_arrow_back.xml`). This avoids a large, mostly unused library.

## 2026-10-17 — WorkManager added in M0
The manifest has to merge WorkManager's `SystemForegroundService` (spec §3.3), so `work-runtime-ktx` is a dependency from M0. Lint would otherwise flag a declared service with no class behind it.

## 2026-10-17 — Permissions merged in by WorkManager
WorkManager adds `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and a `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. None of them allows network traffic, so the "no network" rule (no INTERNET) still holds. They are kept because WorkManager uses them for constraint tracking and rescheduling. Removing `ACCESS_NETWORK_STATE` with `tools:node="remove"` is possible if we never use network constraints. Revisit in M7.

## 2026-10-17 — XML theme without the Material Components library
`Theme.ImageResizer` extends the platform `android:Theme.Material(.Light).NoActionBar`. All styling is done in Compose `BatchResizerTheme`, so `com.google.android.material` was dropped.

## 2026-10-17 — `ExactSize` + `STRETCH` ignores `allowUpscale`
§5.1's no-upscale carve-out ("For FILL/FIT with upscale disallowed...") only names FIT and FILL. `STRETCH` has no single scale factor to clamp (width and height scale independently), and choosing STRETCH is already an explicit opt-in to ratio distortion, so `DimensionCalculator` always decodes to the exact requested box for `STRETCH` regardless of `allowUpscale`.

## 2026-10-17 — M1 domain coverage not measured with a coverage tool
§10's M1 AC asks for ≥90% coverage of `domain/`. No coverage plugin (JaCoCo) is configured — it isn't in spec §2's tool list and wasn't requested, so it wasn't added unprompted. Coverage was instead manually audited branch-by-branch against the 61 unit tests (see `PROGRESS.md`). Revisit if a measured number is wanted later.

## 2026-10-18 — M2 test fixtures: real OnePlus HEIC/AVIF files from `.ai-data`, not iPhone
M2's AC calls for an "iPhone HEIC" and an AVIF sample. No tool on this machine (checked: ffmpeg, ImageMagick, heif-enc, avifenc, Python+Pillow — none installed) can legitimately produce a HEIC/AVIF, and AGENTS.md says not to fake one. The user pointed at `.ai-data/`, which has 11 real HEIC files (OnePlus 13 captures, confirmed via an on-device `ExifInterface`/`ImageDecoder` probe: 3696×6560 portrait / one 6560×3696 landscape, Display P3, GPS, capture date, EXIF orientation already 1 — not rotated) and 5 real AVIF "image sequence" files (confirmed to decode fine on the API 37 x86_64 emulator, API 31+ AV1 software decoder present). The user confirmed OnePlus-style HEIC is actually the primary real-world source for this app, not a stand-in for iPhone. Copied the smallest representative files into `app/src/androidTest/assets/` (portrait+landscape HEIC, one AVIF) and into `app/src/main/assets/probe/` for the runtime `FormatSupport` probe. Gap: none of the 11 HEIC files is a genuine multi-item (burst/Live-Photo-style) container — they're 11 separate single-image captures — so that specific AC item ("decode the primary image only" from a multi-image HEIC) has no dedicated test; flag if a real multi-item HEIC becomes available.

## 2026-10-18 — `FormatSupport` probe assets aren't actually "tiny"
§5.5 says to bundle a "tiny" HEIC/AVIF sample in `assets/probe/` for the runtime decode-support probe. With no HEIC/AVIF encoder available (see above), the smallest real files available were used as-is: `sample.heic` (OnePlus capture, 1.4MB) and `sample.avif` (97KB). This adds ~1.5MB to the app. Revisit at M7's <10MB release-size check; a genuinely tiny sample would need either an encoder or a user-supplied minimal file.

## 2026-10-18 — `OutputWriter`'s SAF plumbing has no instrumented test yet
`OutputWriter` (storage/) creates/locates documents via raw `DocumentsContract` against a real SAF tree `Uri`, which in this test harness has no automated way to be granted (no UI-automator dependency in spec §2, and no non-interactive trick to get a persisted tree permission). Its logic was verified by code review; `FileNamer.resolveConflict`, which it delegates to for RENAME, is already unit-tested (M1). The actual `DocumentsContract` calls get their first live exercise in M3 (output-folder selection) or M5 (full UI flow), where a real granted tree exists naturally. `ImageProcessor`'s own instrumented tests avoid this entirely by using `Uri.fromFile(...)` against app-private files, since `ContentResolver` handles the `file://` scheme natively without needing SAF.

## 2026-10-18 — Added `androidx.exifinterface`; `androidx.documentfile` still unused
Both are in spec §2's dependency list (version catalog already had entries) but neither was wired into `app/build.gradle.kts` until needed. `androidx.exifinterface` is now used by `MetadataCopier`/`TestFixtures`. `androidx.documentfile` remains unused: `OutputWriter` uses raw `DocumentsContract` directly (matching §6.1's explicit guidance to avoid `DocumentFile.listFiles()` for performance), and nothing else needs the `DocumentFile` wrapper yet. Will add it if a later milestone actually needs it, with its own note.

## 2026-10-18 — A real `DocumentsProvider` test double is not viable for a third-party app
Tried building an in-app `DocumentsProvider` subclass (androidTest-only) to get a genuine SAF tree `Uri` for testing `InputResolver.resolveFolder` and `OutputWriter`, without interactive picker consent. Confirmed empirically: the OS refuses to even instantiate it — `DocumentsProvider.attachInfo()` requires the declaring `<provider>` tag to be protected by the signature-level `android.permission.MANAGE_DOCUMENTS` permission, which only system apps can hold, regardless of which package declares it or what it actually does. Crash: `SecurityException: Provider must be protected by MANAGE_DOCUMENTS`. This rules out ever hosting a real SAF-pickable provider from this app for testing purposes; genuine SAF tree testing needs either interactive picker automation (UI-automator, not in spec §2) or stays a documented gap deferred to UI-driven tests (M5).

## 2026-10-18 — A plain `ContentProvider` declared in `androidTest` doesn't work either, for two unrelated reasons
Pivoted to a plain `ContentProvider` (not `DocumentsProvider`, avoiding the `MANAGE_DOCUMENTS` wall above) declared in `app/src/androidTest/AndroidManifest.xml`, since that's the natural place for test-only components. Hit two more platform walls in sequence, both confirmed empirically:
1. Written in Kotlin, the provider crashed with `NoClassDefFoundError: Lkotlin/jvm/internal/Intrinsics` the moment the OS spawned it as its own process. AGP excludes `kotlin-stdlib` from the `androidTest` APK's own packaging because the tested app APK already provides it — normal test *methods* run fine because Instrumentation merges classloaders for that, but a provider the OS instantiates as a genuinely separate component (because its declaring package, `pl.fehu.imageresizer.test`, differs from the app under test) only gets that package's own dex. Adding `androidTestImplementation(kotlin("stdlib"))` explicitly did not fix it — AGP still deduplicates it out of the test APK's packaging regardless of how it's declared. `android:process` pointing at the app's process name didn't help either: same-name `android:process` across different packages doesn't merge classloaders or dex paths (only true package identity / the deprecated, now largely blocked `sharedUserId` does).
2. Rewriting the provider in plain Java (no `kotlin-stdlib` dependency at all) fixed the crash, but then `query()` silently returned nothing: the spawned provider process runs under the **test package's own UID** (`pl.fehu.imageresizer.test`), completely isolated — different static memory, different private storage — from the test *code*, which (via Instrumentation) actually executes in the **app's** process/UID. A static in-memory registry populated by test code is invisible to the provider's own separate process.

Both problems disappear if the provider is part of the **app's own package** instead. See the next entry.

## 2026-10-18 — `OutputResolver` interface extracted from `OutputWriter` for M4 testability
`BatchRunner` (work/) needs to be tested with a fake output location, same as M2 made `ImageProcessor` an interface for the same reason — no real SAF tree is available in this harness (see the `DocumentsProvider`/`FakeContentProvider` entries above). `OutputWriter`'s three public methods were extracted verbatim into a new `OutputResolver` interface, which `OutputWriter` now implements; `BatchRunnerTest` uses a local-directory-backed `FakeOutputResolver` (androidTest) instead. `OutputWriter`'s own SAF-writing behavior is still untested for the same reason as before (deferred to M5).

## 2026-10-18 — M4 naming uses pre-encode (not post-encode) dimensions for `{w}x{h}`
The output document must be created (and therefore named) *before* `ImageProcessor.process` can stream-encode into it (so a 20MP photo is never buffered twice in memory). `BatchRunner` reads EXIF-oriented source dimensions cheaply via a new `Decoder.readDimensions` (aborts `ImageDecoder`'s decode from inside the header-decoded listener, decoding zero pixels) and feeds them through the same `DimensionCalculator.compute` the real decode uses, so `{w}x{h}` is correct for every resize mode except `TargetFileSize`: there, `QualitySearch`'s extra downscale-to-hit-size-budget step runs *after* the name is already chosen, so the name reflects the pre-search cap (`maxLongestEdgePx`, or the original size if unset), not necessarily the final written pixel dimensions. Revisit if a user reports surprising file names in Max file size mode.

## 2026-10-18 — `ItemResult.Success` gained an optional `note` field
`ImageProcessor.process` already returned a `ProcessedImage.note` (e.g. "first frame only (animated source)", "target size not reached") that nothing downstream ever read. `BatchRunner` is the first caller that assembles the final `ItemResult`, so it now threads that note through. Added as `val note: String? = null` (last field, default null) so M1's existing positional-constructor tests (`ModelSerializationTest`) keep compiling unchanged.

## 2026-10-18 — OOM retry does not force `MEMORY_POLICY_LOW_RAM`
§7.2 says to retry a per-item `OutOfMemoryError` "at parallelism 1", which `BatchRunner` implements by acquiring the rest of the `Semaphore`'s permits before retrying (so no other item decodes concurrently) — not by also switching the retry's own decode to `ImageDecoder.MEMORY_POLICY_LOW_RAM` regardless of the device. An earlier version did force it, and that surfaced a real `ImageDecoder` bug: on the API 28 x86 emulator (not API 37), decoding a JPEG that needs an EXIF-driven rotation with `MEMORY_POLICY_LOW_RAM` *after* an earlier decode of the same job didn't use that policy throws `IOException: getPixels failed with error internal error` from native code. The retry now reuses the caller's original `lowRamDevice` value unchanged. Hard rule 6 already covers the case where the retry itself fails for any reason (platform bug included): the item is marked `ItemResult.Failed`, not crashed. **Flag for the user:** this looks like a genuine, narrow `ImageDecoder` bug tied to EXIF-rotated JPEGs + the low-RAM memory policy on at least one real Android version/device image (API 28); it's unconfirmed whether it also affects real (non-emulator) low-RAM hardware. Worth a manual check during M7's hardening pass (§11's "Portrait phone photos (EXIF rotated)" and "Low-RAM emulator (2 GB)" rows interact here) and worth reporting upstream if reproducible on real hardware.

## 2026-10-18 — M5 Options/preset functionality is UI-only; persistence is M6's job
Spec §9.2 says "Changes save immediately to settings" and has a working Preset dropdown + "Save as
preset", but `SettingsRepository`/`PresetRepository` (both DataStore-backed) are M6 tasks, and
AGENTS.md says not to implement later milestones early. Resolution: `JobDraftViewModel` holds the
in-progress `JobConfig`, selected images and output folder purely in memory (surviving rotation
automatically as a ViewModel, and process death via `SavedStateHandle` for the selected URIs and
output tree URI/name, per spec §9.5) but **not** a fresh app launch — every cold start resets to
`JobDraftViewModel.defaultConfig()`. The five built-in presets from spec §8 are hardcoded in
`ui/options/BuiltInPresets.kt` (applying one only changes the in-memory config, never the output
folder, per `Preset`'s own kdoc); selecting one from the dropdown works, but "Save as preset" is
disabled (no `PresetRepository` to save into yet). M6 should replace `BuiltInPresets` with real
`PresetRepository` seeding and wire `SettingsRepository` under `JobDraftViewModel` so config and
output folder survive a cold start.

## 2026-10-18 — `androidx.documentfile` finally wired in, for output-folder display names
M3's notes flagged `androidx.documentfile` as present in the catalog but unused. `JobDraftViewModel.
resolveTreeDisplayName` now uses `DocumentFile.fromTreeUri(context, uri)?.name` to show a human
-readable name for the chosen output folder (Home's output-folder card, spec §9.1) — simpler than
hand-rolling a `DocumentsContract` query for a single document's display name.

## 2026-10-18 — M5 extended M4's `work/` classes with small, additive changes
`BatchRunner.run`'s `onProgress` callback gained a third `lastItemName: String` parameter (so
Progress can show "Last finished: x.jpg", spec §9.3); `JobProgress` gained `lastItemName: String?`
plumbed the same way through `ResizeWorker`'s `setProgress` and `JobScheduler.toJobProgress`.
`JobScheduler.start()` now returns our own `jobId` (a `String`) instead of WorkManager's own
`WorkRequest.id` (a `UUID`) — nothing consumed the latter, and the UI needs *our* id to load the
`JobRecord` from `JobStore`. `JobScheduler` also gained `cancel()` (wraps
`WorkManager.cancelUniqueWork`) and `currentJobId()` (reads `JobStore.recentJobIds(1)`, so it
survives process death the same way the job record itself does — needed for the Progress screen
reopened from the notification after the app process was recreated). `BatchRunnerTest`'s lambdas
were updated for the new arity; behavior is unchanged.

## 2026-10-18 — Home's resize-mode picker is a scrollable `FilterChip` row, not `SegmentedButton`
Spec §9.2 says "segmented control", but Material3's `SingleChoiceSegmentedButtonRow` doesn't scroll
and 6 resize modes (Original/Longest/Shortest/Exact/Percent/Max file size) overflow a phone-width
row. A horizontally-scrollable row of single-select `FilterChip`s is functionally identical (one
selected mode, mode-specific inputs appear below) and remains fully accessible; revisit if a
design review wants the exact `SegmentedButton` look.

## 2026-10-18 — `ContentResolver.loadThumbnail` needs a minSdk-28 fallback
Spec §9.1 calls for `ContentResolver.loadThumbnail(uri, Size(256,256))` for the selection grid, but
that method is API 29+ (lint caught this: `minSdk 28`). `ui/common/Thumbnail.kt` falls back to
`BitmapFactory.decodeStream` with `inSampleSize = 4` on API 28 — a simpler, slightly lower-quality
downsample than true thumbnail generation, but adequate for a 72dp grid tile.

## 2026-10-18 — Options' Preset section is a list, not a dropdown (M6)
M5 built a read-only dropdown for the 5 hardcoded built-ins. M6 needs full CRUD (create/rename/
delete/apply), and a per-item rename/delete action doesn't nest cleanly inside a Material3
`DropdownMenuItem` (its content slot isn't meant to host further interactive children). Replaced
with a plain `Column` of rows (apply on tap, trailing rename/delete `IconButton`s) plus a
`PresetNameDialog` (a single reusable `AlertDialog` with a text field) for both "Save as preset"
and rename. Functionally equivalent to spec §9.2's "Preset dropdown", just a different widget.

## 2026-10-18 — `presets` StateFlow bug: `SharingStarted.WhileSubscribed` never started without a real subscriber
First cut of `JobDraftViewModel.presets` was `presetRepository.presets.stateIn(viewModelScope,
SharingStarted.WhileSubscribed(5_000), emptyList())`. `WhileSubscribed` only starts collecting the
upstream flow once something actually *subscribes* (`collect`/`collectAsState`/etc.) — reading a
`StateFlow`'s `.value` directly does not count. Compose's `collectAsStateWithLifecycle()` always
subscribes, so the real UI never showed the bug; an instrumented test that polled `presets.value`
in a loop (no Compose involved) hung forever because the upstream `DataStore` read had never
started. Fixed to match `config`/`outputTreeUri`/etc.'s existing pattern: an eager
`viewModelScope.launch { presetRepository.presets.collect { _presets.value = it } }` in `init`,
which starts immediately regardless of subscribers. Lesson for future `stateIn` usage in this
codebase: `WhileSubscribed` is fine for values only ever read through Compose, never for anything
a plain coroutine might poll via `.value`.

## 2026-10-18 — Removed `optimization.packageScope` from the release build type: it crashed the app on launch
M0 configured the release build type with `optimization { enable = true; packageScope = setOf("androidx.**",
"kotlin.**", "kotlinx.**") }`. Nobody had actually *launched* a release build before M7 (`assembleRelease`
alone just checks R8 doesn't error out, not that the result runs) — doing so for T7.2's "verify
reflection-free serialization works minified" AC surfaced a real crash on startup, before even
reaching `MainActivity`:
```
FATAL EXCEPTION: main
java.lang.IllegalAccessError: Class kotlin.sequences.d extended by class cy1 is inaccessible
  (declaration of 'cy1' appears in base.apk)
  at kotlin.text.l.y(...)
  at io2.O(...)
  at androidx.work.WorkManagerInitializer.b(...)
```
`packageScope` restricts *which* classes R8 is allowed to repackage/merge; scoping it to only
`androidx.**`/`kotlin.**`/`kotlinx.**` (presumably meant to leave `pl.fehu.imageresizer.**` alone
for readable stack traces) repackaged two classes with a package-private access relationship
(an androidx.work internal class and a kotlin-stdlib internal class it calls into) into
incompatible packages, breaking that access at runtime. Removed `packageScope` entirely — `release
{ optimization { enable = true } }` uses R8's default full-app scope and does not reproduce the
crash (verified by installing the release APK, applying a preset, and confirming it survives a
force-stop + relaunch — exercising the exact `kotlinx.serialization` encode/decode path through
DataStore under R8). Lesson: **a release build must actually be launched on a device at least
once**, not just compiled, before trusting any R8 configuration change.

## 2026-10-18 — Local-only release signing keystore (M7)
Generated `keystore/release.jks` (RSA 2048, 10000-day validity, password `batchresizer123`,
alias `release`) purely so `assembleRelease` produces a signed, installable APK to satisfy M7's
AC and to let a real device/emulator run exercise R8's effect on the app. **This is not a
distribution-ready signing identity** — it was generated locally by the agent with a trivial
password, is gitignored (`/keystore`, `*.jks`), and its passwords live in `local.properties`
(also gitignored), referenced from `app/build.gradle.kts`'s `signingConfigs.release`. Before any
real release (Play Store or otherwise), generate a proper key, protect it (password manager /
secrets vault, not a plaintext password chosen by an agent), and never reuse this one.

## 2026-10-18 — M4's "300-image batch in the background" and real-WorkManager ACs are code-reviewed, not instrumented
`BatchRunnerTest` (androidTest) exercises `ResizeWorker`'s actual logic — format filtering, naming/conflicts, OOM retry-then-fail, cancellation, and resuming a partial job — via `BatchRunner` directly with a fake `ImageProcessor`/`OutputResolver`, per AGENTS.md's testing section. `ResizeWorker` itself (the `CoroutineWorker` adapter: `setForeground`/`setProgress`/notification wiring) has no automated test: `androidx.work:work-testing` isn't in spec §2's dependency list, so it wasn't added without asking first. The real end-to-end path (300 images, app backgrounded, actual foreground-service notification) was verified by code review and the manual test matrix (§11) rather than an instrumented test. Ask if `work-testing` should be added for M5/M6 — it would let `ResizeWorker` itself (and `JobScheduler`'s unique-work/cancel-intent wiring) be driven with `TestListenableWorkerBuilder` instead of relying solely on `BatchRunner`-level tests and manual verification.

## 2026-10-18 — `FakeContentProvider` lives in `app/src/debug/`, not `androidTest`
Following from the two entries above, declaring the test-support provider in the `debug` source set (`src/debug/AndroidManifest.xml`, `src/debug/java/.../FakeContentProvider.kt`) makes it part of `pl.fehu.imageresizer` itself for debug builds — same UID as wherever the test code runs, same dex (so plain Kotlin is fine, `androidx.core` KTX too), real shared static state. It's absent from release builds (AGP never merges `debug/` into `release`), so it never ships. It needs `android:grantUriPermissions="true"` for `UriPermissionManagerTest`'s `grantUriPermission`/`takePersistableUriPermission` calls to succeed. It answers two shapes: plain `content://authority/{id}` (backing `InputResolver.resolveUris` tests — chosen over MediaStore insert, which needs `WRITE_EXTERNAL_STORAGE` on API 28, forbidden by this app's hard rules) and `DocumentsContract`-shaped `tree/.../document/.../children` queries backed by a real directory (backing `InputResolver.resolveFolder` tests, including the 1,000-file performance AC) — without extending the forbidden `DocumentsProvider`, by parsing the URI's path segments manually. It does not implement `createDocument`/`openDocument` for a tree, so `OutputWriter`'s SAF write path remains untested (deferred to M5, see `PROGRESS.md`).
