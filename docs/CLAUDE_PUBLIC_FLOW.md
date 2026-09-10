# Claude public A→Z interaction model used as SA reference

This document intentionally distinguishes **publicly documented behaviour** from private implementation details that Anthropic does not publish.

## 1. Start a project / session

Claude Projects are self-contained workspaces with their own chats and knowledge. A project can contain files and project instructions. On mobile, Claude projects are available and chats can be started inside them.

SA equivalent:
- one persistent local project workspace
- project files stored locally
- chat history stored locally
- bounded project context supplied to the local model

## 2. User writes a request

The composer remains the main interaction surface. Attachments are added from the composer using the plus control in Claude's public mobile documentation.

SA equivalent:
- IME-aware composer
- plus menu
- image/file/ZIP/code entry points
- Send is disabled for blank text
- duplicate generation is blocked

## 3. Work starts in the conversation

Claude Code publicly exposes a plan mode that is read-only, while code/accept-edits modes permit changes according to permissions. Claude Code also exposes Read, Edit and Write style operations and a diff/rewind workflow.

SA equivalent:
- work is shown inline in the chat instead of navigating away from the conversation
- each work line is a single compact line
- tapping a line expands its details
- no fake progress timer is used

## 4. Read / inspect

Before changing a project, SA reads the bounded local workspace and prepares a context snapshot. The 1.5B model is not given an unbounded project dump.

The work line only becomes `✓` after the local preparation operation returns.

## 5. Generate / stream

Anthropic documents incremental streaming events for text and tool/thinking events. SA uses the local GGUF stream instead of an HTTP API.

Visual rule:
- response text grows in place
- no fake token animation
- UI updates are micro-batched for smoothness
- Stop cancels the native generation

## 6. Create / update / delete a file

SA uses a strict internal action envelope:

`<sa_action type="create|update|delete" path="relative/path">content</sa_action>`

Only valid relative paths are accepted. `..`, absolute paths and oversized content are rejected.

After an accepted action:
- workspace state changes
- the selected editor is refreshed when needed
- a compact work line reports the real result
- tapping the line reveals the detail

The visible chat response hides the internal action envelope so the user does not see tool protocol markup.

## 7. Verification

A successful work line is not itself proof of a build. SA only reports an operation that actually happened.

This is deliberately stricter because public Claude Code issue reports have documented cases where agents reported file changes without the expected filesystem state. SA therefore keeps an explicit post-action state boundary.

## 8. Errors

If local generation fails:
- the active work line becomes failed
- the error is shown in the error screen
- no success state is emitted afterward
- stale completion callbacks are ignored

A full autonomous Android build/auto-fix loop is not claimed until a real build executor exists in the APK.

## 9. Edit previous message

Editing a user turn truncates later turns and resets the local model session. This prevents the model from continuing with stale context from the discarded branch.

## 10. Pause / resume

Pause cancels native generation. Resume returns the task prompt to the composer; it does not pretend that generation continued while cancelled.

## 11. Keyboard behaviour

The activity uses `adjustResize`. The chat list occupies the resized space and the composer consumes IME/navigation insets. The header is not positioned using absolute keyboard-relative coordinates.

Streaming auto-scroll follows only when the viewport is already at the bottom. Manual upward scrolling is not stolen.

## 12. File creation / artifacts distinction

Claude's public file-creation and artifact documentation shows that substantial generated content may appear in a dedicated artifact/file surface. SA's target image is a coding-chat UI, so SA keeps code/file work inside the project/file screens rather than pretending that a proprietary Claude artifact surface is identical.

## 13. Public-reference limitation

Anthropic does not publish a complete specification for every pixel, private animation duration, internal tool-card implementation, or exact mobile rendering state. Those details cannot honestly be claimed to be an exact copy from web research alone.

SA therefore matches the documented interaction principles and the supplied 15-panel visual reference, while keeping its own implementation and offline constraints explicit.
