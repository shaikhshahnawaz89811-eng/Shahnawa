package com.sa.assistant

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val BG = Color(0xFF050914)
private val SURFACE = Color(0xFF091221)
private val SURFACE2 = Color(0xFF0D182A)
private val SURFACE3 = Color(0xFF102039)
private val BORDER = Color(0xFF14518A)
private val BLUE = Color(0xFF168BFF)
private val CYAN = Color(0xFF2EDBFF)
private val MUTED = Color(0xFF7894AF)
private val TXT = Color(0xFFEAF6FF)
private val GREEN = Color(0xFF2BD37E)
private val RED = Color(0xFFFF5872)
private val AMBER = Color(0xFFFFC857)

internal enum class Screen { CHAT, CODE, CODE_STREAM, FILES, BUILD, ERROR, ATTACHMENTS, PAUSE, EDIT, ZIP, NEW_PROJECT, RESUME, SETTINGS }
internal enum class StepState { WAITING, RUNNING, SUCCESS, FAILED, PAUSED }

data class ProjectFile(val path: String, val content: String)
data class ChatMessage(val id: Long, val user: Boolean, val text: String, val streaming: Boolean = false)
data class Attachment(val uri: String, val name: String, val kind: String = "file")
internal data class WorkLine(val id: Long, val title: String, val detail: String = "", val state: StepState = StepState.WAITING, val expandable: Boolean = true)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SAApp() }
    }
}

