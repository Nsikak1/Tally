# Tally: project handoff

Read this whole file first. It is the complete state of the project at the end of a very long chat.
Project: **Tally**, a native Android app (Kotlin, Jetpack Compose) for a school library:
scan book barcodes, look books up on a LAN server, search the catalog, and read handwritten accession numbers.

---------------------------------------------------------------------------------------------------

## 0. START HERE (token-saving rules for the assistant)

Latest state: **v0.8.0 app + v0.8 server** (see section 6a for what changed). Sections to read: 0, 1, 4a, 4, 6, 9, 11. Everything else is reference: open it only when the task needs it.
1. Do NOT read the whole code base. Read `docs/CODE_MAP.md` (one line per file), then open only the files the task touches (`sed -n`, `grep -n`). Regenerate the map with `python3 scripts/code_map.py` before zipping.
2. Do NOT redo anything marked DONE or WRITTEN in section 6, do not re-research pinned versions (section 5), do not re-ask anything answered in section 1 or 4. Ask only what is listed in section 9 "Open questions".
3. Edit files in place in ONE working copy (unzip the project once; patch with small `python3` replace scripts that `assert` each match, then re-zip). Never retype a whole file unless it is new or mostly rewritten.
4. Check each change before delivering: `python3 scripts/logic_tests.py` (pure logic ports), `python3 server/test_api.py` and `python3 server/test_online_lookup.py` (need `pip install --break-system-packages fastapi httpx`), a brace-balance scan of the .kt files, and `grep` for removed names. The assistant cannot compile Android.
5. Keep chat replies short (phone): what changed, the numbered commands (where each runs), what to test, known risks. No code listings in chat; the zip carries the files. Add every file DELETED or RENAMED to `docs/REMOVED_FILES.txt` (cumulative).
6. Web-search only for facts not already in this file (new API shapes, versions never pinned). One search, not five.
7. Save durable facts to memory as they appear, never secrets (API keys stay in `/etc/olib-api.env` on the server).

---------------------------------------------------------------------------------------------------

## 1. The person and how to work with them

- Experienced programmer. Wants COMPLETE files (never partial snippets or diffs) and the exact commands
  to run, in NUMBERED STEPS, saying where each file goes and on which machine each command runs
  (laptop / server / Termux / phone). Files are best delivered as a zip of the whole project plus heredoc commands.
- Works milestone by milestone and TESTS each before the next. Ask at most 3 clarifying questions, then build.
- Reads on a phone: lead with the answer, keep prose short, use lists for steps. No filler.
- Be honest about what is verified and what is not. NOTHING in this project has been compiled by the assistant:
  the sandbox has no Android SDK or Gradle download access. The user builds on their server and pastes errors back.
  Say so, and ask for "the first line starting with `e:`" when a build fails.
- Research versions and APIs before pinning them (web search); the knowledge cutoff is behind today's libraries.
  Test pure logic in the sandbox with Python ports where possible (done for ISBN check digits, accession
  normalisation, the model-dictionary converter).
- Sandbox network: github.com, api.github.com, pypi.org, npm are reachable; huggingface.co and Maven Central are NOT.

## 2. Environment

