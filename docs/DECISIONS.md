# Implementation decisions

Date: 3 November 2026

## Platform and renderer

- Native Kotlin Android TV app, one Gradle app module. Native Views own the library, focus, import screen, dialogs, and reader overlays. No Compose or dependency injection framework.
- Minimum API 26, compile/target API 35. These are provisional until the actual TV model and OS version are supplied. The repository contains no device inventory or user books; reflowable DRM-free EPUB remains the documented input assumption.
- EPUB.js **0.3.93**, vendored in `app/src/main/assets/reader`, BSD-2-Clause. Original license is bundled. Its CFI locators, pagination, navigation, themes, and images sit behind `ReaderEngine`. Upstream: https://github.com/futurepress/epub.js/tree/v0.3.93 . The npm release is old; this is a provisional engine selection pending the physical-TV compatibility gate, not a claim that that gate has passed.
- Use an unpacked, validated OPF, avoiding JSZip and a second in-memory decompression step. EPUB.js owns pagination and CFIs. Book markup has scripts, event handlers, active embeds, inline CSS, and external styles removed. Publisher formatting is deliberately normalized, as permitted by the spec.
- Literata variable font, bundled under SIL Open Font License 1.1, for offline rendering. Source: https://github.com/google/fonts/tree/main/ofl/literata . Native controls use the platform sans-serif font.
- Six text sizes: 24, 28, 32 (default), 36, 42, 48 CSS pixels. Warm paper / forest ink or dark forest / warm ivory. Reader viewport uses 7% margins and caps column width at 1,180 CSS pixels. Final sizes require couch testing.

## Import and private storage

- NanoHTTPD 2.3.1 (BSD-3-Clause) provides a session-only local HTTP server on an ephemeral port. Browser sends one raw file body, avoiding multipart buffering. QR encoding uses ZXing core 3.5.3 (Apache-2.0). Sanitization uses jsoup 1.18.3 (MIT).
- Pairing: 192-bit SecureRandom token, constant-time comparison, monotonic 10-minute expiry; new session invalidates old token and closes old listener. Stop on screen exit/background. Token never enters app logs.
- Limits: 50 MiB compressed, 200 MiB expanded, 16 MiB per entry, 4,000 entries. Reject duplicate paths, traversal, encoded archive paths, external XML entities, encryption/DRM, right-to-left progression, fixed layouts, remote manifest resources, missing spine resources, and unsupported spine media.
- Keep sanitized resources only in app-private persistent storage. Source upload is staged in private cache and deleted after validation. Content hash is over original upload bytes, so identical imports deduplicate.
- One SQLite database with version 1, unique content hashes, foreign keys, transactional catalog insertion, and cascading position deletion. Finalize directories before catalog insertion; startup reconciliation removes orphan directories and temporary files and drops entries with missing package files. Future schema changes require explicit migrations.
- Locator JSON: `{version:1,format:"epub",cfi,href}`; position writes are queued after each completed relocation, independently per book. They do not rely on an Activity exit callback. Preferences persist globally.

## Explicit gate limitations

The documents require a renderer spike on the actual TV before finalizing compatibility. Hardware is unavailable, so implementation proceeds provisionally with automated fixtures and a buildable APK. The physical-device renderer gate, 20-minute couch session, phone/desktop LAN upload checks, API-26 verification, and measured reopen/page-turn targets remain release blockers. Do not equate a host-browser engine test or successful APK build with passing those checks.


## 5 November 2026 update

