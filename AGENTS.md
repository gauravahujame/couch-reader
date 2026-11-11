# Agent notes

Couch Reader is a remote-driven Android TV app: every screen is built in code (no XML layouts), and every control is reached by D-pad focus. The README covers the product; the Makefile holds the check commands.

## Where to look

- **TV UI or focus styling** (buttons, rows, cards, shadows, anything focusable): read [docs/agents/tv-ui.md](docs/agents/tv-ui.md) before editing `ui/` or the screen builders in `MainActivity.kt`.
- **Checking a change on the TV or emulator** (build, install, screenshot, remote input): read [docs/agents/device-loop.md](docs/agents/device-loop.md).
- **Why something is built the way it is**: `docs/DECISIONS.md`, newest section last.
- **Intended behavior**: `docs/FEATURE_SPEC.md`; validation evidence goes in `docs/VALIDATION.md`.

## Done means seen

A UI change is done when a screenshot from the device shows it in every state it can be in, not when the build passes. For focusable controls that means three states: fresh launch before any key press, focused, and after focus has left.

## Adding agent docs

Put each new topic in its own file under `docs/agents/`, holding only what an agent cannot find by reading the code or config: gotchas, unwritten conventions, the reason behind a choice. Then add one line under "Where to look" that starts with the trigger. Correct or delete a note when the code it describes changes.