internal class SAViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("sa_state", Context.MODE_PRIVATE)

    var screen by mutableStateOf(Screen.CHAT)
    var input by mutableStateOf("")
    var projectName by mutableStateOf(prefs.getString("project", "MyApp") ?: "MyApp")
    var selectedFile by mutableStateOf("")
    var code by mutableStateOf("")
    var modelPath by mutableStateOf(prefs.getString("modelPath", "") ?: "")
    var modelName by mutableStateOf(prefs.getString("modelName", "No GGUF model loaded") ?: "No GGUF model loaded")
    var modelLoaded by mutableStateOf(false)
    var modelMeta by mutableStateOf("Import an instruction-tuned GGUF model")
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
    var validationText by mutableStateOf("Workspace not validated")
    var attachmentContext by mutableStateOf("")

    val messages = mutableStateListOf<ChatMessage>()
    val files = mutableStateListOf<ProjectFile>()
    val attachments = mutableStateListOf<Attachment>()
    val workLines = mutableStateListOf<WorkLine>()

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

    init {
        restoreState()
        if (files.isEmpty()) loadStarter()
        if (selectedFile.isBlank() && files.isNotEmpty()) {
            selectedFile = files.first().path
            code = files.first().content
        }
    }

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
            prefs.edit()
                .putString("project", projectSnapshot)
                .putString("messages", m.toString())
                .putString("files", f.toString())
                .putString("workLines", w.toString())
                .putString("selectedFile", selectedSnapshot)
                .putString("taskTitle", taskSnapshot)
                .putBoolean("taskSaved", taskSavedSnapshot)
                .putString("modelPath", modelPathSnapshot)
                .putString("modelName", modelNameSnapshot)
                .putString("validation", validationSnapshot)
                .apply()
        }
    }

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
        taskTitle = ""
        lastTaskSaved = false
        sessionActive = false
        runCatching { LlamaBridge.sessionReset() }
        persistState()
        screen = Screen.CHAT
    }

    fun importModel(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "models").apply { mkdirs() }
                val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(dir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024) }
                } ?: error("Unable to open selected model")
                withContext(Dispatchers.Main) {
                    modelPath = target.absolutePath
                    modelName = name
                    modelMeta = "Imported locally • ${target.length() / (1024 * 1024)} MB • loading…"
                    prefs.edit().putString("modelPath", modelPath).putString("modelName", modelName).apply()
                }
                loadModel(target.absolutePath)
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { modelLoaded = false; modelMeta = "Model import failed: ${e.message ?: "unknown error"}" }
            }
        }
    }

    private fun loadModel(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                LlamaBridge.updateGenerateParams(
                    temperature = 0.35f,
                    maxTokens = 1024,
                    topP = 0.90f,
                    topK = 40,
                    repeatPenalty = 1.08f,
                    contextLength = 4096,
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
                        "Loaded • ${type ?: "unknown/base"} • context 4096 • mmap"
                    } else "Model load failed"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { modelLoaded = false; modelMeta = "Load failed: ${e.message ?: "native error"}" }
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isWorking) return
        saveFile()
        streamMessageId = null
        messages += ChatMessage(System.currentTimeMillis(), true, text)
        input = ""
        taskTitle = text
        lastTaskSaved = false
        errorText = ""
        workLines.clear()
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
        generationJob = viewModelScope.launch { runAgent(text) }
    }

    private suspend fun runAgent(user: String) {
        try {
            val context = buildContext()
            addWorkLine("Read workspace", "Loaded ${files.size} project files and bounded each file to the phone-safe context budget.", StepState.SUCCESS)
            addWorkLine("Prepare task context", "Prepared conversation history, project files, and the current user request.", StepState.SUCCESS)
            generationWorkLineId = addWorkLine("Generate", "Local GGUF generation started. Tokens will appear directly in the assistant response.", StepState.RUNNING)
            streamAgent(user, context)
        } catch (e: Throwable) {
            failTask(e.message ?: "Generation failed")
        }
    }

    private fun addWorkLine(title: String, detail: String, state: StepState): Long {
        val id = System.nanoTime()
        workLines += WorkLine(id, title, detail, state)
        return id
    }

    private suspend fun streamAgent(user: String, context: String) {
        val system = """You are SA, an offline Android coding assistant.
Never claim a file was changed, a build passed, or an app was installed unless SA actually performed that operation.
Inspect first. Explain root cause before proposing a fix. Prefer minimal, connected changes. Keep answers concise but useful.
When you actually need to change workspace files, emit one or more exact blocks using <sa_action type="create|update|delete" path="relative/path">content</sa_action>. Do not claim an action succeeded unless the block is valid.
Example: <sa_action type="create" path="app/src/main/java/com/sa/app/Student.kt">package com.sa.app
data class Student(val id: Long, val name: String)</sa_action>
Always use this exact tag syntax to create or edit files. A plain ``` code fence is only for showing a snippet in the chat reply — it never updates the workspace, so any file the user asked for must also appear as an <sa_action> block.
Project: $projectName"""
        val history = recentHistory()
        val prompt = "Conversation:\n$history\n\nCurrent project context:\n$context\n\nAttachment context:\n$attachmentContext\n\nUser request:\n$user"

        val assistantId = System.currentTimeMillis()
        messages += ChatMessage(assistantId, false, "", true)
        streamMessageId = assistantId
        synchronized(streamBuffer) { streamBuffer.setLength(0) }
        synchronized(rawStreamBuffer) { rawStreamBuffer.setLength(0) }

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
                        context + "\n\n" + history,
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
        if (index >= 0 && chunk.isNotEmpty()) {
            val old = messages[index]
            messages[index] = old.copy(text = visibleResponse(old.text + chunk))
        }
        // Parse completed SA file-action blocks only on the coalesced UI tick. This avoids
        // launching a main-thread coroutine for every native token.
        val raw = synchronized(rawStreamBuffer) { rawStreamBuffer.toString() }
        applyModelActions(raw)
    }

    private fun completeStream() {
        synchronized(this) {
            if (generationFinished || generationCancelled) return
            generationFinished = true
        }
        viewModelScope.launch(Dispatchers.Main) {
            flushStreamBuffer(true)
            generationWorkLineId?.let { id ->
                val i = workLines.indexOfFirst { it.id == id }
                if (i >= 0) workLines[i] = workLines[i].copy(state = StepState.SUCCESS, detail = "Generation completed and the response was finalized.")
            }
            addWorkLine("Verify", "Response state, generated actions, and workspace persistence were finalized.", StepState.SUCCESS)
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

    fun exportZip(context: Context, uri: Uri) {
        saveFile()
        val exportFiles = files.toList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    ZipOutputStream(output).use { zip ->
                        exportFiles.forEach { f ->
                            zip.putNextEntry(ZipEntry(f.path))
                            zip.write(f.content.toByteArray())
                            zip.closeEntry()
                        }
                    }
                } ?: error("Unable to create destination file")
                withContext(Dispatchers.Main) { screen = Screen.ZIP }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { errorText = "ZIP export failed: ${e.message ?: "unknown error"}"; screen = Screen.ERROR }
            }
        }
    }

    fun resumeLater() {
        lastTaskSaved = true
        persistState()
        screen = Screen.CHAT
    }

    fun toggleWorkLine(id: Long) {
        expandedWorkLineId = if (expandedWorkLineId == id) null else id
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

    private fun applyModelActions(raw: String) {
        val actionRegex = Regex(
            """<sa_action\s+type=\"(create|update|delete)\"\s+path=\"([^\"]+)\">(.*?)</sa_action>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val actions = actionRegex.findAll(raw).toList()
        if (actions.isEmpty()) return
        actions.forEach { match ->
            val key = match.value
            if (!processedActionKeys.add(key)) return@forEach
            val type = match.groupValues[1].lowercase()
            val path = match.groupValues[2]
            val content = match.groupValues[3].trimStart('\n', '\r')
            if (!safeWorkspacePath(path) || content.length > 200_000) {
                addWorkLine("Rejected file action", "Unsafe path or oversized content: $path", StepState.FAILED)
                return@forEach
            }
            when (type) {
                "create" -> {
                    if (files.any { it.path == path }) {
                        addWorkLine("Create failed", "$path already exists", StepState.FAILED)
                    } else {
                        files += ProjectFile(path, content)
                        addWorkLine("Created $path", "Created ${content.lines().size} lines.", StepState.SUCCESS)
                    }
                }
                "update" -> {
                    val i = files.indexOfFirst { it.path == path }
                    if (i < 0) {
                        addWorkLine("Update failed", "$path does not exist", StepState.FAILED)
                    } else {
                        files[i] = files[i].copy(content = content)
                        if (selectedFile == path) code = content
                        addWorkLine("Updated $path", "Replaced the saved file content and refreshed the selected editor when applicable.", StepState.SUCCESS)
                    }
                }
                "delete" -> {
                    val removed = files.removeAll { it.path == path }
                    if (removed) {
                        if (selectedFile == path) {
                            selectedFile = files.firstOrNull()?.path.orEmpty()
                            code = files.firstOrNull()?.content.orEmpty()
                        }
                        addWorkLine("Deleted $path", "The workspace entry was removed.", StepState.SUCCESS)
                    } else addWorkLine("Delete failed", "$path does not exist", StepState.FAILED)
                }
            }
        }
        saveFile()
    }

    private fun visibleResponse(raw: String): String {
        val complete = raw.replace(
            Regex("""<sa_action\s+type=\"(create|update|delete)\"\s+path=\"[^\"]+\">.*?</sa_action>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            ""
        )
        val open = complete.indexOf("<sa_action", ignoreCase = true)
        return (if (open >= 0) complete.substring(0, open) else complete).trimEnd()
    }

    private fun recentHistory(): String = messages.takeLast(12).joinToString("\n") {
        if (it.user) "USER: ${it.text}" else "SA: ${it.text.take(3500)}"
    }

    private fun buildContext(): String = files.take(12).joinToString("\n\n") {
        "FILE: ${it.path}\n${it.content.take(6000)}"
    }

    override fun onCleared() {
        runCatching { LlamaBridge.nativeCancelGenerate() }
        viewModelScope.launch(Dispatchers.IO) { runCatching { LlamaBridge.shutdown() } }
        super.onCleared()
    }
}