- User explicitly requested PDF upload and reading in addition to the mobile and TV UI fixes. Android `PdfRenderer` is the PDF engine; no extra PDF library or WebView scripting is used. PDF pages render on a single worker thread with a bounded 12M-pixel raster; fixed-layout pages fit the column width and scroll vertically. Left/Right page turns are sequential; Up/Down scrolls; EPUB behavior is unchanged. PDF controls show the fixed-layout explanation and themes, without pretending that EPUB font resizing or chapters apply. Dark mode inverts the PDF page, including its images.
- Catalog schema 2 adds `format` with default `epub`; an explicit ALTER migration preserves schema-1 books and positions. The legacy database column named `opf` remains for compatibility but is exposed as the format-neutral `contentPath`. PDF content is stored as `book.pdf` in its own atomic private book directory. Original-byte SHA-256 deduplication applies to both formats.
- PDF locator schema 1 stores `{version:1,format:"pdf",page,offset}`. EPUB CFIs keep their existing schema. Offsets are normalized within the current page's scrollable region, not viewport page numbers. Password-protected/malformed PDFs receive an explanatory failure without a catalog entry.
- Pairing tokens rotate atomically on the existing listener every ten minutes while Add book stays visible. Refresh is deferred while receiving a file; leaving/backgrounding still closes the server. Codes and readable URLs update together, and the TV stays awake while waiting. Old mobile pairing pages need a new scan after their token expires.
- Native Literata headings use explicit line heights with font padding/fallback spacing disabled, the empty-state introduction has a weighted column, and its underline is drawn rather than dependent on glyph alignment. SquareQrView enforces equal measured width and height. The original TV QR measured 368 × 368 px; there was no distortion in that capture.
- The upload page uses constrained fluid spacing/type, full-width file input and buttons, minimum-width guards, dynamic viewport-height fallback, and an explicit PDF+EPUB extension/MIME filter. Native phone pickers are controlled by the browser/OS; both registered PDF MIME and extension forms are supplied.


## Fullscreen reading — 5 November 2026 (v0.2.1)

At the user's request, the reading surfaces now occupy the entire display. EPUB uses a 100% width/height viewport with no column-width cap, body padding, or pagination gutter. PDF removes the native outer frame padding and scrollbar. PDF page aspect ratio and the document's own printed margins remain intact. This supersedes the earlier capped reading column/safe-margin choice for book rendering; native controls and overlays retain their existing layout.

## Global settings — 6 November 2026 (v0.3.0)

Settings is a native, opaque full-screen page above the existing reader, retaining its measured viewport and engine. Back returns directly to reading; library access works even with no books. Option rows own Left/Right/OK and save immediately. Preferences schema 2 defaults missing spread/sleep fields to Off/30 minutes, preserving size/theme, and writes with apply(). Theme remains a reader quick action.

EPUB auto spread uses a 900 CSS-pixel threshold and a 40px pagination gap when wide; single-page remains fullscreen without a gutter. The first visible CFI is redisplayed after preferences change. PDF ignores EPUB size/spread.

The inactivity handler releases KEEP_SCREEN_ON after 15/30/60 minutes since the last key, including while reader settings or controls are visible. Never has no timeout; Add book keeps its existing behavior. The timeout does not force power-off or alter the TV's system settings. Backgrounding cancels the handler and clears the flag.

About computes catalog count and private book-file bytes off the main thread. Instrumented tests may be built with -PisolatedTestApp to use dev.couchreader.qa and keep real TV books/preferences separate.

## Settings review fixes — 7 November 2026 (v0.3.1)

EPUB preferences retain a pending `{cfi,href}` passage across consecutive reflows. Relocation events save that passage instead of successively earlier page starts. Explicit page turns and chapter navigation clear it. This supersedes saving the first visible CFI during preference-only reflows; normal navigation still saves the new first visible position.

Text size and sleep options clamp at their endpoints. Both text-size controls show CSS px; spread help refers to wide screens. Missing package version names display Unknown. Back to the library restores focus to Settings. Reader errors during settings are deferred until returning to reading; boundary events cannot open overlays and ready events cannot steal settings focus.

Settings tests use typed module-internal Activity members rather than private-name reflection, and wait on conditions/renderer queue completion. Persistence is verified by polling and parsing the on-disk preferences XML after apply(), not by treating Activity recreation as a process restart.
