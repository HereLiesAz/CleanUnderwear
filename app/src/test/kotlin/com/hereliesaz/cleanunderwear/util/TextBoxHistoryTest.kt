package com.hereliesaz.cleanunderwear.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextBoxHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun removesTheHistoryFilesAndNothingElse() {
        val dir = folder.root
        val history = listOf("az_text_box_history_default.txt", "az_text_box_history_registry_search.txt").map { File(dir, it).apply { writeText("Jane Doe") } }
        val other = File(dir, "crash.txt").apply { writeText("keep") }

        assertEquals(2, TextBoxHistory.purge(dir))

        assertTrue(history.none { it.exists() })
        assertTrue(other.exists())
    }

    @Test
    fun anEmptyOrMissingFolderIsFine() {
        assertEquals(0, TextBoxHistory.purge(folder.root))
        assertFalse(TextBoxHistory.purge(java.io.File(folder.root, "nope")) > 0)
    }
}
