package com.sa.assistant

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Theme tokens. `internal` (not `private`) on purpose: SAViewModel.kt,
// ChatScreen.kt, WorkCards.kt, EditorScreen.kt, ProjectScreens.kt and
// Shared.kt all live in this same package and reference these directly.
// Kotlin top-level `private` is file-scoped, not package-scoped, so once a
// single file was split into several, `private` here would stop compiling
// anywhere else it's used.
// ---------------------------------------------------------------------------
internal val BG = Color(0xFF050914)
internal val SURFACE = Color(0xFF091221)
internal val SURFACE2 = Color(0xFF0D182A)
internal val SURFACE3 = Color(0xFF102039)
internal val BORDER = Color(0xFF14518A)
internal val BLUE = Color(0xFF168BFF)
internal val CYAN = Color(0xFF2EDBFF)
internal val MUTED = Color(0xFF7894AF)
internal val TXT = Color(0xFFEAF6FF)
internal val GREEN = Color(0xFF2BD37E)
internal val RED = Color(0xFFFF5872)
internal val AMBER = Color(0xFFFFC857)

// CODE_STREAM was a separate full-screen destination that only showed
// whatever `code` happened to hold, with no automatic link to what was
// actually streaming. It is gone; CODE now covers both — see EditorScreen.kt.
internal enum class Screen { CHAT, CODE, FILES, BUILD, ERROR, ATTACHMENTS, PAUSE, EDIT, ZIP, NEW_PROJECT, RESUME, SETTINGS }
internal enum class StepState { WAITING, RUNNING, SUCCESS, FAILED, PAUSED }

// STREAMING/DONE/FAILED only. There is no separate "queued" state: a file
// card is not created until its opening <sa_action> tag has actually been
// seen in the stream, so nothing is ever shown before there is real, live
// model output behind it.
internal enum class CardState { STREAMING, DONE, FAILED }

data class ProjectFile(val path: String, val content: String)
data class ChatMessage(val id: Long, val user: Boolean, val text: String, val streaming: Boolean = false)
data class Attachment(val uri: String, val name: String, val kind: String = "file")
internal data class WorkLine(val id: Long, val title: String, val detail: String = "", val state: StepState = StepState.WAITING, val expandable: Boolean = true)

// One card per <sa_action> block. `seq` orders it against WorkLines on the
// same timeline (see TimelineItem below); `id` is a stable per-turn key
// ("t<ordinal>") used to find-and-update the same card across stream ticks
// instead of appending a new one every time more content arrives.
internal data class FileCard(
    val id: String,
    val seq: Long,
    val kind: String, // "create" | "update" | "delete"
    val path: String,
    val liveContent: String = "",
    val summary: String = "",
    val state: CardState = CardState.STREAMING
)

// WorkLine ("Read workspace", "Wiring", "Verify"...) and FileCard both carry
// their own timestamp-derived ordering key. The chat screen renders them as
// one merged, chronologically-sorted list instead of two separate blocks, so
// a file card appears exactly where it really happened relative to the
// generic steps around it.
internal sealed interface TimelineItem {
    val seq: Long
    data class Line(val line: WorkLine) : TimelineItem { override val seq get() = line.id }
    data class Card(val card: FileCard) : TimelineItem { override val seq get() = card.seq }
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
