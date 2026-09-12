# Card streaming redesign (v2.3.0)

## The problem this fixes

In v2.2.0, file generation was invisible while it was actually happening:

- The visible chat bubble is derived by cutting the raw stream at the first still-open
  `<sa_action>` tag (see `visibleResponse()`). That's correct — raw action markup should never
  reach the chat bubble — but nothing replaced it, so the bubble text visibly stalled for as
  long as a file took to write, with no indication of what was going on.
- Only one generic "Generate" work line existed per turn. A request that created five files and
  a request that created one looked identical while running: one spinner, no file name, no
  progress.
- A "Created path/to/File.kt" line only appeared after `</sa_action>` had fully streamed in, so
  there was no live view of a file while it was being written.
- `CodeStream` was a second, separate full-screen destination with no automatic link to what was
  actually streaming — reaching it required a manual "Live" tap, and it did not select the file
  currently being generated.
- There was no "wiring" step at all: files were created/updated/deleted independently with
  nothing checking whether they actually referenced each other correctly.

## What changed

**Open-tag detection.** `applyModelActions()` used to require a full `<sa_action ...>...</sa_action>`
match before doing anything. It now also matches the opening tag alone (`openActionRegex`), so a
file's card exists from the moment the model starts it, not from the moment it finishes. The closing
regex (`closeActionRegex`) and `processedActionKeys` dedup are unchanged — the actual file mutation
still only happens once a block fully closes.

**FileCard.** One card per action, keyed by its position among opening tags seen so far this turn
(`"t0"`, `"t1"`, ...) — stable because the raw buffer only ever grows during a turn. A card is
`STREAMING` (forced open, live scrolling preview, height-capped) until its closing tag arrives, then
becomes `DONE` or `FAILED` and auto-collapses to one line. Tapping a finished card re-expands it or
opens the file in the editor.

**Timeline.** WorkLines (Read workspace, Generate, Wiring, Verify) and FileCards both carry a
creation-ordered `seq` and are rendered as one merged, chronologically-sorted list (`vm.timeline`)
instead of two separate blocks, so a file card appears exactly where it happened relative to the
steps around it.

**Wiring.** A new step that runs once, after a turn's file actions finish, only if something
actually changed. It is a **local static heuristic**, not a compiler: it checks `import` lines
against known local classes and checks Activity-shaped files against `AndroidManifest.xml` by plain
substring match. It reports what it checked and what it found, deliberately worded so it never
implies a real build ran (see `Build` screen and `docs/PIPELINE.md`, which already establishes SA
does not run Gradle inside the app).

**Interrupted-card safety net.** If generation is stopped, errors out, or hits the 1024-token
output cap while a card is still `STREAMING`, `markInterruptedCardsAsFailed()` closes it out as
`FAILED — Interrupted before completion` instead of leaving a card that claims to be in progress
forever. The same coercion applies to a card that is somehow still `STREAMING` in persisted state
(process death mid-generation) when it's read back on the next launch.

**Status orb.** The existing header badge (the gradient "SA" circle) gains a pulsing ring while
`vm.isWorking`, and the header subtitle switches to `vm.statusLabel` — computed from the same
`fileCards`/`workLines` state the timeline renders from, so it can only ever describe something
that is actually true at that moment.

**Editor merge.** `Code` and `CodeStream` are now one `Editor` screen. It checks whether the
currently open file has a `STREAMING` card and shows that card's live content read-only if so,
otherwise the normal editable file content — reached the same way whether you tap a file card
mid-stream or open a finished file from the Files list.

**File split.** The single 1278-line `MainActivity.kt` is now `Models.kt` (enums, data classes,
theme colors, `starterFiles()`), `SAViewModel.kt` (all state/logic), `Shared.kt` (app shell, header,
bottom nav), `ChatScreen.kt`, `WorkCards.kt` (WorkLine + FileCard rendering), `EditorScreen.kt`,
`ProjectScreens.kt`, and a thin `MainActivity.kt`. Everything shared across files is `internal`
rather than `private` — Kotlin top-level `private` is file-scoped, not package-scoped, so this
split would not otherwise have compiled.

## What was actually checked

This was written and reviewed in a sandbox with no Android SDK, Gradle, or `kotlinc` available, so
none of this was compiled or run on a device. What was possible, and was done:

- Every new/changed `.kt` file passed a brace/paren/quote balance check (the same algorithm
  `tools/qa_static.py` already used).
- Every `vm.<member>` reference across the UI files was cross-checked against what
  `SAViewModel.kt` actually declares, and every Composable/function call was cross-checked
  against actual declarations — no unresolved references found.
- Every `Icons.Default.X` and every Modifier extension used (`weight`, `clip`, `border`,
  `verticalScroll`, etc.) was checked against that file's imports.
- The open/close tag parsing algorithm was ported to Python and run against simulated streaming
  input for: a plain response with no `<sa_action>` at all, a single file streamed in small
  chunks, a three-file turn mixing `.kt`/`.py`/`.md` in one stream, and a stream that cuts off
  mid-file with no closing tag — all four produced the expected card states.
- `tools/qa_static.py` was updated (it was pointed at a single hard-coded file, and its starter-
  project check was already stale against `com.sa.notes`, a name the domain-neutral starter
  stopped producing before this change — see `starterIsDomainNeutral` in `StarterProjectTest.kt`)
  and re-run; it passes, including new assertions for the file-card/wiring code specifically.
  `StarterProjectTest.kt`'s existing assertions were independently re-verified against the
  relocated `starterFiles()` since the real JUnit runner isn't available here either.

What this does **not** replace: an actual Gradle build, lint pass, unit test run, and on-device
check on real hardware. The GitHub Actions workflow (`:app:testDebugUnitTest`, `:app:lintDebug`,
`:app:assembleDebug`) already in this repo is the first real compiler this code will meet — treat
its result, not this document, as the authoritative build check.

## What the real build actually caught

It did catch something, exactly as expected: `compileDebugKotlin` failed in `ChatScreen.kt`,
`EditorScreen.kt`, `ProjectScreens.kt`, `Shared.kt`, and `WorkCards.kt` with `Cannot access 'val
RowColumnParentData?.weight: Float': it is internal in file`. Every one of those files had an
`import androidx.compose.foundation.layout.weight` line. That import was wrong: `weight()` for
`Modifier` is not a top-level function in that package — it's declared as a member of the
`RowScope`/`ColumnScope` interfaces themselves (`fun Modifier.weight(...)` inside `interface
RowScope`), so it needs no import at all once code is lexically inside a `Row { }` or `Column { }`
— it resolves automatically through the implicit scope receiver. The bare import instead matched
an unrelated internal top-level property of the same short name used by Row/Column's own layout
math, and the compiler correctly refused to let application code touch it. The fix was to delete
that one import line from each of the five files; every actual `.weight(1f)` call site was already
correctly nested inside a `Row`/`Column`, so nothing else needed to change.

This is a real gap in the sandbox verification described above: checking "is there an import
statement for this name" cannot catch importing the *wrong* symbol of the same name, only a real
compiler resolving actual scope and visibility can. That's exactly what this CI run is for.

## Known limitations

- Wiring only understands `import` lines and plain substring matches against the manifest. It has
  no real symbol resolution, so it can both miss real problems and flag things that are actually
  fine (an import used only for its side effects, a manifest entry using a fully-qualified name
  instead of the short class name it looks for).
- A FileCard's `liveContent` is not persisted — only `id`, `kind`, `path`, `summary`, and `state`
  are. This matches how `WorkLine` was already persisted, and it means a restart mid-turn loses
  the in-progress preview along with the rest of that turn's live state, not just the card.
