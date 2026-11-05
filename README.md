# Couch Reader

A native Kotlin Android TV reader for your own DRM-free EPUBs and unlocked PDFs. Add books from a browser on the same home network, then read offline with a TV remote.

## Build and install

Use JDK 17 and Android SDK 35. Open the project in Android Studio, or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/Android/sdk`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.couchreader/.MainActivity
```

Minimum Android API 26. The debug APK is signed for sideloading. Actual-TV compatibility is still pending.

## Use

1. Open **Add book** on the TV. Connect the uploading device to the same trusted home network.
2. Scan the QR code, or enter the complete address shown. Select one EPUB or PDF, up to 50 MiB, and choose **Send to TV**. Keep Add book open until completion.
3. Choose **Read now**, or select the book in the library.
4. **Left / Right** turns pages. For PDFs, **Up / Down** scrolls within a fixed page; EPUB text-size and chapter controls apply to EPUBs only. **OK** opens text size, theme, and chapters. **Back** closes controls, then returns to the library. Up/Down does not move EPUB text. Held page-turn repeats are ignored.
5. Progress saves automatically after each completed page turn. Book actions offer confirmed removal.

While Add book stays open, the TV remains awake and the QR code renews automatically every ten minutes. Old tokens expire; scan the current code to reconnect. Leaving Add book or putting the app in the background stops the upload service. Guest Wi-Fi may block device-to-device traffic. Upload uses local HTTP and must stay on a trusted home network. No accounts, internet reading dependency, analytics, or cloud storage.

Unsupported: fixed-layout or right-to-left EPUBs, DRM/encrypted EPUBs, password-protected PDFs, and EPUBs requiring remote resources. EPUB package and markup are validated before a catalog entry is committed.

## Verification

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# With a compatible device/emulator attached:
./gradlew :app:connectedDebugAndroidTest
```

Instrumented tests include SQLite persistence and reconciliation, deduplication/deletion, token expiry, and WebView rendering with synthetic EPUB 2/3 fixtures. See [tests](tests/README.md), [decisions](docs/DECISIONS.md), [specification](docs/FEATURE_SPEC.md), and [development plan](docs/DEVELOPMENT_PLAN.md).
