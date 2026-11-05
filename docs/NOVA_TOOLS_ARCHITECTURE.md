# NOVA Tools: offline-first local executor

## Goal
NOVA runs reasoning, voice, vision and media tools offline on Android. Network is a **separate optional acquisition capability**, used only after the owner approves the exact resource. Downloading a file is not permission to execute it.

## Existing state (Director Lab)
- One offline GGUF powers chat and three sequential director / inner voice / critic passes.
- `NovaBrain.importModel` imports a user-selected GGUF via Android's document picker.
- Android manifest already declares `INTERNET`. There is no arbitrary file-manager, downloader, app installer, shell or video generator.
- Never overwrite the user's existing installed Director Lab or delete stored models without approval.

## Safe tools architecture
```
User intent
  -> NOVA core (local Gemma, no arbitrary-code execution)
  -> planner (bounded structured action + arguments, never raw shell)
  -> policy & permission gate
  -> native Android tool registry
  -> result + integrity/status
  -> director/critic, optional next bounded action
```

### Phased capabilities

1. **Resource acquisition (prototype in this branch)**: native `NovaTools` bridge; HTTPS URLs restricted to approved GitHub/Hugging Face hosts; models / media only; native confirmation for each download; Android DownloadManager manages transfer; native app-local download list, status and cancellation; optional SHA-256 verification; export via system document picker. No code or APK installation. No LLM-initiated downloads yet.
2. **File tools**: `listSelectedDirectory`, `inspectMedia`, `copy/move`, `export` using Storage Access Framework with user-granted scoped folder URIs; never request `MANAGE_EXTERNAL_STORAGE` by default. Destructive operations require explicit approval.
3. **Model manager**: trusted machine-readable resource catalog with verified hashes, licenses, target RAM/disk and compatible engines; verify before activation, use staging and rollback; don't overwrite a running GGUF.
4. **Media tools**: offline FFmpeg engine, voice, frame extraction and local image generation; read/write only approved project directories.
5. **Android assistant tools**: open applications via intents. Accessibility and voice access only as separately requested, justified opt-in features; do not bypass Android restrictions.
6. **Structured tool planner**: allowlisted tool schemas, per-action explicit privileges, bounded retries, audit log and stop control. Never treat model-generated text as shell commands, URLs to execute, or blanket permission grants.

## Network modes
- Default: local AI/voice/project operations stay offline. The downloader initiates network traffic only after a native per-download approval prompt.
- User may keep airplane mode on for entirely offline use.
- Future switch: `Network resources disabled` should disable the downloader independently of the `INTERNET` manifest permission. Android does not grant an app an unrestricted 'install anything' permission through this switch.

## Permission plan
| Capability | Mechanism | Owner approval |
|---|---|---|
| Download allowed model/media | existing `INTERNET` + DownloadManager | Native prompt per resource |
| Read/write chosen local files | `ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`, chosen folder via `ACTION_OPEN_DOCUMENT_TREE` | Android system picker |
| Background long-running downloads | DownloadManager system service | Initial resource prompt |
| Microphone, notifications | only when implementing voice/background | Runtime permission as needed |
| Full storage access | **Not required** for starter tools | Avoid |
| Install APK or run code | **Not implemented** | Requires separately reviewed design |

## Risk controls and test acceptance
- Reject non-HTTPS, unapproved hosts, local IPs, URLs with embedded credentials, file/path traversal and executable extensions.
- Check URLs as untrusted input and sanitize filenames. Do not make network calls from LLM-generated prose.
- Make source, expected checksum (when available), storage location and potential metered data charges visible before downloading.
- Verify saved files with SHA-256; mismatch means "untrusted"; do not activate an unchecked model automatically.
- Storage for queued/completed files is app-scoped. Export via picker for long-term user-owned storage; avoid assuming external private downloads are permanent.
- Confirm download in airplane mode pauses and resumes after connectivity returns; confirm imported previous GGUF and council are unchanged.
- CI must pass JS wiring, Kotlin policy unit tests and APK build. On-device use remains unverified until installed and exercised.

## Later: Director workflow
One authorized command ("make a 20s horror video") becomes:
read approved media -> scenario -> critique -> render -> inspect -> export;
request permission only when accessing new outside sources, public posting or deleting material.