@Composable
private fun SAApp(vm: SAViewModel = viewModel()) {
    MaterialTheme(colorScheme = darkColorScheme(background = BG, surface = SURFACE, primary = BLUE, secondary = CYAN, onBackground = TXT, onSurface = TXT, error = RED)) {
        Surface(Modifier.fillMaxSize(), color = BG) {
            when (vm.screen) {
                Screen.CHAT -> Chat(vm)
                Screen.CODE -> Code(vm)
                Screen.CODE_STREAM -> CodeStream(vm)
                Screen.FILES -> Files(vm)
                Screen.BUILD -> Build(vm)
                Screen.ERROR -> Error(vm)
                Screen.ATTACHMENTS -> Attachments(vm)
                Screen.PAUSE -> Pause(vm)
                Screen.EDIT -> Edit(vm)
                Screen.ZIP -> Zip(vm)
                Screen.NEW_PROJECT -> NewProject(vm)
                Screen.RESUME -> Resume(vm)
                Screen.SETTINGS -> Settings(vm)
            }
        }
    }
}

@Composable
private fun Header(vm: SAViewModel, title: String = "SA", back: Boolean = false) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.screen = if (back) Screen.CHAT else Screen.SETTINGS }, modifier = Modifier.size(34.dp)) {
            Icon(if (back) Icons.Default.ArrowBack else Icons.Default.Menu, null, tint = TXT)
        }
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Brush.radialGradient(listOf(CYAN, BLUE, Color(0xFF071E3A)))), contentAlignment = Alignment.Center) {
            Text("SA", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TXT, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            Text(if (vm.modelLoaded) vm.modelName else "Offline AI Coding Assistant", color = CYAN, fontSize = 9.sp, maxLines = 1)
        }
        IconButton(onClick = { vm.screen = Screen.SETTINGS }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.MoreVert, null, tint = TXT) }
    }
}

