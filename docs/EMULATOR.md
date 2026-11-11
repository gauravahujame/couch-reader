# Run locally on this Mac

An Android TV emulator named **CouchReaderTV** is installed. It uses the official Android 14 / API 34 Android TV ARM64 revision 3 system image, hardware-accelerated on Apple Silicon, with a 1920×1080 display and 2 GiB RAM.

From this project:

```sh
python3 scripts/run_emulator.py
```

The launcher reuses the running CouchReaderTV device, or boots a visible emulator window, then installs the debug APK and opens the app. Arrow keys navigate, Enter selects, and Escape goes back. The emulator toolbar also supplies remote controls.

This machine's current Android tools create AVDs in `~/.config/.android/avd`, while its emulator defaults to `~/.android/avd`. The launcher sets the correct AVD directory for its subprocess. It does not change shell settings.

Installed files:

- Image: `~/Library/Android/sdk/system-images/android-34/android-tv/arm64-v8a`
- Profile: `~/.config/.android/avd/CouchReaderTV.avd`
- APK: `dist/CouchReader-0.4.0-debug.apk`
- Launch log: `outputs/emulator/emulator.log`

The official image download was checked against SHA-1 `a31d0d8326f2c82787f95dd387614274082a96a0` from Google's image catalog. Its large zero-filled system partition is stored sparsely to conserve disk space. Temporary download archives were removed after installation. The emulator enforces a 6 GiB data partition and checks for roughly 7.2 GiB free before its initial creation; normal allocated disk usage is much smaller.

The sample **The Quiet Hour** is original synthetic text bundled with the integration tests. Emulator networking differs from a physical TV: the host test upload uses an ADB port forward. Uploading from a phone over a physical TV's LAN still needs a real-device check.
