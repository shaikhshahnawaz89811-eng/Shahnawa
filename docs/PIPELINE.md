# SA Work Pipeline

User request
  -> inspect workspace
  -> assemble bounded project context
  -> prepare model prompt
  -> local GGUF generation
  -> actual token delta stream
  -> UI micro-batch update
  -> completion/cancellation guard
  -> persist chat/workspace state

For future real code-change execution, the intended verified pipeline remains:

inspect -> dependency/data-flow analysis -> plan -> patch -> build -> parse diagnostics -> root-cause fix -> rebuild -> UI/security audit -> final report

A screen state is not allowed to claim a later stage has happened before that stage actually runs.
