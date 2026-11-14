# Motion Wall Cars — independent Android prototype v0.1

**Separate package ID:** `ai.motionwall.cars`; installed NOVA and stable release source are not changed.

## Implemented
- Android system image chooser; image downsampled to max 1800px and saved in private storage
- Native preview and real Android `WallpaperService`
- Eight-second loop: camera movement, rain, purple light and road streaks
- System wallpaper confirmation through `ACTION_CHANGE_LIVE_WALLPAPER`
- Wallpaper stops scheduling frames when invisible; ~15fps while visible
- No INTERNET, tracking, gallery/storage access, AI downloads or payment permissions

## Not implemented yet
True moving cars, rotating wheels, automatic segmentation, video generation, MP4 export,
cloud AI, financial donations, Google Play signing. The car movement is an optical effect.

## Build
Android SDK 36 / Gradle 8.13 / Java 17:
```bash
cd motion-wall
bash tests/run_core_tests.sh
gradle :app:assembleDebug --no-daemon
```
Debug APK in `app/build/outputs/apk/debug/app-debug.apk`.

## Phone acceptance
Choose one car photo; confirm the animated preview has no black edges;
tap apply; confirm in Android's official wallpaper UI; check home screen; switch apps;
lock/unlock; observe whether it stops consuming battery when hidden.
This is a debugging build, not a signed Play Store release. If changing an existing debug
installation fails, uninstall the *Motion Wall* test app only; never delete NOVA.
