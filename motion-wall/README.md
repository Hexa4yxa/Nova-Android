# Motion Wall — Cars Edition

Independent native Android live wallpaper project. Does not import NOVA's Java/Kotlin modules and does not modify its installed data. Package name stays **`ai.motionwall.cars`** in every release.

## v0.2 bundled patch candidate

- Choose a photo with Android's document picker; privately keep a resized copy. No blanket Gallery permission.
- Fit the *entire* uploaded photo into the live-wallpaper foreground instead of portrait-cover cropping off half a car. A subdued copy fills the remaining screen without black bars.
- Three distinct periodic, entirely offline moods: Night Ride, Rain Drive and Neon.
- Adjustable 20–100% effect intensity; preset and slider settings survive app restarts. Active live wallpaper listens to preference changes without reinstalling wallpaper.
- Smooth, deterministic eight-second repeating camera movement, particles and colored reflection sweeps.
- WallpaperService only schedules draws while visible; reduces from about 20 FPS to 10 FPS when Android battery saver is active.
- Real system wallpaper picker. No network/AI/ads/MP4 export, and **wheels do not physically rotate**.
- Android SDK 36, min SDK 28, target SDK 36; same stable package ID, versionCode 2.

## IMPORTANT: one-time prototype signature migration

The owner's first installed v0.1 **TEST APK** used an ephemeral CI-generated **debug** certificate. Android enforces signing certificate continuity for any in-place update. This was discovered and independently verified by extracting the public signing certificates from the actual v0.1 and v0.2 APK files:

- v0.1 debug APK cert SHA-256: `1AC9470E5BD339BA43DF275ACFCDCFDFFE57E3ECCF676C70F18E8BE5826A1CCC`
- v0.2 owner-signed cert SHA-256: `570A89C508EBEE0EC13E7315762FB02A82C48317864FB2D88F30101355BA1305`

**No APK can upgrade v0.1 in place under the new owner certificate without the original ephemeral signing private key.** That private key was not retained. Do not suggest clearing data, forcing `adb install -r`, or bypassing Android signature checks.

When the owner is ready to migrate: first ensure the *original photo* still exists in Gallery/Downloads (the old app stores only its own private copy); then uninstall only the **Motion Wall Cars v0.1 TEST** application, install the v0.2 owner-signed candidate with the same package ID, reselect the original photo and reapply the live wallpaper. This is a **one-time exception**, not a second side-by-side application. No NOVA installation or data should be modified.

Subsequent Motion Wall releases must use the same owner-controlled signing certificate and strictly increasing versionCode so they **update in place**, preserving photo and preferences. The permanent owner key is provided by existing private GitHub Actions secrets and never committed to source code or uploaded in artifacts. At present the signer is intentionally shared with the owner's NOVA releases; distinct package IDs prevent app data sharing, but losing that owner key would affect both products.

## Build and checks

```bash
cd motion-wall
bash tests/run_core_tests.sh
gradle :app:assembleDebug --no-daemon
```

CI runs the Java regression suite, compiles the APK, verifies `ai.motionwall.cars`, and rejects network/gallery permissions. The owner-signed candidate workflow also validates the owner key's pinned public certificate and APK identity, then uploads **one private-to-the-repository Actions artifact**, without public GitHub Release publishing.

Do not call v0.2 Play-ready or claim online image animation. Before release, check the owner's live-wallpaper experience, image framing, effect loop and power use on real phones.

## Privacy

All photo processing remains on device. No `INTERNET` or `READ_MEDIA_IMAGES` permission, account, telemetry or billing. Images are imported through a user-initiated document picker into private app storage. `allowBackup=false`. Clearing data or uninstalling the application deletes its private copy, not the original photo in Gallery.
