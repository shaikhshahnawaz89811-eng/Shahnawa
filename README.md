# SA Offline Coding Assistant — v2.3.0

This package is the fresh audited SA revision focused on a **Claude-like coding-chat work flow**, while keeping the supplied 15-panel image as the visual master reference.

v2.3.0 redesigns how file generation is shown: earlier revisions rendered a single generic
"Generate" line for an entire turn regardless of how many files it touched, and the chat text
visibly stalled while an `<sa_action>` block streamed in. Now each file gets its own card the
moment its opening tag appears, live content streams into it in place, and it collapses to a
one-line summary once it finishes — see `docs/CARD_STREAMING_REDESIGN.md` for the full design
and the reasoning behind it. Generic steps (Read workspace, Generate, Wiring, Verify) still
render as the existing compact inline lines; only per-file activity is a card now.

## What is real in this revision

- Persistent chat remains visible while work happens.
- Local GGUF token streaming is rendered incrementally.
- Streaming is UI-micro-batched for smoothness; it is not simulated token animation.
- Generic step activity (Read workspace, Generate, Wiring, Verify) is shown as compact inline text lines; tap a line to expand its real detail.
- Each file create/update/delete gets its own live card the moment its action tag opens, showing real streamed content in a height-capped, scrollable preview, and collapsing to a one-line summary once it finishes. Nothing renders before there is real model output behind it, and no card is invented for a turn that touched no files.
- After a turn's file actions finish, Wiring runs a best-effort local check of imports and AndroidManifest entries across the files that were touched. It is a static heuristic over in-memory files, not a compiler or a build.
- Validated model action envelopes can create, update, or delete workspace files.
- File actions are path-safe and size-bounded.
- The visible assistant response hides internal action markup.
- Workspace changes are persisted and the selected editor is refreshed after an update.
- Text/code/JSON attachments are read into a bounded local attachment context. ZIP attachments expose their entry list. Binary/image attachments are stored as metadata only because the selected 1.5B text model is not a multimodal vision model.
- Keyboard handling uses `adjustResize`, IME insets, navigation-bar insets, and bottom-safe streaming scroll behaviour.
- Stop, pause, completion and error paths are guarded against duplicate/stale native callbacks.
- Editing an earlier user message truncates later turns and resets the native context.
- ZIP export uses a snapshot of the current workspace.
- Workspace validation is a real local check; the app does not fabricate an APK build result.
- GitHub Actions performs dependency resolution, unit tests, Android lint, debug APK assembly and artifact upload. Pull requests also run dependency review.

## Offline model profile

Target model: `Qwen2.5-Coder-1.5B-Instruct-GGUF`, preferably `Q4_K_M`.

The official Qwen GGUF repository currently lists the Q4_K_M file at about 1.12 GB. SA uses a conservative 4096-token runtime context, mmap, a 2–6 CPU-thread cap, 256 prompt batch, and 1024 maximum output tokens to keep memory use appropriate for phone-class hardware.

## Claude reference boundary

Anthropic publicly documents Projects, file creation/editing, attachments, Claude Code Plan/Accept Edits modes, permissions, diffs, rewind, and incremental API streaming. It does **not** publish a complete pixel-level specification of every private mobile animation, card implementation, or internal tool renderer.

Therefore SA reproduces the documented interaction principles and the supplied visual reference without falsely claiming access to private Claude implementation details.

## Build

GitHub Actions uses Java 17, Gradle 9.6.1, Android API 37 and the pinned Android/Compose dependencies in `app/build.gradle.kts`.

A Gradle wrapper is not bundled because this package is intended for the user's Gradle-capable Termux environment or the configured GitHub Actions runner.

## Capability boundary

The local 1.5B model is substantially smaller than a large cloud coding agent. SA therefore does not claim web search, browser automation, autonomous Android Gradle execution inside the APK, image understanding, APK installation, or successful builds unless those operations are genuinely implemented and verified.

## Final QA

See `docs/FINAL_DEVICE_TEST_PLAN.md` and `docs/BACKGROUND_EXECUTION_AUDIT.md` for the final background-thread audit, manual device test matrix, and honest performance-estimation limits.
