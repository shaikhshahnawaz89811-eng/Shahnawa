# Final A→Z Verification Checklist

## Source structure
- AndroidManifest present
- Gradle settings present
- root build script present
- app build script present
- release ProGuard file present
- Termux build script present
- GitHub Actions workflow present
- unit tests present

## Chat
- blank Send blocked
- duplicate Send blocked during generation
- real local token stream
- 35 ms UI micro-batching
- Stop cancels native generation
- stale callbacks ignored
- complete/error finalization guarded
- long-response LazyColumn
- manual scroll respected
- IME resize enabled
- IME + navigation insets applied to composer

## Work display
- inline compact work lines
- no fake timer progress
- success/fail/paused states reflect real state
- tap-to-expand details
- create/update/delete result lines
- internal action markup hidden from visible response

## Workspace
- bounded project context
- safe relative path validation
- create file
- update file
- delete file
- selected editor refresh after update
- local persistence
- ZIP export from immutable snapshot

## Model
- GGUF import into app-private storage
- local-only inference
- conservative 4096 context profile
- mmap enabled
- CPU thread cap
- explicit cancellation
- native shutdown in ViewModel lifecycle
- no INTERNET permission

## Build / CI
- dependency graph resolution
- unit tests
- Android lint
- debug APK build
- APK existence check
- artifact upload
- pull-request dependency review

## Honest boundaries
- no web search engine in the APK
- no browser automation in the APK
- no embedded Android Gradle daemon
- no fabricated APK install result
- no fabricated build success
- no fake API endpoint
