<p align="center"><img src="app/src/main/res/drawable-nodpi/openoven_logo.png" width="112" alt="Ikona OpenOven"></p>

<h1 align="center">OpenOven</h1>

<p align="center">An independent Android app for controlling compatible Amica ovens over your home Wi-Fi.</p>

<p align="center"><a href="https://techparka.github.io/openoven/">Project website</a> · <a href="https://github.com/techparka/openoven/releases/latest"><strong>Download APK</strong></a> · <a href="README.pl.md">Polski</a></p>

<p align="center">
  <img src="docs/screenshots/dashboard.png" width="29%" alt="Dashboard with oven status and quick controls">
  <img src="docs/screenshots/baking.png" width="29%" alt="Cooking in progress with remaining time and a stop button">
  <img src="docs/screenshots/settings-wheel.png" width="29%" alt="Temperature and time pickers">
</p>

*Screenshots show the Polish interface in demo mode. The emulator uses simulated oven readings.*

## Features

- Check the oven temperature and remaining time. Toggle the light and rapid preheat from the dashboard.
- Add time or adjust the temperature while cooking when the oven supports the change.
- Set temperature and time with swipe controls, configure up to three stages, or reuse recent settings.
- Save recipes with ingredients, instructions, and a photo. Import or export them as ZIP files, or start with one of four editable presets.
- Connect over home Wi-Fi without an account. Share the oven profile with a household member using a QR code. Use one active control session at a time.
- Try demo mode without an oven. In the app, go to <span lang="pl">Więcej → Połączenie z piekarnikiem → Wypróbuj demonstrację</span>.

## First connection

These steps apply when the oven is already connected to your home Wi-Fi.

1. Connect your phone to the same network and close any other oven control apps.
2. In OpenOven, open <span lang="pl">Więcej → Połączenie z piekarnikiem → Dodaj piekarnik → Piekarnik już jest w domowym Wi-Fi</span>.
3. On the oven panel, set <b lang="pl">ŁĄCZENIE</b> (pairing) to **ON** and press **OK**. The tested panel shows a countdown starting at 05. [Amica control panel guide (PDF, Polish)](https://www.amica.pl/media/wysiwyg/programatory/tv/android_manual.pdf)
4. In OpenOven, tap <b lang="pl">Otworzyłem WI — połącz</b>. When <span lang="pl">Gotowe</span> (Ready) appears, check the oven status on the dashboard.

Keep the oven's existing network settings. Share the profile QR code only with people you want to give access to local oven control.

## Testing

| Environment | What was tested |
| --- | --- |
| Amica ED87489BA+ Q-TYPE Steam | Version 0.5.1 was used to check live status, add the oven on an existing Wi-Fi network after clearing app data, reconnect after the pairing window closed, and pair again after a full reinstall. The fresh installation received a status reading after pairing was enabled on the oven. |
| Android 14 and 16 emulators | Version 0.6.0 passed 34 tests on each emulator, covering navigation, settings pickers, draft state, recipe ZIP files, permissions, and simulated oven control. |
| JVM | 56 unit tests cover connection logic, oven readings, cooking stages, setting changes, and session loss. |

Other oven models still need testing. Initial Wi-Fi setup was tested with the simulator. The physical oven tests used an existing home network.

## Build

The build requires JDK 17 and Android SDK 34. The app runs on Android 6.0 or later.

```sh
./gradlew :core:test :app:assembleDebug
```

On Windows, use `gradlew.bat`. The APK appears in `app/build/outputs/apk/debug/`.

## Website

The [OpenOven website](https://techparka.github.io/openoven/) includes screenshots, an interactive settings demo, and pairing instructions. The source is in `docs/`. To preview it locally, run:

```sh
python -m http.server -d docs 8000
```

OpenOven is an independent project. The Amica name identifies the appliances the app is designed to control.
