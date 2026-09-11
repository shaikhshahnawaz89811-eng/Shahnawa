package com.sa.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterProjectTest {
    @Test fun starterProjectHasBuildableCoreFiles() {
        val files = starterFiles("SchoolManager")
        val paths = files.map { it.path }
        assertEquals(7, paths.size)
        assertTrue(paths.contains("app/src/main/java/com/sa/app/MainActivity.kt"))
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

    @Test fun starterIsDomainNeutral() {
        // Regression test: the starter used to always emit Note/NoteRepository/NoteViewModel
        // under com.sa.notes regardless of the requested project name, which fed a misleading
        // "notes app" context into every generation request. It must stay domain-neutral so a
        // request like "school management app" isn't answered with leftover notes-app code.
        val files = starterFiles("SchoolManager")
        assertTrue(files.none { it.path.contains("com/sa/notes") })
        assertTrue(files.none { it.content.contains("NoteRepository") || it.content.contains("NoteViewModel") })
    }

    @Test fun starterPathsAreRelativeAndSafe() {
        starterFiles("SchoolManager").forEach { file ->
            assertTrue(!file.path.startsWith("/"))
            assertTrue(!file.path.split('/').contains(".."))
        }
    }
}
