# Development status

This document describes the current checkout. Read `FEATURE_SPEC.md` for intended product behavior, `DECISIONS.md` for durable implementation rationale, and `VALIDATION.md` for dated evidence. Do not use old validation entries as a checklist for routine feature changes.

## Current implementation

- Native Kotlin Android TV app, minimum API 26, target/compile API 35, JDK 17. Native Views build every screen; EPUB content is rendered by the bundled EPUB.js 0.3.93 WebView adapter.
- Local library with app-private files, SQLite catalog, format-specific versioned positions, duplicate detection, deletion, startup reconciliation, and metadata refresh.
- EPUB import and reading: EPUB 2/3 validation and sanitization, local images, chapters, page turns, themes, 11 text sizes (14–48 CSS px, default 16), optional two-page spread, stable CFI resume, and cached background locations for chapter/progress display.
- PDF import and reading: Android `PdfRenderer`, unlocked PDFs up to 50 MiB, fixed pages, vertical scrolling, page/scroll resume, metadata extraction, page dial, and text extraction for read-aloud. EPUB typography and spread settings do not apply.
- Browser upload: local HTTP service scoped to Add book, pairing code or QR token exchanged for a session cookie, ten-minute expiry/renewal, cooldown after bad codes, multiple sequential uploads, 50 MiB per file, validation, and private storage.
- Native Settings: text size, EPUB spread, inactivity timeout, read-aloud speed and offline voice, plus About information. Read aloud supports EPUB and PDFs while the reader is foregrounded.
- D-pad focus, reader overlays, progress cards, chapter/page controls, deletion confirmation, and recovery states are implemented.

## Remaining release verification

The target TV is known (OnePlus, Android 11 / API 30, 1920×1080, WebView 156), and most features have been exercised on it (see `VALIDATION.md`). Passing automated checks still does not establish all real-device behavior. Remaining gates are physical-TV comfort, a 20-minute couch session, real phone/desktop LAN upload on the target network, remote/focus and overscan behavior on the target TV, and measured page-turn/reopen performance. Record evidence in `VALIDATION.md` when those checks are performed. Do not claim these checks passed based on an emulator or a build.

There is no known unimplemented core feature from the current product scope. Enhancements such as PDF outlines, sync, accounts, cloud storage, annotations, search, and phone remote control remain out of scope unless requested.

## Routine feature workflow

1. Read the relevant code, the matching section of `FEATURE_SPEC.md`, and the topic file named in `AGENTS.md`.
2. Make the smallest complete change and add/update focused tests when behavior changes.
3. Run a focused check; use `make check` if no narrower check is suitable. Run `make device-test` only when device behavior matters and a compatible test device is available.
4. Update the docs the change affects (see "Keeping docs current" in `AGENTS.md`), then report the change and the checks actually run. Do not repeat release acceptance checks for routine work.

## Project checks

- `make check`: host unit tests, Android Lint, debug APK, and instrumentation APK builds.
- `make device-test`: connected Android instrumentation suite. It removes every book in the app under test, so on the user's TV run it only through the isolated QA package described in `tests/README.md`.
- `make engine-server`: optional desktop browser harness for EPUB.js checks; it does not validate Android WebView, TV input, or physical comfort.
