# Batch Image Resizer for Android — Implementation Spec

> Audience: coding agents working in Android Studio.
> Work through milestones **in order**. Each milestone must build, pass its tests, and meet its acceptance criteria before the next one starts.

---

## 1. Goal

An Android app that resizes and **converts** many images at once, **entirely on the device**, and writes results to a **user-chosen folder**. Converting iPhone HEIC photos to JPEG is a headline use case and gets a one-tap shortcut.

### Hard requirements

1. **No network access.** The app must not declare `android.permission.INTERNET`, and no dependency may add it (see §3.3).
2. **Batch processing** of hundreds of images without running out of memory.
3. **User-selected output directory**, remembered between sessions.
4. **Rich options:** resize modes, output format, quality, naming, conflict policy, and metadata handling.
5. **Format conversion** with or without resizing: any supported input (§5.5) to JPEG, PNG or WebP, including a "keep original size" mode for pure conversion.
6. **Originals are never modified** unless the user explicitly chooses "overwrite" *and* the output folder equals the source folder.

### Out of scope (v1)

- Image editing (filters, rotation by user, watermarks)
- HEIC/AVIF **output** (possible in v2 via `androidx.heifwriter` on devices with an HEVC encoder)
- Deleting originals after conversion
- Cloud sync, accounts, analytics, ads

---

## 2. Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin (latest stable) |
| UI | Jetpack Compose + Material 3, Navigation Compose |
| Architecture | Single `:app` module, MVVM (ViewModel + `StateFlow`), manual DI via an `AppContainer` |
| Decode | `android.graphics.ImageDecoder` with `setTargetSize()` |
| Encode | `Bitmap.compress()` — JPEG, PNG, WebP |
| Metadata | `androidx.exifinterface:exifinterface` |
| File access | Storage Access Framework, `androidx.documentfile:documentfile` |
| Background work | WorkManager (`CoroutineWorker`, foreground) |
| Concurrency | Kotlin coroutines, `Semaphore`-limited parallelism |
| Persistence | DataStore (Preferences) + `kotlinx.serialization` JSON |
| Tests | JUnit4, kotlinx-coroutines-test, AndroidX Test, Compose UI test |

### SDK levels

- `minSdk = 28` (required for `ImageDecoder`)
- `targetSdk` / `compileSdk` = latest stable
- Use a Gradle **version catalog** (`gradle/libs.versions.toml`) with latest stable versions. Do **not** add libraries outside this list without noting why in `DECISIONS.md`.

---

## 3. Project setup

### 3.1 Identity

- Application ID: `com.example.batchresizer` (placeholder — change before release)
- App name: "Batch Resizer"

### 3.2 Package layout

```
com.example.batchresizer
├── App.kt                      // Application, creates AppContainer
├── AppContainer.kt             // manual DI
├── MainActivity.kt             // single activity, handles share intents
├── domain/
│   ├── model/                  // pure Kotlin data classes (§4)
│   ├── DimensionCalculator.kt
│   ├── FileNamer.kt
│   ├── FormatResolver.kt       // input format → output format (§5.5)
│   └── QualitySearch.kt
├── engine/
│   ├── ImageProcessor.kt       // interface + implementation
│   ├── Decoder.kt
│   ├── Encoder.kt
│   ├── FormatSupport.kt        // runtime probe: which inputs this device decodes
│   └── MetadataCopier.kt
├── storage/
│   ├── InputResolver.kt        // URIs / tree → list of InputImage
│   ├── OutputWriter.kt         // SAF writes, conflict handling
│   └── UriPermissionManager.kt
├── work/
│   ├── JobStore.kt             // job JSON on disk
│   ├── ResizeWorker.kt
│   └── JobScheduler.kt
├── data/
│   ├── SettingsRepository.kt   // DataStore
│   └── PresetRepository.kt
└── ui/
    ├── navigation/
    ├── home/
    ├── options/
    ├── progress/
    ├── results/
    └── theme/
```

