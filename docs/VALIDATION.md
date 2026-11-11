# Validation — 3 November 2026

## Automated checks

Build baseline: JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20, Android SDK 35, minimum API 26. Gradle distribution checksum verified against the official downloads.gradle.org SHA-256. The wrapper now uses the direct distribution URL and a longer timeout.

- Importer unit suite: 10 tests, passed. EPUB 2/3 metadata, identical-byte hashes, sanitizer, traversal, DRM, fixed layout, external entities, missing resources, expanded-entry limit, entry-count limit.
- Host integration suite: 5 tests, passed; total host tests: 15 passed, zero failures. Robolectric API 28. Catalog/locator/preference reopen, independent positions, cascade deletion, failed-import isolation, reconciliation, locator schemas, and real local HTTP uploads/token expiry. See generated Gradle test report for final counts.
- Final clean build passed `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` using the verified Gradle 8.11.1 distribution. Android app and instrumentation APKs compile. Android Lint reports zero errors and 15 warnings; warnings cover intentionally synchronous preference commits, the provisional target API, TV orientation/banner conventions, and English-only UI strings.
- Desktop Chromium engine regression suites passed separately for original synthetic EPUB 2 and EPUB 3 fixtures processed through the production Kotlin validator. Both verify two navigation entries, a successfully decoded local inline image, forward/backward page movement, first/last boundaries, six sizes, both themes, passage containment on each resize, chapter boundary traversal without repeating/skipping the chapter opening, and restoration at a different size after renderer reconstruction.

Desktop engine testing uses a loopback origin in place of the Android adapter's intercepted virtual HTTPS origin. It does not prove Android WebView integration.

## Failed checks and corrections

- Initial wrapper distribution request timed out; downloaded and SHA-256-verified the same Gradle 8.11.1 distribution from the official direct endpoint. Increased wrapper timeout and enabled URL/checksum verification. The Java wrapper downloader still timed out in this environment; equivalent checks were run with the verified downloaded distribution, and the redundant wrapper retry was stopped.
- First Kotlin compile found a trailing-lambda parameter order error in UploadServer; corrected.
- Engine image check found that shell CSP `base-uri 'none'` blocked EPUB.js's local section base. Sanitized publications cannot supply base elements; changed the shell policy to permit its own origin. Confirmed `naturalWidth == 1` for the fixture image.
- XML validation rejects DTD/entity declarations before parsing and supports UTF-8/UTF-16 explicitly. Unsupported platform-specific XML security switches are optional defense in depth, so Android parsers can still import safely.
- Preloaded the bundled typeface before initial layout to reduce font-related pagination shifts.
- Raw HTTP test client originally read until socket EOF, which can reset after an early rejected upload. Corrected the client to parse and honor Content-Length; expected rejection statuses were preserved.
- An incremental instrumentation build encountered stale `* 2.dex` duplicate generated files. Rebuild from clean output rather than altering source or suppressing duplicate-class checks.

## Device verification not performed

`adb devices` showed no attached device. The existing AVD referenced a missing Android 33 image. An isolated AVD using the only installed image (Wear OS/API 35, unsuitable as TV compatibility evidence) could not start: approximately 7.0 GiB free versus 7.2 GiB required for its data partition. No user files or installed AVDs were removed to make room. Instrumented tests were compiled, not executed. A desktop browser is not a replacement for the required couch session.

Before calling this release-ready, record and verify:

- Actual TV manufacturer/model, Android/API version, display resolution/density, WebView version, and remote type.
- User books are reflowable, DRM-free left-to-right EPUBs.
- Android WebView intercepts local OPF/resources, blocks external resources/scripts, displays inline images, and restores CFI passages.
- Phone and desktop browser uploads over the real LAN; invalid/expired tokens, regeneration, interruption, storage exhaustion, duplicate uploads, and server shutdown on exit/background/expiry.
- Entire native UI using only D-pad, OK, and Back, including focus restoration, long book titles, long error text, chapter scrolling, and confirmation dialogs.
- Sleep, force-stop, app restart, viewport change, offline reading, deletion and reimport. All preferences and independent reading positions survive as specified.
- Twenty minutes reading from the normal seat; no clipping, unsafe margins, focus loss, skipped passages, or crashes. Measure page-turn P95 (target <=200 ms) and representative-book reopen (target <=3 seconds), and record hardware/fixture details.

Performance numbers and physical comfort are intentionally not claimed.

## Android TV emulator verification — 4 November 2026

The prior emulator limitation is resolved. Installed the official Android TV API 34 ARM64 revision 3 image and created CouchReaderTV on this Apple M2 Pro Mac. The system image is stored sparsely; temporary download archives were removed. The emulator boots in a visible window at 1920×1080. WebView is com.google.android.webview 113.0.5672.136.

