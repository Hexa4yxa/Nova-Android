# NOVA Director Council v1 — Android/offline compatibility checkpoint

## What is implemented

The experimental application is named **NOVA Director Lab** and is installed as
`ai.nova.director.counciltest` in GitHub Actions. It does NOT replace the
working Gemma Fix build `ai.nova.director.gemmafix`.

**One loaded GGUF, three sequential reasoning roles:**

1. **NOVA director** drafts a shot-by-shot idea with a hook, scene timings and sound.
2. **Internal voice** challenges two or three artistic decisions using the SAME Gemma model.
3. **NOVA director** accepts or rejects criticism and returns a revised production plan.

A separate deterministic text checklist **Critic** flags whether the final plan
is sufficiently detailed and references timings, vertical format and sound.
It is NOT a third model and does NOT inspect or verify rendered video.

Three bounded passes: 200 + 160 + 320 max generated tokens, performed serially
through the existing single-model `InferenceEngineImpl` (one native `g_model`).
The plugin's existing generation lock also blocks parallel normal chat calls.
The existing cancellation method stops the currently generating pass, and the
council checks for cancellation between passes. No loops or autonomous retries.

Completed council plans are stored in Android app-private SharedPreferences,
most recent 8, accessible in the Council tab. No server required at runtime
after the GGUF has been imported.

## Preserved

- No change to core llama.cpp native inference or the working chat-template/BOS fix.
- Existing NOVA Gemma Fix #30 remains installable and unmodified.
- Android chat, streaming, stop control and 100-message chat UI history remain in place.
- Existing `nova_dual_mind.py` and `nova_critic.py` files on the user's device are **not** moved,
  deleted, or overwritten. Their actual contents have not been audited here.

## Limitations / honesty

- This release uses ONE model in three **roles**, not a genuinely independent Qwen brain.
- The entire council discussion stays in the model's in-memory conversation context;
  council and normal chat are not yet isolated. Cancelled passes may leave partial
  context. Adding safe model-context reset is a distinct future engineering task.
- The text Critic has no FFmpeg access, does not analyze frames or measure sync.
- This is a creative planning tool, not a renderer, agent, hotword detector or
  autonomous background process.
- No physical phone performance or stability can be guaranteed from CI builds.
- The new app owns a separate app-data directory; the user must import the GGUF once.

## Android acceptance tests (manual, after successful CI)

1. Keep Gemma Fix #30 installed. Install Director Lab test alongside it.
2. Import Gemma E2B GGUF in Director Lab using Android's document picker.
3. Check normal Chat can answer twice, in Russian, without visible analysis.
4. In **Совет**, enter e.g. "Ужас: отражение в зеркале запаздывает, 30 сек".
5. Verify live stage sequence plan → internal voice → revised plan,
   exactly once per stage and with no endless loop.
6. Verify the resulting Critic labels are limited to script features.
7. Close/reopen Director Lab and verify a saved project appears.
8. Trigger another council and press **Остановить**; confirm no third
   stage proceeds after cancellation.
9. With internet disabled after installing/importing the model, repeat a small test.
10. Open Gemma Fix #30 and verify that its existing chat/GGUF still works.

## Next milestone

Only after real Android tests pass, test Qwen separately with a short prompt,
and design safe sequential unload/load with rollback and context restoration.
Do not load two 3-4B GGUFs into the native singleton simultaneously.
Integration with Director, Vision, FFmpeg and historical Python Critic modules
must be staged and tested separately.

## Validation

- GitHub workflow: Node.js contract/syntax checks + Android Kotlin unit
  tests + assembleDebug. Native JNI library uses the same pinned llama.cpp
  reference as the stable Gemma Fix build.
- Passing CI proves compilation/test contracts, not runtime inference on
  Redmi Note 15 Pro+.
