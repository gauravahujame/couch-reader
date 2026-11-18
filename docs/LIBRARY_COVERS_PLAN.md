# Library cover artwork implementation plan

**Proposed · 18 November 2026**

Show the publisher’s cover for an EPUB and the first visually useful page for a PDF. Use the existing typographic cover whenever artwork is unavailable. Apply the same choice on the library shelf and the opening screen, including for books already on the TV. All artwork comes from the local publication.

## 1. Resolve EPUB artwork from the stored book

Start from the committed book directory and its catalogued OPF path. The importer already unpacks the EPUB, validates the package and sanitizes markup. A cover resolver can therefore serve fresh imports and older libraries without the original upload. Share the validator’s bounded XML parsing and local path checks.

Try the following candidates in order. If a candidate cannot produce an image, continue to the next distinct candidate.

1. EPUB 3 manifest items whose space-separated `properties` include the exact token `cover-image`.
2. EPUB 2 metadata entries with `name="cover"`, resolving their `content` as a manifest ID.
3. An OPF guide reference of type `cover`. Follow a local raster image directly or inspect its cover page for a local image. Also try an explicitly cover-named manifest image such as `images/cover.jpg` when the declarations are absent or broken. Keep document order within each priority. Prefer the generated cover over guessing from an arbitrary illustration.

Resolve each relative reference against the file that declares it, preserving case and handling fragments and encoded spaces. Require the canonical target to be a regular file inside this book’s directory. Reject external schemes, authorities, traversal outside the book, and embedded data URLs. Bound candidate attempts (initially eight), wrapper depth (one), input bytes and bitmap allocations. Existing import rejection rules still apply before this optional work starts.

Decode raster dimensions first, then subsample to a thumbnail with a maximum long edge of 480 px. Check dimensions and allocation arithmetic before decoding, honor supported image orientation metadata, and composite transparency on a neutral backing. Continue past corrupt files, invalid dimensions and wholly transparent images. For XHTML or SVG wrappers, only follow an identifiable local raster reference that survives sanitization. Pure vector covers and stripped SVG content use the next candidate or the generated cover in this first release. Keep the current sanitizer intact.

## 2. Choose a PDF page, including when page one is blank

Use a separate read-only Android `PdfRenderer` on the stored PDF. Start at page one and inspect up to the first **five pages**, or the end of the document. Render one page at a time into a white bitmap at its native proportions, capped at 480 px on its long edge. Classify the actual rendered pixels so image-only scans work too. A chosen page is for display on the shelf only. Opening the book still uses its saved locator.

| Rendered page | Selection rule |
| --- | --- |
| Visible design, photograph, text, or a sparse title | Use this page immediately. A plain title page is a valid thumbnail. |
| Uniform white, black, or other solid color | Advance to the next page. |
| Nearly uniform scan with dust, a scan border, or only a running footer/page number | Advance when the blank-page detector is confident. |
| Faint content of uncertain significance | Keep the earlier page. Skipping a real cover is the more visible mistake. |
| A single page fails to render | Close it and try the next page if the renderer is still usable. |
| Five blank or unusable pages, or a shorter document with no usable page | Show the generated cover. |

Implement blank detection as a pure function on a small RGB sample grid. Estimate the background from several border patches, then measure how far interior pixels differ from that background and whether those differences form coherent regions. Ignore isolated speckles and a narrow uniform scan border. Treat marks confined to a small footer differently from a centered title. Consider color differences as well as luminance so dark covers and colored artwork survive. For inconsistent border samples or ambiguous interior patterns, keep the page. Calibrate the thresholds on the fixtures below, record them beside the function, and use the full rendered page for display. Detection margins never crop the thumbnail.

Close each page before opening the next, release temporary bitmaps, and close the renderer and descriptor on every exit. Check for cancellation between renders. A complex native page can outlast a time budget, so keep rendering on a dedicated background worker and bound the number of attempts. Reading, navigation and upload completion must never wait for this scan.

## 3. Cache results and upgrade existing libraries

Add a cover-thumbnail store beside the repository’s existing derived-data handling in `data/Library.kt`. Use an app-private cache directory outside publication resources, keyed by book ID, original content hash and cover algorithm version. A new version permits another attempt after decoder or blank-detection improvements. Keep the catalog schema and stored book files unchanged.

