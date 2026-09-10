# Web research sources used for this revision

Checked 2026-09-11.

## Anthropic / Claude

- Claude file creation/editing: https://support.claude.com/en/articles/12111783-create-and-edit-files-with-claude
- Claude artifacts: https://support.claude.com/en/articles/9487310-what-are-artifacts-and-how-do-i-use-them
- Claude file uploads: https://support.claude.com/en/articles/8241126-upload-files-to-claude
- Claude Projects: https://support.claude.com/en/articles/9519177-how-can-i-create-and-manage-projects
- Claude Code cheatsheet / Plan / Accept Edits / permissions / diff / rewind: https://support.claude.com/en/articles/14553413-claude-code-cheatsheet
- Claude mobile Code routes: https://support.claude.com/en/articles/14898120-open-the-claude-mobile-app-with-a-link
- Anthropic streaming API: https://docs.anthropic.com/en/api/messages-streaming

## Local inference

- Qwen2.5-Coder-1.5B-Instruct GGUF: https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF
- Llamatik: https://github.com/ferranpons/Llamatik
- Llamatik Android Maven artifact: https://central.sonatype.com/artifact/com.llamatik/library-android
- PocketPal AI: https://github.com/a-ghorbani/pocketpal-ai

## CI / security

- Gradle Actions: https://github.com/gradle/actions
- GitHub checkout action: https://github.com/actions/checkout
- GitHub setup-java action: https://github.com/actions/setup-java
- GitHub dependency-review-action: https://github.com/actions/dependency-review-action

## Important research conclusion

The public Claude documentation describes the user-facing capabilities and interaction concepts, but it does not provide a complete pixel-level/private implementation specification. SA therefore does not claim a proprietary exact copy of undocumented Claude internals. The supplied 15-panel image remains the visual master reference for SA's own UI.

## Android threading / performance checked for this revision

- Android responsive UI / ANR guidance: https://developer.android.com/topic/performance/anrs/keep-your-app-responsive
- Android processes and threads: https://developer.android.com/guide/components/processes-and-threads
- Android background optimization: https://developer.android.com/topic/performance/background-optimization
- Android Compose layout/performance basics: https://developer.android.com/develop/ui/compose/layouts/basics
- Android overdraw guidance: https://developer.android.com/topic/performance/rendering/overdraw
- llama.cpp Android documentation: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- llama.cpp Android source example: https://github.com/ggml-org/llama.cpp/blob/master/examples/llama.android/lib/src/main/cpp/ai_chat.cpp
- llama.cpp build targets / Android arm64-v8a: https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md
- llama.cpp token-generation performance tips: https://github.com/ggml-org/llama.cpp/blob/master/docs/development/token_generation_performance_tips.md
