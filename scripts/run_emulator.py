#!/usr/bin/env python3
"""Launch the locally installed CouchReaderTV AVD and open Couch Reader."""
from pathlib import Path
import os
import subprocess
import time

PROJECT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
ADB = SDK / 'platform-tools/adb'
APK = PROJECT / 'dist/CouchReader-0.3.1-debug.apk'
AVD = 'CouchReaderTV'
LOG = PROJECT / 'outputs/emulator/emulator.log'


def command(*args, check=True, timeout=15):
    return subprocess.run([str(ADB), *args], capture_output=True, text=True,
                          check=check, timeout=timeout)


def find_device():
    for line in command('devices').stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) != 2 or not fields[0].startswith('emulator-') or fields[1] != 'device':
            continue
        name = command('-s', fields[0], 'emu', 'avd', 'name', check=False).stdout.splitlines()
        if name and name[0].strip() == AVD:
            return fields[0]
    return None


def main():
    if not APK.is_file():
        raise SystemExit('Build the debug APK first; see README.md.')
    candidates = [Path(os.environ['ANDROID_AVD_HOME'])] if os.environ.get('ANDROID_AVD_HOME') else []
    candidates += [Path.home() / '.config/.android/avd', Path.home() / '.android/avd']
    avd_home = next((p for p in candidates if (p / (AVD + '.ini')).is_file()), None)
    if avd_home is None:
        raise SystemExit('CouchReaderTV is not installed on this machine. See docs/EMULATOR.md.')
    device = find_device()
    process = None
    if device is None:
        LOG.parent.mkdir(parents=True, exist_ok=True)
        environment = os.environ.copy()
        environment['ANDROID_AVD_HOME'] = str(avd_home)
        with LOG.open('w') as log:
            process = subprocess.Popen([
                str(SDK / 'emulator/emulator'), '-avd', AVD, '-gpu', 'auto',
                '-no-snapshot', '-no-boot-anim', '-no-audio', '-memory', '2048',
            ], stdout=log, stderr=subprocess.STDOUT, env=environment, start_new_session=True)
        print('Starting Android TV…', flush=True)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise SystemExit('Emulator exited. See ' + str(LOG))
        device = find_device()
        if device and command('-s', device, 'shell', 'getprop', 'sys.boot_completed', check=False).stdout.strip() == '1':
            break
        time.sleep(2)
    else:
        raise SystemExit('Android TV is still starting. See ' + str(LOG))
    result = command('-s', device, 'install', '-r', str(APK), timeout=60)
    print(result.stdout.strip())
    command('-s', device, 'shell', 'am', 'start', '-n', 'dev.couchreader/.MainActivity')
    print('Couch Reader is running in ' + AVD + ' (' + device + ').')
    print('Use arrow keys to navigate, Enter to select, and Escape to go back.')


if __name__ == '__main__':
    main()
