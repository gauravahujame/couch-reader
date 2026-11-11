# Couch Reader — MVP feature specification

Status: Ready for implementation planning  
Date: 3 November 2026  
Platform: Android TV / Google TV  
Working name: Couch Reader

## User-requested scope update — 5 November 2026

This update supersedes the EPUB-only/PDF-deferred and manual-pairing-renewal statements below. Upload and reading of **unlocked PDFs** are now included alongside reflowable EPUBs. PDFs use Android's native renderer, retain fixed pages, use Left/Right for page turns and Up/Down for vertical scrolling, and save a versioned PDF page/scroll locator. PDF text does not reflow or use EPUB text-size/chapter controls. Both formats retain the 50 MiB input limit and app-private storage; the catalog migration preserves existing EPUBs and positions.

While Add book remains open, the TV stays awake and automatically replaces each expired ten-minute pairing code in place. Old tokens become invalid, refresh is deferred during an active upload, and the server still stops when leaving the screen or entering the background. The phone page accepts EPUB and PDF files and adapts to mobile widths. Native headings, landing columns, centered illustration mark, and square QR sizing must remain unclipped.

### Fullscreen reading amendment — 5 November 2026

The book viewport fills the display for EPUB and PDF. Remove app-added outer margins, borders, width caps and EPUB pagination gutters. This supersedes the centered capped reading-column requirement for book content; controls retain their safe placement.

## 1. Goal

Let one person comfortably read their own books on a television using only the TV remote after importing a book from a phone or computer.

The core loop is: add book → open book → adjust text → turn pages → return later at the same passage.

The first release succeeds when a user can complete a 20-minute reading session from their normal seat, with comfortable text and predictable page turns, then resume after restarting the app.

## 2. Assumptions and boundaries

- Personal use, one local reader profile, sideloaded installation initially.
- Reflowable, DRM-free EPUB books with left-to-right text are the supported input. Validate EPUB 2 and EPUB 3 fixtures during renderer selection. Fixed-layout EPUBs, PDFs, comics, and DRM-protected books are outside the MVP.
- The phone/computer and TV share a trusted home network during import. No internet connection is needed to read an imported book.
- No account, cloud service, subscription, or companion mobile app is required.
- Target Android version and renderer dependency will be selected after checking the actual TV. They must be recorded before implementation starts.
- “Page” means a viewport of reflowed text, not a publisher's printed page number.

These assumptions define the initial release; unsupported formats must produce an explanatory message rather than an unreadable screen.

## 3. MVP user flows and requirements

### F1. Add a book from another device

1. Select **Add book** on the TV.
2. The TV displays a QR code and a readable local URL containing a short-lived pairing token.
3. Open that address from a phone or computer, choose one EPUB, and upload it.
4. The TV validates and imports the file, then offers **Read now** or **Back to library**.

Requirements:

- Run the local upload service only while the Add book screen is open. Stop it when that screen closes, the app enters the background, or the pairing session expires.
- Pairing expires after 10 minutes. Regenerating it invalidates the previous token. Tokens must be unpredictable and required for upload operations.
- Show instructions and a retry action when no usable local network address is available. A network that blocks device-to-device traffic may prevent import; explain this in the error state.
- Accept a single file per upload, with a 50 MiB compressed-file limit. Stream to temporary storage rather than buffering the entire file in memory.
- Validate the EPUB package and supported layout before committing the import. Apply expanded-size and entry-count limits, reject path traversal, and never execute book scripts or fetch remote book resources.
- Commit the book file and metadata consistently. Failed or interrupted imports must not leave a broken library entry; clean abandoned temporary files on the next launch.
- Calculate a content hash. Reimporting identical content shows **Already in library** and can open the existing book.
- Use embedded title and author when available; fall back to filename and “Unknown author.” Cover art is optional, with a built-in fallback.
- Explain invalid format, unsupported layout, file too large, insufficient storage, and interrupted transfer with a recovery action.

Acceptance:

- An EPUB uploaded from both a phone browser and a desktop browser appears once in the TV library and opens successfully.
- Invalid, oversized, duplicate, and interrupted uploads produce the expected state without corrupting existing books.
- Expired or missing tokens cannot upload. Leaving Add book makes the service unavailable.

Security boundary: local HTTP on a trusted home network is an explicit MVP compromise; the pairing token does not encrypt book contents. Do not expose the upload service to the public internet. Internet-based import requires a separate transport and authentication design.

### F2. Browse a small local library

- Show a remote-navigable grid of cover, title, and author, ordered by most recently opened, with unopened books ordered by import time.
- Provide **Continue reading** for the most recently opened book.
- The empty state has one primary action: **Add book**.
- Restore focus to the book previously selected when returning from reading.
- Offer **Remove from device** in book actions, followed by confirmation. Remove that book's content and reading position; preserve global reading preferences.
- No search, folders, sorting controls, or filters in the MVP.

Acceptance: every book and action is reachable by D-pad; deleting one book leaves other books and positions intact; the empty library remains usable after the final deletion.

### F3. Read with the remote

- Open directly into a distraction-free reading screen. Hide the toolbar during reading.
- Display a single centered text column with a generous safe margin. Reflow content; do not scale a captured book page.
- Support chapter text, headings, emphasis, lists, and proportionally fitted inline images. Exact publisher styling is not a requirement.
- Provide a basic chapter list using the book's navigation data. If unavailable, show spine sections with fallback labels. Selecting a chapter moves to its beginning.
- At the beginning or end of the book, keep the current page and show a brief boundary message.
- At the end, offer **Back to library** and **Read again**. Restarting is an explicit action.

Remote mapping:

| Context | Input | Result |
| --- | --- | --- |
| Reading | Left / Right | Previous / next page |
| Reading | OK / Select | Open reader controls |
| Reading | Up / Down | No reading-position change |
| Reading | Back | Save progress and return to library |
| Controls or chapter list | D-pad | Move visible focus |
| Controls or chapter list | OK / Select | Activate focused item |
| Controls or chapter list | Back | Close overlay and return to reading |

Process page-turn events sequentially. Ignore held-key repeats initially so a long press cannot skip many pages. Moving through controls must never turn the book page.

Acceptance: traverse chapter boundaries forward and backward without skipped or repeated passages; navigate controls and chapters without touch input; no focused item is hidden or unreachable.

### F4. Adjust reading comfort

Reader controls expose only:

- **Text size:** at least six discrete sizes, including sizes suited to reading across a room.
- **Theme:** warm light background with dark text, or dark background with light text.
- **Chapter list.**

Use one bundled readable typeface, fixed comfortable line spacing, and a capped text-column width. Start with a large default and tune it on the actual TV. Font families, line-spacing controls, column controls, animation controls, and brightness controls are deferred.

Preferences apply immediately, persist across app restarts, and are global across books. On a text-size change, preserve the first visible passage as the anchor even though page boundaries change.

Acceptance: all sizes and themes work without clipping or covering controls; resizing preserves the passage; preferences survive restart. UI controls and reader text should meet WCAG AA contrast targets.

### F5. Save and resume automatically

- Persist a stable content locator after each completed page turn and chapter jump, and before leaving the reader when lifecycle callbacks permit.
- Do not depend on an exit callback to save progress.
- Store position by book content, not by viewport page number. Store the locator schema version.
- Resume the last committed passage after app restart, TV sleep, or interruption. Keep the anchor passage visible when typography or viewport dimensions change.
- If a locator cannot be restored, open the containing section if identifiable, otherwise the book beginning, and explain the fallback briefly.
- Reimporting a deleted book starts fresh in the MVP.

Acceptance: restart and reopen at the same passage; change font size and reopen without losing the passage; another book retains its own independent position.

## 4. Screens and states

Only five destinations are needed: **Library**, **Add book**, **Reader**, **Reader controls**, and **Chapter list**. Delete confirmation and errors are dialogs or inline states.

Every async operation has a visible loading state. Errors preserve the current library or reading position and offer retry or return. Back closes the topmost overlay first. Focus is clearly visible and restored after closing overlays.

