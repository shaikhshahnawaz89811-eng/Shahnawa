package com.sa.assistant

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun SAApp() {
    val vm: SAViewModel = viewModel()
    MaterialTheme(colorScheme = darkColorScheme(background = BG, surface = SURFACE, primary = BLUE)) {
        Surface(Modifier.fillMaxSize(), color = BG) {
            Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
                Header(vm)
                Box(Modifier.weight(1f)) {
                    when (vm.screen) {
                        Screen.CHAT -> Chat(vm)
                        Screen.CODE -> Editor(vm)
                        Screen.FILES -> Files(vm)
                        Screen.BUILD -> Build(vm)
                        Screen.ERROR -> ErrorScreen(vm)
                        Screen.ATTACHMENTS -> AttachmentsScreen(vm)
                        Screen.PAUSE -> PauseScreen(vm)
                        Screen.EDIT -> EditScreen(vm)
                        Screen.ZIP -> ZipScreen(vm)
                        Screen.NEW_PROJECT -> NewProjectScreen(vm)
                        Screen.RESUME -> ResumeScreen(vm)
                        Screen.SETTINGS -> SettingsScreen(vm)
                    }
                }
                Bottom(vm)
            }
        }
    }
}

// The existing "SA" badge doubles as the status orb: no new visual element, it just
// gains a pulsing ring while vm.isWorking, and the subtitle switches to vm.statusLabel —
// the same state the timeline renders from, so this never shows a "working" cue that
// isn't backed by something actually happening.
@Composable
private fun Header(vm: SAViewModel) {
    Row(
        Modifier.fillMaxWidth().background(SURFACE).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { vm.screen = Screen.FILES }) { Icon(Icons.Default.Menu, contentDescription = "Files", tint = TXT) }
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (vm.isWorking) {
                val transition = rememberInfiniteTransition(label = "sa-pulse")
                val scale by transition.animateFloat(
                    initialValue = 0.85f, targetValue = 1.7f,
                    animationSpec = infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing), RepeatMode.Restart),
                    label = "sa-pulse-scale"
                )
                val ringAlpha by transition.animateFloat(
                    initialValue = 0.7f, targetValue = 0f,
                    animationSpec = infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing), RepeatMode.Restart),
                    label = "sa-pulse-alpha"
                )
                Box(
                    Modifier
                        .size(34.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale, alpha = ringAlpha)
                        .border(1.5.dp, CYAN, CircleShape)
                )
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Brush.radialGradient(listOf(CYAN, BLUE, Color(0xFF071E3A)))),
                contentAlignment = Alignment.Center
            ) { Text("SA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(vm.projectName, color = TXT, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(vm.statusLabel, color = if (vm.isWorking) CYAN else MUTED, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { vm.screen = Screen.NEW_PROJECT }) { Icon(Icons.Default.Add, contentDescription = "New project", tint = TXT) }
        IconButton(onClick = { vm.screen = Screen.SETTINGS }) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TXT) }
    }
}

@Composable
private fun Bottom(vm: SAViewModel) {
    Row(Modifier.fillMaxWidth().background(SURFACE).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Action(Icons.Default.Chat, "Chat", vm.screen == Screen.CHAT) { vm.screen = Screen.CHAT }
        Action(Icons.Default.Code, "Files", vm.screen == Screen.FILES || vm.screen == Screen.CODE) { vm.screen = Screen.FILES }
        Action(Icons.Default.Build, "Build", vm.screen == Screen.BUILD) { vm.screen = Screen.BUILD }
        Action(Icons.Default.Settings, "Settings", vm.screen == Screen.SETTINGS) { vm.screen = Screen.SETTINGS }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = if (active) CYAN else MUTED, modifier = Modifier.size(20.dp))
        Text(label, color = if (active) CYAN else MUTED, fontSize = 10.sp)
    }
}
