package com.hereliesaz.cleanunderwear.network

import android.content.Context
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identity / nickname helper. Provides:
 *  - Nickname expansion for first-name matching ([getNicknames])
 *  - Heuristic + ML name validation ([validatePersonName])
 *
 * URL discovery (the search-engine-backed "find the inmate roster page"
 * machinery) was deleted in the source-catalog-only redesign — sources now
 * come from the curated [SourceCatalog]. Identity enrichment for UNVERIFIED
 * contacts is user-initiated via the in-app browser flow, not run from the
 * auto pipeline.
 */
@Singleton
class OnDeviceResearchAgent @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        init {
            try {
                System.loadLibrary("tensorflowlite_flex_jni")
            } catch (e: Throwable) {
                // Native flex library loading handled by delegate if this fails
            }
        }
    }

    private var interpreter: Interpreter? = null
    private var nicknames: Map<String, List<String>> = emptyMap()

    init {
        try {
            val modelBuffer = loadModelFile("research_agent.tflite")
            val options = Interpreter.Options().apply {
                addDelegate(FlexDelegate())
            }
            interpreter = Interpreter(modelBuffer, options)
            DiagnosticLogger.log("Intelligence Agent: Model loaded successfully with Flex support.")
        } catch (e: Exception) {
            DiagnosticLogger.log(
                "Intelligence Agent Error: Failed to load LiteRT model. ${e.message}",
                DiagnosticLogger.LogEntry.LogLevel.ERROR
            )
            interpreter = null
        }
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
                "Intelligence Agent: Failed to load nicknames asset. ${e.message}",
                DiagnosticLogger.LogEntry.LogLevel.ERROR
            )
        }
    }

    fun getNicknames(name: String): List<String> {
        return nicknames[name.lowercase()] ?: emptyList()
    }

    /**
     * Heuristic + ML check that [text] reads like a person name. Used by the
     * harvesters when classifying contact fields, not by the scrape loop.
     *
     * Logging note: a single registry sweep runs this hundreds of times, and
     * the in-app log buffer is only 500 entries — per-rejection logging here
     * used to flush every other diagnostic off the operator's screen. Callers
     * that want a per-run trail should accumulate counts and emit a summary
     * themselves.
     */
    fun validatePersonName(text: String): Boolean {
        if (text.isBlank() || text == "Unnamed Entity") return false

        val hasSpace = text.trim().contains(" ")
        val isAlphaish = text.all { it.isLetter() || it == '-' || it == '\'' || it == ' ' }
        val hasNoDigits = text.none { it.isDigit() }
        val looksLikeName = (hasSpace || (text.length > 1 && isAlphaish)) && hasNoDigits

        val score = scoreWithModel(text)

        val serviceKeywords = listOf(
            "customer", "service", "support", "help", "bank",
            "office", "pizza", "taxi", "delivery", "store"
        )
        val isService = serviceKeywords.any { text.contains(it, ignoreCase = true) }

        return (score > 0.3f || looksLikeName) && !isService
    }

    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(fileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    private fun scoreWithModel(text: String): Float {
        val currentInterpreter = interpreter ?: return 1.0f
        val input = arrayOf(text)
        val output = Array(1) { FloatArray(1) }
        return try {
            currentInterpreter.run(input, output)
            output[0][0]
        } catch (e: Exception) {
            DiagnosticLogger.log(
                "Inference Error: ${e.message}",
                DiagnosticLogger.LogEntry.LogLevel.ERROR
            )
            0.5f
        }
    }
}
