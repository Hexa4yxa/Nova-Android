# Motion Wall Cars — Privacy Policy

**Effective date:** 14 November 2026  
**Application:** Motion Wall Cars (`ai.motionwall.cars`)  
**Developer contact:** [Open a support or privacy inquiry on GitHub](https://github.com/Hexa4yxa/Nova-Android/issues).

This policy applies to the experimental Android app and is updated before public Google Play publication.

## What the app does

Motion Wall Cars lets you choose a photo or MP4 video from your device and set it as an Android live wallpaper. It includes a locally generated sample driving animation and an optional subject-segmentation beta using Google ML Kit.

The application does not require registration, subscription, access to all photos, or a payment method.

## Your photos and video

- You select individual files through Android's system document picker.
- The app copies the chosen photo or MP4 into its **private internal app storage**. It uses them only to preview or display wallpaper. The chosen files are **not uploaded to our servers**.
- The optional "Drive 2.5D" feature separates the subject from its background **on the device** using Google's ML Kit subject-segmentation SDK. Its output is saved privately on the device.
- The app does not ask for `INTERNET`, `READ_MEDIA_IMAGES`, or broad gallery/storage permissions, and does not run its own analytics or ad network.
- Photos and clips stay in private app storage until you replace them, clear application data or uninstall. We do not keep a backup on our servers. Your original Gallery files are not changed or deleted by the app.
- Files stored internally are protected by Android's standard application sandbox and applicable device storage protections; no separate app-level encryption feature is claimed.

## Google ML Kit / Google Play services

The optional subject-segmentation feature uses Google ML Kit and Google Play services. The input image and segmentation result are processed **on-device** and are not sent to Google servers by ML Kit.

However, ML Kit / Google Play services may connect to Google to download/update model components and report **SDK performance and diagnostic information**, including device/app information, identifiers and error events as described by Google. This may occur separately from the application's own network permission.

Sources:
- [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms)
- [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)

Review Google's privacy terms for Google's handling of SDK metrics. We do not control Google's independent Google Play services infrastructure.

## Advertising and payments

This experimental version contains **no advertising, subscriptions, paid unlocks or in-app purchases**. We do not collect payment information. An optional voluntary donation feature may be added only after separate review of Google Play requirements and this privacy policy.

## Data rights / questions

To remove the app's locally stored photo, MP4 and generated mask, use Android **Settings → Apps → Motion Wall Cars → Storage → Clear data**, or uninstall the application. This does not remove your original photo or video from the Gallery.

Questions or privacy requests can be submitted via the developer contact link above. Since we have no server database of user photos, we cannot retrieve or delete copies located only on your phone.

## Future online AI

This version **does not upload photos for cloud AI generation**. If an online AI provider is ever added, the app must first introduce a separate explicit opt-in, new data handling disclosures, destination/provider identification, retention details and appropriate safeguards. This policy must be updated before releasing that feature.
