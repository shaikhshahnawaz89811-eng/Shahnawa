# Background Execution Audit

## Result: PASS

### Generation
`LlamaBridge.generateWithContextStream()` / `generateContinueStream()` are invoked from a `Dispatchers.IO` coroutine. Native callbacks only append to synchronized buffers.

### UI streaming
A single coalesced `Dispatchers.Main.immediate` flush is scheduled approximately every 35 ms. There is no main-thread coroutine launched for every token.

### Persistence
The ViewModel snapshots small immutable references on the UI thread, then performs JSON serialization and SharedPreferences disk writes from `Dispatchers.IO`. This prevents large chat/workspace JSON construction from blocking rendering.

### Model import/load
The selected GGUF is copied and loaded from `Dispatchers.IO`.

### Attachments
Text/ZIP inspection runs from `Dispatchers.IO`; only the final attachment state is published to Compose.

### ZIP export
The workspace snapshot is compressed from `Dispatchers.IO`.

### Cleanup
Generation cancellation is immediate; native model shutdown is dispatched away from the UI thread.

## No full-screen loading state

There is no `CircularProgressIndicator`, `LinearProgressIndicator`, blocking modal loader, or fake timer for generation. The chat remains interactive except for the explicit generation action guard, and work is shown as compact inline lines.

## Why this design

Android's official guidance says the UI thread must not be blocked and time-consuming work should be moved to worker threads. Android also recommends Perfetto/CPU Profiler for real bottleneck measurement. llama.cpp's Android documentation warns that an excessive context size can cause memory spikes and recommends starting around 4096. The runtime profile in this project follows that conservative direction.
