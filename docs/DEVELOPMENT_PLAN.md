# Development plan

## Contract

Read `FEATURE_SPEC.md` once before implementation. Build only its MVP. Work through the tasks below in order; update checkboxes and append brief validation evidence to this document. Keep one app module. Add packages and dependencies when needed, not speculative infrastructure. The current app only displays a launch screen.

## Tasks

- [ ] **1. Validate the reading engine.** Confirm TV API level and EPUB availability; use an emulator and legal sample EPUBs while hardware is unavailable. Evaluate a maintained EPUB engine against spec section 5. Record engine/version/license, TV details, and chosen UI toolkit in `docs/DECISIONS.md`. Implement opening a bundled EPUB, remote page turns, font resizing, and locator restoration. Block remote resources and book scripts. Verify EPUB 2/3, chapter boundaries, images, and stable passage restoration. Keep actual couch testing pending until hardware is available.
- [x] **2. Build local persistence.** Add `data`, `reader`, and `ui` packages. Implement BookRepository, PositionRepository, PreferencesRepository, and an EPUB ReaderEngine adapter. Use private files and transactional metadata; persist versioned content locators, never viewport page numbers. Add import finalization/reconciliation and deletion recovery. Verify duplicate hashes, independent book positions, interrupted writes, and preferences after restart.
- [ ] **3. Complete the reader.** Implement spec F3–F5: single-column pages, six sizes, two themes, chapter list, controls overlay, boundary messages, automatic position saving, and fallback restoration. Route remote input through reader actions; ignore held repeats and prevent overlay navigation from turning pages. Verify focus, chapter transitions, resizing, process restart, and offline reading.
- [ ] **4. Build the library.** Implement F2: empty state, recent-first grid, Continue reading, focus restoration, and confirmed deletion. Connect actual stored books; remove the scaffold screen. Verify all actions using only D-pad/Select/Back.
- [ ] **5. Add browser upload.** Implement F1 with a temporary local service, QR/URL pairing, 10-minute token lifetime, streamed 50 MiB uploads, deduplication, and explicit recovery messages. Choose and document concrete expanded-archive and entry-count limits. Reject unsafe paths, malformed packages, DRM, and fixed layouts. Add network permission only when needed. Shut down on exit/background/expiry. Verify phone and desktop uploads, invalid tokens, expired tokens, interrupted transfer, low storage, and startup cleanup.
- [ ] **6. Verify and deliver.** Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Add meaningful unit tests for validation and persistence, and instrumented tests for engine/lifecycle behavior where necessary. Run `:app:connectedDebugAndroidTest` when a device is attached. Complete spec section 9 on the actual TV, record measured timings and any remaining manual checks, and deliver the APK with installation instructions.

## Architecture rules

- UI coordinates application actions; repositories own persistence; the EPUB adapter owns engine-specific types.
- Keep import transport separate from validation and catalog insertion.
- Maintain stable book IDs and versioned format-specific locators so annotations, speech, and sync can be added later.
- Do not implement future features or create unused tables/services. No accounts, cloud, PDF support, or companion app in this release.
- Preserve the spec's full error handling and acceptance criteria even when a task above summarizes them.

## Completion

All six tasks and the spec's release checklist pass. Report any unperformed hardware checks explicitly; an emulator build alone does not establish reading comfort or TV compatibility.

## Implementation evidence — 3 November 2026

The scaffold has been replaced with the MVP implementation. Task checkboxes above remain release gates, rather than claims of physical-TV validation.

| Task | Implemented | Evidence / remaining gate |
| --- | --- | --- |
| 1. Engine spike | Provisional EPUB.js adapter, bundled Literata, original EPUB 2/3 fixtures | Desktop Chromium verifies chapter navigation, images, forward/backward transitions, both boundaries, all six sizes, both themes, and CFI passage restoration. Actual TV compatibility and couch test pending. See DECISIONS.md. |
| 2. Persistence | SQLite catalog and position repositories, private atomic directory finalization, startup reconciliation, global preferences | Host-side Robolectric tests exercise reopen, deduplication, independent positions, deletion, failed import, and reconciliation. Device process-death and low-storage behavior still require manual checks. |
| 3. Reader | Single column, sequential turns, repeat suppression, controls, chapters, six sizes, themes, versioned CFIs, restore fallback | Desktop engine suites pass. Instrumented WebView suite compiles; physical remote/focus/lifecycle verification pending. |
| 4. Library | Empty state, recent-first three-column grid, continue action, selected-book focus restoration, confirmed deletion | Compiles and passes lint. Native D-pad usability requires device verification. Cover fallback is generated locally; embedded cover extraction remains optional. |
| 5. Browser upload | Session-only server, QR and URL, 192-bit token, 10-minute expiry, streamed file body, strict EPUB validation and limits, duplicate result, retry states | Unit and host HTTP tests. Phone/desktop browser LAN tests and background/expiry lifecycle checks on TV pending. |
| 6. Delivery | Debug APK, test APK, build commands, test helpers, install/use documentation | Physical-TV acceptance checks remain release blockers. |

No deferred formats, cloud services, accounts, annotations, or speech features were added. The engine choice and API level remain provisional because no actual TV information or user EPUB collection was available.

Final host verification: 15 tests passed, zero failures; lint zero errors / 15 warnings; debug and instrumentation APKs built. Both desktop EPUB engine fixture suites passed. Tasks 1 and 3–6 retain unchecked release gates for the device-only acceptance checks.

4 November follow-up: Android TV API 34 ARM64 emulator installed and running. All three instrumented integration tests pass (8.201 seconds). Real paired upload through an ADB-forwarded endpoint, WebView reading, D-pad page turning, and controls were exercised. Physical-TV checks remain pending.


5 November follow-up (v0.2.0): user requested responsive mobile upload, PDF selection/upload/reading, continuously refreshed square QR codes, and landing-screen corrections. These amend the original EPUB-only scope. Native PdfRenderer and format-specific locators are implemented; schema 2 migrates existing EPUB records in place. The mobile form now accepts PDF and EPUB extensions/MIME types. Expired codes renew on the existing listener with synchronized QR/URL updates, and Add book keeps the display awake. The heading column, native line heights, and drawn underline are corrected. Host migration/token tests, native QR decode/renewal/layout and PDF scroll/restore tests were added.
