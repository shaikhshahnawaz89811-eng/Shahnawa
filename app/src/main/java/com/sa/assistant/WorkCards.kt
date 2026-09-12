package com.sa.assistant

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Timeline(vm: SAViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        vm.timeline.forEach { item ->
            when (item) {
                is TimelineItem.Line -> WorkLineRow(vm, item.line)
                is TimelineItem.Card -> FileCardRow(vm, item.card)
            }
        }
    }
}

@Composable
private fun WorkLineRow(vm: SAViewModel, line: WorkLine) {
    val expanded = vm.expandedWorkLineId == line.id
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = line.expandable) { vm.toggleWorkLine(line.id) }
            .animateContentSize()
            .padding(vertical = 4.dp, horizontal = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            when (line.state) {
                StepState.RUNNING -> CircularProgressIndicator(Modifier.size(12.dp), color = CYAN, strokeWidth = 2.dp)
                StepState.SUCCESS -> Icon(Icons.Default.Check, contentDescription = null, tint = GREEN, modifier = Modifier.size(14.dp))
                StepState.FAILED -> Icon(Icons.Default.Close, contentDescription = null, tint = RED, modifier = Modifier.size(14.dp))
                StepState.PAUSED -> Icon(Icons.Default.Pause, contentDescription = null, tint = AMBER, modifier = Modifier.size(14.dp))
                StepState.WAITING -> Box(Modifier.size(8.dp).clip(CircleShape).background(MUTED))
            }
            Spacer(Modifier.width(8.dp))
            Text(line.title, color = MUTED, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (line.state == StepState.RUNNING && line.title == "Generate") {
                TextButton(onClick = vm::pause, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Pause", color = AMBER, fontSize = 10.sp)
                }
            }
        }
        if (expanded && line.detail.isNotBlank()) {
            Text(line.detail, color = MUTED, fontSize = 10.sp, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
        }
    }
}

// The centerpiece of this redesign. A card exists the instant its opening <sa_action>
// tag streams in — while STREAMING it stays forced-open so the live content is always
// visible; once it finishes it collapses to one line automatically. Tapping a finished
// card re-expands it or opens the full file, exactly the same gesture either way.
@Composable
private fun FileCardRow(vm: SAViewModel, card: FileCard) {
    val expanded = card.state == CardState.STREAMING || vm.expandedFileCardId == card.id
    val borderColor = if (card.state == CardState.STREAMING) CYAN else BORDER
    val verb = when (card.kind) {
        "create" -> "Creating"
        "update" -> "Updating"
        "delete" -> "Deleting"
        else -> card.kind
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SURFACE)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = card.state != CardState.STREAMING) { vm.toggleFileCard(card.id) }
            .animateContentSize()
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            when (card.state) {
                CardState.STREAMING -> CircularProgressIndicator(Modifier.size(13.dp), color = CYAN, strokeWidth = 2.dp)
                CardState.DONE -> Icon(Icons.Default.Check, contentDescription = null, tint = GREEN, modifier = Modifier.size(15.dp))
                CardState.FAILED -> Icon(Icons.Default.Close, contentDescription = null, tint = RED, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                card.path.substringAfterLast('/'),
                color = TXT, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.weight(1f), maxLines = 1
            )
            Text(
                if (card.state == CardState.STREAMING) "$verb…" else card.summary,
                color = if (card.state == CardState.FAILED) RED else MUTED, fontSize = 10.sp
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            val scrollState = rememberScrollState()
            LaunchedEffect(card.liveContent) {
                if (card.state == CardState.STREAMING) scrollState.scrollTo(scrollState.maxValue)
            }
            val previewText = if (card.state == CardState.STREAMING) card.liveContent
                else vm.files.firstOrNull { it.path == card.path }?.content ?: card.liveContent
            if (previewText.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BG)
                        .verticalScroll(scrollState)
                        .padding(8.dp)
                ) {
                    Text(previewText, color = MUTED, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            if (card.state != CardState.STREAMING) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { vm.openFileCard(card) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Open in editor", color = CYAN, fontSize = 11.sp)
                }
            }
        }
    }
}
