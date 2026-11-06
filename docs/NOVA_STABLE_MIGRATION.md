# NOVA Stable: safe phone-only migration

## Critical user instruction (November 6, 2026)

**Do not install any GitHub Actions `app-debug.apk`, `NOVA-AI-APK`
or old `NOVA-Native-Image-Test.apk` as an update.** These are experimental
builds signed with disposable keys and Android will reject them with
"conflicts with another package". They do not upgrade the installed NOVA.

From this revision, CI development builds are labelled
`NOVA-DEBUG-INSPECTION-ONLY-NOT-AN-UPDATE` and native-image releases are
not auto-published. A one-time `NOVA-Stable.apk` will be offered only after
the same owner-controlled signing key is configured and tested.

The manual stable release workflow has been added to the **default branch**
so GitHub can show its workflow_dispatch button; its branch guard still
requires selecting the tested native-image branch, and without signing
secrets it exits without releasing anything.

**There is no cryptographic shortcut to updating an already installed APK
signed by a key we do not possess.** The first transition must preserve
the previous app until SD1.5, chat history and any other important data
are safe. Future `.stable` APKs can update each other, provided the signing
key is retained.

## Why we cannot replace the currently installed Native Image APK
Early `ai.nova.director.nativeimage` APKs were signed with GitHub-hosted
**ephemeral debug keys**. Android will not install a differently signed
APK over the same applicationId. A new permanent signing key **cannot**
retroactively upgrade these existing installs.

**Never ask the user to uninstall current NOVA to fix a signature mismatch.**
Uninstalling can delete the ~2.13 GB SD1.5 model stored under
`Android/data/ai.nova.director.nativeimage/files/Download/NOVA/models/`.

## What the owner has already done
The owner backed up
`v1-5-pruned-emaonly-fp16.safetensors` (~2.13 GB), using the Android system
file manager's **Copy** action, to:

`Internal storage/Download/NOVA-Models/v1-5-pruned-emaonly-fp16.safetensors`

The backup was visually confirmed as present, but **its SHA-256 has not yet
been independently confirmed**. A matching displayed size alone is not
sufficient. Keep both copies until hash verification completes.

Expected SHA-256:
`e9476a13728cd75d8279f6ec8bad753a66a1957ca375a1464dc63b37db6e3916`

## Safe one-time migration (after signed stable APK exists)

1. Keep the existing `.nativeimage` app installed. Do not clear its storage.
2. Install the new **separately identified** package
   `ai.nova.director.stable` beside it. The first installation is
   a migration, **not** an in-place update of the old debug-signed app.
3. Open Stable NOVA → **Мозги** →
   **Восстановить SD 1.5 из NOVA-Models**. Android's system document picker
   will open; navigate to Downloads / NOVA-Models and select the safetensors.
4. NOVA copies to its own private app folder while hashing. It activates the
   model **only after the full 64-digit SHA-256 matches**. The source backup
   stays unchanged in Downloads.
5. Verify PNG generation in airplane mode with Wi-Fi disabled, then Gemma
   text response. Review chat history and projects separately: WebView
   localStorage from the old app is **not automatically transferred** between
   different Android applicationIds. Until safe export/import is implemented,
   leave the old app installed to retain access to historical data.
6. Once all required data is independently recoverable, the owner may decide
   whether to remove the old test app. Never delete it automatically.
7. All future `.stable` versions must use the **same permanent signing key**
   and an increasing versionCode. Then Android updates the app in place and
   preserves its private data and downloaded model.

## Release signing: requires one owner-controlled secret setup

`.github/workflows/nova-stable-release.yml` is an **explicitly triggered**
workflow. It intentionally fails if any of the following GitHub Actions
repository secrets are missing:

- `NOVA_SIGNING_KEYSTORE_B64` — base64-encoded owner-owned PKCS12 keystore
- `NOVA_SIGNING_PASSWORD` — long, randomly generated password for the
  keystore and its `nova-stable` key alias

The project pins the **public certificate SHA-256** in
`android/signing/NOVA_STABLE_CERT_SHA256.txt`. GitHub Actions compares it
against both the supplied keystore and signed APK. A rotated or incorrect
keystore makes the release fail rather than causing another update conflict.

The owner-only signing key was created for this migration and packaged for
private download; it is **not** uploaded to GitHub source or posted into the
chat. The owner must download and preserve the ZIP; GitHub's Android workflow
cannot read it until the owner copies the two values above into repository
Actions secrets.

The owner must generate a secure long-lived signing key and keep an encrypted
backup **outside the project and outside the phone if possible**. Losing it
can prevent future direct updates. Never post keystore bytes, passwords, or
backup phrases in chat, tickets, GitHub issues, or commits.

Only after secrets are configured should the owner manually run the stable
workflow from the tested branch. It produces `NOVA-Stable.apk`, verifies the
Android signature and package ID, and publishes a release.

`ai.nova.director.stable` is a new permanent ID chosen to avoid colliding
with the existing debug-signed test package and its protected user data.
It is not silently installed or auto-updated without the owner's choice.

## Model recovery implementation

`NovaModelBackupPlugin` uses
`Intent.ACTION_OPEN_DOCUMENT` / `ContentResolver.openInputStream`;
the Android picker grants read access to exactly the selected backup.
No `MANAGE_EXTERNAL_STORAGE` or filesystem access bypass is used.

Restore streams into a temporary private file, reports progress, verifies
size and SHA-256, then atomically promotes the checked file to NOVA's
model folder and updates the selected-model preferences. It never moves
or deletes the backup in Downloads. Incomplete restoration removes only
the newly created temporary file.

**Current status:** source and CI tests; model restoration and future
stable update behavior require one real installation and on-device testing.
