# Settings screen plan

## Purpose

Collect reading and screen options in one place, reachable from the library and from inside a book. Settings are global across books, as the spec (F4) already requires.

## What goes in settings

| Section | Setting | Values | Default | Status |
|---|---|---|---|---|
| Text | Text size (EPUB) | 6 steps (24, 28, 32, 36, 42, 48 px) | 32 px | Exists in reader controls |
| Page | Two-page spread (EPUB, wide screens) | Off / On | Off | New |
| Screen | Let the TV sleep after no key press | 15 / 30 / 60 min / Never | 30 min | New |
| About | Version, books stored, storage used | Read-only | — | New |

The reader controls (OK) keep their current quick actions for text size, theme and chapters. Theme is not part of this settings screen.

## Where it appears

- **Library:** a `Settings` button in the masthead next to `Add book`.
- **Reader controls (OK):** add `All settings →`. Back from settings returns to the book. Changes apply live, and the current passage stays in view (`reader.js preferences()` already re-displays the anchor CFI).
- Text size and two-page spread show "Applies to EPUB" so it's clear they don't change PDFs.

## Layout and remote behaviour

- Full-screen native View, in the same style as the library (`TvViews.kt` helpers, Palette, Literata headings). No Leanback or Compose, matching DECISIONS.md.
- One row per setting, grouped under the section headings. Each row reads `Label    ◀ Value ▶`.
- Up/Down moves between rows. Left/Right changes the value. OK also steps the value. Back leaves the screen.
- Every value change saves immediately. There is no Save button.
- A short description under the focused row says what it does. The layout keeps a 5% safe margin for overscan.

## Implementation outline

1. **Model and storage.** Add `spread: Boolean` and `sleepMinutes: Int` (0 means Never) to `ReadingPreferences`. In `PreferencesRepository`, raise the stored `version` from 1 to 2 and fill missing keys with defaults so the existing text size and theme carry over. Switch writes from `commit()` to `apply()` so they don't block the main thread.
2. **Settings screen.** Add a `settings()` destination in `MainActivity` that returns to wherever it was opened from (library or reader). Build a reusable option row in `ui/` that handles Left/Right itself, so the reader's key handling never fires.
3. **Two-page spread.** Pass `spread` through `EpubReaderEngine.preferencesJson`. In `reader.js`, call `rendition.spread(p.spread ? 'auto' : 'none', minWidth)` so two pages appear only when the screen is wide enough. Add a gap between the two pages, because the current pagination gap is 0 and the pages would otherwise touch. A page turn then moves by two pages, and saving still uses the first visible CFI.
4. **Screen sleep.** Replace the permanent `FLAG_KEEP_SCREEN_ON` in the reader with a timer that every key press resets. When the timer runs out, clear the flag so the TV's normal screensaver and sleep take over. The next key press sets the flag again. `Never` keeps today's behaviour. Add book keeps the screen on as it does now.
5. **About.** Read the version from `PackageManager`. On the IO thread, count books from `BookRepository.books()` and add up the file sizes under `BookRepository.root`. Show "Calculating…" until the totals arrive.

## Verification

- Unit tests: preference migration from version 1, defaults, and a round trip of every field.
- Instrumented tests: D-pad reaches every row; Left/Right changes values without turning a page; changing text size or spread from inside the reader keeps the passage; settings survive an app restart; About shows the correct book count.
- On the TV: two-page spread is readable from the couch at each text size; with a 15-minute timeout the TV actually sleeps while a book is open; the screen respects the overscan margin.

## Open questions

- Should two-page spread default to On for screens wide enough to use it?
- Is 30 minutes the right default for sleep, or should it match the TV's own sleep setting?

## Review amendment — 7 November 2026

- Consecutive preference changes retain one pending CFI/href and save it until a page turn or chapter jump establishes a new reading position. Reflowed page starts must not replace this anchor.
- Text-size and sleep choices stop at their endpoints. Quick text controls and Settings both show px; spread help says wide screens.
- Settings defers reader errors that would open controls, ignores boundary overlays, and retains focus through reader-ready events. Back to library focuses Settings.
- Regression verification includes repeated size changes, saved-anchor equality, endpoint behavior, queued error overlays, focus restoration, and reading the on-disk preferences file. Activity recreation alone is not evidence of a process restart.