- Debug APK installed successfully and MainActivity is the resumed activity; no AndroidRuntime startup crash.
- Uploaded the original synthetic EPUB 3 fixture through the real token-paired HTTP endpoint, using an ADB port forward from this Mac: HTTP 200, book added.
- Confirmed actual Android WebView chapter rendering, local inline image loading, remote next-page input, and the native reader-controls overlay. Captures are in `outputs/emulator/`.
- Ran the built instrumentation APK directly through AndroidJUnitRunner: **OK (3 tests)**, 8.201 seconds. This exercises EPUB 2/3 WebView opening, page turns, resizing, locator restoration and chapter jumps; SQLite catalog/position/preference persistence, deduplication/deletion and reconciliation; and live/invalid/expired token rejection and stopped listener behavior.
- Added and exercised `python3 scripts/run_emulator.py` for future local launches. The image and AVD remain installed.

These checks replace the initial build's unperformed emulator checks. Physical TV model/API compatibility, 20-minute couch comfort, phone/desktop uploads over a real LAN, overscan/focus behavior on the actual remote, and measured physical-device performance remain pending. The first empty-library screen also shows clipped introductory copy at this emulator configuration; Add book and the reader remain reachable.


## v0.2.0 UI, pairing and PDF update — 5 November 2026

- Final build: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` passed. **17 host tests**, zero failures; lint **zero errors / 18 warnings**. Added migration-preservation, PDF locator and old/new pairing-token checks.
- Final APK installed on isolated Android TV API 34 emulator; AndroidJUnitRunner reports **OK (5 tests)**, 7.176 seconds. Tests cover EPUBs, native PDF import/validation/deduplication/page turns/scroll restoration, and the actual native landing bounds and QR expiry/renewal path. The QR test decodes the newly displayed bitmap and verifies the old token gets HTTP 403 while the new one gets HTTP 200, without waiting ten minutes or exposing a production test endpoint.
- Original QR on the physical OnePlus TV measured **368 × 368 px**; the captured image was already square. SquareQrView now explicitly maintains equal measured dimensions when constrained. The screen stays awake while pairing and renews the code/URL in place every ten minutes. Background/exit still stops the listener.
- Upload page tested in an isolated browser at 320, 390, 768 and 1280 CSS px; document width equals viewport width at each size. At 320 px the file input fits its form and the submit target is 52 px tall. Native OS phone-picker UI itself was not driven; the HTML accept list explicitly includes `.pdf`, `.epub`, `application/pdf`, and `application/epub+zip`.
- Browser test at 390 px selected a synthetic two-page PDF and submitted it to the emulator's real paired endpoint via ADB forwarding: HTTP 200 / Book added. Opened it in the actual app and exercised a remote page turn. EPUB and PDF validation remain independent and preserve format-specific locators.
- Installed v0.2.0 on the connected OnePlus TV at 192.168.1.55 (Android API 30). Package version 2 / 0.2.0 confirmed, and catalog schema 2 with its new default-EPUB format column was confirmed. The initial automatic launch did not immediately reach the foreground; an explicit waited activity launch brought the app forward. No AndroidRuntime crash was recorded. TV library is empty; no user books or reading positions were deleted or test suites run on the physical TV.
- Physical TV screenshot confirms the complete two-line introduction, corrected line spacing, and centered drawn underline. Screenshots are in `outputs/emulator/tv-landing-fixed.png`, `tv-qr-fixed.png`, `mobile-320.png`, `mobile-390.png`, and `pdf-reading.png`.

Scope notes: PDFs retain fixed pages and scroll vertically; EPUB font-size and chapter controls do not apply to PDF pages. Password-protected PDFs are rejected. Dark theme inverts the rendered PDF page including images. The original actual-TV couch legibility/performance session and native phone-picker/device-specific behavior still require user testing with their own books.

Final live-TV check: fetched the upload HTML directly from the OnePlus TV over its LAN endpoint. It byte-matches the HTML exercised at mobile viewport sizes, includes the PDF+EPUB accept filter, and the updated QR view measures 368 × 368 px. Left the physical TV on Add book with a fresh automatically renewing code. No synthetic books were added to the physical TV.


## Fullscreen reading — v0.2.1, 5 November 2026

Removed the EPUB outer 86% viewport, 1180px cap, theme body padding and internal pagination gutter. Removed the native PDF frame padding and scrollbar. The book surface now covers the display; PDF page proportions and its own printed content margins are preserved. No database or locator schema changes.

Build, lint and 17 host tests passed. The final APK's bundled EPUB CSS/JS were checked for the full-size viewport, zero body padding and zero gutter. All five Android integration tests passed in 8.443 seconds, including EPUB/PDF page turns and location restoration. A synthetic PDF was opened and captured in the emulator to confirm the surrounding frame is gone. Installed v0.2.1 / version code 3 on the connected OnePlus TV and confirmed the installed version.

## v0.3.0 settings — 6 November 2026

- Final main-package build, Android Lint, and all 18 host tests passed. Lint: zero errors, 20 warnings.
- The local emulator could not start because of insufficient disk space. Built the same source with `-PisolatedTestApp` (dev.couchreader.qa), installed it separately on the connected OnePlus Android TV, and ran all seven instrumented tests successfully (26.303s). The main app library/preferences were not used by this suite.
- After the final focus improvements, reran both SettingsIntegrationTest cases successfully (12.241s). They cover option D-pad input, immediate saved values, Activity recreation, exact About book count, Back to the same reader engine, CFI containment after text/spread changes, divisor=2/gap=40, and timer flag release followed by key reset. Timeout release is exercised by invoking its Runnable; no 30-minute wall-clock/system-sleep test is claimed.
- Visually inspected actual-TV captures `outputs/emulator/settings-tv.png` and `settings-about-tv.png`. Native rows fit, descriptions remain visible, About scrolls into view with focus, and the 5% safe margin is retained.
- Delivered `dist/CouchReader-0.3.0-debug.apk` with SHA-256 manifest. Installed main app v0.3.0/versionCode 4 using `adb install -r`, preserving its existing private data, and launched it. Removed both temporary QA packages afterward.

## v0.3.1 review fixes — 7 November 2026

- Addressed repeated-reflow passage drift in both rendering and persisted relocations; size/sleep endpoint wrapping; reader overlay events during settings; library Settings focus restoration; matching text-size labels, wide-screen help and version fallback.
- Host suite: 18 tests passed, zero failures/errors. Android Lint: zero errors. Used the installed Gradle 8.11.1 distribution under /private/tmp/couch-gradle because the wrapper download remains unreliable; this does not claim a wrapper fix. Used in-process Kotlin compilation after a compiler-daemon failure during concurrent builds.
- Isolated dev.couchreader.qa installation on the connected Android TV: all seven instrumented tests passed in 27.536s. Repeated size changes retain the initial visible passage and the exact saved CFI; page navigation clears the pending anchor. Endpoint clamps, deferred synthetic reader errors, focus return and actual preferences XML contents are asserted. Tests use typed Activity access and condition/queue polling instead of private-name reflection and fixed rendering delays.
- The preferences persistence check now reads and parses the disk file after apply(). This verifies disk persistence; it is not a claim of killing and restarting the process. The earlier Activity-recreation check alone was insufficient for that claim.
- Main-package v0.3.1 APK build passed; delivered dist/CouchReader-0.3.1-debug.apk and updated its checksum. Installation uses -r to preserve the main library/preferences; temporary QA packages are removed afterward.

## v0.4.0 chapter/progress — 7 November 2026

- Final main-package build, 19 host tests and Android Lint passed (zero test failures/errors, zero lint errors). Used the installed Gradle 8.11.1 distribution with in-process Kotlin compilation.
- Catalog migration test upgrades schema 2 to 3, preserves the saved EPUB CFI, persists derived chapter/fraction data and verifies cascading deletion. Existing schema-1 migration tests also pass.
- All eight instrumented integration tests passed on the connected OnePlus Android TV in the separate dev.couchreader.qa installation (final run 33.915s). ProgressIntegrationTest verifies background cache creation, matching content hash, chapter/percentage in controls, a library progress bar, stable percentage after reflow, EPUB locators without page numbers, cached reload without regeneration/rewrite, and corrupt-cache recovery. Existing PDF, remote controls, repeated-reflow anchors, settings, pairing and EPUB 2/3 tests also pass.
- An initial device run found that View.post on an unattached index WebView delayed cache completion callbacks. Switching bridge delivery to an explicit main Handler fixed it; the full suite was rerun successfully. Cached reader opens now skip the index worker altogether. Previously opened EPUBs with no derived progress are queued from the library; unopened books show zero without indexing.
- Visually inspected synthetic-book captures outputs/emulator/progress-controls-tv.png and progress-library-tv.png: chapter and 11% progress appear in controls, and the library card shows the matching bar/percentage. Captures wait for animation frames rather than assuming the native view tree has already reached the display compositor.
- Indexing is cooperative background EPUB.js work in a separate book/WebView and yields between sections; these synthetic tests do not claim a measured bound for parsing an unusually large individual XHTML section or a long-book couch performance gate.
- Delivered dist/CouchReader-0.4.0-debug.apk and SHA-256 manifest; main TV installation uses install -r to preserve user data. Temporary QA packages are removed after validation. EPUB resume payloads remain version-1 CFIs; schema 3 adds only derived display data.
## Book titles and authors — 11 November 2026
- Host tests passed, including new BookMetadataTest (9) and PdfMetadataReaderTest (9) cases for generator noise, placeholder names, download-site filenames, UTF-16/UTF-8/PDFDocEncoding strings, compressed object streams, incremental updates, encryption and corrupt input, plus a schema-3 library upgrade test. No instrumented tests were added or run.
- A temporary probe read 16 real PDFs (research papers, IRS forms, Homebrew manuals, app assets): titles and authors matched the documents or fell back to tidy filenames, and IRS routing-code authors were dropped. The slowest, a 3 MB IRS publication, took about 0.6 s on an M2 Mac; TV hardware will be slower.
- On the OnePlus TV, the existing PDF changed from "Understanding_Harness_Engineering" / "Unknown author" to "Understanding Harness Engineering" with no author line on the library card and in the book actions popup.
