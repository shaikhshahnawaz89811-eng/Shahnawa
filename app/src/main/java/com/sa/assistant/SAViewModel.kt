package com.sa.assistant

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class SAViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("sa_state", Context.MODE_PRIVATE)

    // ---- Screen / composer / task state -----------------------------------
    var screen by mutableStateOf(Screen.CHAT)
    var input by mutableStateOf("")
    var projectName by mutableStateOf(prefs.getString("project", "MyApp") ?: "MyApp")
    var selectedFile by mutableStateOf("")
    var code by mutableStateOf("")
    var modelPath by mutableStateOf(prefs.getString("modelPath", "") ?: "")
    var modelName by mutableStateOf(prefs.getString("modelName", "No GGUF model loaded") ?: "No GGUF model loaded")
    var modelLoaded by mutableStateOf(false)
    var modelMeta by mutableStateOf("Import an instruction-tuned GGUF model")
    var modelOp by mutableStateOf(ModelOp.IDLE)
    // Rough estimate only — GenStream hands back text deltas, not a token count, so
    // these are chars/4 (a standard rough approximation), not an exact tokenizer count.
    var lastPromptTokens by mutableStateOf(0)
    var lastOutputTokens by mutableStateOf(0)
    var lastGenSeconds by mutableStateOf(0.0)
    private var generationStartMs = 0L
    var taskTitle by mutableStateOf("")
    var errorText by mutableStateOf("")
    var isWorking by mutableStateOf(false)
    var isPaused by mutableStateOf(false)
    var editingId by mutableStateOf<Long?>(null)
    var editedText by mutableStateOf("")
    var showComposerMenu by mutableStateOf(false)
    var sessionActive by mutableStateOf(false)
    var lastTaskSaved by mutableStateOf(false)
    var expandedWorkLineId by mutableStateOf<Long?>(null)
    var expandedFileCardId by mutableStateOf<String?>(null)
    var validationText by mutableStateOf("Workspace not validated")
    var attachmentContext by mutableStateOf("")

    val messages = mutableStateListOf<ChatMessage>()
    val files = mutableStateListOf<ProjectFile>()
    val attachments = mutableStateListOf<Attachment>()
    val workLines = mutableStateListOf<WorkLine>()

    // One entry per <sa_action> block seen so far this turn — created the moment its
    // opening tag streams in, updated live while it streams, finalized when it closes.
    // See applyModelActions() below for how these are populated.
    val fileCards = mutableStateListOf<FileCard>()

    // Chat renders WorkLines and FileCards as one merged, time-ordered list so a file
    // card shows up exactly where it really happened relative to the generic steps
    // around it, instead of two separate blocks that drift out of true order.
    val timeline: List<TimelineItem>
        get() = (workLines.map { TimelineItem.Line(it) } + fileCards.map { TimelineItem.Card(it) }).sortedBy { it.seq }

    // Single live line for the header subtitle. Only ever describes something that is
    // actually true right now — it reads off the same state the timeline renders from,
    // it does not invent its own "phase" tracking.
    val statusLabel: String
        get() {
            fileCards.firstOrNull { it.state == CardState.STREAMING }?.let {
                // Match the exact verb the card itself shows ("Creating…" / "Updating…" /
                // "Deleting…") so the header never describes the same action with a
                // different word — that mismatch is what made this read like two
                // different things were happening (and confused with the unrelated
                // "Wiring" step name) instead of one.
                val verb = when (it.kind) {
                    "create" -> "Creating"
                    "update" -> "Updating"
                    "delete" -> "Deleting"
                    else -> "Writing"
                }
                return "$verb ${it.path.substringAfterLast('/')}"
            }
            workLines.lastOrNull { it.state == StepState.RUNNING }?.let { return it.title }
            return if (isWorking) "Working" else if (modelLoaded) modelName else "Offline AI Coding Assistant"
        }

    private var generationJob: Job? = null
    private var flushJob: Job? = null
    private var persistJob: Job? = null
    private val streamBuffer = StringBuilder()
    private val rawStreamBuffer = StringBuilder()
    private var streamMessageId: Long? = null
    private var generationWorkLineId: Long? = null
    private var generationFinished = false
    private var generationCancelled = false
    private val processedActionKeys = mutableSetOf<String>()
    // Small local GGUF models can lose the thread on a big multi-file ask (e.g. "build
    // a whole shopping app") and start streaming out the same boilerplate body for every
    // file it opens — same imports, same placeholder Text(...), regardless of the file's
    // name or purpose. That is a model-quality failure, not a parse bug (each file really
    // is being fed that exact text), but nothing was stopping the app from happily writing
    // that duplicate content into file after file. Tracks the trimmed content already
    // written this turn so a later create/update that is byte-for-byte the same as an
    // earlier one this turn gets skipped instead of silently accepted as a real file.
    private val turnContentSignatures = mutableSetOf<String>()
    // Set from completeStream() when generation stops with a card still STREAMING — i.e.
    // the maxTokens cutoff hit mid-file, not a real finish. Holds (path, whatever content
    // had streamed in so far) so the *next* turn's prompt can tell the model exactly what
    // it left unfinished, instead of the model only seeing "continue"/the next message
    // with zero memory of being cut off — which is what made it just restart the file
    // (or something adjacent to it) from scratch. Consumed (read once, then cleared) the
    // next time streamAgent() runs.
    private var pendingResume: Pair<String, String>? = null
    // Set true only when a create/update/delete action actually mutates `files` this turn.
    // completeStream() checks this to decide whether an auto-zip snapshot and a wiring
    // check are worth running.
    private var workspaceChangedThisTurn = false

    // Open tag alone (no closing tag required) — this is what lets a file card appear
    // the moment the model starts a file, instead of only once it finishes. The full
    // block regex below is unchanged from before and is still what actually applies a
    // file mutation; nothing is written to `files` until a block fully closes.
    private val openActionRegex = Regex(
        """<sa_action\s+type="(create|update|delete)"\s+path="([^"]+)">""",
        RegexOption.IGNORE_CASE
    )
    private val closeActionRegex = Regex(
        """<sa_action\s+type="(create|update|delete)"\s+path="([^"]+)">(.*?)</sa_action>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    init {
        restoreState()
        if (files.isEmpty()) loadStarter()
        if (selectedFile.isBlank() && files.isNotEmpty()) {
            selectedFile = files.first().path
            code = files.first().content
        }
    }

    // ---- Persistence --------------------------------------------------------
    private fun restoreState() {
        try {
            val rawMessages = prefs.getString("messages", null)
            if (!rawMessages.isNullOrBlank()) {
                val arr = JSONArray(rawMessages)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    messages += ChatMessage(o.getLong("id"), o.getBoolean("user"), o.getString("text"), false)
                }
            }
            val rawFiles = prefs.getString("files", null)
            if (!rawFiles.isNullOrBlank()) {
                val arr = JSONArray(rawFiles)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    files += ProjectFile(o.getString("path"), o.getString("content"))
                }
            }
            selectedFile = prefs.getString("selectedFile", "") ?: ""
            code = files.firstOrNull { it.path == selectedFile }?.content.orEmpty()
            taskTitle = prefs.getString("taskTitle", "") ?: ""
            lastTaskSaved = prefs.getBoolean("taskSaved", false)
            validationText = prefs.getString("validation", "Workspace not validated") ?: "Workspace not validated"
            val rawWork = prefs.getString("workLines", null)
            if (!rawWork.isNullOrBlank()) {
                val arr = JSONArray(rawWork)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val state = runCatching { StepState.valueOf(o.getString("state")) }.getOrDefault(StepState.WAITING)
                    workLines += WorkLine(o.getLong("id"), o.getString("title"), o.getString("detail"), state, o.optBoolean("expandable", true))
                }
            }
            val rawCards = prefs.getString("fileCards", null)
            if (!rawCards.isNullOrBlank()) {
                val arr = JSONArray(rawCards)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val state = runCatching { CardState.valueOf(o.getString("state")) }.getOrDefault(CardState.DONE)
                    // A card only reaches disk between turns, so STREAMING here means the
                    // process died mid-generation, not that it is still in progress.
                    val safeState = if (state == CardState.STREAMING) CardState.FAILED else state
                    val summary = if (state == CardState.STREAMING) "Interrupted before completion" else o.getString("summary")
                    fileCards += FileCard(o.getString("id"), o.getLong("seq"), o.getString("kind"), o.getString("path"), summary = summary, state = safeState)
                }
            }
        } catch (_: Throwable) {
            messages.clear()
            files.clear()
        }
    }

    private fun persistState() {
        // Snapshot on the UI thread; JSON serialization and disk I/O stay off the UI thread.
        val projectSnapshot = projectName
        val messageSnapshot = messages.toList()
        val fileSnapshot = files.toList()
        val workSnapshot = workLines.toList()
        val cardSnapshot = fileCards.toList()
        val selectedSnapshot = selectedFile
        val taskSnapshot = taskTitle
        val taskSavedSnapshot = lastTaskSaved
        val modelPathSnapshot = modelPath
        val modelNameSnapshot = modelName
        val validationSnapshot = validationText
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            val m = JSONArray()
            messageSnapshot.forEach { m.put(JSONObject().apply { put("id", it.id); put("user", it.user); put("text", it.text) }) }
            val f = JSONArray()
            fileSnapshot.forEach { f.put(JSONObject().apply { put("path", it.path); put("content", it.content) }) }
            val w = JSONArray()
            workSnapshot.forEach { w.put(JSONObject().apply { put("id", it.id); put("title", it.title); put("detail", it.detail); put("state", it.state.name); put("expandable", it.expandable) }) }
            val c = JSONArray()
            cardSnapshot.forEach {
                c.put(JSONObject().apply {
                    put("id", it.id); put("seq", it.seq); put("kind", it.kind); put("path", it.path)
                    put("summary", it.summary); put("state", it.state.name)
                    // liveContent is intentionally not persisted: it only means anything while
                    // a card is still streaming, and persistState() only ever runs at a turn
                    // boundary, never mid-stream.
                })
            }
            prefs.edit()
                .putString("project", projectSnapshot)
                .putString("messages", m.toString())
                .putString("files", f.toString())
                .putString("workLines", w.toString())
                .putString("fileCards", c.toString())
                .putString("selectedFile", selectedSnapshot)
                .putString("taskTitle", taskSnapshot)
                .putBoolean("taskSaved", taskSavedSnapshot)
                .putString("modelPath", modelPathSnapshot)
                .putString("modelName", modelNameSnapshot)
                .putString("validation", validationSnapshot)
                .apply()
        }
    }

    // ---- Lifecycle / starter -------------------------------------------------
    private fun loadStarter() {
        files.clear()
        files.addAll(starterFiles(projectName))
        selectedFile = files.first().path
        code = files.first().content
        messages.clear()
        messages += ChatMessage(1L, false, "Hello, I'm SA\n\nYour offline AI coding assistant.\n\nLoad a local instruction-tuned GGUF model to enable real token streaming. Your project files and chat state stay on this device.")
        persistState()
    }

    fun selectFile(path: String) {
        saveFile()
        selectedFile = path
        code = files.firstOrNull { it.path == path }?.content.orEmpty()
        screen = Screen.CODE
    }

    // Opens the editor already pointed at a specific file card — used when tapping a
    // card in the chat timeline instead of navigating via the Files list.
    fun openFileCard(card: FileCard) {
        if (card.state != CardState.STREAMING) selectFile(card.path) else {
            selectedFile = card.path
            screen = Screen.CODE
        }
    }

    fun saveFile() {
        val i = files.indexOfFirst { it.path == selectedFile }
        if (i >= 0) files[i] = files[i].copy(content = code)
        persistState()
    }

    fun newProject(name: String) {
        stopGeneration()
        projectName = name.trim().ifBlank { "NewProject" }
        files.clear()
        files.addAll(starterFiles(projectName))
        selectedFile = files.first().path
        code = files.first().content
        messages.clear()
        messages += ChatMessage(System.currentTimeMillis(), false, "Project $projectName created. The workspace contains real editable files.")
        workLines.clear()
        fileCards.clear()
        expandedFileCardId = null
        expandedWorkLineId = null
        pendingResume = null
        taskTitle = ""
        lastTaskSaved = false
        sessionActive = false
        runCatching { LlamaBridge.sessionReset() }
        persistState()
        screen = Screen.CHAT
    }

    // Every one of these four (Import/Load/Unload/Delete) checks modelOp == IDLE
    // before doing anything and holds a non-IDLE value for its entire duration —
    // that's the whole fix for double-tap races: a second tap on anything while
    // one of them is running is simply a no-op, not a second overlapping action.
    fun importModel(context: Context, uri: Uri) {
        if (modelOp != ModelOp.IDLE) return
        modelOp = ModelOp.IMPORTING
        viewModelScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    val dir = File(context.filesDir, "models").apply { mkdirs() }
                    val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf")
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val t = File(dir, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(t).use { output -> input.copyTo(output, 1024 * 1024) }
                    } ?: error("Unable to open selected model")
                    t
                }
                modelPath = target.absolutePath
                modelName = target.name
                modelMeta = "Imported locally • ${target.length() / (1024 * 1024)} MB • loading…"
                prefs.edit().putString("modelPath", modelPath).putString("modelName", modelName).apply()
                performLoad(target.absolutePath)
            } catch (e: Throwable) {
                modelLoaded = false
                modelMeta = "Model import failed: ${e.message ?: "unknown error"}"
            }
            modelOp = ModelOp.IDLE
        }
    }

    // Load button — only reachable from the UI when a model is imported and not
    // already loaded (see SettingsScreen), but re-checked here too since state and
    // UI can be one frame apart.
    fun loadModel(path: String) {
        if (modelOp != ModelOp.IDLE || path.isBlank() || modelLoaded) return
        modelOp = ModelOp.LOADING
        viewModelScope.launch {
            performLoad(path)
            modelOp = ModelOp.IDLE
        }
    }

    private suspend fun performLoad(path: String) {
        withContext(Dispatchers.IO) {
            try {
                LlamaBridge.updateGenerateParams(
                    temperature = 0.35f,
                    // 1024 was cutting whole-project generations off mid-file (e.g. a
                    // build.gradle.kts card left "Interrupted before completion") because a
                    // scaffolded app needs several files in one response. Raised alongside
                    // contextLength below so there's still room for the prompt + history.
                    // Lower both back down if this OOMs on low-RAM devices.
                    maxTokens = 4096,
                    topP = 0.90f,
                    topK = 40,
                    repeatPenalty = 1.08f,
                    contextLength = 8192,
                    numThreads = maxOf(2, (Runtime.getRuntime().availableProcessors() - 2).coerceAtMost(6)),
                    useMmap = true,
                    flashAttention = false,
                    batchSize = 256,
                    gpuLayers = 0
                )
                runCatching { LlamaBridge.shutdown() }
                val ok = LlamaBridge.initGenerateModel(path)
                val type = runCatching { LlamaBridge.getModelFinetuneType() }.getOrNull()
                withContext(Dispatchers.Main) {
                    modelLoaded = ok
                    sessionActive = false
                    modelMeta = if (ok) {
                        "Loaded • ${type ?: "unknown/base"} • context 8192 • mmap"
                    } else "Model load failed"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { modelLoaded = false; modelMeta = "Load failed: ${e.message ?: "native error"}" }
            }
        }
    }

    // Unload button — only reachable from the UI while modelLoaded is true. Frees the
    // native model from memory but keeps the .gguf file on disk, so Load can bring the
    // same model straight back without re-importing.
    fun unloadModel() {
        if (modelOp != ModelOp.IDLE || !modelLoaded) return
        modelOp = ModelOp.UNLOADING
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { LlamaBridge.shutdown() } }
            modelLoaded = false
            sessionActive = false
            modelMeta = "Unloaded • tap Load to use this model again"
            modelOp = ModelOp.IDLE
        }
    }

    // Delete button — only reachable from the UI when a model is imported AND not
    // loaded (Delete never runs against a model still in memory; Unload first).
    fun deleteModel() {
        if (modelOp != ModelOp.IDLE || modelLoaded || modelPath.isBlank()) return
        modelOp = ModelOp.DELETING
        val pathToDelete = modelPath
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { File(pathToDelete).delete() } }
            modelPath = ""
            modelName = "No GGUF model loaded"
            modelMeta = "Import an instruction-tuned GGUF model"
            prefs.edit().remove("modelPath").remove("modelName").apply()
            modelOp = ModelOp.IDLE
        }
    }

    // ---- Sending / generation --------------------------------------------------
    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isWorking) return
        saveFile()
        streamMessageId = null
        messages += ChatMessage(System.currentTimeMillis(), true, text)
        input = ""
        // "zip do" / "export zip" etc. are a request for the project ZIP, not a coding
        // task — routing them to the model just gets a generic refusal, since the model
        // has no idea that feature exists. Short-circuit straight to a real MediaStore
        // save and drop the result right in the chat bubble as an Open/Share card.
        if (isZipExportIntent(text)) {
            val placeholderId = System.currentTimeMillis() + 1
            messages += ChatMessage(placeholderId, false, "Saving ${projectName}.zip to Downloads…")
            persistState()
            exportZipToChatDownloads(placeholderId)
            return
        }
        taskTitle = text
        lastTaskSaved = false
        errorText = ""
        workLines.clear()
        fileCards.clear()
        // Card ids are just "t0", "t1"... positional within a turn (see applyModelActions),
        // so they get reused every turn. Leaving a stale expandedFileCardId/expandedWorkLineId
        // around from a card the user tapped open earlier meant a brand-new, unrelated card
        // that happened to land on the same id this turn rendered already-expanded — that's
        // the "card stays open even after it's done writing" symptom. Clear both so every
        // new turn starts with everything collapsed until the user actually taps something.
        expandedFileCardId = null
        expandedWorkLineId = null
        persistState()
        if (!modelLoaded) {
            errorText = "No local GGUF model is loaded. Open Settings → Model and import an instruction-tuned GGUF file."
            isWorking = false
            lastTaskSaved = false
            workLines += WorkLine(System.nanoTime(), "Model required", errorText, StepState.FAILED)
            persistState()
            screen = Screen.ERROR
            return
        }
        isWorking = true
        isPaused = false
        screen = Screen.CHAT
        generationFinished = false
        generationCancelled = false
        processedActionKeys.clear()
        turnContentSignatures.clear()
        workspaceChangedThisTurn = false
        generationJob = viewModelScope.launch { runAgent(text) }
    }

    // Matches short zip-export requests typed in chat, in English or common Hinglish
    // phrasing ("zip do", "zip banao", "zip bhejo"), without catching normal sentences
    // that merely mention the word "zip" as part of a real coding request.
    private fun isZipExportIntent(text: String): Boolean {
        val t = text.trim().lowercase()
        val zipWord = Regex("\\bzip\\b")
        if (!zipWord.containsMatchIn(t)) return false
        val actionWord = Regex("\\b(do|de|dedo|dijiye|banao|bana|bhejo|chahiye|export|download|save)\\b")
        return t.split(Regex("\\s+")).size <= 5 && actionWord.containsMatchIn(t)
    }

    // A small local model sitting inside an Android/Kotlin project scaffold will keep
    // writing Kotlin even when asked for "a website" — the existing .kt files in its own
    // context (see buildContext()) anchor it far more strongly than one line in the
    // system prompt saying it isn't limited to Kotlin. Detected here so streamAgent() can
    // give an explicit, request-specific override instead of relying on that one generic
    // line. Matches common English and Hinglish phrasings ("website", "web app", "vebsite",
    // "html/css site"), not just the bare words "html"/"css" alone, to keep false positives
    // low on requests that only mention a language in passing.
    private fun isWebRequest(text: String): Boolean {
        val t = text.lowercase()
        val webPhrase = Regex("\\b(website|web[\\s-]?app|web[\\s-]?page|webpage|vebsite|landing page|static site)\\b")
        val webStack = Regex("\\bhtml\\b.*\\bcss\\b|\\bcss\\b.*\\bhtml\\b")
        return webPhrase.containsMatchIn(t) || webStack.containsMatchIn(t)
    }

    private suspend fun runAgent(user: String) {
        try {
            val webRequest = isWebRequest(user)
            val context = buildContext(webRequest)
            addWorkLine("Read workspace", "Loaded ${files.size} project files and bounded each file to the phone-safe context budget.", StepState.SUCCESS)
            addWorkLine("Prepare task context", "Prepared conversation history, project files, and the current user request.", StepState.SUCCESS)
            generationWorkLineId = addWorkLine("Generate", "Local GGUF generation started. Tokens will appear directly in the assistant response.", StepState.RUNNING)
            streamAgent(user, context, webRequest)
        } catch (e: Throwable) {
            failTask(e.message ?: "Generation failed")
        }
    }

    private fun addWorkLine(title: String, detail: String, state: StepState): Long {
        val id = System.nanoTime()
        workLines += WorkLine(id, title, detail, state)
        return id
    }

    private suspend fun streamAgent(user: String, context: String, webRequest: Boolean = false) {
        val system = """You are SA, an offline coding assistant. This project happens to be Android/Kotlin, but you are not limited to Kotlin — if the user asks for code in Python, Java, JavaScript, C++, or any other language, write it in that language and give the file the matching extension.
Never claim a file was changed, a build passed, or an app was installed unless SA actually performed that operation.
Inspect first. Explain root cause before proposing a fix. Prefer minimal, connected changes. Keep answers concise but useful.
When you actually need to change workspace files, emit one or more exact blocks using <sa_action type="create|update|delete" path="relative/path">content</sa_action>. Do not claim an action succeeded unless the block is valid.
Example: <sa_action type="create" path="app/src/main/java/com/sa/app/Student.kt">package com.sa.app
data class Student(val id: Long, val name: String)</sa_action>
This example only demonstrates the tag syntax. It is Kotlin because the surrounding project is Kotlin — always use whatever language and file extension the user's actual request calls for (.py, .js, .java, .cpp, etc.), not just .kt.
Always use this exact tag syntax to create or edit files. A plain ``` code fence is only for showing a snippet in the chat reply — it never updates the workspace, so any file the user asked for must also appear as an <sa_action> block.
Project: $projectName""" + if (webRequest) """

The current user request below is asking for a WEBSITE / web app, not an Android app. Write plain HTML, CSS, and JavaScript files only, using extensions .html, .css, .js — for example <sa_action type="create" path="index.html">...</sa_action> and <sa_action type="create" path="style.css">...</sa_action>. Do NOT write Kotlin, do NOT use .kt paths, and do NOT reuse Compose/Activity/Fragment patterns for this request, even though the files listed in the project context are Kotlin — those belong to this app itself, not to the website being asked for.""" else ""
        val history = recentHistory()
        // Consume pendingResume exactly once, for this call only — see its declaration.
        // Without this, the model has no idea the last turn was cut off mid-file, so a
        // plain "continue" reads to it like a brand-new ask and it restarts that file (or
        // something near it) from zero rather than picking up the actual unfinished work.
        val resumeNote = pendingResume?.let { (path, partial) ->
            pendingResume = null
            "\n\nIMPORTANT: your previous response was cut off by the response length limit " +
                "while writing $path, and that partial version was NOT saved to the project. " +
                "Partial content streamed so far (incomplete, do not treat as usable):\n$partial\n" +
                "Before anything else, send one fresh, COMPLETE <sa_action type=\"create\" path=\"$path\">...</sa_action> " +
                "block that finishes this file properly from the start, then continue with the rest of the request below."
        }.orEmpty()
        val fullContext = context + resumeNote
        val prompt = "Conversation:\n$history\n\nCurrent project context:\n$fullContext\n\nAttachment context:\n$attachmentContext\n\nUser request:\n$user"

        val assistantId = System.currentTimeMillis()
        messages += ChatMessage(assistantId, false, "", true)
        streamMessageId = assistantId
        synchronized(streamBuffer) { streamBuffer.setLength(0) }
        synchronized(rawStreamBuffer) { rawStreamBuffer.setLength(0) }

        // chars/4 is a standard rough stand-in for a token count — GenStream only
        // hands back text deltas, no real tokenizer count, so this is an estimate
        // shown as "~" in the Model card, not an exact figure.
        val promptCharsForEstimate = if (sessionActive) prompt.length else system.length + fullContext.length + history.length + user.length
        lastPromptTokens = (promptCharsForEstimate / 4).coerceAtLeast(0)
        lastOutputTokens = 0
        lastGenSeconds = 0.0
        generationStartMs = System.currentTimeMillis()

        withContext(Dispatchers.IO) {
            try {
                val callback = object : GenStream {
                    override fun onDelta(text: String) = appendStream(text)
                    override fun onComplete() = completeStream()
                    override fun onError(message: String) = failTask(message)
                }
                if (sessionActive) {
                    LlamaBridge.generateContinueStream(prompt, callback)
                } else {
                    LlamaBridge.generateWithContextStream(
                        system,
                        fullContext + "\n\n" + history,
                        user,
                        onDelta = { text -> appendStream(text) },
                        onDone = { completeStream() },
                        onError = { message -> failTask(message) }
                    )
                }
            } catch (e: Throwable) {
                failTask(e.message ?: "Native generation failed")
            }
        }
    }

    private fun appendStream(text: String) {
        if (generationCancelled || generationFinished) return
        synchronized(streamBuffer) { streamBuffer.append(text) }
        synchronized(rawStreamBuffer) { rawStreamBuffer.append(text) }
        // Native callbacks can be much faster than Compose can render. Keep callbacks off the
        // UI thread and coalesce them into one bounded UI update roughly every 35 ms.
        if (flushJob?.isActive != true) {
            flushJob = viewModelScope.launch(Dispatchers.Main.immediate) {
                delay(35)
                flushStreamBuffer(false)
            }
        }
    }

    private fun flushStreamBuffer(force: Boolean) {
        flushJob = null
        val chunk = synchronized(streamBuffer) {
            if (streamBuffer.isEmpty()) "" else streamBuffer.toString().also { streamBuffer.setLength(0) }
        }
        if (chunk.isEmpty() && !force) return
        val id = streamMessageId ?: return
        val index = messages.indexOfFirst { it.id == id }
        // Recompute the visible text from the FULL raw buffer every tick, not from
        // (previously shown text + this delta). old.text is already-stripped output;
        // feeding it back into visibleResponse() together with only the newest raw
        // delta silently drops whatever was cut out earlier (e.g. an in-progress
        // <sa_action> block) and glues unrelated fragments together with no space —
        // that's what produced merged words, missing line breaks, and a stray
        // "</sa_action>" leaking into the bubble. The raw buffer already holds the
        // complete text since this message started, so always derive from it.
        val raw = synchronized(rawStreamBuffer) { rawStreamBuffer.toString() }
        if (index >= 0 && chunk.isNotEmpty()) {
            val old = messages[index]
            messages[index] = old.copy(text = visibleResponse(raw))
        }
        // Parse in-progress and completed SA file-action blocks only on the coalesced UI
        // tick. This avoids launching a main-thread coroutine for every native token.
        applyModelActions(raw)
    }

    private fun completeStream() {
        synchronized(this) {
            if (generationFinished || generationCancelled) return
            generationFinished = true
        }
        viewModelScope.launch(Dispatchers.Main) {
            flushStreamBuffer(true)
            val outChars = synchronized(rawStreamBuffer) { rawStreamBuffer.length }
            lastOutputTokens = (outChars / 4).coerceAtLeast(0)
            lastGenSeconds = (System.currentTimeMillis() - generationStartMs) / 1000.0
            // A max-token cutoff can end generation mid-tag; a card left STREAMING forever
            // would silently lie about still being in progress, so close it out here too.
            // Before wiping it, remember what that one unfinished file had so far — see
            // pendingResume's declaration above. Only ever at most one card is genuinely
            // STREAMING when generation stops (every earlier one already closed or got
            // marked "skipped" the moment a later tag opened), so this is unambiguous.
            val cutOff = fileCards.firstOrNull { it.state == CardState.STREAMING }
            pendingResume = cutOff?.let { it.path to it.liveContent }
            markInterruptedCardsAsFailed()
            generationWorkLineId?.let { id ->
                val i = workLines.indexOfFirst { it.id == id }
                if (i >= 0) workLines[i] = workLines[i].copy(
                    state = StepState.SUCCESS,
                    detail = if (cutOff != null)
                        "Hit the response length limit while writing ${cutOff.path.substringAfterLast('/')} — it was not saved. Send another message (e.g. \"continue\") and SA will rewrite that file properly instead of leaving it half-done."
                    else "Generation completed and the response was finalized."
                )
            }
            if (workspaceChangedThisTurn) checkWiring()
            addWorkLine("Verify", "Response state, generated actions, and workspace persistence were finalized.", StepState.SUCCESS)
            if (workspaceChangedThisTurn) autoExportZip()
            messages.indexOfFirst { it.id == streamMessageId }.takeIf { it >= 0 }?.let { i -> messages[i] = messages[i].copy(streaming = false) }
            isWorking = false
            sessionActive = true
            generationJob = null
            lastTaskSaved = true
            persistState()
            screen = Screen.CHAT
        }
    }

    private fun failTask(message: String) {
        synchronized(this) {
            if (generationFinished || generationCancelled) return
            generationFinished = true
        }
        viewModelScope.launch(Dispatchers.Main) {
            flushStreamBuffer(true)
            val outChars = synchronized(rawStreamBuffer) { rawStreamBuffer.length }
            lastOutputTokens = (outChars / 4).coerceAtLeast(0)
            lastGenSeconds = (System.currentTimeMillis() - generationStartMs) / 1000.0
            markInterruptedCardsAsFailed()
            generationWorkLineId?.let { id ->
                val i = workLines.indexOfFirst { it.id == id }
                if (i >= 0) workLines[i] = workLines[i].copy(state = StepState.FAILED, detail = message)
            }
            val id = streamMessageId
            if (id != null) {
                val index = messages.indexOfFirst { it.id == id }
                if (index >= 0) {
                    messages[index] = messages[index].copy(text = messages[index].text.ifBlank { "Generation failed: $message" }, streaming = false)
                }
            } else {
                messages += ChatMessage(System.currentTimeMillis(), false, "Generation failed: $message")
            }
            errorText = message
            isWorking = false
            generationJob = null
            lastTaskSaved = false
            persistState()
            screen = Screen.ERROR
        }
    }

    fun pause() {
        if (!isWorking) return
        isPaused = true
        generationCancelled = true
        runCatching { LlamaBridge.nativeCancelGenerate() }
        markInterruptedCardsAsFailed()
        generationWorkLineId?.let { id ->
            val i = workLines.indexOfFirst { it.id == id }
            if (i >= 0) workLines[i] = workLines[i].copy(state = StepState.PAUSED, detail = "Generation cancelled safely; resume can continue from the task prompt.")
        }
        persistState()
        screen = Screen.PAUSE
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        isWorking = false
        input = taskTitle
        screen = Screen.CHAT
    }

    fun stopGeneration() {
        generationCancelled = true
        runCatching { LlamaBridge.nativeCancelGenerate() }
        generationJob?.cancel()
        generationJob = null
        flushJob?.cancel()
        flushStreamBuffer(true)
        markInterruptedCardsAsFailed()
        isWorking = false
        isPaused = false
        generationJob = null
        messages.indexOfFirst { it.streaming }.takeIf { it >= 0 }?.let { i -> messages[i] = messages[i].copy(streaming = false) }
        persistState()
    }

    fun edit(message: ChatMessage) {
        if (!message.user) return
        editingId = message.id
        editedText = message.text
        screen = Screen.EDIT
    }

    fun saveEdit() {
        val id = editingId ?: return
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) {
            messages[i] = messages[i].copy(text = editedText.trim())
            sessionActive = false
            runCatching { LlamaBridge.sessionReset() }
            if (i < messages.lastIndex) {
                while (messages.lastIndex > i) messages.removeAt(messages.lastIndex)
            }
        }
        persistState()
        screen = Screen.CHAT
    }

    fun addAttachment(uri: Uri, name: String) {
        showComposerMenu = false
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching {
                val resolver = getApplication<Application>().contentResolver
                val type = resolver.getType(uri).orEmpty()
                if (type.startsWith("text/") || type == "application/json" || name.endsWith(".md", true) || name.endsWith(".kt", true) || name.endsWith(".gradle", true) || name.endsWith(".kts", true)) {
                    resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(120_000) }.orEmpty()
                } else if (type == "application/zip" || name.endsWith(".zip", true)) {
                    resolver.openInputStream(uri)?.use { input ->
                        java.util.zip.ZipInputStream(input).use { zip ->
                            buildString {
                                var entry = zip.nextEntry
                                var count = 0
                                while (entry != null && count < 100) {
                                    if (!entry.isDirectory) append(entry.name).append('\n')
                                    count++
                                    entry = zip.nextEntry
                                }
                            }.take(20_000)
                        }
                    }.orEmpty()
                } else ""
            }.getOrDefault("")
            withContext(Dispatchers.Main) {
                attachments += Attachment(uri.toString(), name, if (content.isBlank()) "binary" else "text")
                if (content.isNotBlank()) {
                    attachmentContext = (attachmentContext + "\nATTACHMENT: $name\n$content").takeLast(180_000)
                }
            }
        }
    }

    // Shared by every export path (manual SAF export, the silent per-turn auto-snapshot,
    // and the chat "zip do" shortcut) so the actual bytes written can never drift between
    // them — one place writes the zip, each caller just decides where it goes.
    private fun writeZipEntries(zip: ZipOutputStream, exportFiles: List<ProjectFile>) {
        exportFiles.forEach { f ->
            zip.putNextEntry(ZipEntry(f.path))
            zip.write(f.content.toByteArray())
            zip.closeEntry()
        }
    }

    fun exportZip(context: Context, uri: Uri) {
        saveFile()
        val exportFiles = files.toList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    ZipOutputStream(output).use { zip -> writeZipEntries(zip, exportFiles) }
                } ?: error("Unable to create destination file")
                withContext(Dispatchers.Main) { screen = Screen.ZIP }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { errorText = "ZIP export failed: ${e.message ?: "unknown error"}"; screen = Screen.ERROR }
            }
        }
    }

    // Auto-zip runs with no user gesture, so it can't use the CreateDocument picker
    // exportZip() uses (Android requires a tap for that). It writes into this app's
    // own external-files directory instead — no runtime permission needed, and it's
    // a separate, additional snapshot; the manual Export ZIP screen is unchanged.
    private fun autoExportZip() {
        val exportFiles = files.toList()
        if (exportFiles.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(getApplication<Application>().getExternalFilesDir(null), "auto_zips")
                dir.mkdirs()
                val target = File(dir, "${projectName}_${System.currentTimeMillis()}.zip")
                FileOutputStream(target).use { output ->
                    ZipOutputStream(output).use { zip -> writeZipEntries(zip, exportFiles) }
                }
                withContext(Dispatchers.Main) {
                    addWorkLine("Auto-saved ZIP", "Saved ${exportFiles.size} files to ${target.absolutePath}", StepState.SUCCESS)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    addWorkLine("Auto ZIP failed", e.message ?: "unknown error", StepState.FAILED)
                }
            }
        }
    }

    fun resumeLater() {
        lastTaskSaved = true
        persistState()
        screen = Screen.CHAT
    }

    // The chat "zip do" shortcut: builds the same zip as exportZip()/autoExportZip(), but
    // inserts it straight into the public Downloads collection via MediaStore. That insert
    // needs no runtime permission and no SAF tap on API 29+ (this app's minSdk), so it can
    // run the instant the request comes in and hand back a real content:// Uri — which is
    // what lets the chat bubble show working Open/Share buttons instead of a plain message.
    private fun exportZipToChatDownloads(placeholderId: Long) {
        saveFile()
        val exportFiles = files.toList()
        val fileName = "${projectName}.zip"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore refused to create the download entry")
                resolver.openOutputStream(uri)?.use { output ->
                    ZipOutputStream(output).use { zip -> writeZipEntries(zip, exportFiles) }
                } ?: error("Unable to open the download entry for writing")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                withContext(Dispatchers.Main) {
                    val i = messages.indexOfFirst { it.id == placeholderId }
                    val confirmText = "Saved $fileName to Downloads (${exportFiles.size} files)."
                    if (i >= 0) messages[i] = messages[i].copy(text = confirmText, streaming = false, zipUri = uri.toString(), zipName = fileName)
                    addWorkLine("Saved ZIP", "$fileName (${exportFiles.size} files) written to Downloads via MediaStore.", StepState.SUCCESS)
                    persistState()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    val i = messages.indexOfFirst { it.id == placeholderId }
                    val failText = "ZIP save failed: ${e.message ?: "unknown error"}"
                    if (i >= 0) messages[i] = messages[i].copy(text = failText, streaming = false)
                    addWorkLine("ZIP save failed", e.message ?: "unknown error", StepState.FAILED)
                    persistState()
                }
            }
        }
    }

    fun toggleWorkLine(id: Long) {
        expandedWorkLineId = if (expandedWorkLineId == id) null else id
    }

    fun toggleFileCard(id: String) {
        expandedFileCardId = if (expandedFileCardId == id) null else id
    }

    fun validateWorkspace() {
        val problems = mutableListOf<String>()
        if (projectName.isBlank()) problems += "Project name is empty"
        if (files.isEmpty()) problems += "No project files"
        if (files.none { it.path == "settings.gradle.kts" }) problems += "settings.gradle.kts is missing"
        if (files.none { it.path == "build.gradle.kts" }) problems += "root build.gradle.kts is missing"
        if (files.none { it.path == "app/build.gradle.kts" }) problems += "app/build.gradle.kts is missing"
        if (files.none { it.path == "app/src/main/AndroidManifest.xml" }) problems += "AndroidManifest.xml is missing"
        if (files.none { it.path == "app/src/main/res/values/styles.xml" }) problems += "styles.xml is missing"
        if (files.any { it.path.startsWith("/") || it.path.split('/').contains("..") }) problems += "Unsafe file path detected"
        validationText = if (problems.isEmpty()) "Workspace validation passed • ${files.size} files" else "Validation failed • ${problems.joinToString("; ")}"
        persistState()
    }

    private fun safeWorkspacePath(path: String): Boolean = path.isNotBlank() && !path.startsWith("/") && !path.split('/').contains("..") && path.length <= 240

    // ---- Streaming action parsing --------------------------------------------
    // Runs on every coalesced UI tick against the FULL raw buffer for this turn.
    // Every <sa_action> opening tag found gets a card the moment it appears, keyed by
    // its position in this tick's match list ("t0", "t1", ...). Because raw only ever
    // grows during one turn, that position is stable across ticks for the same block,
    // so re-scanning from scratch each time is safe and keeps this stateless.
    private fun applyModelActions(raw: String) {
        val opens = openActionRegex.findAll(raw).toList()
        if (opens.isEmpty()) return
        val closes = closeActionRegex.findAll(raw).toList()
        var appliedAny = false

        opens.forEachIndexed { index, openMatch ->
            val cardId = "t$index"
            val type = openMatch.groupValues[1].lowercase()
            val path = openMatch.groupValues[2]
            val closeMatch = closes.firstOrNull { it.range.first == openMatch.range.first }

            if (closeMatch != null) {
                // Fully streamed in. processedActionKeys still guards the actual file
                // mutation so a re-scan on the next tick can never apply it twice.
                if (processedActionKeys.add(closeMatch.value)) {
                    applyClosedAction(cardId, type, path, closeMatch.groupValues[3].trimStart('\n', '\r'))
                    appliedAny = true
                }
            } else if (index != opens.lastIndex) {
                // A later <sa_action> already opened before this one's closing tag
                // arrived, so this one will never receive another token — the model
                // moved on without closing it. Previously this stayed marked
                // STREAMING forever (open, spinner, cyan border) with frozen content,
                // which is why several cards could sit open at once with nothing
                // actually being written into any but the newest. Mark it failed
                // immediately instead so only the one file truly being written now
                // ever shows as open.
                upsertFileCard(cardId, type, path, summary = "Skipped — next file started before this one finished", state = CardState.FAILED)
            } else {
                // Still streaming: this is the one action with no close yet, and
                // nothing has opened after it — live content is everything since its
                // tag opened.
                val contentStart = openMatch.range.last + 1
                val live = raw.substring(contentStart).trimStart('\n', '\r')
                upsertFileCard(cardId, type, path, liveContent = live, state = CardState.STREAMING)
            }
        }
        // Disk persistence only follows an actual applied mutation, exactly like before
        // this change — not every tick, which for a multi-second file write would mean
        // dozens of redundant writes for a card update that only touches in-memory state.
        if (appliedAny) saveFile()
    }

    // Replaces (or creates) the card for `id`, preserving its original `seq` — the
    // position where it first appeared — across every later update so a card never
    // jumps position in the timeline once it starts streaming.
    private fun upsertFileCard(id: String, kind: String, path: String, liveContent: String = "", summary: String = "", state: CardState) {
        val i = fileCards.indexOfFirst { it.id == id }
        if (i >= 0) {
            fileCards[i] = fileCards[i].copy(kind = kind, path = path, liveContent = liveContent, summary = summary, state = state)
        } else {
            fileCards += FileCard(id, System.nanoTime(), kind, path, liveContent, summary, state)
        }
    }

    private fun applyClosedAction(cardId: String, type: String, path: String, content: String) {
        if (!safeWorkspacePath(path) || content.length > 200_000) {
            upsertFileCard(cardId, type, path, summary = "Unsafe path or oversized content", state = CardState.FAILED)
            return
        }
        // See turnContentSignatures' declaration above. Only guard create/update — a
        // duplicate "delete" isn't a real problem (deleting the same path twice is a
        // no-op the second time) and content doesn't apply to it anyway.
        if (type != "delete") {
            val signature = content.trim()
            if (signature.isNotEmpty() && !turnContentSignatures.add(signature)) {
                upsertFileCard(
                    cardId, type, path,
                    summary = "Skipped — identical to another file already written this turn (the model looks stuck repeating itself)",
                    state = CardState.FAILED
                )
                return
            }
        }
        when (type) {
            "create" -> {
                // A brand-new project is seeded with starter scaffold files (AndroidManifest.xml,
                // build.gradle.kts, MainActivity.kt...), so the model will very often "create" a
                // path that already exists purely because the scaffold got there first — that is
                // not a real conflict, it's the model writing the real version of a placeholder.
                // Treat "create" as create-or-overwrite instead of hard-failing on it.
                val i = files.indexOfFirst { it.path == path }
                if (i >= 0) {
                    files[i] = files[i].copy(content = content)
                    if (selectedFile == path) code = content
                    workspaceChangedThisTurn = true
                    upsertFileCard(cardId, type, path, summary = "Overwrote existing, ${content.lines().size} lines", state = CardState.DONE)
                } else {
                    files += ProjectFile(path, content)
                    workspaceChangedThisTurn = true
                    upsertFileCard(cardId, type, path, summary = "Created, ${content.lines().size} lines", state = CardState.DONE)
                }
            }
            "update" -> {
                val i = files.indexOfFirst { it.path == path }
                if (i < 0) {
                    upsertFileCard(cardId, type, path, summary = "$path does not exist", state = CardState.FAILED)
                } else {
                    files[i] = files[i].copy(content = content)
                    if (selectedFile == path) code = content
                    workspaceChangedThisTurn = true
                    upsertFileCard(cardId, type, path, summary = "Updated, ${content.lines().size} lines", state = CardState.DONE)
                }
            }
            "delete" -> {
                val removed = files.removeAll { it.path == path }
                if (removed) {
                    if (selectedFile == path) {
                        selectedFile = files.firstOrNull()?.path.orEmpty()
                        code = files.firstOrNull()?.content.orEmpty()
                    }
                    workspaceChangedThisTurn = true
                    upsertFileCard(cardId, type, path, summary = "Deleted", state = CardState.DONE)
                } else {
                    upsertFileCard(cardId, type, path, summary = "$path does not exist", state = CardState.FAILED)
                }
            }
        }
    }

    // Safety net for generation that stops (cancel, error, or a max-token cutoff) while
    // a card is still STREAMING. Without this a card could sit showing "Writing…"
    // forever even though nothing will ever update it again.
    private fun markInterruptedCardsAsFailed() {
        fileCards.forEachIndexed { i, card ->
            if (card.state == CardState.STREAMING) {
                fileCards[i] = card.copy(state = CardState.FAILED, summary = "Interrupted before completion")
            }
        }
    }

    // Best-effort, local, static check — not a compiler and not a build. It only looks at
    // import lines, top-level class/object names, and plain substring matches against
    // AndroidManifest.xml, entirely from files already held in memory. It is named and
    // worded so it never overstates what it actually verified.
    private fun checkWiring() {
        val touched = fileCards.filter { it.state == CardState.DONE }
        if (touched.isEmpty()) return

        val kotlinFiles = files.filter { it.path.endsWith(".kt") }
        val classOf = kotlinFiles.associate { f ->
            f.path to (Regex("""\b(?:class|object)\s+(\w+)""").find(f.content)?.groupValues?.get(1)
                ?: f.path.substringAfterLast('/').removeSuffix(".kt"))
        }
        val packageOf = kotlinFiles.associate { f ->
            f.path to (Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE).find(f.content)?.groupValues?.get(1).orEmpty())
        }
        val manifest = files.firstOrNull { it.path.endsWith("AndroidManifest.xml") }?.content.orEmpty()

        var connections = 0
        val warnings = mutableListOf<String>()

        touched.filter { it.kind != "delete" && it.path.endsWith(".kt") }.forEach { card ->
            val file = files.firstOrNull { it.path == card.path } ?: return@forEach
            Regex("""^\s*import\s+([\w.]+)""", RegexOption.MULTILINE).findAll(file.content).forEach { m ->
                val imported = m.groupValues[1]
                val importedClass = imported.substringAfterLast('.')
                val matched = classOf.entries.firstOrNull { it.value == importedClass && it.key != card.path }
                when {
                    matched != null -> connections++
                    packageOf.values.any { it.isNotBlank() && imported.startsWith(it) } ->
                        warnings += "${card.path.substringAfterLast('/')} imports $imported but no matching file was found"
                }
            }
            val looksLikeActivity = file.content.contains("ComponentActivity") || file.content.contains(": Activity")
            val className = classOf[card.path].orEmpty()
            if (looksLikeActivity && className.isNotBlank() && !manifest.contains(className)) {
                warnings += "$className looks like an Activity but AndroidManifest.xml has no matching entry"
            }
        }

        touched.filter { it.kind == "delete" && it.path.endsWith(".kt") }.forEach { card ->
            val deletedClass = card.path.substringAfterLast('/').removeSuffix(".kt")
            val stillReferenced = files.any { it.path != card.path && it.path.endsWith(".kt") && it.content.contains(deletedClass) }
            if (stillReferenced) warnings += "$deletedClass was deleted but another file still references it"
        }

        val detail = when {
            warnings.isNotEmpty() -> "Checked imports and manifest entries across ${touched.size} touched file(s). ${warnings.size} possible issue(s): ${warnings.joinToString("; ")}"
            connections > 0 -> "Checked imports and manifest entries across ${touched.size} touched file(s); $connections cross-file reference(s) matched."
            else -> "Checked imports and manifest entries across ${touched.size} touched file(s); no cross-file Kotlin references to verify."
        }
        addWorkLine("Wiring", detail, if (warnings.isEmpty()) StepState.SUCCESS else StepState.FAILED)
    }

    private fun visibleResponse(raw: String): String {
        val complete = raw.replace(closeActionRegex, "")
        val open = complete.indexOf("<sa_action", ignoreCase = true)
        return (if (open >= 0) complete.substring(0, open) else complete).trimEnd()
    }

    private fun recentHistory(): String = messages.takeLast(12).joinToString("\n") {
        if (it.user) "USER: ${it.text}" else "SA: ${it.text.take(3500)}"
    }

    private fun buildContext(webRequest: Boolean = false): String {
        val label = if (webRequest)
            "(This is this app's own Android/Kotlin scaffold — unrelated to the website being asked for below. Do not copy its language or structure.)\n\n"
        else ""
        return label + files.take(12).joinToString("\n\n") {
            "FILE: ${it.path}\n${it.content.take(6000)}"
        }
    }

    override fun onCleared() {
        runCatching { LlamaBridge.nativeCancelGenerate() }
        viewModelScope.launch(Dispatchers.IO) { runCatching { LlamaBridge.shutdown() } }
        super.onCleared()
    }
}