@Composable
private fun Chat(vm: SAViewModel) {
    val list = rememberLazyListState()
    var userScrolledAway by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.addAttachment(it, it.lastPathSegment?.substringAfterLast('/') ?: "Attachment") }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.addAttachment(it, it.lastPathSegment?.substringAfterLast('/') ?: "Image") }
    }

    LaunchedEffect(list) {
        snapshotFlow { list.canScrollForward } .collect { userScrolledAway = it }
    }
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text) {
        // Only follow a stream when the viewport is already at the bottom.
        if (!userScrolledAway && vm.messages.isNotEmpty()) list.animateScrollToItem(vm.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Header(vm)
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item {
                if (vm.messages.size <= 1) Welcome()
            }
            items(vm.messages, key = { it.id }) { message -> MessageBubble(vm, message) }
            if (vm.workLines.isNotEmpty()) item(key = "work-log") { WorkLog(vm) }
            if (vm.attachments.isNotEmpty()) item(key = "attachments") { AttachmentStrip(vm) }
        }
        Composer(vm, picker, imagePicker)
    }
}

@Composable
private fun Welcome() {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(50)).background(Brush.radialGradient(listOf(CYAN, BLUE, Color(0xFF0A1230)))), contentAlignment = Alignment.Center) { Text("", fontSize = 1.sp) }
        Text("Hello, I'm SA", color = TXT, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text("Your offline AI coding assistant.", color = MUTED, fontSize = 13.sp, textAlign = TextAlign.Center)
        listOf("Code & fix errors", "Create new projects", "Work with multiple files", "Build & run", "Attach project files", "Pause / resume sessions").forEach { Feature(it) }
    }
}

