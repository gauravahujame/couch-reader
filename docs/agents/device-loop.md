# Device loop

For the emulator, `docs/EMULATOR.md` and `scripts/run_emulator.py` cover setup. The physical TV is reached over Wi-Fi ADB. Get its serial from `adb devices -l` and pass `-s <serial>` on every call, because an emulator may also be attached.

## Build and install

From the agent shell, a foreground `./gradlew` can outlive the command timeout even after the build finished, because the Gradle daemon keeps the output stream open. When that happens, the commands chained after it never run. Run the chain as a background process that ends with a marker, then poll its output:

```sh
sh -c "./gradlew -q :app:assembleDebug && adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk && adb -s <serial> shell am start -S -n dev.couchreader/.MainActivity && echo DONE"
```

`am start -S` stops the old process first. Without it, the running instance keeps showing the previous build. If a result looks unchanged, compare the APK's modification time with your last edit before you debug the code.

## Seeing the screen

- Screenshot: `adb -s <serial> exec-out screencap -p > /tmp/shot.png`. Screens are 1920x1080.
- Move focus with `adb -s <serial> shell input keyevent DPAD_UP` (or `DPAD_DOWN`, `DPAD_LEFT`, `DPAD_RIGHT`, `DPAD_CENTER`, `BACK`), wait about a second, then take the screenshot.
- Edges, borders and shadows are a few pixels wide. Crop before you judge them: `sips -c <h> <w> --cropOffset <y> <x> file.png`. PIL is not installed.
- For the resting look, take the screenshot right after `am start -S`, before sending any key.