- **Schedule work on demand.** Paint the shelf immediately. Request visible covers first, then the next row as the user scrolls. Opening a book can request the same result at higher priority. Coalesce requests for the same key on a single worker. Use the committed source after import or deduplication. Older books enter through exactly the same binding path.
- **Distinguish absence from interruption.** Atomically publish either a complete thumbnail or a small marker for a definitive miss (e.g. no supported EPUB image or only blank PDF pages). Cancellation, low storage, and transient I/O or renderer failures leave the result retryable. Suppress repeated attempts for that key for the current shelf session. After cache eviction or a corrupt cache hit, regenerate on demand.
- **Bound retained data.** Start with an 8 MiB decoded bitmap LRU and a 32 MiB disk cache. Trim oldest entries, including miss markers. Release offscreen image references so a long shelf cannot retain one decoded bitmap per tile outside the LRU. Treat eviction as normal.
- **Handle races.** Generate into a temporary file outside the repository lock. Before publishing, check the book still exists with the same hash under the same lock used for deletion. Remove its cache entries on deletion and prune obsolete versions, orphaned entries and abandoned temporary files at startup before starting workers. Drop UI callbacks after detachment, rebinding or Activity destruction. A late completion must never change another book’s cover or recreate deleted data.

## 4. Present artwork on the TV

Extend `ui/BookCover.kt` to show either the existing generated design or the resolved image. Update both construction sites in `MainActivity.kt`. Reserve a shared portrait frame with a target width-to-height ratio of **2:3**. In a short shelf viewport, shrink both dimensions together within the existing tile slot and center the cover so one row still fits. Use the same proportions for the opening hero.

Center and fit the full image inside the frame using a contain operation such as `FIT_CENTER`. Fill unused space with a quiet matte. Keep a narrow spine and hinge beside the artwork, the cloth sheen on the generated design, and the existing restrained tile focus shadow. Keep edge lettering visible even on wide, square and very tall covers. Swap the image inside its reserved footprint when ready. Retain the tile as the sole focus target, with title, author, progress and actions available throughout loading.

## 5. Verify before release

| Check | Fixtures or scenario | Pass condition |
| --- | --- | --- |
| EPUB selection | EPUB 2 ID and EPUB 3 property disagree, multiple declarations, nested directories, escaped spaces, guide wrapper, misleading inline illustration | Pick the expected declared image, continue past a broken candidate, and show the fallback when none qualifies. |
| EPUB failure and limits | Corrupt and oversized raster, transparent PNG, unsupported vector, remote or out-of-directory wrapper target | Stay within decode limits, read only local book resources, and retain normal reading for accepted books. |
| PDF selection | Page-one photo, sparse pale title, dark cover, blank then illustrated page, noisy scan then title, footer-only page, all blank, useful page at positions five and six | Assert the selected page number or fallback for each fixture, including the five-page cutoff. Exercise the pixel classifier on the host and the real renderer on Android. |
| Cache lifecycle | Existing EPUB and PDF after upgrade, duplicate upload, repeated binds, process death mid-write, storage full, corrupt or evicted entry, deletion during generation | Warm hits skip extraction. Definitive misses skip rescans. Retryable failures recover. No partial file or stale callback becomes visible. Saved positions and progress survive. |
| Shelf performance | Cold and warm opens of a multirow mixed library while scrolling, opening a book and returning | First paint and D-pad input proceed before extraction finishes. Verify one active render, bounded bitmap retention and no focus jumps. Record cold/warm timings and peak memory on the target TV. |
| TV presentation | Synthetic covers with marked edges, landscape PDF, portrait EPUB, generated fallback and short viewport | All four edge marks remain visible, images retain their proportions, and shelf and opening hero show the same choice. |

Run `make check` for host tests, lint and builds. Use the emulator and the isolated QA package for instrumentation and screenshots, following [the device loop](agents/device-loop.md) and [test instructions](../tests/README.md). Capture fresh launch before any remote input, tile focused, and after focus leaves for artwork and fallback tiles. Capture the opening hero as well. Record target, fixtures, screenshot evidence and remaining gaps in [VALIDATION.md](VALIDATION.md).

When shipping, update the README and feature spec with the selection and fallback rules. Add the dated design decision, mark the old shrink-only sizing rule superseded, and update the TV UI notes. The five-page scan, thumbnail size and cache budgets are proposed starting values to confirm on the OnePlus TV.
