# EPUB reader typography

**Implementation proposal · 18 November 2026**

Offer three typefaces and four layout controls for reflowable EPUBs. Keep Literata and today’s layout as the defaults so an existing reader sees the same page after upgrading. PDF pages retain their printed typography.

## Choices and placement

Keep text size and theme in the primary reading menu. Put the five new controls together under **Settings → EPUB typography**, reachable through the existing **All settings** action and from the library. Use the existing option rows: Up/Down moves focus, Left/Right adjusts the value, and changes save immediately. Add one short sample sentence in the selected face so readers can compare fonts without leaving Settings. The preview is decorative for D-pad navigation. Settings labels keep their native UI typeface.

| Control | Proposed values | Default |
| --- | --- | --- |
| Font style | **Literary** — bundled Literata serif. **Modern** — Android system sans-serif. **Accessible** — bundle Atkinson Hyperlegible for its distinct letterforms. | Literary |
| Line spacing | Compact (1.35), Standard (1.58), Relaxed (1.85), as multiples of the body font size | Standard |
| Text alignment | Left or Justified, for prose paragraphs. Keep the last line of a justified paragraph start-aligned. | Left |
| Page margins | Narrow (4% horizontal, 3% vertical), Standard (7%, 6%), Wide (10%, 8%), measured per outer edge | Standard |
| Paragraph spacing | Small (0.4 em), Standard (0.8 em), Large (1.2 em) after a paragraph | Standard |

Font choice and layout are independent. Switching to Accessible keeps the reader’s chosen size and spacing. Label the group as applying to EPUBs, including when reached from a PDF. Changes made there apply to the next EPUB.

## Implementation sequence

1. **Persist the choices.** Extend `ReadingPreferences` and `PreferencesRepository` in `data/Library.kt` with stable preset identifiers. Default missing or unknown values individually. Preserve existing size, theme, spread, sleep and voice preferences on upgrade and whenever either Settings or quick controls save a copy.
2. **Prepare the fonts.** Reuse the existing Literata asset and system sans. Verify the redistribution terms for the selected Atkinson release and ship its license and local font assets, including bold and italic support. Register distinct families in the EPUB section documents, provide system fallback for missing glyphs, and check the existing virtual resource handler serves the new assets offline. Use the same fonts for the Settings sample. Check capital I, lowercase l, numeral 1, O and 0 at couch distance.
3. **Apply one set of renderer preferences.** Extend `EpubReaderEngine.preferencesJson` and map the known preset IDs to CSS values in the reader assets. Apply styles to current and newly opened sections. Preserve emphasis, heading hierarchy, lists, code whitespace and table layout. Scope justification and paragraph spacing to prose. Verify that applying a new font does not override monospace code or turn italic and bold text into regular text.
4. **Reflow around the saved passage.** Change the dimensions of the centered outer `#pages` element in `reader.css`. EPUB.js measures column widths and turns from this container. Keep publication body padding at zero. Recalculate single-page versus spread layout and its gap using consistent dimensions, especially where wider margins cross the spread threshold. In the serialized preference handler in `reader.js`, capture the original CFI/href before the first change, wait for the selected font in the rendered iframe and the updated viewport, then redisplay that anchor. A failed font load should settle on a fallback and allow reading to continue. Guard against stale font completions when the reader changes choices rapidly or leaves the book.
5. **Connect the TV controls.** Use the existing Settings save/reflow path and retain the live reader behind Settings. Keep focus on the adjusted row while it updates. Back returns to the same passage. Refresh read-aloud highlight geometry after relayout and preserve existing speech pause/return behavior. Typography affects pagination only; keep the existing content-based location cache and resume format.

## Acceptance checks

- **Upgrade and persistence:** Start with an existing preferences file containing nondefault text size and theme. Check the old appearance on first open, each new value after process restart, and recovery from one invalid preset without resetting other choices.
- **Rendering:** Inspect computed styles in EPUB 2 and EPUB 3 fixtures, including a newly opened chapter. Exercise every preset individually and the tightest combination (largest text, widest margins, relaxed lines, large paragraph gaps). Include headings, bold/italic passages, lists, code, long words and mixed-script text. Check both themes and a missing font asset with networking disabled.
- **Navigation and progress:** Change several settings quickly in the middle of a chapter. Verify the original CFI remains in the visible passage after reflow and reopening. Turn forward and back across chapter boundaries in single and two-page layouts. Check for clipped columns, skipped text, blank trailing pages and drift in saved progress. Confirm PDF page, zoom/layout and scroll offset are unaffected.
- **TV usability:** Verify all rows remain reachable and readable, especially the last row on short screens. Capture fresh launch before input, focused rows, and rows after focus leaves. Capture representative EPUB pages for each font, justified text, margin extremes, and read-aloud highlights after a font change. Use the emulator and isolated QA package as described in the [device loop](agents/device-loop.md) and [test instructions](../tests/README.md).
- **Delivery:** Extend preference, renderer and Settings integration coverage. Run the repository checks and record actual device results in VALIDATION. At implementation time update the README, feature spec and Settings reference, and record that the earlier fixed Literata and margin choices now serve as defaults.