## 5. Architecture

Use a native Kotlin Android TV application. Select the TV UI toolkit and EPUB engine in an early compatibility spike; avoid implementing EPUB parsing, pagination, and location restoration from scratch if an engine meets the requirements.

Organize one application into ordinary packages with narrow interfaces. Separate Gradle modules, a plugin system, a dependency-injection framework, and cloud infrastructure are unnecessary for this release.

```text
TV UI: Library / Reader / Import / Settings overlays
                  |
Application actions: ImportBook / OpenBook / TurnPage / SavePosition
                  |
BookRepository    ReaderEngine    PositionRepository    PreferencesRepository
       |                |                 |                       |
Local metadata     EPUB adapter      Local database          Local storage
and private files

Local upload service → bounded temporary file → ImportBook
```

Responsibilities:

| Boundary | MVP responsibility | Future extension enabled |
| --- | --- | --- |
| `BookRepository` | Local catalog, private files, deduplication, deletion | Additional import sources and remote catalogs |
| `ReaderEngine` | Open EPUB, render pages, move, expose navigation and stable locators | A separate PDF engine or enhanced typography |
| `PositionRepository` | Save and restore one position per book | Account sync and conflict resolution |
| `PreferencesRepository` | Persist global size and theme | Per-book and accessibility preferences |
| `ImportBook` | Validate staged file and commit catalog entry | USB, document providers, or cloud import |
| Reader UI state | Coordinate focus, overlays, rendering, persistence | Read-aloud controls and annotations |

Keep format-specific renderer types inside the EPUB adapter. Do not force future PDFs to behave like reflowable EPUBs; locators and capabilities must identify the format and schema version. Add another engine only when a second format is actually implemented.

### Minimal data model

- **Book:** app-generated ID, content hash, format, title, author, private content path, optional cover path, import timestamp, last-opened timestamp.
- **ReadingPosition:** book ID, versioned content locator, updated timestamp.
- **ReadingPreferences:** schema version, text-size preset, theme.
- **ReaderSession:** transient open book, current locator, navigation state, active overlay, focused control, loading/error state. Do not persist rendered pages.

Use transactional metadata writes and atomic file finalization where possible, plus startup reconciliation for interrupted imports or deletes. Keep all book content in app-private storage. Database migrations must preserve books and positions.

### Renderer selection gate

Before building the full UI, prove the chosen renderer can:

1. Render representative EPUB 2 and EPUB 3 reflowable books on the actual TV.
2. Paginate with remote events, preserve passage anchors across size changes, and restore stable locators after restart.
3. Support a chapter list and inline images.
4. Block scripts and remote resource loads, and reject unsupported layouts predictably.
5. Meet the performance targets below with an acceptable dependency license.

If the engine uses a WebView, implement explicit focus and remote-event ownership between the native controls and book surface. Treat imported markup as untrusted content.

### Keeping room for advanced features

- **Read-aloud:** a later service maps speech segments to content locators. Do not build speech extraction, playback, or background services now.
- **Highlights and notes:** later store annotations as book ID plus a versioned content range and quoted-text fallback. Do not create unused annotation tables now.
- **Sync:** keep persistence behind repositories and stable IDs. Define book identity, account ownership, and position conflict rules when sync is introduced; timestamps alone are not a conflict policy.
- **Phone controls:** keep navigation actions outside the remote event handler so another input source can invoke them later. The MVP phone page handles upload only.
- **Additional formats:** use engine-specific locators and declared capabilities rather than adding PDF assumptions to EPUB pagination.

These are architectural seams, not partially implemented features. No speculative services or empty abstractions beyond the listed boundaries are required.

## 6. Quality targets

Targets must be measured on the user's actual TV and adjusted explicitly if the hardware cannot meet them:

- Normal page turns: visible response within 200 ms at the 95th percentile during a 20-minute text-heavy reading session.
- Reopen a previously imported, representative text-heavy book within 3 seconds; record the fixture and hardware used.
- No application crash, focus loss, unintended page skipping, or reading-position loss during that session.
- All reading functions work offline after import.
- At least 5% screen-edge safe margin for primary controls; verify text and overlays on the actual display.
- No analytics or book-content logging. Local diagnostic messages may record operation names and error codes, without pairing tokens or book passages.

