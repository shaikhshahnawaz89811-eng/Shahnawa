# Research and Capacity Notes

## High-signal projects / libraries reviewed

### Llamatik
Llamatik exposes Android GGUF inference, streaming callbacks, context-aware generation, KV-cache session save/load, cancellation, model introspection and runtime parameter controls. SA uses these capabilities instead of inventing a custom fake streaming API.

### PocketPal AI
PocketPal is a high-star open-source mobile local-LLM project built around llama.cpp. It was reviewed for practical mobile model-management/chat patterns. SA does not copy its UI or claim feature parity; the useful lesson is to keep model lifecycle and device constraints explicit.

### LM Playground
LM Playground was reviewed as another real Android local-LLM implementation for model loading and offline chat.

## Model capacity

Qwen2.5-Coder-1.5B-Instruct is approximately 1.54B parameters with a full model context of 32,768 tokens. Official GGUF variants include Q2_K through Q8_0. The Q4_K_M file is about 1.12 GB in the official Qwen Xet listing.

For a phone-first profile SA intentionally starts with:

- Q4_K_M target
- 4096 runtime context
- mmap enabled
- 2–6 CPU threads selected from available cores
- batch 256
- max output 1024 tokens
- temperature 0.35
- top-p 0.90
- top-k 40
- repeat penalty 1.08
- GPU layers 0 by default

This is a conservative profile. The model's full 32K context is not automatically enabled because the goal is stable phone memory use, not maximum theoretical context.

## Weaknesses of a 1.5B local coder

- weaker multi-file planning than large cloud models
- limited ability to infer hidden project architecture
- more hallucination risk on unfamiliar APIs
- slower on CPU than a desktop GPU
- context must be budgeted carefully
- code generation quality varies strongly with prompt structure
- no inherent web freshness while offline

The app therefore keeps project context bounded and never turns an unverified model statement into a build-success claim.
