# Verification

Run checks from the repository root:

- `make check` runs host unit tests (`:app:testDebugUnitTest`), Android Lint, `:app:assembleDebug` and `:app:assembleDebugAndroidTest`. Narrow a host run with `./gradlew :app:testDebugUnitTest --tests '<Class>'`.
- `make device-test` runs the instrumentation suite on a connected device. **It removes every book in the app under test.** Never run it against an install that holds the user's library; use the QA package below.
- `make engine-server` starts the optional desktop EPUB.js harness on port 8765. It needs a prior debug build (`make check` or `:app:assembleDebug`), because it runs the production validator from the compiled classes to prepare the fixtures. Open `http://127.0.0.1:8765/reader/index.html?fixture=2` (and `?fixture=3`) in a fresh tab, then paste `tests/engine_checks.js` into the console.

## Coverage

- `app/src/test` (Robolectric): catalog persistence and migrations, positions, preferences, import validation and metadata, deduplication, deletion and reconciliation, and real loopback upload/pairing.
- `app/src/androidTest`: EPUB WebView rendering, navigation and resume; PDF import, render, scroll and resume; Settings; the progress cache; upload/pairing; and layout. Fixtures in `app/src/androidTest/assets/fixtures` are original synthetic EPUB 2/3 books with a tiny image.
- `tests/engine_checks.js`: host-browser renderer checks only. It does not cover Android WebView interception, native focus, networking, lifecycle or TV comfort.

## Testing on a device that holds real books

Build and install the isolated QA app (`dev.couchreader.qa`, its own data directory), run it, then uninstall it:

```sh
./gradlew -PisolatedTestApp :app:assembleDebug :app:assembleDebugAndroidTest < /dev/null
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w dev.couchreader.qa.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> uninstall dev.couchreader.qa.test; adb -s <serial> uninstall dev.couchreader.qa
```

Rebuild without `-PisolatedTestApp` before installing the main app again.

## Writing tests

- EPUB positions are CFIs. The app never stores viewport page numbers, so assert that the saved passage falls inside the displayed CFI range, or compare PDF page and offset.
- Poll for a condition or for the renderer queue to drain instead of waiting a fixed time. Prefer `internal` members (as `SettingsIntegrationTest` uses) over reflection; `LayoutPairingIntegrationTest` still reflects into `UploadServer.pairing` to advance expiry.
- Check persistence by reading `shared_prefs/reading.xml` or the database after `apply()`. Recreating the Activity does not prove the value survives process death.
- Pairing: `LibraryIntegrationTest.browserCodePairsWithSessionCookieAndLimitsGuesses` covers codes, cooldown, cookies, cross-origin rejection, renewal and expiry. `LayoutPairingIntegrationTest` covers the renewed code and the token-free displayed address. Production expiry stays ten minutes; tests advance the private expiry instead of adding a test-only endpoint.