TV layout and navigation rationale: [Android Developers — Build adaptive apps for TV](https://developer.android.com/develop/adaptive-apps/guides/tv/build-adaptive-apps-for-tv).

## 7. Explicitly deferred

PDFs and comics; Kindle/DRM integration; stores or subscriptions; online catalogs; accounts and profiles; cloud backup and sync; highlights, notes, dictionary, translation, and AI features; text-to-speech; timed page turns; phone remote controls; reading statistics; full-text search; elaborate shelf organization; public internet hosting; app-store release work.

## 8. Implementation sequence

1. **Reading spike:** sideload onto the actual TV, open one bundled test EPUB, turn pages with the remote, adjust text size, and restore a saved locator. Conduct the couch reading test before proceeding.
2. **Local reading core:** add persistent catalog, private file storage, library, chapter navigation, preferences, position persistence, and deletion.
3. **Import:** implement the temporary local upload page, pairing lifetime, validation, deduplication, and failure recovery.
4. **Release verification:** test unsupported files, interruption recovery, offline use, focus behavior, and performance; deliver a sideloadable APK with brief setup instructions.

## 9. Release acceptance checklist

- [ ] Import a valid EPUB from phone and computer; duplicate content does not create duplicate entries.
- [ ] Reject malformed, unsupported, oversized, unsafe archive, and unauthorized upload inputs safely.
- [ ] Read for 20 minutes from the normal couch position with comfortable typography.
- [ ] Navigate the entire product using the standard TV remote.
- [ ] Turn pages across sections and jump chapters without missing content.
- [ ] Change size/theme, restart, and resume with the same passage visible.
- [ ] Read without internet and without the uploading device remaining connected.
- [ ] Recover cleanly from interrupted import, low storage, app restart, and invalid saved position.
- [ ] Remove a book and its position without affecting other books.
- [ ] Verify the upload endpoint closes and old pairing tokens expire.
- [ ] Record actual TV model, Android version, renderer version/license, representative fixtures, and measured performance.

Use automated checks for importer validation, deduplication, repository persistence, and locator round trips where the engine supports them. Use physical-device verification for legibility, remote focus, lifecycle behavior, and performance. Do not substitute screenshots for the couch reading test.

## 10. Information needed before coding

Confirm the TV model/Android version and whether the user's actual books are available as reflowable DRM-free EPUBs. The specification proceeds with EPUB as the default; if the collection is mainly PDF or locked to a reading service, revisit format and import scope before implementation.

## Settings amendment — 6 November 2026

Library masthead and reader controls expose global Settings. Native option rows support Up/Down navigation, Left/Right and OK changes, immediate persistence, focused descriptions, and 5% safe margins. Text size is 24/28/32/36/42/48px (32 default); two-page EPUB spread defaults Off and activates only on wide viewports. Both show Applies to EPUB; theme remains in quick reader controls. Screen inactivity choices are 15/30/60 minutes/Never, default 30. Expiry releases the screen to TV system behavior; any key resets it. Add book stays awake. About shows package version, book count, and private book storage asynchronously. Returning from settings retains the book engine and passage.

## Chapter and reading progress — 7 November 2026

Reader controls show the current EPUB chapter and percentage through the book. Before its text-location index is ready, show Calculating progress; a failed index must not prevent reading. Library cards show a non-focusable progress bar and percentage, with unopened books at zero. PDF controls show the fixed page position and corresponding progress.

EPUB progress derives from generated character-based CFI locations, independent of viewport, font size or spread. Generate asynchronously on a separate book instance after the reader is ready, outside the page-command queue, and reuse the versioned, content-hash-bound private cache on later opens. Keep EPUB resume positions as CFIs; never persist reflowed page numbers. Derived percentage/chapter display data is separate from resume positions. Removing a book removes its cache and derived display data.
