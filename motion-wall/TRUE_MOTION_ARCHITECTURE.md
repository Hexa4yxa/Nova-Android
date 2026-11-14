# Motion Wall — True Motion pivot (2026-11-14)

## Product correction from owner's phone trial

The owner explicitly rejected the image + rain/glow/pan result: **the vehicle must actually move**, not be a static photo overlaid with particles. Previous v0.2 and in-progress CPU/GPU Canvas optimizations improve a wallpaper *renderer* but do **not** meet that core product promise. Never market the old effect as "AI driving animation" or release a tiny v0.3 patch claiming this goal is complete.

### Desired behavior

Input: one photograph of the user's actual car. Output: 4–6 seconds of generated motion, preferably loopable: genuine scene change, consistent car identity, rolling wheels and background movement, camera following the car. A 2D pan/zoom is *not* successful output. Model hallucinations, inconsistent license plates, physically broken wheels, and distorted body must be treated as failed generation. AI cannot guarantee exact brand/plate fidelity or perfect loops; inspect each clip.

### New two-part architecture

1. **Generate once (online; user opt-in)**: The app uploads the chosen photo to an image-to-video provider through **a server-owned proxy**, with protected API credentials, spend caps, rate limits, explicit cost/credit presentation, per-user moderation/report path, status/poll/cancel, and consent/privacy notice (remote image storage and deletion/expiry policy). The Android app never carries a provider API key. Provider should be swappable (Kling, Runway, or other licensed commercial endpoint); no unofficial free-service abuse.
2. **Use forever (offline)**: A verified MP4 clip is stored in the app's private storage. A *video* wallpaper engine uses Android `WallpaperService` and an Android media decoder / Media3 ExoPlayer attached to the wallpaper SurfaceHolder to loop the clip. Pause rendering and decoding when `onVisibilityChanged(false)`, release on destroyed surface. No network after generation; no CPU-based frame-by-frame Canvas repainting. User can choose crop/fit and verify loop seam and frame consistency.
3. **Optional fallback, labelled honestly**: Static wallpaper or old `Atmosphere` mode is separate and clearly labelled, not misrepresented as true vehicle motion. It should not be the default product demo.

### Release gates

- Preserve **package `ai.motionwall.cars` and owner-signed certificate** from v0.2; versionCode must increment and in-place install retains photo/settings.
- **Gate A (no AI spend):** Build a local video wallpaper player first; use a *legitimately sourced pre-existing car driving MP4* loaded via document picker to prove smooth, no black screen, no static screenshot and invisible-pause behavior on Redmi. Verify low battery and device rotation/lock screen behavior.
- **Gate B (one paid test, opt-in):** Connect exactly one authorized image-to-video endpoint and validate a single car photo -> true-motion clip -> local wallpaper. Do not make API calls or charge without explicit authorization and credits. Test 2+ generations if needed, track distortion and cost.
- **Gate C (loop):** Verify seamless cut using start/end frame analysis; where needed use provider start/end frame control, optical/interpolated seam or a gentle looping strategy. No "ping-pong" reverse for passing traffic unless it looks natural.
- **Gate D (Android acceptance):** Signed consolidated patch, real on-device installation, FPS/battery/heat tests, pause on hidden, verify photo/movie survive update, no new duplicate app.
- **Gate E (Play):** Rights/licensing, privacy policy, Data Safety, user report button, content moderation for remote AI-generated video, payment scheme when AI compute costs money; do not promise unlimited free AI video for voluntary donations.

### Provider research snapshot (2026-11-14)

- Runway Dev Gen-4 Turbo image-to-video supports image+prompt at 720p. Official API price: 5 credits/sec at USD 0.01/credit = **USD 0.25 per 5s clip**, excluding possible taxes. Source https://docs.dev.runwayml.com/guides/pricing/
- fal offers Kling 2.5 Turbo Pro image-to-video ~**USD 0.35 per 5s** (offer varies with model). Source https://fal.ai/explore/image-to-video-apis
- Newer Kling endpoints support first/last frame guidance; still no perfect guarantee. Source https://fal.ai/models/kling/v4/image-to-video/api
- Android ExoPlayer can render video via `setVideoSurfaceHolder` and repeat mode; WallpaperService must stop CPU work while invisible. Sources https://developer.android.com/media/media3/exoplayer/hello-world and https://developer.android.com/reference/android/service/wallpaper/WallpaperService.Engine

### Current status

**MP4 video wallpaper foundation implemented in source, NOT accepted on device.** VideoClipStore imports/validates a locally chosen MP4 (1–30 seconds, up to 50 MB), and MotionVideoWallpaperService loops it with Android MediaPlayer and pauses it when invisible. MainActivity exposes separate Video Wallpaper buttons, and the old still-photo service stays available. Android compilation on this source passed initial CI for commit `fd21d97d11`. **No image-to-video AI generation, cloud provider, server proxy, billing or true-photo-animation is connected yet.** The revised playback lifecycle is still under CI; must get owner phone acceptance for video playback before releasing a single signed v0.3 update. Never suggest the old effects are true car motion.