### 3.3 Manifest

- Explicitly remove INTERNET in case a dependency merges it in:
  ```xml
  <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
  ```
- Declare:
    - `POST_NOTIFICATIONS` (requested at runtime on API 33+)
    - `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`
    - WorkManager's `SystemForegroundService` with `android:foregroundServiceType="dataSync"` (via `tools:node="merge"`)
- No `READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, or `MANAGE_EXTERNAL_STORAGE`. All access goes through pickers and SAF.
- Intent filters on `MainActivity`: `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for `image/*`.

**Check:** after every milestone, inspect the merged manifest (`app/build/intermediates/merged_manifests/...`) and confirm INTERNET is absent.

---

## 4. Domain model

All in `domain/model`, annotated `@Serializable`, no Android types except where noted (URIs stored as `String`).

```kotlin
sealed interface ResizeMode {
    data object OriginalSize : ResizeMode                          // convert only, no resize
    data class LongestEdge(val px: Int) : ResizeMode
    data class ShortestEdge(val px: Int) : ResizeMode
    data class ExactSize(val width: Int, val height: Int, val fit: FitMode) : ResizeMode
    data class Percentage(val percent: Int) : ResizeMode           // 1..100 (or ..400 if upscaling)
    data class TargetFileSize(val maxKb: Int, val maxLongestEdgePx: Int?) : ResizeMode
}

enum class FitMode { FIT /* inside box, keep ratio */, FILL /* cover box, center-crop */, STRETCH }

enum class OutputFormat { KEEP_ORIGINAL, JPEG, PNG, WEBP }

enum class InputFormat { JPEG, PNG, WEBP, HEIF, AVIF, GIF, BMP, DNG, OTHER }   // from MIME + extension

enum class ColorPolicy { CONVERT_TO_SRGB, KEEP_SOURCE }

enum class ConflictPolicy { RENAME, SKIP, OVERWRITE }

enum class MetadataPolicy { KEEP_ALL, STRIP_GPS, STRIP_ALL }

data class NamingSpec(
    val pattern: String = "{name}_{w}x{h}",   // tokens below
)

data class JobConfig(
    val resize: ResizeMode,
    val format: OutputFormat,
    val quality: Int,                  // 1..100, ignored for PNG
    val allowUpscale: Boolean = false,
    val naming: NamingSpec,
    val conflict: ConflictPolicy,
    val metadata: MetadataPolicy,
    val color: ColorPolicy = ColorPolicy.CONVERT_TO_SRGB,
    val inputFilter: Set<InputFormat> = emptySet(),   // empty = all; e.g. {HEIF} = only convert HEIC files
    val outputTreeUri: String,
)

data class InputImage(val uri: String, val displayName: String, val mimeType: String,
                      val format: InputFormat, val sizeBytes: Long?)

sealed interface ItemResult {
    val input: InputImage
    data class Success(override val input: InputImage, val outputName: String,
                       val outWidth: Int, val outHeight: Int,
                       val bytesBefore: Long?, val bytesAfter: Long) : ItemResult
    data class Skipped(override val input: InputImage, val reason: String) : ItemResult
    data class Failed(override val input: InputImage, val error: String) : ItemResult
}

data class Preset(val id: String, val name: String, val config: JobConfig /* outputTreeUri may be blank */)
```

### Naming tokens

| Token | Value |
|---|---|
| `{name}` | original file name without extension |
| `{w}` / `{h}` | output width / height in px |
| `{index}` | 1-based position in batch, zero-padded to batch size digits |
| `{date}` | job start date `yyyyMMdd` |
| `{preset}` | preset name, or empty |

The extension is always added by `FileNamer` from the output format. Sanitize the result: replace `/ \ : * ? " < > |` with `_`, trim, max 120 chars, and fall back to `image_{index}` if empty.

---

## 5. Core algorithms

### 5.1 `DimensionCalculator` (pure, unit-tested)

