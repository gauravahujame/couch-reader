# TV UI and focus styling

Helpers live in `app/src/main/java/dev/couchreader/ui/TvViews.kt`. Build focusable controls with `action()` (text) or `iconAction()` (icon) so they inherit the rules below.

## One painter per control

Each focusable control has a single `paint(focused)` function. It runs once at creation with `false` and again from the focus listener. Then the **resting look** (before any focus) and the unfocused look (after focus leaves) are the same pixels by construction.

Known regression: a control created with one background and repainted by its focus listener with a different one (for example, borderless at creation and a 1dp `Palette.line` border after) changes its look the first time focus passes over it. Users report this as "it fixes itself after I highlight it".

When a call site replaces the listener with `setOnFocusChangeListener` (the library's `BookTile` does, calling `tile.paint`), call `paintAction(focused)` from the replacement so it keeps background, text color, elevation and scale in step. `OptionRow` and the Settings About row follow the same rule by hand.

## Focus look

The user asked for a restrained focus system. Buttons: green at rest has no border and no shadow; focused adds a 2dp `Palette.gold` outline, 2dp elevation and a 1.02× scale; pressed swaps to `Palette.greenPressed`. White (secondary) buttons still turn green on focus, because an outline alone is too faint across a room. Rows, the About box and `PageDial` use the same 2dp gold outline but no scale, since a full-width row would grow past the scroller's clip. Don't go back to 3dp outlines or 6dp shadows.

## Shadows

- Set `stateListAnimator = null` on every `Button` or `ImageButton`. The platform animator gives a 2dp resting elevation that the code never sees, and tight parents clip its shadow. Elevation comes only from `paint`: 2dp focused, 0 otherwise.
- A shadow draws outside the view, so every ancestor up to the page must leave it unclipped. `row()` and `column()` set `clipChildren = false` and `clipToPadding = false`, and `page()` is a `column()`. A plain `LinearLayout`/`FrameLayout` in that chain clips the shadow again.
- Because those parents no longer clip, a scrolling region draws over its siblings unless it clips itself. The library `ScrollView` uses `outlineProvider = BOUNDS` with `clipToOutline = true`; any new scroller needs the same. For a list with section headings under a fixed title, use `SectionScrollView` and register each heading with `addHeading`: it only rests with a heading at its top edge, so no heading is left half cut. Shadows of controls inside a scroller are therefore cut at the scroller's edge.

## Library shelf

Each book is one `BookTile` (cover, title, author, progress): OK opens, a long OK press or the Menu key opens `bookActions`. There is no per-card button, so a tile at rest has no frame; focus adds a white card with the 2dp gold outline. The tile reserves two title lines and one author line so progress bars line up across a row. The library scroller is a `SectionScrollView` whose stops are the gaps above each row, so a row is never left half cut and the focused tile's outline stays inside the clip. Covers only shorten (down to 5:4) to fit one full row; they never get narrower than the column count allows. `BookCover` shrinks its title until the longest word fits, because a narrow cover otherwise splits words ("Moby-Dic / k").

## Selected vs focused

For a mutually exclusive choice (the reader theme), use `ChoiceButton`. Focus is green fill plus gold border, so a selected option must not also be a plain green fill, or two options look focused at once. The selected option uses `Palette.greenTint`, a 2dp green border and a ✓. After a change, call `refresh()` on every option in the group. `restColor` sets the fill when the option is neither focused nor selected (Warm light uses the page colour).

## Popups

Build popups with `overlayPanel()` in `MainActivity`, not `AlertDialog`. D-pad focus search ignores z-order, so `overlayPanel` blocks focus on the page underneath and `hideOverlay` restores each view's original `descendantFocusability`. To send focus back to the control that opened the popup, set `overlayReturnFocus` before calling `overlayPanel` (as `bookActions` does).

`overlayPanel` returns the scrolling body, not the card: the title sits outside it, so `panel.getChildAt(0)` is the first thing you added. Never grow a popup with the size of the data (one button per page or item); a long list scrolls, and a numeric choice uses a single control such as `PageDial`.
