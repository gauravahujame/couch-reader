# Device loop

Use this when a change needs checking on the emulator or the physical TV. Emulator setup is in `docs/EMULATOR.md`.

## Devices

- **Physical TV:** OnePlus, Android 11 (API 30), 1920×1080 at density 2, WebView 156, Google TTS. Reached over Wi-Fi ADB (`adb connect <tv-ip>:5555`; it was last at 192.168.1.55, but the address can change).
- **Emulator:** `CouchReaderTV`, API 34, 1920×1080. `python3 scripts/run_emulator.py --boot-only` boots it and prints its serial.

Get serials from `adb devices -l` and pass `-s <serial>` on every call, because both may be attached.

## The TV holds the user's real library

The user reads on this TV and imports their own books.

- **Never run `make device-test` or `connectedDebugAndroidTest` against the main `dev.couchreader` install.** `ReadingIntegrationTest` removes every book in the catalog under test. Use the isolated QA package instead (see `tests/README.md`), then uninstall it.
- Before sending keys, check that nobody is using the TV. Recent `Unhandled key` lines in `adb -s <serial> logcat -d` mean someone is pressing the real remote. Also check that `adb -s <serial> shell dumpsys activity activities | grep mResumedActivity` shows Couch Reader: other agent sessions sometimes use the TV too. If either check fails, stop and ask.
- Opening a book or changing a setting in the main app changes the user's saved positions, last-opened times and preferences. Do that kind of check in the QA package (`tests/README.md`), loading test books with the upload recipe below. If a check really needs the main app, ask the user first.
- With the user's agreement, a backup is a safety net, not a way to undo testing. Stop the app first, and copy whole directories, because the database keeps journal files (`library.db-journal`, or `-wal`/`-shm` in WAL mode) beside `library.db`:

  ```sh
  adb -s <serial> shell am force-stop dev.couchreader
  adb -s <serial> exec-out run-as dev.couchreader tar cf - databases shared_prefs files > outputs/backup/<new-name>.tar
  ```

  Restoring into the app has not been verified. Don't do it without asking the user, and never write a database while the app is running.
- Uninstalling deletes the library. Installing a lower `versionCode` over a higher one fails with `INSTALL_FAILED_VERSION_DOWNGRADE`.

## Build and install

From an agent shell, a foreground `./gradlew` can outlive the command timeout even after the build has finished, because the Gradle daemon keeps the output stream open. Any commands chained after it then never run. Run the chain in the background, end it with a marker, and poll its output. Redirect stdin (`< /dev/null`), because a shell prompt on this machine can otherwise block the build.

```sh
sh -c "./gradlew -q :app:assembleDebug < /dev/null && adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk && adb -s <serial> shell am start -S -n dev.couchreader/.MainActivity && echo DONE"
```

`am start -S` stops the old process first. Without it, the running instance keeps showing the previous build. If a result looks unchanged, compare the APK's modification time with your last edit before you debug the code. A `-PisolatedTestApp` build installs as `dev.couchreader.qa`; start it with `am start -S -n dev.couchreader.qa/dev.couchreader.MainActivity`. Rebuild without the property before installing the main app.

## Driving the screen

- Screenshot: `adb -s <serial> exec-out screencap -p > outputs/<new-name>.png`. `outputs/` is gitignored; use a new filename for each capture and delete the files when done.
- Keys: `adb -s <serial> shell input keyevent DPAD_UP` (or `DPAD_DOWN`, `DPAD_LEFT`, `DPAD_RIGHT`, `DPAD_CENTER`, `BACK`, `MENU`, `MEDIA_PLAY_PAUSE`). Wait about a second before a screenshot. `input keyevent 3` is HOME, not the digit 3; type digits with `KEYCODE_3`. Keys that must arrive together go in one call: `input keyevent KEYCODE_2 KEYCODE_2`.
- Check focus before pressing OK. The library focuses the most recently opened book, so counting keys blind is unreliable. Run `adb -s <serial> shell uiautomator dump /sdcard/ui.xml && adb -s <serial> exec-out cat /sdcard/ui.xml` and look for `focused="true"`. Put long sequences in a script that aborts on unexpected focus.
- The resting look is the screenshot taken right after `am start -S`, before any key. Then capture focused, and after focus has left.
- Edges, borders and shadows are a few pixels wide. Crop before you judge them: `sips -c <h> <w> --cropOffset <y> <x> file.png`. PIL is not installed.
- Read-aloud logs use the tag `CouchReadAloud` (state and sentence index only, never book text).

## Uploading a test book without a browser

The displayed address carries no token. Read the six-digit code and port from the Add book screen (the uiautomator dump above shows both), pair, then upload with the returned cookie:

```sh
curl -si -X POST -H "X-Pairing-Code: 123456" http://<tv-ip>:<port>/pair   # prints Set-Cookie: couch_pair_<port>=...
curl --data-binary @book.epub -H "Content-Type: application/epub+zip" -H "Cookie: couch_pair_<port>=<token>" "http://<tv-ip>:<port>/upload?name=book.epub"
```

For the emulator, forward the port first (`adb -s <serial> forward tcp:<port> tcp:<port>`) and use `127.0.0.1`. The upload page itself can be driven from desktop Chrome at a phone-sized viewport.
