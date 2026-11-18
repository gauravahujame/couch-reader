# Continue reading on the library screen

**Proposed · 18 November 2026**

A returning reader should recognize their current book, see where they left off, and press OK once to resume. Put one compact **Continue reading** section above the remaining books. Let it scroll away when the reader browses the shelf.

## What exists today

| Capability | Current behavior |
| --- | --- |
| Resume in one press | The header has a Continue button with the most recently opened book’s title. On a fresh launch its shelf tile receives focus, so OK already resumes it. Both use the same reader entry point. [Library screen](../app/src/main/java/dev/couchreader/MainActivity.kt#L170) |
| Cover, chapter and progress | The shelf tile shows a generated cover and progress. The opening screen can show the saved chapter or PDF page and percentage after the user opens the book. The header shows only the title. [Opening screen](../app/src/main/java/dev/couchreader/MainActivity.kt#L458) |
| Remaining books | The grid includes every catalog entry, including the book named in the header. [Shelf construction](../app/src/main/java/dev/couchreader/MainActivity.kt#L202) |
| Estimated time remaining | The app saves an opening timestamp, a locator, and derived chapter and fraction. It has no measure of active reading pace. Elapsed wall time since opening cannot support an estimate. [Stored reading data](../app/src/main/java/dev/couchreader/data/Library.kt) |

## Implementation

1. **Choose the featured book once.** Use the first previously opened book in the catalog’s existing most-recent-first order. Preserve import order for unopened books and add a stable ID tie-breaker for equal timestamps. Build the grid from the same list minus the featured ID. Keep the library total inclusive. An import, background indexing, or moving focus must never promote a book. Retain the current most-recently-opened rule even at 100% because a rounded percentage is insufficient evidence of completion.
2. **Replace the header shortcut with the section.** Keep the masthead actions reachable. Inside the existing section scroller, place a small portrait cover beside the title, chapter or page, progress, and a primary **Resume** button. Below it, show “Your library” and the remaining tiles if any exist. Register the shelf heading and row gaps as scroll stops. Update the height calculation that currently assumes the first grid child is a shelf row. Measure an actual tile row against the viewport it will occupy after the featured section scrolls away.
3. **Bind existing reading data.** Use the same book ID and merged saved/live progress as the shelf. Show the saved chapter or section label for EPUB, and the saved “Page X of Y” label for PDF. Show the bar and percentage for a known fraction, including zero. With missing data, show the details that are known. Show “Calculating…” only while EPUB indexing is pending, and “Progress unavailable” on failure. Update the featured chapter and progress together on index completion without rebuilding the section or moving focus. Guard callbacks by screen and book ID.
4. **Reuse the existing cover and resume paths.** Use the same cover presentation as a shelf tile and adopt publisher art or PDF thumbnails through the shared cover component when available. The generated cover works independently of the [artwork plan](LIBRARY_COVERS_PLAN.md). Resume calls `open(book)`, which restores the EPUB CFI or PDF page and offset and handles an unavailable position. Rendering the section itself must never open an engine, write a locator, or change recency.
5. **Keep the book manageable from a remote.** Resume is the section’s single focus target. Give it the existing primary-button focus styling, a spoken label including the book title, and accessible chapter/progress details. Hold OK or Menu opens the featured book’s existing actions, including confirmed removal. Closing the popup returns focus to Resume. On fresh launch focus Resume. Down reaches the remaining shelf, Up returns to Resume or the masthead. On return from reader or settings, restore focus by control or book ID, mapping a newly featured tile to Resume.
6. **Omit time remaining for the initial release.** There is no trustworthy estimate for existing libraries. Leave the line out entirely. If active reading duration is collected later, make estimates a separate change with a minimum sample, excluding idle time, seeks, reflows and read-aloud. The section can ship with the data already saved on the TV.

## Edge cases and acceptance checks

| Scenario | Expected result |
| --- | --- |
| Empty library / books imported but never opened | Empty library focuses Add book. An unopened collection shows the full grid and focuses its first tile. Neither shows a Resume section. |
| Exactly one previously opened book and no others | Show the section alone, with no empty shelf heading or scroll stop. Settings, Add book, and book actions remain reachable. |
| Resume an EPUB or PDF after relaunch | One OK from launch restores the saved EPUB passage or PDF page and scroll offset. Missing progress never disables Resume. A missing or corrupt locator follows the existing beginning-of-book recovery. |
| Open another shelf book, then return | That book appears once in the featured section and receives focus on Resume. Its predecessor appears once in the grid, in catalog order. |
| Cancel or confirm removal of the featured book | Cancel restores Resume focus. Confirm promotes the next previously opened book, or shows the unopened shelf or empty state. Late progress updates cannot repopulate deleted content. |
| Large collection, short viewport, long title or chapter | Limit visible text with ellipsis and retain full accessible text. Verify Resume and the first shelf row are each fully reachable, focus outlines stay visible, and scrolling back returns to the section. |

Add host checks for selection, ordering, filtering and missing progress. Extend Android integration coverage for one-press resume in both formats, focus restoration, removal, and background progress updates. Run `make check`; use the emulator or isolated QA install for device checks per the [test instructions](../tests/README.md) and [device loop](agents/device-loop.md).

Before shipping, inspect TV screenshots of fresh launch before input, Resume focused, and focus after leaving it. Include EPUB, PDF, missing-progress and single-book layouts. Update the README, library feature spec and TV UI notes with the shipped behavior. Record the layout decision in DECISIONS and dated device evidence in VALIDATION.
