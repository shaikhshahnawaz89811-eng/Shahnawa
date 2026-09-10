# UI Screen Map — image reference vs implementation

The supplied 15-panel image is the visual master reference. The implementation keeps one shared visual system rather than creating unrelated screens.

1. Normal Chat — `Chat()`
2. User Typing — same `Chat()` state with IME-aware composer
3. AI Thinking / Working — inline `WorkLog()` with compact expandable lines
4. Code Streaming / Live Update — `CodeStream()`
5. Project Files / Explorer — `Files()`
6. Build & Run — `Build()` with real workspace validation + honest CI boundary
7. Error Handling / Auto Fix — `Error()` root-cause pipeline
8. Attachments — composer attachment menu + bounded text/ZIP context handling
9. Large Message / Auto Scroll — same chat list with bottom-follow guard
10. Pause / Resume — `Pause()`
11. Edit Previous Message — long-press user bubble -> `Edit()`
12. ZIP Only When Asked — `Zip()` / CreateDocument
13. Create New Project — `NewProject()`
14. Continue / Resume Later — `Resume()` + persisted workspace/session metadata
15. Settings / Model / Tools — `Settings()`

## Shared visual tokens

- Background: #050914
- Surface: #091221
- Secondary surface: #0D182A
- Blue: #168BFF
- Cyan: #2EDBFF
- Text: #EAF6FF
- Muted: #7894AF
- Success: #2BD37E
- Error: #FF5872
- Amber: #FFC857
- Consistent 14–18 dp card radius family
- Compact 34–36 dp top-bar controls
- 12–13 sp chat body and 10–12 sp metadata

The image is treated as a visual reference, not as a license to fabricate functionality. UI status must be backed by real state.