@Composable
private fun Feature(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, null, tint = BLUE, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = TXT, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(vm: SAViewModel, message: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (message.user) Color(0xFF123D69) else SURFACE2,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, BORDER.copy(alpha = .65f)),
            modifier = Modifier.widthIn(max = 350.dp).combinedClickable(onClick = {}, onLongClick = { vm.edit(message) })
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(message.text.ifBlank { "Generating…" }, color = TXT, fontSize = 13.sp, lineHeight = 18.sp)
                if (message.streaming) {
                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(CYAN))
                        Spacer(Modifier.width(5.dp))
                        Text("Generating", color = CYAN, fontSize = 9.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = vm::stopGeneration, contentPadding = PaddingValues(0.dp)) { Text("Stop", color = RED, fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkLog(vm: SAViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
        vm.workLines.forEach { line ->
            val color = when (line.state) {
                StepState.SUCCESS -> GREEN
                StepState.RUNNING -> CYAN
                StepState.FAILED -> RED
                StepState.PAUSED -> AMBER
                StepState.WAITING -> MUTED
            }
            Column(
                Modifier.fillMaxWidth()
                    .animateContentSize()
                    .clickable { vm.toggleWorkLine(line.id) }
                    .padding(vertical = 3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (line.state) {
                            StepState.SUCCESS -> "✓"
                            StepState.FAILED -> "×"
                            StepState.PAUSED -> "Ⅱ"
                            StepState.RUNNING -> "…"
                            StepState.WAITING -> "·"
                        },
                        color = color, fontSize = 12.sp, modifier = Modifier.width(18.dp)
                    )
                    Text(line.title, color = if (line.state == StepState.WAITING) MUTED else TXT, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (line.state == StepState.RUNNING) TextButton(onClick = vm::pause, contentPadding = PaddingValues(0.dp)) { Text("Pause", color = AMBER, fontSize = 10.sp) }
                    if (line.expandable && line.detail.isNotBlank()) Text(if (vm.expandedWorkLineId == line.id) "⌃" else "⌄", color = MUTED, fontSize = 11.sp)
                }
                if (vm.expandedWorkLineId == line.id && line.detail.isNotBlank()) {
                    Text(line.detail, color = MUTED, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 18.dp, top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun Composer(
    vm: SAViewModel,
    picker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Uri?>,
    imagePicker: androidx.activity.compose.ManagedActivityResultLauncher<String, Uri?>
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().background(BG)) {
        if (vm.showComposerMenu) {
            Surface(color = SURFACE2, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, BORDER), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ComposerAction("Image", Icons.Default.Image, { imagePicker.launch("image/*") })
                    ComposerAction("File", Icons.Default.InsertDriveFile, { picker.launch(arrayOf("text/*", "application/pdf", "application/zip", "application/octet-stream", "*/*")) })
                    ComposerAction("ZIP", Icons.Default.Archive, { picker.launch(arrayOf("application/zip")) })
                    ComposerAction("Code", Icons.Default.Code, { vm.screen = Screen.FILES; vm.showComposerMenu = false })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp).clip(RoundedCornerShape(18.dp)).background(SURFACE).border(1.dp, BORDER, RoundedCornerShape(18.dp)).padding(4.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { vm.showComposerMenu = !vm.showComposerMenu }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Add, null, tint = TXT) }
            BasicTextField(
                value = vm.input,
                onValueChange = { vm.input = it.take(12000) },
                modifier = Modifier.weight(1f).heightIn(min = 36.dp, max = 120.dp).padding(horizontal = 8.dp, vertical = 9.dp),
                textStyle = TextStyle(color = TXT, fontSize = 13.sp, lineHeight = 18.sp),
                maxLines = 6,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CYAN),
                decorationBox = { inner -> if (vm.input.isEmpty()) Text("Message SA...", color = MUTED, fontSize = 13.sp); inner() }
            )
            IconButton(onClick = { if (vm.isWorking) vm.stopGeneration() else vm.send() }, enabled = vm.isWorking || vm.input.isNotBlank(), modifier = Modifier.size(36.dp)) {
                Icon(if (vm.isWorking) Icons.Default.Stop else Icons.Default.Send, null, tint = if (vm.isWorking) RED else CYAN)
            }
        }
    }
}

@Composable
private fun RowScope.ComposerAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = CYAN, modifier = Modifier.size(18.dp))
            Text(title, color = TXT, fontSize = 9.sp)
        }
    }
}

