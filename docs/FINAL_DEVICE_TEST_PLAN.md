# Final Device Test Plan and Expected Behavior

## Background work / UI rule

- Model import, native model initialization, inference, attachment reading, ZIP export, and persistence JSON serialization/disk writes are background work.
- The chat screen never switches to a full-screen loading/progress screen for generation.
- The visible work state is compact inline text only; it is not a blocking loader.
- Compose state updates return to the main thread in bounded/coalesced updates.
- Native token callbacks are coalesced to roughly one UI flush per 35 ms while generation continues on the worker/native path.

This follows Android's threading guidance: do not block the UI thread; move time-consuming work to worker threads and update UI state on the main thread. Android also recommends measuring real bottlenecks with Perfetto/CPU Profiler. See the official Android performance guidance and llama.cpp Android guidance in `WEB_RESEARCH_SOURCES.md`.

## Representative task used for manual device validation

> Create a simple Android Notes app in Kotlin with add, edit, delete, and a local in-memory list. Inspect the existing workspace first, create or update the required files, and do not claim a build succeeded unless a real build was executed.

### Expected sequence

1. User sends the request.
2. User message remains visible.
3. Inline work lines appear: `Read workspace`, `Prepare task context`, `Generate`.
4. Local GGUF generation starts in the background.
5. Assistant text grows progressively from real token deltas.
6. Completed `<sa_action>` blocks become compact `Created/Updated/Deleted` work lines.
7. Tapping a work line expands only its detail; tapping again collapses it.
8. If generation stops, no late callback may turn the task back to success.
9. If generation completes, only the tracked `Generate` line becomes successful; a later `Created file` line must not be overwritten.
10. `Verify` is added after generation finalization.
11. Workspace state is persisted off the UI thread.
12. A real APK build must be performed by the configured Termux/GitHub build pipeline; SA does not fabricate an APK-success state inside the chat UI.

## Time estimate (not a device benchmark)

Exact speed must be measured on the target phone. A 1.5B Q4 model can vary substantially with CPU architecture, thermals, thread count, context length, and memory bandwidth. The project therefore does not hard-code a fake ETA.

For planning only:

| Work | Expected range on a phone-class CPU | Main variable |
|---|---:|---|
| Small prompt/context preparation | <1–10 s | project size / storage |
| First response startup | ~5–60 s | prompt processing + model load/cache |
| 200 generated tokens | ~25 s–3 min | tokens/s |
| 500 generated tokens | ~1–6 min | tokens/s + context |
| 1000 generated tokens | ~2–12 min | tokens/s + context |
| First Gradle Android build in Termux | ~1–10 min | CPU, Gradle cache, dependencies |
| Incremental Gradle build | ~10 s–3 min | changed modules / cache |

These are planning ranges, not measured results. A real on-device benchmark should record prompt-processing tok/s, generation tok/s, peak RAM, temperature, cancellation latency, and time-to-first-token.

## Failure cases to test on device

- No model selected.
- Model import cancelled midway.
- Invalid/corrupt GGUF.
- Model too large for available memory.
- Generation stopped by user.
- Generation paused and resumed.
- Native generation error.
- App process killed during generation.
- Keyboard opened while streaming.
- User scrolls upward while tokens arrive.
- User sends twice rapidly.
- Model callback completes and errors nearly simultaneously.
- File create for an existing path.
- File update for a missing path.
- Unsafe path (`../x`, absolute path).
- Oversized generated file action.
- ZIP export cancelled.
- Large workspace persistence.
- Edit an earlier user message and regenerate.
- Model reload while a previous model exists.

## Known boundary

The APK does not contain a full Android SDK/Gradle daemon or a shell executor. Real Android builds are delegated to the Termux/GitHub build pipeline. This is intentional: claiming an in-app build succeeded without a real compiler would be a false result.