| What | Detail |
|---|---|
| Laptop | HP 15-bs1xx, Ubuntu 26.04, Android Studio (the user's dev machine) |
| Build server | headless Ubuntu 26.04, hostname `school`, user `ils`, IP **192.168.1.117**, `school.local` via Avahi. About 4 GB RAM plus zram and about 5 GB swap. Project lives at `~/dev/Tally`. Builds with `./gradlew --no-daemon assembleRelease`. The FastAPI library server also runs here (port 4321). A Cloudflare Tunnel exposes it as nsikak.name.ng (nginx maps services to URL paths). |
| Phone used for SSH | Android in **Termux on an ARMv7 (32-bit) device**. The release APK therefore targets `armeabi-v7a`. They `scp` zips to the server and `rsync -P --partial` the APK back, then `termux-open` it. |
| Test devices | real tablets/phones (camera needs a real device). Waydroid and the emulator are x86, so use the DEBUG build there (release is armeabi-v7a only). |
| Debug key | must be the same on laptop and server: copy `~/.android/debug.keystore` to both (release is signed with the debug key so it installs over debug builds). |

Server `~/.gradle/gradle.properties` (low RAM): `org.gradle.jvmargs=-Xmx2g` and `kotlin.compiler.execution.strategy=in-process`.
If a build fails with "Could not read workspace metadata ... transforms", the Gradle cache is corrupted (usually an OOM kill):
`rm -rf ~/.gradle/caches/8.11.1/transforms .gradle app/build build` and rebuild.

## 3a. The server source is now in this project (`server/`)
`server/api.py` is a copy of `~/olib/api.py` (FastAPI + SQLite; service `olib-api`, env file `/etc/olib-api.env` holds LIB_API_KEY and GOOGLE_BOOKS_API_KEY; NEVER put keys in the project or in memory notes). Since v0.7.1 the user ALLOWS changes to the server (it was "reference only" before). Update it with `bash ~/dev/Tally/server/install_update.sh` (backup, restart, health check, automatic rollback). The service now listens on **4321** (unit file fixed in v0.7.2; the user must copy it to `/etc/systemd/system/` or run install_update.sh).

## 3. Library server API (UPDATED: the full, authoritative text is `docs/SERVER_BRIEFING.md`)

The old text here (no auth, no holdings) was wrong. Summary of the real server: base `http://192.168.1.117:4321` (or `school.local`, or any https tunnel),
15 s read timeout. Reads are open; POST/PATCH/DELETE on `/holdings` need header `X-API-Key` (entered in Settings).
Endpoints used so far: `/health`, `/scan/{isbn}` (book + the library's copies in one call), `/isbn/{isbn}`, `/holdings` (list/create/get/patch/delete).
Still to use: `/search`, `/work/{id}`, `/same/{a}/{b}`. There is NO date filter on `/holdings`: the app pages newest-first until it passes the period start.
NEW in v0.7.0: `GET /lookup/{isbn}` comes from `server/online_lookup.py` (the user installs it on the server: see server/README.md). Until it is installed the app shows "online lookup (not installed on the server)" and everything else works.
Avahi advertises `_library._tcp` port 4321 (file: `server/library.service`).

## 4a. Update workflow (decided in v0.7.0)
- ONE project folder on the server (`~/dev/Tally`), never recreated. Update = `cd ~/dev && unzip -oq Tally-vX.zip` (overwrites changed files, leaves `build/`, `.gradle/` and `app/src/main/assets/ppocr/` alone), then delete the files listed in `docs/REMOVED_FILES.txt` (the zip cannot delete): `cd ~/dev/Tally && xargs -a docs/REMOVED_FILES.txt -r rm -f --`. ALWAYS keep REMOVED_FILES.txt cumulative and update it whenever a source file is deleted or renamed.
- `bash scripts/build.sh` builds and copies the APK to `dist/Tally.apk` + sha256. The phone pulls it with `rsync -P --partial --stats ils@ssh.nsikak.name.ng:~/dev/Tally/dist/Tally.apk ~/storage/downloads/Tally.apk`: rsync sends only the parts that differ from the old `Tally.apk` already on the phone. Do NOT xz the APK (it defeats the delta). Termux reaches the server as `ils@ssh.nsikak.name.ng`.

## 4. Fixed decisions

- Package/applicationId `ng.name.nsikak.libaccession`; app name **Tally**; **minSdk 25** (v0.7.2). NO `java.time`, no API 26+ calls (the user chose to avoid desugaring); legacy icon in `mipmap-anydpi`; compileSdk/targetSdk 35; JDK 17 bytecode.
- Architecture: MVVM, single Activity, Navigation Compose, coroutines/Flow, Material 3, hand-rolled DI (`AppContainer` in `TallyApp.kt`),
  one `AppViewModelProvider.Factory` (add one `initializer` per ViewModel), Gradle version catalog, minimal dependencies.
- Cleartext HTTP only for `192.168.1.117` and `school.local`: listed in BOTH `res/xml/network_security_config.xml` and
  `CleartextPolicy.ALLOWED_HOSTS` (ServerConfig.kt). Any `https://` address works with no entry (used for localhost.run / cloudflared test tunnels).
- ISBN rule: accept only EAN-13 starting 978/979 with a valid check digit, and valid ISBN-10 (converted to ISBN-13).
- Accession number: formats are SET IN SETTINGS, one per line (X = digit, @ = letter, X+ = digits, rest literal); default `ILS-XXXX`.
  The user said there are TWO formats but has only shown `ILS-XXXX` (open question). `domain/AccessionFormat.kt` fits OCR text to each format (O->0 in digit slots, 1->I in letter slots).
- Every confirmed OCR crop is saved with its corrected label (training data for a custom digit model later):
  `<app external files>/ocr_samples/` (`crop_*.png` + `labels.csv`), shareable as a zip from the app.
- Smallest download wanted: release build with R8 + resource shrinking, `armeabi-v7a` only, `localeFilters en`,
  compressed native libs; transfer as `.apk.xz`, resumable, verified with sha256.

## 5. Pinned versions (all researched, none compiled here)

AGP 8.9.1, Gradle 8.11.1, Kotlin 2.1.20, Compose BOM 2025.04.01, lifecycle 2.8.7, navigation-compose 2.8.9, activity-compose 1.10.1,
DataStore 1.1.4, Retrofit **3.0.0** with `com.squareup.retrofit2:converter-kotlinx-serialization` 3.0.0, OkHttp 4.12.0,
kotlinx-serialization-json 1.8.1, coroutines 1.10.2, CameraX **1.4.2** (core, camera2, lifecycle, view, mlkit-vision),
ML Kit barcode-scanning 17.3.0 (bundled), ML Kit text-recognition 16.0.1 (bundled Latin; now only used to find text lines),
ONNX Runtime Android 1.26.0 (a newer 1.31.1 exists on GitHub; not verified on Maven). KSP 2.1.20-2.0.1 and Room 2.8.4 (added in v0.5.0; Room 2.8.5 and a Room 3.0 with different package `androidx.room3` exist, deliberately not used). Cover images use a small own loader (OkHttp + BitmapFactory + LruCache), not Coil.
Known API fact: in CameraX 1.4 the constant is `ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED` (not `CameraController.`).

## 6. Status (what exists; DO NOT REDO)

DONE and TESTED by the user on a device (milestones 1-2): setup, Settings (server address, mDNS detect, test connection), Home; scanner with boxes, tap-select, tap-to-focus.

WRITTEN (v0.5.0 to v0.7.1), compiled by the user at least once up to some version but NOT functionally tested, except the one crash noted below:
- Scanner rework (smoothing, own auto-zoom, pinch, torch, HD, type-an-ISBN), portrait/landscape layout, PaddleOCR PP-OCRv6 reader (small + medium, chosen in Settings; ML Kit fallback), ISBN / accession / any-text reading modes, saved training crops.
- ISBN lookup chain: server `/scan` -> saved copy (Room v2, `source` column) -> server `/lookup/{isbn}` (Google Books, Open Library, Library of Congress, BnF, DNB, Crossref; all asked together). Book preview with cover and "copies we own".
- Double-tap on camera (barcode: read the square under the finger, zoom to the Settings level, focus; text: focus, capture, box pre-placed).
- Guided camera fill (ISBN -> title -> author -> accession) from the scanner when nobody knows the ISBN and from the copy form.
- Library list (Today/3/7/30/All, search, status) + add/edit/delete a copy (PATCH sends only changed fields); API key, double-tap zoom, model size, accession formats in Settings.
- Server (`server/`): `api.py` (+ `/lookup`, richer `/health`, cache-only autofill), `online_lookup.py`, `olib-api.service`, `install_update.sh` (backup, restart, health check, auto rollback). Tested offline only; the Google Books key works (user ran curl: HTTP 200).
- Update workflow (section 4a) and `scripts/build.sh`, delta download by rsync.

KNOWN PROBLEMS reported by the user:
- The Library list crash: `java.time` removed in v0.7.2 (Period.kt now uses Calendar/SimpleDateFormat). Cause was never confirmed by logcat: ask the user to retest.
- Google Books gave one transient HTTP 503 (Google side); retries returned 200. Not a bug. Optional: retry once on 503 in `online_lookup.google_books`.
- Port is 4321 (confirmed by the user); unit file updated.

NOT STARTED: milestone 4 (search screen: `/search`, `/work/{id}`), milestone 5 (same-book check `/same`), wiring a confirmed accession into the add-a-copy form beyond the guided fill, unit tests for Kotlin, cover thumbnails in the list, "changed in period" filter.

## 6a. v0.7.2 changes (untested on a device)
- minSdk 25; `java.time` removed; legacy launcher icon `mipmap-anydpi/`; service port 4321.
- Accession box autocorrect: `AccessionFormat.autocorrect` (typed: upper-case, fixed part of the format written for you, "ils0123" -> "ILS-0123", "123" -> "ILS-123", no trailing dash so Backspace works). Used by the copy form (`correctAccessionInput`) and the OCR review box; OCR `normalize` also adds the prefix when only the number was read. Python port + tests in `scripts/logic_tests.py`.
- Add-copies flow: ISBN scanned or typed -> after the lookup answers (+700 ms) the camera switches to the ACCESSION step (book not in any catalog: ACCESSION, TITLE, AUTHOR) -> confirm -> copy form opens prefilled (title/author from the lookup) -> Save -> back to the scanner, cleared ("Saved ILS-0123. Next book:"). Switch on the scanner panel turns this off (`SettingsStore.autoAccession`, default on). Logic: `ScannerViewModel.autoAdvance / resetForNextBook`, result handed back through `savedStateHandle["copy_saved"]` (AppNav).

## 6b. v0.8 (server + filler; app/web untouched)
- Library DB: new columns on `holdings` (subtitle, publisher, pub_date, edition, language, country, pages, format, dewey, description, list_price, purchase_price, currency, info_sources) and table `holding_tags`; added by `migrate()` on every start (add-only, old data kept; `install_update.sh` first copies library.db into the backup folder). API: see SERVER_BRIEFING.md ("DETAIL FIELDS", `GET /tags`, filters `tag`, `language`, `publisher`). Tests: `server/test_details.py` (+ `test_api.py` unchanged and passing).
- The Android app ignores unknown JSON keys (ApiFactory `ignoreUnknownKeys = true`), so it keeps working; it does NOT show or edit the new fields yet. Same for tally-web.
- tally-fill v2 fills all of these from 9 services; `--probe ISBN` checks them live. Parsers for loc/bnf/dnb/crossref/internet_archive/openlibrary_edition were written from documented shapes, not live answers.

## 6c. v0.8.0 app: typed accession page (NO OCR for the accession number any more in the add-copies flow)
- Scanner (add-copies mode on, or the "Add a copy" button) -> `Routes.addCopy(isbn)` -> `ui/addcopy/AddCopyScreen` + `AddCopyViewModel`:
  1. lookup (server -> online catalogs, `BookRepository.lookup`); server unreachable = message + "Try again".
  2. NOT FOUND: confirm "couldn't be found" and ask "fill in the details by hand?" Yes -> MANUAL (title, author, shelf, note) -> accession page; No -> accession page with only the ISBN.
  3. ACCESSION page: book details on top (`BookPreviewCard` from the lookup, or the typed details), two prefix chips, a digits-only box (`KeyboardType.NumberPassword` + digits filter), focused when the page opens. Save -> `HoldingsRepository.create` -> back to the scanner (`copy_saved` handle) which clears for the next book. "Edit all fields" opens the old copy form instead. 409 = "already in the library".
- Formats (Settings, key `accession_formats_v2`, defaults `ILS-SR-XX-X+` and `ILS-X+`): `domain/AccessionTemplate.kt` turns a digits-only pattern into prefix + layout ("05123" -> "ILS-SR-05-123"; no trailing dash). Each digits-only format in Settings becomes a chip; the last chosen one is remembered (`last_accession_pattern`). Patterns with letters ("@") are not offered there but still validate the copy form.
- OCR is still used for TITLE/AUTHOR/ISBN camera fills started from the copy form or the not-found preview card, and `FillStep.ACCESSION` still exists there; the add-copies flow no longer starts it.
- Verified: `scripts/logic_tests.py` (Python port) AND the real `AccessionTemplate.kt`/`AccessionFormat.kt` compiled with kotlinc 2.4 and run. NOT compiled: the Compose files (no Android SDK here): first build may show small errors.

## 7. How the code is laid out (`app/src/main/java/ng/name/nsikak/libaccession/`)

- `TallyApp.kt` AppContainer: settings, apiFactory, apiProvider, discovery, recognizer (ML Kit text), paddle, samples, books, holdings, covers.
- `data/settings/` ServerConfig (normalised base URL, `parseServerAddress`, `CleartextPolicy`), SettingsStore (DataStore).
- `data/api/` LibraryApi (models + endpoints), ApiFactory (15 s read timeout, X-API-Key on non-GET), ApiProvider (current address+key), ApiErrors (`ApiException`, `apiCall`), ConnectionCheck (`describeError`).
- `data/lookup/` BookProvider, OnlineBookFinder, GoogleBooksProvider, OpenLibraryProvider (parsers are `parseGoogleBooks` / `parseOpenLibrary`). `data/ocr/PaddleRecognizer` is per model size (`installedTiers`, `useModel`).
- `ui/scanner/GuidedFill.kt` (FillStep, GuidedState); guided state lives in ScannerViewModel; OcrViewModel.confirmed emits each confirmed text.
- `data/db/` Room v2 (BookEntity has `source`; destructive migration). `data/repo/` BookRepository (`lookup` -> Found/NotInCatalog/Failed), HoldingsRepository (+ `splitSince`, paging). `data/cover/CoverLoader.kt`.
- `data/discovery/ServerDiscovery.kt` NSD `_library._tcp`, fallback resolve `school.local`.
- `data/ocr/` PaddleRecognizer (ONNX Runtime, CTC decode, restricted alphabet), ImagePreprocess (crop, contrast, Otsu, margins),
  TextRecognizerService (ML Kit lines/text), OcrSampleStore (crops + labels.csv + zip export).
- `domain/` Isbn, AccessionFormat, AutoZoom, TapZoom, TapBox, Period (+ `formatServerTime`), AccessionInput. Python ports of the pure logic: `scripts/logic_tests.py`.
- `ui/scanner/` ScannerScreen (host, portrait/landscape), CameraPane (camera, overlay, pinch, auto-zoom, torch, shutter), ScannerViewModel (tracking/smoothing, selection).
- `ui/ocr/` OcrViewModel, OcrReviewPane (photo + drag box + result panel), ShareFile. `ui/common/Layout.kt` PanelContainer, OverlayBackButton.
- `ui/common/BookPreview.kt` (BookPreviewCard, CoverImage, Preview), `ui/holdings/` (HoldingsScreen/ViewModel, HoldingEditScreen/ViewModel).
- `ui/home`, `ui/settings`, `ui/nav` (Routes: home, settings, scanner, holdings, holding?accession=&isbn=), `ui/theme`.
- `scripts/fetch_models.sh [tiny|small|medium]` + `yml_to_dict_json.py`: downloads PP-OCRv6 recogniser ONNX + dictionary into `app/src/main/assets/ppocr/` (run on the server before building).
- `server/library.service` Avahi service file. `docs/gen_icon.py` regenerates the tally-marks-as-books launcher icon (shapely + cairosvg).

## 8. PaddleOCR facts (verified from the model cards, not yet exercised on a device)

- Models: Hugging Face `PaddlePaddle/PP-OCRv6_{tiny,small,medium}_rec_onnx`; files `inference.onnx` and `inference.yml` (the character list is inside the yml).
  Sizes about 4.4 / 21 / 73 MB. Paddle's benchmark, handwritten English: medium 67.8, small 61.1, tiny 39.3 (PP-OCRv5 server was 59.6).
- Input `[1,3,48,W]` float32, BGR, `(x/255-0.5)/0.5`, W dynamic (min 320, zero-padded on the right, max 3200). Output `[1,T,C]` probabilities, CTC:
  class 0 = blank, 1..N = dictionary, last = space (small has 18,708 characters and 18,710 classes; tiny's dictionary is different).
- `inference.yml` contains plain `~` entries, so it is parsed literally by the script, not with a YAML loader.
- No Paddle DETECTION model is used: the user draws the box. Multi-line boxes are split with ML Kit's line finder. A possible later upgrade is PP-OCRv6 det in Kotlin.

## 9. Remaining work, in order

Open questions (ask these first, in ONE short message, only the ones still unanswered):
1. What is the user's device API level / is minSdk 25 intended? If so: set `minSdk = 25` in `app/build.gradle.kts` AND enable core library desugaring (`compileOptions { isCoreLibraryDesugaringEnabled = true }` + `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:<look up current>")`) so `java.time` works; get `adb logcat -b crash` text of the Library list crash to confirm first.
2. The second accession number format.
3. Port: 4321 or 8080 for the service (`sudo ss -ltnp | grep -E ':4321|:8080'`); if 4321, change `--port` in `server/olib-api.service` and `/etc/systemd/system/olib-api.service`.

Then, in order:
0. Triage the user's build/test results for v0.7.x (their first `e:` line, crash logs). Fix before adding features.
1. Fix the Library list crash (see question 1). Re-check every `java.time`, `String.format`, `Locale`, other API-26+ call with `grep -rn "java.time\|Instant\|ZoneId" app/src`.
2. Milestone 4: search screen with 250 ms debounce, separate title/author fields, results -> work detail -> editions list; works with 0 editions can be saved as a holding with `work` only. Reuse BookRepository / HoldingsRepository.
3. Milestone 5: same-book check (same `work`, else `/same`; confirm dialog on "maybe").
4. Optional polish: retry once on Google 503; cover thumbnails in the list; "changed in period" filter; Kotlin unit tests; accession reading tuning from the user's saved crops.

## 10. Checks the user should run on the latest build (and report)

v0.7.0 additions (run after the v0.5.0 checks below):
A. Settings > Handwriting reader shows small and medium; Save; text mode "Reader:" names the chosen model.
B. Server add-on installed: `python3 online_lookup.py <ISBN the server lacks>` on the server shows which catalogs answered. In the app, scanning that ISBN shows details with "Details from <catalog>". Without the add-on: "not installed on the server".
C. On "Not found in any catalog": "Fill the details with the camera" -> title, author, accession (double-tap the text each time) -> the copy form opens filled.
D. Copy form: "Fill the empty fields with the camera" returns the values. ISBN step: barcode, or Text mode (ISBN reader).
E. Settings > Accession number formats: default ILS-XXXX. Add a second line (e.g. LIB-X+), Save; a handwritten "IL5-O123" is corrected to ILS-0123.
Open question for the user: they said the accession numbers have TWO formats but showed one (ILS-XXXX).

(v0.5.0 checks:)

1. Build passes (first `e:` line otherwise). First build after this update downloads KSP + Room.
2. Settings: enter the API key, set the double-tap zoom, Save. Home -> Test connection still passes.
3. Scan a book: its title, author, cover (needs internet), year/language/Dewey and "copies in the library" appear by themselves; the box label turns into the title.
4. Double-tap a small barcode: the spot is read, the camera zooms (to the Settings level, less near the edge), focus lands there; double-tap again zooms out. Text mode: double-tap writing -> photo with a box already around it.
5. "Add a copy" -> accession typed -> Save. Library list (Today) shows it; tap to edit shelf/status/note; Save changes; Delete works.
6. Wrong or missing key -> "The server refused the API key" message. Server off -> the saved copy of a scanned book still shows, marked offline.
7. Older checks still open: barcode boxes steady, auto-zoom, pinch, PaddleOCR reading quality, tunnel https address.

## 11. Pitfalls already hit (do not repeat)

- Plain `sh` does not expand `{a,b}`; use `bash` or write directories separately.
- Do not ship a zip that overwrites `app/build.gradle.kts` with an older version: the release config (R8, `armeabi-v7a`, packaging) lives there.
- Keep `network_security_config.xml` and `CleartextPolicy` in sync.
- `PreviewView` crops to fill, so clip its container (`clipToBounds`) or it draws over neighbouring controls.
- Preview + analysis + capture together overloads LEGACY-level tablets: only one of analysis/capture is enabled at a time.
- Compose `Rect` coordinates from MlKitAnalyzer view-referenced mode are PreviewView pixels: the overlay must have exactly the PreviewView's bounds.
- minSdk is 25: never use `java.time`, `java.util.Base64`, `Files`/`Path`, `readAllBytes`, adaptive-icon-only resources, etc.
- `ProtectSystem=strict` + `ReadWritePaths=/home/ils/olib/data`: the server may write ONLY there (the lookup cache lives there). New files the service writes must go to `data/`.
- A zip cannot delete files: list every removed file in `docs/REMOVED_FILES.txt` or stale classes break the build after an overlay unzip.
- Never put API keys in files, the zip, chat summaries or memory. Server keys live in `/etc/olib-api.env` (mode 600). The user's Google key was pasted in chat once: advise restricting it to the Books API.
- The Library of Congress catalogue needs outbound port 210; the network may block it (shown as "unreachable", the others still work).
- Do not xz the APK: it defeats rsync's delta transfer.