@Composable
private fun AttachmentStrip(vm: SAViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        vm.attachments.forEach { a ->
            Surface(color = SURFACE2, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BORDER), modifier = Modifier.padding(end = 6.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AttachFile, null, tint = CYAN, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(a.name, color = TXT, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Code(vm: SAViewModel) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Code", true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.selectedFile.substringAfterLast('/'), color = TXT, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(vm.code)) }) { Text("Copy") }
            TextButton(onClick = { vm.screen = Screen.CODE_STREAM }) { Text("Live") }
            TextButton(onClick = vm::saveFile) { Text("Save") }
        }
        BasicTextField(vm.code, { vm.code = it }, Modifier.weight(1f).fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF06101C)).verticalScroll(rememberScrollState()).padding(12.dp), textStyle = TextStyle(color = TXT, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(CYAN))
        Bottom(vm)
    }
}

@Composable
private fun CodeStream(vm: SAViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Code Streaming / Live Update", true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.selectedFile.substringAfterLast('/'), color = TXT, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("● Live", color = CYAN, fontSize = 10.sp)
        }
        BasicTextField(vm.code, { vm.code = it }, Modifier.weight(1f).fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF06101C)).verticalScroll(rememberScrollState()).padding(12.dp), textStyle = TextStyle(color = TXT, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(CYAN))
        Bottom(vm)
    }
}

@Composable
private fun Files(vm: SAViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Project Files", true)
        Text("Project: ${vm.projectName}", color = TXT, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(vm.files, key = { it.path }) { f ->
                Surface(color = if (f.path == vm.selectedFile) SURFACE3 else SURFACE, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BORDER.copy(alpha = .7f)), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { vm.selectFile(f.path) }) {
                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.path.endsWith(".kt")) Icons.Default.Code else Icons.Default.InsertDriveFile, null, tint = CYAN, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(9.dp)); Text(f.path, color = TXT, fontSize = 12.sp)
                    }
                }
            }
        }
        Bottom(vm)
    }
}

@Composable
private fun Build(vm: SAViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Build & Run", true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Status("Workspace validation", vm.validationText, vm.validationText.startsWith("Workspace validation passed"))
            Status("Local APK build", "The APK does not bundle Gradle or the Android SDK, so no local build success is fabricated.", false)
            Status("GitHub Actions", "Real CI workflow: dependency resolution → tests → lint → assembleDebug → APK artifact.", true)
            Button(onClick = vm::validateWorkspace, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Validate workspace") }
        }
        Bottom(vm)
    }
}

@Composable
private fun Status(title: String, body: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(SURFACE).border(1.dp, BORDER, RoundedCornerShape(14.dp)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (ok) GREEN else MUTED)
        Spacer(Modifier.width(10.dp)); Column { Text(title, color = TXT, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(body, color = MUTED, fontSize = 11.sp) }
    }
}

@Composable
private fun Error(vm: SAViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Error Handling & Auto Fix", true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Surface(color = Color(0xFF35131C), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, RED.copy(alpha = .55f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) { Text("Task failed", color = RED, fontWeight = FontWeight.Bold); Text(vm.errorText.ifBlank { "No current error." }, color = TXT, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            Spacer(Modifier.height(12.dp)); Text("Root-cause pipeline", color = TXT, fontWeight = FontWeight.Bold)
            listOf("Locate diagnostic", "Inspect symbol and dependent files", "Plan smallest safe change", "Apply only verified changes", "Rebuild and audit").forEach { Text("• $it", color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        Bottom(vm)
    }
}

@Composable
private fun Attachments(vm: SAViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Attachments: Image / File / ZIP", true)
        LazyColumn(Modifier.weight(1f).padding(14.dp)) {
            if (vm.attachments.isEmpty()) item { Text("No attachments", color = MUTED) }
            items(vm.attachments, key = { it.uri }) { a -> Surface(color = SURFACE2, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, BORDER), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(a.name, color = TXT, modifier = Modifier.padding(13.dp)) } }
        }
        Bottom(vm)
    }
}

