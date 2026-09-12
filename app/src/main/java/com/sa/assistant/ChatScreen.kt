package com.sa.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Chat(vm: SAViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (vm.messages.isEmpty()) {
            Box(Modifier.weight(1f)) { Welcome() }
        } else {
            val listState = rememberLazyListState()
            val itemCount = vm.messages.size + vm.timeline.size + (if (vm.attachments.isNotEmpty()) 1 else 0)
            val atBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    last == null || last.index >= info.totalItemsCount - 1
                }
            }
            // Keyed on the last message's text AND the last timeline entry (a WorkLine or a
            // FileCard) so the list keeps following while a file card is actively growing,
            // not only when a new chat bubble appears.
            LaunchedEffect(vm.messages.lastOrNull()?.text, vm.timeline.lastOrNull(), itemCount) {
                if (atBottom && itemCount > 0) listState.animateScrollToItem(itemCount - 1)
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                items(vm.messages, key = { it.id }) { message -> MessageBubble(vm, message) }
                items(vm.timeline, key = { "tl_${it.seq}" }) { entry ->
                    when (entry) {
                        is TimelineItem.Line -> WorkLineRow(vm, entry.line)
                        is TimelineItem.Card -> FileCardRow(vm, entry.card)
                    }
                }
                if (vm.attachments.isNotEmpty()) item(key = "attachments") { AttachmentStrip(vm) }
            }
        }
        Composer(vm)
    }
}

@Composable
private fun Welcome() {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(Brush.radialGradient(listOf(CYAN, BLUE, Color(0xFF071E3A)))),
            contentAlignment = Alignment.Center
        ) { Text("SA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp) }
        Spacer(Modifier.height(16.dp))
        Text("Offline AI coding assistant", color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("Runs a local GGUF model on this device. No internet required.", color = MUTED, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Feature("Describe an app or a change in plain language")
        Feature("Files stream in live, one card per file")
        Feature("Everything stays on this device")
    }
}

@Composable
private fun Feature(text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(CYAN))
        Spacer(Modifier.width(8.dp))
        Text(text, color = MUTED, fontSize = 12.sp)
    }
}

@Composable
private fun MessageBubble(vm: SAViewModel, message: ChatMessage) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (message.user) Alignment.End else Alignment.Start
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (message.user) SURFACE3 else SURFACE2)
                .border(1.dp, BORDER, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Column {
                if (message.text.isNotBlank()) {
                    // Cap the bubble at ~7 lines (7 * 20sp line height) and scroll the
                    // overflow inside it instead of letting the bubble grow without limit.
                    // While tokens are still streaming in, keep this inner scroll pinned to
                    // the bottom so the newest text is always the visible text.
                    val bubbleScroll = rememberScrollState()
                    LaunchedEffect(message.text) {
                        if (message.streaming) bubbleScroll.scrollTo(bubbleScroll.maxValue)
                    }
                    Box(
                        Modifier
                            .heightIn(max = 140.dp)
                            .verticalScroll(bubbleScroll)
                    ) {
                        Text(message.text, color = TXT, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                if (message.streaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = if (message.text.isNotBlank()) 6.dp else 0.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(12.dp), color = CYAN, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Generating", color = MUTED, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = vm::stopGeneration, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                            Text("Stop", color = RED, fontSize = 11.sp)
                        }
                    }
                }
                if (message.zipUri != null) {
                    Spacer(Modifier.height(if (message.text.isNotBlank()) 8.dp else 0.dp))
                    ZipDownloadCard(message.zipUri, message.zipName ?: "project.zip")
                }
            }
        }
        if (message.user && !vm.isWorking) {
            TextButton(onClick = { vm.edit(message) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                Text("Edit", color = MUTED, fontSize = 10.sp)
            }
        }
    }
}

// The card a "zip do" chat message ends with once the file is actually written to
// Downloads. Both buttons act on the same real content:// Uri MediaStore handed back —
// there is nothing to fake here, the zip already exists on disk before this shows.
@Composable
private fun ZipDownloadCard(zipUri: String, zipName: String) {
    val context = LocalContext.current
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SURFACE3)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.AttachFile, contentDescription = null, tint = CYAN, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(zipName, color = TXT, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = { openZip(context, zipUri) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("Open", color = CYAN, fontSize = 11.sp)
        }
        TextButton(onClick = { shareZip(context, zipUri, zipName) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("Share", color = CYAN, fontSize = 11.sp)
        }
    }
}

private fun openZip(context: Context, zipUri: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(zipUri), "application/zip")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun shareZip(context: Context, zipUri: String, zipName: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(zipUri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share $zipName").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
}

@Composable
private fun AttachmentStrip(vm: SAViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        vm.attachments.forEach { a ->
            Row(
                Modifier.padding(end = 6.dp).clip(RoundedCornerShape(10.dp)).background(SURFACE2).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, tint = MUTED, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(a.name, color = MUTED, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
            }
        }
    }
}

@Composable
private fun Composer(vm: SAViewModel) {
    val context = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.addAttachment(it, queryFileName(context, it)) }
    }
    Column(Modifier.fillMaxWidth().background(SURFACE)) {
        if (vm.errorText.isNotBlank() && vm.screen == Screen.CHAT) {
            Text(vm.errorText, color = RED, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { pickFile.launch("*/*") }) { Icon(Icons.Default.Add, contentDescription = "Attach", tint = TXT) }
            OutlinedTextField(
                value = vm.input,
                onValueChange = { vm.input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message SA\u2026", color = MUTED) },
                textStyle = TextStyle(color = TXT, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BORDER, unfocusedBorderColor = BORDER,
                    focusedContainerColor = SURFACE2, unfocusedContainerColor = SURFACE2
                ),
                shape = RoundedCornerShape(18.dp),
                maxLines = 5
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { if (vm.isWorking) vm.stopGeneration() else vm.send() },
                enabled = vm.isWorking || vm.input.isNotBlank()
            ) {
                Icon(
                    if (vm.isWorking) Icons.Default.Close else Icons.Default.ArrowUpward,
                    contentDescription = if (vm.isWorking) "Stop" else "Send",
                    tint = if (vm.isWorking) RED else CYAN
                )
            }
        }
    }
}

private fun queryFileName(context: Context, uri: Uri): String {
    var name = "attachment"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
    }
    return name
}
