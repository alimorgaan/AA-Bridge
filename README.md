<p align="center">
  <img src="docs/logo.svg" alt="AA Bridge" width="480" />
</p>

AA Bridge brings Android Auto to cars whose screen only supports CarbitLink (also called EasyConnection) phone mirroring.

The app runs on your phone. It starts a local Android Auto session and sends it to the car over the CarbitLink USB connection. Touches on the car screen go back to Android Auto. It can also mirror the phone screen and has its own car home screen.

## What you need

- An Android 10 or newer phone with Android Auto
- A car whose screen has CarbitLink or EasyConnection
- A USB data cable

## Setup

1. Install the APK from [Releases](../../releases).
2. Turn on Android Auto developer mode: open Android Auto settings and tap Version 10 times.
3. Turn on the AA Bridge accessibility service. AA Bridge uses it only to start Android Auto's head unit server for you.
4. Plug the phone into the car's USB port and allow AA Bridge when Android asks.

## Known limits

- Android Auto's head unit server stops when the phone restarts. AA Bridge starts it again through the accessibility service.
- Phone mirroring needs the phone screen on and unlocked.
- Tested on a 2026 Geely Emgrand (Egypt) and one other CarbitLink head unit so far.

## Building

Needs JDK 17 and the Android SDK.

```bash
./gradlew assembleDebug
```

## Credits

Based on [CarAALink](https://github.com/JObersi10/CarAALink) by JObersi10. The Android Auto head unit certificate comes from aasdk, see [PROVENANCE.md](app/src/main/assets/aa/PROVENANCE.md).

## License

GPL-3.0, see [LICENSE](LICENSE).

Android Auto is a trademark of Google LLC. AA Bridge is not affiliated with Google or the makers of CarbitLink.
