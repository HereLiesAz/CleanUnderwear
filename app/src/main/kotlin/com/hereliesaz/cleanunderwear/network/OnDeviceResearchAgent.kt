package com.hereliesaz.cleanunderwear.network

import android.content.Context
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Name helpers, on the device:
 *  - nickname variants for duplicate detection ([getNicknames]), from `nicknames.json`;
 *  - whether a contact's display name reads like a person ([validatePersonName]).
 *
 * The name check used to OR a TensorFlow Lite score into the rules below. That model was the one
 * the repo's training script builds (scripts/train_research_ai.py): a classifier of official
 * sheriff and government websites, not of names, so its score meant nothing for a name and it
 * could only wave through what the rules had rejected. It's gone, with the LiteRT runtime it
 * pulled into every build.
 */
@Singleton
class OnDeviceResearchAgent @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var nicknames: Map<String, List<String>> = emptyMap()

    init {
        loadAssets()
    }

    private fun loadAssets() {
        try {
            val gson = Gson()
            val nicknameJson = context.assets.open("nicknames.json")
                .bufferedReader().use { it.readText() }
            val nicknameType = object : TypeToken<Map<String, List<String>>>() {}.type
            nicknames = gson.fromJson(nicknameJson, nicknameType)
        } catch (e: Exception) {
            DiagnosticLogger.log(
                "Name helpers: failed to load nicknames. ${e.message}",
                DiagnosticLogger.LogEntry.LogLevel.ERROR
            )
        }
    }

    fun getNicknames(name: String): List<String> {
        return nicknames[name.lowercase()] ?: emptyList()
    }

    /**
     * Whether [text] reads like a person's name: no digits; two or more words, or one word of
     * letters longer than one character; and no business word ("Pizza", "Customer Service").
     * Used by the harvesters when classifying contacts, not by the records check.
     *
     * Logging note: a single registry sweep runs this hundreds of times, and the in-app log
     * buffer is only 500 entries, so it logs nothing; callers summarise.
     */
    fun validatePersonName(text: String): Boolean {
        if (text.isBlank() || text == "Unnamed Entity") return false

        val hasSpace = text.trim().contains(" ")
        val isAlphaish = text.all { it.isLetter() || it == '-' || it == '\'' || it == ' ' }
        val hasNoDigits = text.none { it.isDigit() }
        val looksLikeName = (hasSpace || (text.length > 1 && isAlphaish)) && hasNoDigits

        val serviceKeywords = listOf(
            "customer", "service", "support", "help", "bank",
            "office", "pizza", "taxi", "delivery", "store"
        )
        // Whole words only: "Jim Banks", "Ann Storey" and "Pat Officer" are people.
        val words = text.lowercase().split(Regex("[^a-z]+")).toSet()
        val isService = serviceKeywords.any { it in words }

        return looksLikeName && !isService
    }
}