@Composable
private fun Pause(vm: SAViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Session Paused", true)
        Column(Modifier.weight(1f).padding(14.dp)) {
            Surface(color = Color(0xFF2A2111), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AMBER.copy(alpha = .55f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) { Text("Session Paused", color = TXT, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Project: ${vm.projectName}", color = MUTED, modifier = Modifier.padding(top = 8.dp)); Text("Last task: ${vm.taskTitle}", color = MUTED, fontSize = 12.sp); Text("The active native generation was cancelled safely. Resume puts the task back in the composer instead of pretending generation continued.", color = AMBER, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { vm.screen = Screen.CHAT }, modifier = Modifier.weight(1f)) { Text("Close") }; Button(onClick = vm::resume, modifier = Modifier.weight(1f)) { Text("Resume") } }
    }
}

@Composable
private fun Edit(vm: SAViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Edit Previous Message", true)
        Column(Modifier.weight(1f).padding(14.dp)) {
            OutlinedTextField(value = vm.editedText, onValueChange = { vm.editedText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), textStyle = TextStyle(color = TXT))
            Spacer(Modifier.height(12.dp)); Text("Saving an edited user message removes later turns so the conversation remains internally consistent.", color = MUTED, fontSize = 11.sp)
        }
        Button(onClick = vm::saveEdit, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) { Text("Save & continue") }
    }
}

@Composable
private fun Zip(vm: SAViewModel) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.exportZip(context, it) } }
    Column(Modifier.fillMaxSize()) {
        Header(vm, "ZIP Export — Only When Asked", true)
        Column(Modifier.weight(1f).padding(14.dp)) { Text("Project: ${vm.projectName}", color = TXT, fontWeight = FontWeight.Bold); Text("Exports the actual workspace files currently saved in SA.", color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        Button(onClick = { save.launch("${vm.projectName}.zip") }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) { Icon(Icons.Default.Archive, null); Spacer(Modifier.width(8.dp)); Text("Create ZIP") }
    }
}

@Composable
private fun NewProject(vm: SAViewModel) {
    var name by remember { mutableStateOf(vm.projectName) }
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Create New Project", true)
        Column(Modifier.weight(1f).padding(16.dp)) { Text("Project name", color = TXT, fontWeight = FontWeight.SemiBold); OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER)); Spacer(Modifier.height(16.dp)); Button(onClick = { vm.newProject(name) }, modifier = Modifier.fillMaxWidth()) { Text("Create project") } }
    }
}

@Composable
private fun Resume(vm: SAViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Continue / Resume Later", true)
        Column(Modifier.weight(1f).padding(14.dp)) { Surface(color = SURFACE2, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BORDER), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Project: ${vm.projectName}", color = TXT, fontWeight = FontWeight.Bold); Text("Last task: ${vm.taskTitle.ifBlank { "None" }}", color = MUTED, fontSize = 12.sp); Text("Files: ${vm.files.size}", color = MUTED, fontSize = 12.sp); Text(if (vm.lastTaskSaved) "Saved locally" else "No completed task snapshot", color = if (vm.lastTaskSaved) GREEN else AMBER, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) } } }
        Button(onClick = { vm.input = vm.taskTitle; vm.screen = Screen.CHAT }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) { Text("Resume") }
    }
}

