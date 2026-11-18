# Agent notes

Couch Reader is a remote-driven Android TV app: every screen is built in code (no XML layouts), and every control is reached by D-pad focus. The README covers the product; the Makefile holds the check commands.

## Where to look

- **TV UI or focus styling** (buttons, rows, cards, shadows, popups, anything focusable): read [docs/agents/tv-ui.md](docs/agents/tv-ui.md) before editing `ui/` or the screen builders in `MainActivity.kt`.
- **Building, installing or testing on the TV or emulator** (screenshots, remote input, test uploads): read [docs/agents/device-loop.md](docs/agents/device-loop.md) first. The TV holds the user's real library, and `make device-test` deletes it.
- **Running or writing tests**: [tests/README.md](tests/README.md).
- **Settings rows, preferences, or read-aloud teardown**: [docs/SETTINGS_PLAN.md](docs/SETTINGS_PLAN.md).
- **Why something is built the way it is**: [docs/DECISIONS.md](docs/DECISIONS.md), newest section last; a later section overrides an earlier one.
- **Intended behavior and scope**: [docs/FEATURE_SPEC.md](docs/FEATURE_SPEC.md); what is open before release: [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md); dated evidence: [docs/VALIDATION.md](docs/VALIDATION.md).

## Done means seen

A UI change is done when a device screenshot shows it in every state it can be in, not when the build passes. For focusable controls that means three states: fresh launch before any key press, focused, and after focus has left. If no device is available, say so instead of claiming the change is verified. Non-UI changes need the relevant tests, not screenshots.

## Keeping docs current

When a change alters behavior described in these docs, update the doc in the same change:

- New design choice or reversal: add a dated section at the end of `docs/DECISIONS.md`, and mark the earlier statement *Superseded* with a pointer.
- Something checked on a device: add a dated entry to `docs/VALIDATION.md` with what was and was not tested.
- User-visible behavior: `README.md` and `docs/FEATURE_SPEC.md`.

Put each new agent topic in its own file under `docs/agents/`, holding only what an agent cannot find by reading the code or config: gotchas, unwritten conventions, the reason behind a choice. Then add one line under "Where to look" that starts with the trigger. Correct or delete a note when the code it describes changes.
