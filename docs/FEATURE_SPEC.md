# Couch Reader product specification

Status: Implemented product scope; physical-TV release acceptance remains open. This specification describes intended behavior, while `docs/DEVELOPMENT_PLAN.md` summarizes what exists in this checkout and `docs/VALIDATION.md` records dated evidence.

## Goal and supported content

Couch Reader is a native Android TV reader for one person's DRM-free, left-to-right reflowable EPUB 2/3 books and unlocked PDFs. Books are imported from a phone or computer on the same trusted home network, stored privately, and read offline using the TV remote.

The app uses minimum Android API 26 and targets API 35. The native UI is built in Kotlin Views. EPUB rendering uses bundled EPUB.js 0.3.93 inside a WebView; PDFs use Android `PdfRenderer`. These choices are implemented, but compatibility and reading comfort on the target physical TV remain release checks.

Unsupported content includes DRM/encrypted or right-to-left EPUBs, fixed-layout EPUBs, EPUBs requiring remote resources, malformed books, and password-protected PDFs. The app does not provide accounts, cloud services, public internet hosting, or DRM access.

## Import and pairing

1. From the TV, open **Add book** and scan the QR code or open the displayed local address.
2. A direct QR token or six-digit TV code pairs the browser. Code pairing and token pairing establish a short-lived browser session cookie; tokens and sessions expire after ten minutes. Five incorrect code attempts cause a server-wide 30-second cooldown, including across code regeneration.
3. Select one or more EPUB/PDF files. The browser sends them sequentially and shows a result for each file.
4. The TV reports arrivals and offers **Read now** for the newest book.

Each file is limited to 50 MiB. Uploads stream to temporary storage, are validated before catalog insertion, and use original-byte hashes for deduplication. EPUB expanded content is limited to 200 MiB, 16 MiB per entry, and 4,000 entries. Unsafe paths, XML entities, encryption/DRM, unsupported layouts, and remote EPUB resources are rejected. PDF input must be valid, readable, unlocked, and within the 10,000-page limit.

The upload server exists only while Add book is open, stops when the app backgrounds, and rotates pairing credentials while the screen remains open. It uses local HTTP and is intended only for a trusted home network; it does not encrypt book contents. Cross-origin browser requests are rejected. Networks that isolate clients may prevent upload.

## Library

- Show a cover-first grid, title, author, and reading progress. Recently opened books sort first; unopened books follow import order.
- **Continue reading** opens the most recently opened book.
- In an empty library, **Add book** is the primary action.
- Each book is one focus target. OK opens it; long OK or Menu opens actions. Removal requires confirmation and deletes that book's files, position, cache, and derived progress without affecting other books or global settings.
- Restore the previously selected book when returning to the library.

## Reading

### EPUB

- Open into a distraction-free reader. Left/Right turns pages; OK opens controls; Back returns to the library. Up/Down does not move the reading position except while read-aloud sentence selection is active. Held page-turn repeats are suppressed.
- Render reflowable text, headings, emphasis, lists, and local inline images. Scripts and remote loads are blocked, and publisher formatting is normalized.
- Controls expose text sizes 14, 16, 18, 20, 22, 24, 28, 32, 36, 42, and 48 CSS px (default 16); warm-light and black dark themes; optional two-page spread; chapters; reading progress; and read-aloud.
- Chapter navigation uses the book navigation data, with spine-section fallbacks. Boundaries retain the current page and show a brief message.
- When available, the end-of-book screen offers **Back to library** and **Read again**.

### PDF

- Keep the document's fixed page layout. Left/Right turns pages; Up/Down scrolls within a page. Save the page and normalized scroll offset.
- Controls show page/progress, **Go to page** using a fixed-size numeric page dial, theme, and read-aloud. EPUB text size, chapters, and two-page spread do not apply.
- Dark theme inverts the rendered page. Scanned pages without extractable text cannot be read aloud.

### Shared behavior

- Save a versioned, format-specific locator after completed navigation. EPUBs save CFIs; PDFs save page and scroll offset. Never use a reflowed viewport page number as an EPUB locator.
- Restore the same passage/page after reopening. On failed EPUB restoration, fall back to the containing section or book start and explain the fallback.
- EPUB chapter/percentage progress uses a versioned, content-hash-bound location cache generated in the background. It is derived display data, separate from the resume locator. An uncached book remains readable while indexing proceeds.
- Controls and dialogs must remain reachable with D-pad/OK/Back, show clear focus, block navigation to content underneath, and restore focus when dismissed.

## Read aloud and settings

Read aloud uses installed offline TV text-to-speech voices and works only while a book is on screen. It starts from the first sentence on the current page when the voice loads; Play/Pause can start it directly. Up/Down selects sentences while active, OK reads from the selection, Left/Right turns pages, and Back stops read-aloud before a second Back leaves the reader. Spoken sentences are highlighted; pages turn or scroll as needed. Text-free pages are skipped, with a pause after five consecutive pages without text. Audio focus loss pauses speech. No background service or cloud voice is used.

Settings are available from the library and reader controls. They include global EPUB text size, EPUB spread, inactivity timeout (15/30/60 minutes/Never; default 30), read-aloud speed (0.75×–2×; default 1×), and installed offline voice selection. Theme stays in reader quick controls. Changes persist immediately. Add book keeps the display awake; in the reader, spoken sentences count as activity. The timeout releases the app's keep-screen-on flag and leaves power behavior to Android/TV settings.

About shows package version, catalog count, and private book storage. Returning from settings opened in the reader retains the reader engine and passage.

## Data and architecture

One Android app module contains native UI, catalog/storage, import validation, EPUB and PDF reader engines, and preferences. Book files and caches stay in app-private storage. Database writes and file finalization must preserve catalog consistency; startup reconciliation removes abandoned staging/orphan data. Schema migrations preserve existing books and positions.

Repositories own catalog, positions, progress, and preferences. Import transport is separate from validation and catalog insertion. Locators and reader capabilities remain format-specific. Imported EPUBs are untrusted input.

## Out of scope

Accounts, profiles, sync, cloud backup, public hosting, DRM/Kindle integration, stores, subscriptions, annotations, dictionary, translation, search, reading statistics, phone remote controls, and app-store release work. PDF outlines are not implemented; the page dial is the current navigation method.

## Release acceptance

Automated tests cover host persistence/import behavior and instrumented Android flows. Release readiness additionally requires evidence on the target physical TV:

- Record TV model, Android version, display/resolution, WebView version, and remote type.
- Use EPUB 2/3 and representative user EPUB/PDF books; verify remote-only reading, focus, import over the real LAN, offline behavior, settings, progress/resume, deletion, and lifecycle recovery.
- Complete a 20-minute couch reading session and measure representative reopen time (target ≤3 seconds) and page-turn P95 (target ≤200 ms).
- Record failures and any unperformed checks in `docs/VALIDATION.md`. Emulator/build success does not count as physical-TV evidence.
