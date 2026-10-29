package com.hereliesaz.cleanunderwear.util

import java.io.File

/** The autocomplete history files AzNavRail's text box writes (`az_text_box_history_<context>.txt`). */
object TextBoxHistory {
    private const val PREFIX = "az_text_box_history_"

    /** Deletes every history file in [filesDir]; returns how many were removed. */
    fun purge(filesDir: File): Int =
        filesDir.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }
            ?.count { it.delete() }
            ?: 0
}
