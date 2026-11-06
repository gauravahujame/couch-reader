# Verification

`make check` builds app and instrumentation APKs, runs importer unit tests, and runs Android Lint. Tests live in standard Gradle source sets:

- `app/src/test`: 18 host tests, including Robolectric API 28 SQLite and real loopback HTTP tests for catalog persistence, independent positions, preference persistence, deduplication, cascading deletion, startup reconciliation, invalid/expired token rejection, oversized/malformed upload rejection, and stopped listener behavior. Also EPUB 2/3 metadata and package validation, stable content hashing, sanitizer behavior, traversal, DRM, fixed layout, XML entity rejection, missing resources, expanded entry size, and entry count.
- `app/src/androidTest`: SQLite reopen, independent positions, deduplication, cascading deletion, preference persistence, startup reconciliation, live/expired/missing upload tokens, server shutdown, and WebView rendering/turning/resizing/restoring with EPUB 2/3 fixtures. Use `make device-test` on a compatible device. These tests remove books in the test installation; use a fresh test device or back up your books first.
- `app/src/androidTest/assets/fixtures`: original synthetic prose and 1-pixel image; no third-party book copyrights. EPUB 2 has NCX navigation and EPUB 3 has a navigation document. The tiny image verifies loading, not visual image layout quality.

## Optional desktop engine check

After `make check`, run `make engine-server`. This helper invokes the production Kotlin validator through its compiled JVM classes, then serves the sanitized fixtures and production renderer shell on loopback port 8765. It rewrites the Android virtual origin to loopback for the host browser only.

Open `http://127.0.0.1:8765/reader/index.html` in an isolated browser. `tests/engine_checks.js` is a browser-console regression suite. Paste it once the scripts have loaded. Run on a fresh shell with `?fixture=2` and `?fixture=3`. It verifies both EPUB versions, local images, chapter navigation, sequential page turns, boundaries, six sizes, two themes, and anchor containment after size changes. It returns a result array and throws on failure. This does not validate Android WebView request interception, native focus, LAN upload, lifecycle, or physical TV comfort.

The app deliberately does not persist viewport page numbers. Renderer assertions must compare CFI anchors or whether a saved passage falls within the displayed CFI interval.


v0.2.0 adds schema-1-to-2 catalog migration and PDF locator tests to the host suite. `PdfIntegrationTest` creates a native two-page PDF and verifies import/deduplication, malformed rejection, page turns, vertical scrolling and restored positions. `LayoutPairingIntegrationTest` checks landing copy bounds/underline centering, advances the private expiry only in the test, decodes the renewed QR bitmap, checks square bounds and rejects the old token. Production token lifetime remains ten minutes; no test-only intent or network endpoint is exposed.


v0.3.0 adds preference defaults, version-1 migration and all-fields round trips. SettingsIntegrationTest verifies remote option navigation, immediate persistence, Activity recreation, exact About book count, retained reader engine, CFI containment after text/spread changes, two-column layout and gap, and screen timeout/key reset. To protect a real device library, build with `./gradlew -PisolatedTestApp :app:assembleDebug :app:assembleDebugAndroidTest`, install both generated APKs, and run `adb -s DEVICE shell am instrument -w dev.couchreader.qa.test/androidx.test.runner.AndroidJUnitRunner`. This uses a separate `dev.couchreader.qa` data directory. Never run the destructive library tests against the user's main installation.

v0.3.1 strengthens SettingsIntegrationTest: waits for native state and renderer queue completion; typed module-internal access replaces field/method reflection. Repeated size changes must retain the original passage and database CFI; navigation clears that pending anchor. It checks clamped text/sleep endpoints, deferred reader-error overlays, and Settings focus restoration. Persistence assertions parse all fields directly from `shared_prefs/reading.xml` after asynchronous apply(); they do not claim an Activity recreation kills the app process.
