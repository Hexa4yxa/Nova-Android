# NOVA — first permanent update channel (phone only)

## Important

This is a ONE-TIME transition from older CI debug-signed
`ai.nova.director.nativeimage` to a permanent app
`ai.nova.director.stable`. Android cannot update an installed app with an
unavailable/incompatible signing key. **Never uninstall the old NOVA just to
make a new APK install.** Backed-up SD1.5 is at
`Download/NOVA-Models/v1-5-pruned-emaonly-fp16.safetensors`.

The owner received a private `NOVA-Stable-Owner-Key-Backup.zip` file in
ChatGPT. The ZIP contains the protected PKCS12 signing key and two text
files. It is not an APK and must not be publicly shared or uploaded to
GitHub source. Store at least one safe private backup outside the phone
when possible.

## Exactly two GitHub Actions secrets

From the owner's Android browser:
1. Open `https://github.com/Hexa4yxa/Nova-Android/settings/secrets/actions`
   while signed into the owner account. In a mobile browser, "Desktop site"
   may be needed to show **Settings**.
2. Tap **New repository secret** and use:
   - Name: `NOVA_SIGNING_KEYSTORE_B64`
   - Value: copy ALL one-line text from
     `NOVA-SECRET-1-KEYSTORE-B64.txt` inside the extracted ZIP.
3. Tap **New repository secret** again and use:
   - Name: `NOVA_SIGNING_PASSWORD`
   - Value: copy the whole one-line text from
     `NOVA-SECRET-2-PASSWORD.txt`.
4. Never put either value in PR comments, Issues, code, screenshots or chat.
   GitHub repository secrets are write-only; once saved, their values cannot
   be displayed again. The private ZIP must be retained by the owner.

The known public certificate SHA-256 is pinned in
`android/signing/NOVA_STABLE_CERT_SHA256.txt`.
CI rejects a nonmatching signing key or APK.

## Build the first signed NOVA

1. Open `https://github.com/Hexa4yxa/Nova-Android/actions`.
2. Select **NOVA Stable • signed manual release**.
3. Tap **Run workflow**, select branch
   `feature/nova-native-image-engine`, then **Run workflow**.
4. Wait for a **successful** job. It first verifies the signing key,
   compiles/tests, signs and checks the package and signer, and only then
   publishes a `nova-stable-N` prerelease.
5. Get `NOVA-Stable.apk` from that exact successful prerelease.
   Do **not** install `app-debug.apk` or `NOVA-Native-Image-Test.apk`
   as an update. Old test APKs are not permanent releases.

## One-time migration without losing SD1.5

1. Install `NOVA-Stable.apk` **beside** the old NOVA. Package name is
   `ai.nova.director.stable`; it does not erase old app storage.
2. In new NOVA, choose **Мозги → Восстановить SD 1.5 из NOVA-Models**.
   Android picker: **Downloads → NOVA-Models →
   v1-5-pruned-emaonly-fp16.safetensors**.
3. NOVA copies the model and compares the full SHA-256 before activation.
   The backup in Downloads is never deleted or renamed.
4. Test offline PNG creation and chat/Gemma. The model import from the
   other old app and chat-history transfer are NOT automatic; keep old
   NOVA until all required user data has been preserved.
5. Future `NOVA-Stable.apk` releases use **the same key** and increasing
   `versionCode`, so Android can update `ai.nova.director.stable` in place.

This text is a setup guide, not a claim that the stable APK has already been
signed or tested on the Redmi. Never clear data or uninstall the old NOVA
without separately checking old messages and other personal files.
