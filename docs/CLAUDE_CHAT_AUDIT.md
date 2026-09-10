# Claude Chat UX Audit — applied to SA

## Public behaviour verified

Anthropic's current public documentation confirms:

- Claude Projects provide self-contained workspaces with chat history and project knowledge.
- Claude mobile supports file uploads from the chat composer.
- Claude can create and edit files from conversations.
- Claude Code exposes Plan mode, Accept Edits mode, permissions, diffs and rewind/checkpoint concepts.
- Claude's API supports incremental streaming events for generated content and tool/thinking events.

SA uses these as interaction references, not as a claim of access to private Claude UI code.

## SA mapping

| Behaviour | SA implementation | Status |
|---|---|---|
| Persistent chat surface | Chat remains primary while generation/work is active | PASS |
| Composer | IME-safe composer with plus menu and send/stop state | PASS |
| Attachments | Image/file/ZIP pickers; text and ZIP entries are bounded into local context | PASS |
| Live response | Real local GGUF callback deltas rendered incrementally | PASS |
| Stop | Native generation cancellation | PASS |
| Inline work flow | Compact one-line work log; tap expands detail | PASS |
| Create/update/delete file | Strict local action envelope with path/size validation | PASS |
| Long response | LazyColumn with bottom-follow guard | PASS |
| Keyboard | `adjustResize` + IME + navigation-bar insets | PASS |
| Edit previous turn | Long-press user message; later turns are truncated | PASS |
| Conversation continuity | Context-aware first generation + KV continuation | PASS |
| Offline boundary | No INTERNET permission and no network endpoint | PASS |
| Workspace validation | Real local structural validation | PASS |
| APK build inside app | Not claimed; GitHub Actions/Termux build path is used | HONEST LIMIT |

## Important UI distinction

The supplied 15-panel image is the visual master reference. The current chat work flow intentionally uses compact inline text lines rather than inventing a large dashboard card for every operation. This follows the user's requested Claude-like conversational presentation while preserving the image's typography, spacing, colors and dark/cyan visual language.

## Keyboard / layout decision

The chat layout is:

`Header -> resizable LazyColumn -> IME-aware composer`

The composer is not positioned with fixed screen coordinates. When the Android IME opens, the activity uses `adjustResize`, the list loses available height, and the composer consumes IME/navigation insets. The header stays in the normal layout flow.

Streaming auto-scroll follows only when the viewport is already at the bottom. Manual upward scrolling is not stolen.

## Completion integrity

Public Claude Code issue reports have documented cases involving false or incomplete reported file writes. SA therefore requires actual local state mutation before a create/update/delete work line becomes successful, and it never turns a build screen into a fake success state.
