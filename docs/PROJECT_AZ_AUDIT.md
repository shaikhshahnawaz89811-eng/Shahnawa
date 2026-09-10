# A→Z Project Audit

## Architecture

- One `SAViewModel` owns conversation, workspace, model state, task state and navigation.
- UI is Compose-only and uses shared colors/components.
- Native model calls run on `Dispatchers.IO`.
- UI state mutations are performed on the main coroutine where required.
- Model lifecycle is cancelled/shutdown in `onCleared()`.

## Duplicate-call checks

- `send()` returns while a generation is active.
- A generation has `generationFinished` and `generationCancelled` guards.
- Native `onComplete` and `onError` cannot both finalize the same generation.
- Stop/Pause marks the generation cancelled before calling native cancellation.
- Model reload shuts down the previous native instance before initializing the new model.
- Editing a prior turn resets the KV session before a new turn is generated.

## Threading checks

- Model import/copy: IO.
- Model initialization: IO.
- Native generation: IO.
- Stream UI flush: main view-model scope.
- ZIP output: IO, using an immutable workspace snapshot.
- Compose state is not mutated from the ZIP worker.

## State checks

- Chat and workspace are persisted locally.
- Selected file is persisted.
- Edited history truncates later turns.
- Model path/name metadata is persisted.
- A completed task snapshot is marked locally.

## UI/IME checks

- Activity: `adjustResize`.
- Composer: `imePadding()` + `navigationBarsPadding()`.
- Header does not use keyboard-relative absolute positioning.
- LazyColumn consumes the remaining resized height.
- Streaming auto-scroll only follows the bottom state.

## Honest capability checks

No code path reports a successful build, APK install, web search, file edit, or external action without a corresponding real implementation. The Build screen explicitly distinguishes the in-app workspace validation from an actual Android build environment.

## Known boundary

The current package has a real local GGUF chat/streaming bridge and real workspace/export persistence. It does not yet contain a complete autonomous patch parser, Android Gradle daemon inside the APK, web-search engine, or browser automation engine. Those are intentionally not represented as completed features.
