# Settings behavior reference

Settings is implemented in `MainActivity.kt`, with reusable focus behavior in `ui/OptionRow.kt` and persistence in `data/Library.kt`. This file records the behavior and edge cases that are easy to break; it is not an implementation plan.

## Current settings

| Setting | Values | Default | Applies to |
| --- | --- | --- | --- |
| Text size | 14, 16, 18, 20, 22, 24, 28, 32, 36, 42, 48 CSS px | 16 px | EPUB |
| Font style | Literary / Modern / Accessible | Literary | EPUB |
| Line spacing | Compact 1.35 / Standard 1.58 / Relaxed 1.85 | Standard | EPUB |
| Text alignment | Left / Justified | Left | EPUB prose |
| Page margins | Narrow / Standard / Wide | Standard | EPUB |
| Paragraph spacing | Small / Standard / Large | Standard | EPUB |
| Two-page spread | Off / On (automatic wide-screen threshold) | Off | EPUB |
| Inactivity timeout | 15 / 30 / 60 minutes / Never | 30 minutes | Reader |
| Read-aloud speed | 0.75×, 0.9×, 1×, 1.15×, 1.3×, 1.5×, 1.75×, 2× | 1× | EPUB and PDF |
| Read-aloud voice | TV default and installed offline voices | TV default | EPUB and PDF |

Theme remains a reader quick action. About shows package version, book count, and private storage use.

## Interaction and persistence

- Settings is a native full-screen page. Up/Down selects rows; Left/Right or OK changes the focused value. Changes save immediately.
- Opening settings from the reader retains the same reader engine and passage. Back returns to the reader; opening from the library returns to the library and restores Settings focus.
- EPUB size, spread, and typography changes reflow around one pending original `{cfi, href}` anchor across repeated changes. A user page turn or chapter jump clears the pending anchor and establishes the new position.
- The EPUB typography section includes a non-focusable sample sentence styled with the selected face. Native Settings labels retain the system typeface.
- Values clamp at their endpoints. Text size labels include CSS px. PDF does not reflow or use spread.
- Voice selection includes only installed offline voices, labels them by language and number, and plays a short sample. A removed saved voice falls back to TV default.
- The inactivity timeout releases `FLAG_KEEP_SCREEN_ON`; it does not power off the TV or alter system settings. Any key resets the timer. Add book keeps the screen awake. Spoken sentences count as activity.
- TTS teardown can block while the platform is still initializing. Release sample/read-aloud engines off the UI thread and do not call stop/rate methods before initialization completes.

## Verification pointers

Focused regression coverage is in `SettingsIntegrationTest` and preference tests. Persistence checks should inspect stored preference values; Activity recreation alone does not prove process-death persistence. Real TV sleep behavior and voice availability depend on the device and remain part of device acceptance.
