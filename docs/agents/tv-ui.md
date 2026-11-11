# TV UI and focus styling

Helpers live in `app/src/main/java/dev/couchreader/ui/TvViews.kt`. Build focusable controls with `action()` (text) or `iconAction()` (icon) so they inherit the rules below.

## One painter per control

Each focusable control has a single `paint(focused)` function. It runs once at creation with `false` and again from the focus listener. Then the **resting look** (before any focus) and the unfocused look (after focus leaves) are the same pixels by construction.

Known regression: a control created with one background and repainted by its focus listener with a different one (for example, borderless at creation and a 1dp `Palette.line` border after) changes its look the first time focus passes over it. Users report this as "it fixes itself after I highlight it".

When a call site replaces the listener with `setOnFocusChangeListener` (the card's read button in `MainActivity.library()` does), the replacement owns every property the helper's `paint` set: background, text or tint color, and `elevation`. `OptionRow` and the Settings About row follow the same rule by hand.

## Shadows

- Set `stateListAnimator = null` on every `Button` or `ImageButton`. The platform animator gives a 2dp resting elevation that the code never sees, and tight parents clip its shadow. Elevation comes only from `paint`: 6dp focused, 0 otherwise.
- A shadow draws outside the view, so every ancestor up to the page must leave it unclipped. `row()` and `column()` set `clipChildren = false` and `clipToPadding = false`, and `page()` is a `column()`. A plain `LinearLayout`/`FrameLayout` in that chain clips the shadow again.
- Because those parents no longer clip, a scrolling region draws over its siblings unless it clips itself. The library `ScrollView` uses `outlineProvider = BOUNDS` with `clipToOutline = true`; any new scroller needs the same. Shadows of controls inside a scroller are therefore cut at the scroller's edge.

## Selected vs focused

For a mutually exclusive choice (the reader theme), use `ChoiceButton`. Focus is green fill plus gold border, so a selected option must not also be a plain green fill, or two options look focused at once. The selected option uses `Palette.greenTint`, a 2dp green border and a ✓. After a change, call `refresh()` on every option in the group. Narrow cells (the PDF page grid) pass `mark = false` and also need `minWidth = 0; minimumWidth = 0`, because the platform `Button` style sets an 88dp minimum width, wider than a tenth of the 560dp overlay.

## Popups

Build popups with `overlayPanel()` in `MainActivity`, not `AlertDialog`. D-pad focus search ignores z-order, so `overlayPanel` blocks focus on the page underneath and `hideOverlay` restores each view's original `descendantFocusability`. To send focus back to the control that opened the popup, set `overlayReturnFocus` before calling `overlayPanel` (as `bookActions` does).
