package com.sa.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// CODE_STREAM used to be a fully separate destination showing whatever `code` happened to
// hold, with no automatic connection to what was actually streaming, and reaching it required
// a manual "Live" tap. This single screen now decides for itself, every time it is shown,
// whether the currently open file has an active streaming card and renders accordingly — so
// tapping a file card while it's writing and opening a finished file from the Files list both
// land here and both show the right thing without a separate mode to reach.
@Composable
internal fun Editor(vm: SAViewModel) {
    val liveCard = vm.fileCards.firstOrNull { it.path == vm.selectedFile && it.state == CardState.STREAMING }
    Column(Modifier.fillMaxSize().background(BG)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.screen = Screen.FILES }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TXT) }
            Text(
                vm.selectedFile.substringAfterLast('/').ifBlank { "No file selected" },
                color = TXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (liveCard != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(10.dp), color = CYAN, strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("Live", color = CYAN, fontSize = 11.sp)
                }
            } else if (!vm.isWorking) {
                TextButton(onClick = { vm.saveFile() }) { Text("Save", color = CYAN, fontSize = 12.sp) }
            }
        }
        if (liveCard != null) {
            // Read-only while streaming: this is showing the model's own in-progress output,
            // not the saved file, so letting someone type into it here would be misleading.
            val scrollState = rememberScrollState()
            LaunchedEffect(liveCard.liveContent) { scrollState.scrollTo(scrollState.maxValue) }
            Text(
                liveCard.liveContent,
                color = TXT, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp)
            )
        } else {
            OutlinedTextField(
                value = vm.code,
                onValueChange = { vm.code = it },
                modifier = Modifier.fillMaxSize().padding(8.dp),
                textStyle = TextStyle(color = TXT, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BORDER, unfocusedBorderColor = BORDER,
                    focusedContainerColor = SURFACE, unfocusedContainerColor = SURFACE
                )
            )
        }
    }
}
