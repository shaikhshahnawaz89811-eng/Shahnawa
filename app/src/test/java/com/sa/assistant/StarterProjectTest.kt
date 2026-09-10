package com.sa.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterProjectTest {
    @Test fun starterProjectHasBuildableCoreFiles() {
        val files = starterFiles("NotesApp")
        val paths = files.map { it.path }
        assertEquals(10, paths.size)
        assertTrue(paths.contains("app/src/main/java/com/sa/notes/MainActivity.kt"))
        assertTrue(paths.contains("app/src/main/AndroidManifest.xml"))
        assertTrue(paths.contains("app/build.gradle.kts"))
        assertTrue(paths.contains("app/src/main/res/values/styles.xml"))
        assertTrue(paths.contains("settings.gradle.kts"))
    }

    @Test fun starterManifestUsesRequestedProjectName() {
        val manifest = starterFiles("MyNotes").first { it.path.endsWith("AndroidManifest.xml") }.content
        assertTrue(manifest.contains("android:label=\"MyNotes\""))
        assertTrue(manifest.contains("android:name=\".MainActivity\""))
    }

    @Test fun starterPathsAreRelativeAndSafe() {
        starterFiles("NotesApp").forEach { file ->
            assertTrue(!file.path.startsWith("/"))
            assertTrue(!file.path.split('/').contains(".."))
        }
    }
}
