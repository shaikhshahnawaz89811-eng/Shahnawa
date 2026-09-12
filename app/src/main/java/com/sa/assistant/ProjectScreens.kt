package com.sa.assistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Files(vm: SAViewModel) {
    LazyColumn(Modifier.fillMaxSize().background(BG), contentPadding = PaddingValues(12.dp)) {
        items(vm.files, key = { it.path }) { file ->
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selectFile(file.path) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(file.path, color = TXT, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = BORDER)
            }
        }
    }
}

@Composable
internal fun Build(vm: SAViewModel) {
    Column(Modifier.fillMaxSize().background(BG).padding(16.dp)) {
        Text("Build & validate", color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text("SA cannot run Gradle on this device. This checks the workspace shape only, the same way Wiring only checks imports and manifest entries — neither one is a real build.", color = MUTED, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.validateWorkspace() }, colors = ButtonDefaults.buttonColors(containerColor = BLUE)) {
            Text("Validate workspace")
        }
        Spacer(Modifier.height(16.dp))
        Text(vm.validationText, color = if (vm.validationText.startsWith("Validation failed")) RED else TXT, fontSize = 12.sp)
    }
}

@Composable
internal fun ErrorScreen(vm: SAViewModel) {
    Column(
        Modifier.fillMaxSize().background(BG).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(Icons.Default.Error, contentDescription = null, tint = RED, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Something needs attention", color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(vm.errorText, color = MUTED, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.screen = Screen.CHAT }, colors = ButtonDefaults.buttonColors(containerColor = BLUE)) { Text("Back to chat") }
    }
}

@Composable
internal fun AttachmentsScreen(vm: SAViewModel) {
    LazyColumn(Modifier.fillMaxSize().background(BG), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Attachments", color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
        }
        items(vm.attachments) { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AttachFile, contentDescription = null, tint = MUTED)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(a.name, color = TXT, fontSize = 13.sp)
                    Text(a.kind, color = MUTED, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
internal fun PauseScreen(vm: SAViewModel) {
    Column(
        Modifier.fillMaxSize().background(BG).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(Icons.Default.Pause, contentDescription = null, tint = AMBER, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text("Generation paused", color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("Nothing further was written. You can resume from the same request.", color = MUTED, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row {
            OutlinedButton(onClick = { vm.screen = Screen.CHAT }) { Text("Close", color = TXT) }
            Spacer(Modifier.width(12.dp))
            Button(onClick = vm::resume, colors = ButtonDefaults.buttonColors(containerColor = BLUE)) { Text("Resume") }
        }
    }
}

@Composable
internal fun EditScreen(vm: SAViewModel) {
    Column(Modifier.fillMaxSize().background(BG).padding(16.dp)) {
        Text("Edit message", color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("Saving replays the conversation from here; later replies are cleared.", color = MUTED, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = vm.editedText, onValueChange = { vm.editedText = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = TextStyle(color = TXT, fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BORDER, unfocusedBorderColor = BORDER)
        )
        Spacer(Modifier.height(12.dp))
        Row {
            OutlinedButton(onClick = { vm.screen = Screen.CHAT }) { Text("Cancel", color = TXT) }
            Spacer(Modifier.width(12.dp))
            Button(onClick = vm::saveEdit, colors = ButtonDefaults.buttonColors(containerColor = BLUE)) { Text("Save") }
        }
    }
}

@Composable
internal fun ZipScreen(vm: SAViewModel) {
    Column(
        Modifier.fillMaxSize().background(BG).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GREEN, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text("Project exported", color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("${vm.files.size} files were written to the location you chose.", color = MUTED, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.screen = Screen.CHAT }, colors = ButtonDefaults.buttonColors(containerColor = BLUE)) { Text("Done") }
    }
}

@Composable
internal fun NewProjectScreen(vm: SAViewModel) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(BG).padding(16.dp)) {
        Text("New project", color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("This replaces the current workspace. Export a ZIP first if you want to keep it.", color = MUTED, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            placeholder = { Text("Project name", color = MUTED) },
            textStyle = TextStyle(color = TXT, fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BORDER, unfocusedBorderColor = BORDER),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row {
            OutlinedButton(onClick = { vm.screen = Screen.CHAT }) { Text("Cancel", color = TXT) }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { vm.newProject(name) }, colors = ButtonDefaults.buttonColors(containerColor = BLUE), enabled = name.isNotBlank()) { Text("Create") }
        }
    }
}

@Composable
internal fun ResumeScreen(vm: SAViewModel) {
    Column(
        Modifier.fillMaxSize().background(BG).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(Icons.Default.History, contentDescription = null, tint = CYAN, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text("Continue where you left off?", color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(vm.taskTitle.ifBlank { "No saved task" }, color = MUTED, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row {
            OutlinedButton(onClick = { vm.screen = Screen.CHAT }) { Text("Not now", color = TXT) }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { vm.input = vm.taskTitle; vm.screen = Screen.CHAT },
                colors = ButtonDefaults.buttonColors(containerColor = BLUE),
                enabled = vm.taskTitle.isNotBlank()
            ) { Text("Continue") }
        }
    }
}

@Composable
internal fun SettingsScreen(vm: SAViewModel) {
    val context = LocalContext.current
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.importModel(context, it) }
    }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.exportZip(context, it) }
    }
    LazyColumn(Modifier.fillMaxSize().background(BG), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Settings", color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            SettingsCard("Model") {
                Text(vm.modelName, color = TXT, fontSize = 13.sp)
                Text(vm.modelMeta, color = MUTED, fontSize = 11.sp)
                if (vm.lastGenSeconds > 0.0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Last run: ~${vm.lastPromptTokens} in \u00b7 ~${vm.lastOutputTokens} out \u00b7 ${"%.1f".format(vm.lastGenSeconds)}s",
                        color = MUTED, fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                val busy = vm.modelOp != ModelOp.IDLE
                if (vm.modelPath.isBlank()) {
                    Button(
                        onClick = { pickModel.launch("*/*") },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = BLUE)
                    ) {
                        Text(if (vm.modelOp == ModelOp.IMPORTING) "Importing…" else "Import GGUF model")
                    }
                } else {
                    Row {
                        if (vm.modelLoaded) {
                            Button(
                                onClick = { vm.unloadModel() },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = BLUE)
                            ) { Text(if (vm.modelOp == ModelOp.UNLOADING) "Unloading…" else "Unload") }
                        } else {
                            Button(
                                onClick = { vm.loadModel(vm.modelPath) },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = BLUE)
                            ) { Text(if (vm.modelOp == ModelOp.LOADING) "Loading…" else "Load") }
                            Spacer(Modifier.width(10.dp))
                            OutlinedButton(onClick = { vm.deleteModel() }, enabled = !busy) {
                                Text(if (vm.modelOp == ModelOp.DELETING) "Deleting…" else "Delete", color = RED)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { pickModel.launch("*/*") }, enabled = !busy) {
                        Text("Import a different model", color = CYAN, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsCard("Project") {
                Text(vm.projectName, color = TXT, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { createZip.launch("${vm.projectName}.zip") }) { Text("Export ZIP", color = TXT) }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { vm.screen = Screen.NEW_PROJECT }) { Text("New project", color = TXT) }
                }
                if (vm.lastTaskSaved && vm.taskTitle.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.screen = Screen.RESUME }) { Text("Resume last task", color = CYAN) }
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsCard("About") {
                Text("SA \u2014 offline coding assistant", color = TXT, fontSize = 13.sp)
                Text("v2.3.0 \u2022 runs entirely on this device, no network permission", color = MUTED, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SURFACE)
            .border(1.dp, BORDER, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(title, color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        content()
    }
}
