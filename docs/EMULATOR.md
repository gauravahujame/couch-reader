# Run locally on this Mac

An Android TV emulator named **CouchReaderTV** is installed. It uses the official Android 14 / API 34 Android TV ARM64 revision 3 system image, hardware-accelerated on Apple Silicon, with a 1920×1080 display and 2 GiB RAM.

Build first, then run the launcher from the project root:

```sh
./gradlew :app:assembleDebug < /dev/null
python3 scripts/run_emulator.py              # boot, install the current build, open it
python3 scripts/run_emulator.py --boot-only  # boot only; prints the emulator serial
```

The launcher reuses a running CouchReaderTV or boots a visible emulator window (up to 3 minutes), then installs `app/build/outputs/apk/debug/app-debug.apk` and restarts the app with `am start -S`. It stops with a message if that APK has not been built or the install fails. Use `--boot-only` before installing anything else, such as the QA build in `tests/README.md`. In the emulator window, arrow keys navigate, Enter selects and Escape goes back; the toolbar also has remote controls.

The APKs in `dist/` are archived releases (0.4.0 is from 7 November 2026). Don't install them for testing: they predate current features, and an older build may not open a database that a newer build has upgraded.

This machine's Android tools create AVDs in `~/.config/.android/avd`, while its emulator defaults to `~/.android/avd`. The launcher sets the correct AVD directory for its own subprocess and does not change shell settings. Gradle signs debug builds with `~/.config/.android/debug.keystore`.

Installed files:

- Image: `~/Library/Android/sdk/system-images/android-34/android-tv/arm64-v8a`
- Profile: `~/.config/.android/avd/CouchReaderTV.avd`
- Launch log: `outputs/emulator/emulator.log`

The emulator enforces a 6 GiB data partition and checks for roughly 7.2 GiB free before it first boots. This Mac's disk has run nearly full, which stops the emulator from starting and makes Gradle tests fail with "No space left on device"; check `df -h /` when either fails oddly.

The test fixtures (for example **The Quiet Hour**) are original synthetic books in `app/src/androidTest/assets/fixtures`. Emulator networking differs from a physical TV: host uploads need an ADB port forward, so phone uploads over the TV's LAN still need a real-device check.