@Composable
private fun Settings(vm: SAViewModel) {
    val context = LocalContext.current
    val model = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importModel(context, it) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.exportZip(context, it) } }
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Settings / Model / Tools", true)
        LazyColumn(Modifier.weight(1f).padding(12.dp)) {
            item { SettingsCard("Model & Mode", vm.modelName, vm.modelMeta, Icons.Default.Memory) { model.launch(arrayOf("application/octet-stream", "application/gguf", "*/*")) } }
            item { SettingsCard("Tools", "Files • Local inference • Export", "Only implemented local tools are exposed.", Icons.Default.Build) {} }
            item { SettingsCard("Project", vm.projectName, "Current workspace", Icons.Default.Folder) { vm.screen = Screen.FILES } }
            item { SettingsCard("New Project", "Create workspace", "Creates a real editable starter project", Icons.Default.CreateNewFolder) { vm.screen = Screen.NEW_PROJECT } }
            item { SettingsCard("Memory", "Session + workspace state", "Saved locally for resume", Icons.Default.History) { vm.screen = Screen.RESUME } }
            item { SettingsCard("ZIP Export", "Project archive", "Create only when you ask for it", Icons.Default.Archive) { save.launch("${vm.projectName}.zip") } }
            item { SettingsCard("Appearance", "Dark", "SA premium dark interface", Icons.Default.DarkMode) {} }
            item { SettingsCard("About SA", "v2.2.0", "Offline-first • no INTERNET permission • local GGUF streaming", Icons.Default.Info) {} }
        }
    }
}

@Composable
private fun SettingsCard(title: String, value: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { action() }, color = SURFACE, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, BORDER)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = CYAN); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, color = TXT, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(value, color = CYAN, fontSize = 11.sp); Text(body, color = MUTED, fontSize = 10.sp) }; Icon(Icons.Default.ChevronRight, null, tint = MUTED) }
    }
}

@Composable
private fun Bottom(vm: SAViewModel) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Action("Files", Icons.Default.Folder) { vm.screen = Screen.FILES }
        Action("Build", Icons.Default.Build) { vm.screen = Screen.BUILD }
        Action("Chat", Icons.Default.Chat) { vm.screen = Screen.CHAT }
    }
}

@Composable
private fun Action(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)) { Icon(icon, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(title, fontSize = 10.sp) }
}

// Deliberately domain-neutral: this used to hard-code a Notes app (Note.kt, NoteRepository.kt,
// NoteViewModel.kt under com.sa.notes) no matter what `name` was passed in, so every new
// project's "current project context" sent to the model was Notes-shaped even when the user
// asked for something else (e.g. a school management app). The model then anchored on that
// misleading context instead of the actual request. This starter now only contains a blank
// activity so buildContext() has nothing domain-specific to leak, and real feature files are
// expected to arrive via <sa_action> once the user describes what they want.
fun starterFiles(name: String): List<ProjectFile> = listOf(
    ProjectFile("app/src/main/java/com/sa/app/MainActivity.kt", """package com.sa.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StarterApp() }
    }
}

@Composable
private fun StarterApp() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("$name", style = MaterialTheme.typography.headlineMedium)
                Text("Blank starter created by SA. Describe the app in chat to generate real files.")
            }
        }
    }
}
"""),
    ProjectFile("app/src/main/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:theme="@style/Theme.SA" android:label="$name" android:allowBackup="false" android:supportsRtl="true">
        <activity android:name=".MainActivity" android:exported="true" android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""),
    ProjectFile("app/build.gradle.kts", """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sa.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.sa.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.4.0")
}
"""),
    ProjectFile("app/src/main/res/values/styles.xml", """<resources>
    <style name="Theme.SA" parent="android:style/Theme.Material.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:statusBarColor">#050914</item>
        <item name="android:navigationBarColor">#050914</item>
    </style>
</resources>
"""),
    ProjectFile("build.gradle.kts", """plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
"""),
    ProjectFile("gradle.properties", """org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
"""),
    ProjectFile("settings.gradle.kts", """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "$name"
include(":app")
""")
)