`fun compute(srcW: Int, srcH: Int, mode: ResizeMode, allowUpscale: Boolean): TargetDims`

`TargetDims(decodeW, decodeH, cropRect: Rect?, finalW, finalH)`

Rules:
- Use **source dimensions after EXIF orientation** (`ImageDecoder` reports oriented dimensions).
- `OriginalSize`: scale = 1.0, no crop (pure format conversion).
- `LongestEdge(px)`: scale = px / max(w, h).
- `ShortestEdge(px)`: scale = px / min(w, h).
- `Percentage(p)`: scale = p / 100.
- `ExactSize FIT`: scale = min(W/w, H/h).
- `ExactSize FILL`: scale = max(W/w, H/h), then center-crop to W×H.
- `ExactSize STRETCH`: decode directly to W×H (ratio not kept).
- `TargetFileSize`: dimensions come from `maxLongestEdgePx` if set, otherwise the original; size is handled by §5.3.
- If `!allowUpscale`, clamp scale to ≤ 1.0. For FILL/FIT with upscale disallowed and a too-small source, output the source size (FIT) or the largest crop with W:H ratio (FILL).
- Round with `roundToInt()`, minimum 1 px per side.

### 5.2 Decode → transform → encode (`ImageProcessor`)

For each input:

1. `ImageDecoder.createSource(contentResolver, uri)`.
2. `decodeBitmap(source) { decoder, info, _ -> ... }`:
    - Read `info.size`, compute `TargetDims`.
    - `decoder.setTargetSize(decodeW, decodeH)`.
    - `decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE` (hardware bitmaps can't be compressed).
    - If FILL, use `decoder.crop = cropRect` (in target-size coordinates).
    - `decoder.memorySizePolicy = MEMORY_POLICY_LOW_RAM` on low-RAM devices.
    - If `ColorPolicy.CONVERT_TO_SRGB`, call `decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))` (see §5.5).
3. Resolve output format with `FormatResolver` (§5.5).
4. Encode:
    - JPEG → `CompressFormat.JPEG`. If the bitmap has alpha, composite onto white first.
    - PNG → `CompressFormat.PNG` (quality ignored).
    - WebP → `CompressFormat.WEBP_LOSSY` on API 30+; `CompressFormat.WEBP` on 28–29. If quality = 100, use `WEBP_LOSSLESS` on API 30+.
5. Always `bitmap.recycle()` in `finally`.

Animated GIF/WebP: decode first frame only and record a note in the result.

### 5.3 `TargetFileSize` (`QualitySearch`, pure where possible)

- Only for JPEG and WebP. If the resolved format is PNG, fail the item with a clear message (the UI also prevents this combination).
- Binary search quality in [30, 95], max 7 iterations, encoding to a `ByteArrayOutputStream`. Keep the highest quality whose size ≤ maxKb.
- If even quality 30 is too large, scale dimensions by 0.85 and retry, up to 5 times. Then accept the smallest result and mark it `Success` with a note "target size not reached".

### 5.4 Metadata (`MetadataCopier`)

- Only for JPEG, PNG and WebP outputs whose source has EXIF.
- After the output is written, open it with `contentResolver.openFileDescriptor(uri, "rw")` and `ExifInterface(fd)`.
- `KEEP_ALL`: copy a defined allow-list of tags (date/time, camera make/model, exposure, lens, copyright, artist, GPS*).
- `STRIP_GPS`: same list without any `GPS_*` tag.
- `STRIP_ALL`: copy nothing (`Bitmap.compress` already writes no EXIF).
- Always set `TAG_ORIENTATION = ORIENTATION_NORMAL` (pixels are already rotated), and set new `PIXEL_X/Y_DIMENSION`.
- Metadata failures must **not** fail the item; record a note.
- HEIC/HEIF and AVIF sources: read EXIF with `ExifInterface(inputStream)` (HEIF reading is supported from API 28) and copy it to the JPEG/PNG/WebP output the same way.

### 5.5 Format conversion (`FormatResolver`, `FormatSupport`)

**Supported inputs** (decoded by `ImageDecoder`):

| Input | MIME | Notes |
|---|---|---|
| JPEG | `image/jpeg` | |
| PNG | `image/png` | alpha kept unless output is JPEG |
| WebP | `image/webp` | animated → first frame |
| HEIC / HEIF | `image/heic`, `image/heif` | iPhone photos; needs a device HEVC decoder (present on nearly all devices on API 28+) |
| AVIF | `image/avif` | API 31+ |
| GIF | `image/gif` | first frame only |
| BMP | `image/bmp` | |
| DNG | `image/x-adobe-dng` | best effort, slow; may fail on some devices |

**Output mapping** (`FormatResolver`, pure and unit-tested):
- Explicit format (JPEG / PNG / WebP): always that format.
- `KEEP_ORIGINAL`: JPEG→JPEG, PNG→PNG, WebP→WebP; HEIC, AVIF, GIF, BMP, DNG → **JPEG** (nothing can re-encode them).
- File extension always follows the output format (`photo.HEIC` → `photo.jpg`).

**HEIC → JPEG specifics** (the main conversion case):
- iPhone HEICs are usually **Display P3** and often 10-bit. Default `ColorPolicy.CONVERT_TO_SRGB` converts to 8-bit sRGB during decode, so colors look right in every viewer and web shop. `KEEP_SOURCE` keeps P3 and relies on `Bitmap.compress` embedding the ICC profile.
- HDR gain maps are ignored; output is SDR.
- Multi-image HEIC files (bursts, Live Photo stills, depth maps): decode the **primary image** only.
- Orientation: `ImageDecoder` applies the HEIF rotation; the output gets orientation tag 1 (§5.4).
- Default quality for HEIC→JPEG: 90, because HEIC sources are already compressed and lower values show artifacts.

**Runtime support probe** (`FormatSupport`):
- On first launch, decode a tiny bundled HEIC and AVIF sample (in `assets/probe/`) and cache which formats work.
- Inputs whose format is unsupported on this device are shown in the selection with a warning badge and result in `Skipped("HEIC is not supported on this device")` instead of a generic failure.

**Input filter:**
- `JobConfig.inputFilter` limits a batch to chosen formats. Typical use: pick a whole camera folder, convert only the HEIC files, ignore the JPEGs already there.

---

## 6. Storage

### 6.1 Input (`InputResolver`)

Three ways in:

1. **Photo Picker:** `PickMultipleVisualMedia(maxItems = MediaStore.getPickImagesMaxLimit())`, images only.
2. **Files:** `OpenMultipleDocuments(arrayOf("image/*"))` — no item cap.
3. **Folder:** `OpenDocumentTree`. Enumerate children with `DocumentsContract.buildChildDocumentsUriUsingTree` + one cursor query (not `DocumentFile.listFiles()` per item — too slow). Filter by `image/*` MIME. Option: include subfolders (recursive, default off).
4. **Share intent:** `ACTION_SEND` / `ACTION_SEND_MULTIPLE` → read `EXTRA_STREAM`.

Return `List<InputImage>` with display name, MIME and size from `OpenableColumns`. Deduplicate by URI. Detect `InputFormat` from the MIME type, falling back to the file extension (`.heic`, `.heif`, `.avif`, `.dng`), because some providers report HEIC files as `application/octet-stream`. For the folder route, also accept those extensions when the MIME type is not `image/*`.

### 6.2 Permissions (`UriPermissionManager`)

The worker may run after the activity is gone, so URIs must stay readable:
- For document/tree URIs: `takePersistableUriPermission(READ)` when the job starts; release grants that no other job or setting needs when the job finishes.
- For Photo Picker URIs: also call `takePersistableUriPermission` (supported by the picker).
- For share-intent URIs (temporary grants, not persistable): process them in the foreground worker started immediately, and document that leaving the app mid-job may fail those items with a clear "permission expired" error.
- Output tree: persist `READ | WRITE`, store the URI in settings. On startup, verify it is still in `contentResolver.persistedUriPermissions`; if not, clear it and ask again.
- Android limits persisted grants per app, so always release input grants after the job.

### 6.3 Output (`OutputWriter`)

- Before the batch starts, list existing names in the output folder once (single cursor query) into a `MutableSet<String>`.
- Per item, resolve the final name using `ConflictPolicy`:
    - `RENAME`: append ` (2)`, ` (3)`… before the extension until unique.
    - `SKIP`: produce `ItemResult.Skipped`.
    - `OVERWRITE`: open the existing document with mode `"wt"`. Never overwrite the item's own source file unless the user confirmed overwriting originals (show a warning dialog when output folder = input folder and policy = OVERWRITE).
- Create new files with `DocumentsContract.createDocument(treeDocUri, mime, name)`. SAF may change the name; read back the actual display name and use it in the result.
- Write via `openOutputStream(uri)` in a `use {}` block. On failure, delete the partial document.

---

## 7. Background execution

### 7.1 `JobStore`

- WorkManager `Data` is limited to ~10 KB, so the full job (config + input list + results) is stored as JSON in `filesDir/jobs/{jobId}.json`. Only `jobId` goes into `Data`.
- Results are appended as items finish so a killed job can show partial results.
- Keep the last 10 jobs; delete older files.

### 7.2 `ResizeWorker` (`CoroutineWorker`)

- Calls `setForeground()` immediately with a progress notification ("12 / 240") and a Cancel action.
- Parallelism: `Semaphore(2)`; use 1 if `ActivityManager.isLowRamDevice`. Run decode/encode on `Dispatchers.Default`, I/O on `Dispatchers.IO`.
- After each item: append result to `JobStore`, call `setProgress(workDataOf("done" to n, "total" to t))`.
- Respect cancellation: check `isStopped` / `ensureActive()` between items; already-written outputs stay.
- Catch `OutOfMemoryError` per item: run `System.gc()`, retry once at parallelism 1, then fail that item. Never crash the whole job.
- Finish with a summary notification: "238 resized, 2 failed — tap to view".

### 7.3 `JobScheduler`

- Enqueue as unique work (`ExistingWorkPolicy.KEEP`) so only one batch runs at a time. If a job is running, the Start button shows "Batch in progress" and opens the Progress screen.
- Expose `Flow<JobProgress>` built from `WorkManager.getWorkInfoByIdFlow`.

---

## 8. Settings and presets

- `SettingsRepository` (DataStore Preferences): last `JobConfig`, output tree URI, "include subfolders", notification permission asked flag.
- `PresetRepository`: list of `Preset` stored as one JSON string. CRUD + reorder.
- Built-in presets on first launch (user can delete): "HEIC → JPEG" (original size, JPEG 90, sRGB, keep metadata, naming `{name}`, filter HEIF only), "Web 1600px JPEG 82", "Shop product 1200px WebP 80", "Email 1024px JPEG 75", "Under 500 KB JPEG".

---

## 9. UI

Single activity, Navigation Compose, four screens. All strings in `strings.xml` (prepare for Polish and English: `values/` = English, `values-pl/` = Polish).

### 9.1 Home

- Big "Add images" button → bottom sheet: Photos / Files / Folder.
- Quick action button **"Convert HEIC to JPEG"**: applies the built-in preset and opens the picker. If the current selection contains HEIC files, show a hint chip "12 HEIC files · Convert to JPEG".
- Selection summary shows a count per input format (e.g. "40 JPEG · 12 HEIC").
- Selected images: count, total size, lazy grid of thumbnails (load small thumbnails with `ContentResolver.loadThumbnail(uri, Size(256,256))` — no image library needed). Long-press to remove; "Clear all".
- Card: current preset/options summary (e.g. "Longest edge 1200 px · WebP 80 · strip GPS") → opens Options.
- Card: output folder (display name) with "Change". If unset, Start is disabled with hint.
- Primary button: "Resize N images".

### 9.2 Options

Sections, each a card:
1. **Preset** dropdown + "Save as preset".
2. **Resize mode** — segmented control: Original size (convert only) / Longest edge / Shortest edge / Exact size / Percent / Max file size, with mode-specific inputs and validation (1–20000 px, 1–100 %, 10–50000 KB). Toggle "Allow upscaling".
3. **Format & quality** — format chips (Keep original / JPEG / PNG / WebP), quality slider (hidden for PNG). Disable PNG when mode is Max file size. Under "Keep original", a line explains "HEIC, AVIF and other formats are saved as JPEG". Toggle "Convert colors to sRGB" (on by default). "Only process these formats" chips (JPEG, PNG, WebP, HEIC, AVIF, other); none selected = all.
4. **File names** — pattern field with token chips to insert, live preview using the first selected image. Conflict policy radio.
5. **Metadata** — Keep all / Remove location / Remove all.

Changes save immediately to settings.

### 9.3 Progress

- Linear progress, "n / total", current file name, elapsed time and ETA.
- Cancel button with confirmation.
- Works when reopened from the notification.

### 9.4 Results

- Totals: succeeded / skipped / failed, size before → after, and percent saved.
- Filterable list (All / Failed / Skipped) with reason per item.
- "Retry failed" → new job with only failed items.
- "Open folder" → `ACTION_VIEW` on the output tree if a handler exists; otherwise hide.

### 9.5 Accessibility and quality bar

- Content descriptions on all icons; touch targets ≥ 48dp.
- Dark mode and dynamic color supported.
- Rotation and process death: state survives via `SavedStateHandle` (selected URIs) and DataStore.

---

## 10. Milestones and tasks

Each task lists **acceptance criteria (AC)**. Agents: mark tasks done in `PROGRESS.md` with a short note.

### M0 — Skeleton
- **T0.1** Create project (Empty Compose Activity), version catalog, package layout §3.2, `AppContainer`.
- **T0.2** Manifest per §3.3, theme, Navigation with four empty screens.
- **AC:** app launches on API 28 and latest API emulators; merged manifest has no INTERNET; `./gradlew lint test` passes.

### M1 — Pure domain logic
- **T1.1** Domain models §4 with serialization.
- **T1.2** `DimensionCalculator` §5.1.
- **T1.3** `FileNamer` (tokens, sanitizing, extension, rename suffixes).
- **T1.4** `QualitySearch` with an injectable `encode(quality, scale) -> Int(bytes)` function so it is unit-testable.
- **T1.5** `FormatResolver` §5.5 and `InputFormat` detection from MIME + extension.
- **AC:** unit tests cover every format mapping (incl. HEIC→JPEG under Keep original, uppercase `.HEIC` extensions, `application/octet-stream` HEIC), every resize mode, upscale on/off, portrait/landscape/square, 1×1 and 20000×20000 inputs, FILL crop rect correctness, all naming tokens, illegal characters, empty names, quality search convergence and fallback scaling. Coverage of `domain/` ≥ 90 %.

### M2 — Single-image engine
- **T2.1** `Decoder` + `Encoder` + `ImageProcessor` §5.2.
- **T2.2** `MetadataCopier` §5.4.
- **T2.3** `OutputWriter` §6.3 (conflict handling, partial-file cleanup).
- **T2.4** Format conversion §5.5: color policy, `FormatSupport` probe, HEIC/AVIF EXIF copy, unsupported-format skip.
- **AC:** instrumented tests with bundled test assets (`androidTest/assets`: JPEG with EXIF orientation 6 + GPS, PNG with alpha, WebP, a 50 MP JPEG, an iPhone HEIC (Display P3, rotated, with GPS), a burst/multi-image HEIC, an AVIF, an animated GIF, a corrupt file) verify output dimensions, orientation, format, alpha handling, GPS stripped/kept, that HEIC→JPEG output is upright, sRGB-tagged, carries the original capture date, and has dimensions equal to the source in Original size mode, and that the corrupt file yields `Failed` without crashing. Peak memory on the 50 MP file stays below 150 MB.

### M3 — Input and permissions
- **T3.1** `InputResolver` for Photo Picker, files, folder (recursive option), share intents.
- **T3.2** `UriPermissionManager` §6.2, output folder selection and validation on startup.
- **AC:** each input route lists correct images; folder with 1,000 files enumerates in < 2 s on emulator; revoked output folder is detected and the user is prompted.

### M4 — Batch engine
- **T4.1** `JobStore`, `ResizeWorker`, `JobScheduler` §7.
- **T4.2** Notifications (progress, cancel action, summary), `POST_NOTIFICATIONS` runtime request.
- **AC:** 300-image batch completes with app in background; cancel stops within one item; killing the process mid-job leaves already-written files and shows partial results; OOM in one item doesn't stop the batch (simulate with a fake processor in tests).

### M5 — UI
- **T5.1** Home §9.1, including the "Convert HEIC to JPEG" quick action and per-format counts.
- **T5.2** Options §9.2 with validation and live name preview.
- **T5.3** Progress §9.3 and Results §9.4 including "Retry failed".
- **AC:** Compose UI tests for: Start disabled without folder/images; invalid inputs show errors; PNG disabled for Max file size; overwrite-originals warning appears when output = input folder; full flow on emulator from picking to results.

### M6 — Presets and settings
- **T6.1** `SettingsRepository`, `PresetRepository`, built-in presets §8.
- **AC:** options and folder persist across restarts; presets create/rename/delete/apply; applying a preset never changes the output folder.

### M7 — Hardening and release
- **T7.1** Polish translations (`values-pl`).
- **T7.2** R8 enabled for release, verify reflection-free serialization works minified.
- **T7.3** Manual test matrix (§11) and fixes.
- **T7.4** Signed release build; `README.md` with build steps.
- **AC:** release APK passes the matrix; size < 10 MB; no INTERNET permission in the release manifest (`aapt dump permissions`).

---

## 11. Manual test matrix

| Case | Expected |
|---|---|
| 500 mixed photos, longest edge 1600 | All succeed, no crash, notification accurate |
| Portrait phone photos (EXIF rotated) | Output upright, orientation tag = 1 |
| 200 iPhone HEICs via "Convert HEIC to JPEG" | All become `.jpg` at original size, colors match the source, capture date kept |
| Camera folder with mixed JPEG + HEIC, filter HEIC only | Only HEIC files processed; JPEGs untouched and not counted as skipped |
| HEIC on a device without HEVC decoder (or probe forced off) | Items skipped with "not supported on this device" |
| PNG with transparency → JPEG | White background, no black areas |
| Max file size 300 KB on 12 MP photo | Output ≤ 300 KB or noted as not reached |
| Output to SD card folder | Works |
| Output folder = input folder, Overwrite | Warning shown; only after confirm are originals replaced |
| Name conflicts with Rename | ` (2)` suffixes, nothing lost |
| Airplane mode | Identical behavior |
| Share 20 images from Gallery app | Selection pre-filled, job runs |
| Low-RAM emulator (2 GB) | Parallelism 1, completes |
| Rotate screen during selection and during job | No state loss |

---

## 12. Rules for agents

1. Follow milestone order; keep the build green.
2. No new dependencies beyond §2 without an entry in `DECISIONS.md`. No networking, analytics or crash-reporting SDKs.
3. Keep `domain/` free of Android imports so it stays unit-testable.
4. Every public function in `engine/`, `storage/`, `work/` gets KDoc stating threading expectations.
5. Never swallow exceptions silently; convert them to `ItemResult.Failed` or a UI error state with a readable message.
6. When a requirement here conflicts with a platform behavior you discover, prefer correctness and data safety, and record the change in `DECISIONS.md`.
