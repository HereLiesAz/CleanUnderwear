package com.hereliesaz.cleanunderwear.network

import java.net.URLEncoder

/**
 * Slot-fills [Source] URL templates with concrete name parts.
 *
 * Throws [IllegalArgumentException] if a [SourceKind.QUERY_TEMPLATE] source is
 * built without a usable first or last name; the caller must skip the source
 * rather than emit an empty-slot URL.
 */
object SourceUrlBuilder {
    fun buildFetchUrl(source: Source, first: String, last: String): String {
        require(source.kind != SourceKind.QUERY_TEMPLATE || (first.isNotBlank() && last.isNotBlank())) {
            "QUERY_TEMPLATE source ${source.id} requires non-blank first and last name"
        }
        return fillSlots(source.urlTemplate, first, last)
    }

    fun buildEvidenceUrl(source: Source, first: String, last: String): String {
        require(source.kind != SourceKind.QUERY_TEMPLATE || (first.isNotBlank() && last.isNotBlank())) {
            "QUERY_TEMPLATE source ${source.id} requires non-blank first and last name"
        }
        return fillSlots(source.evidenceUrlTemplate, first, last)
    }

    fun buildFormFields(source: Source, first: String, last: String): Map<String, String> {
        if (source.formFields.isEmpty()) return emptyMap()
        return source.formFields.mapValues { (_, value) -> fillSlots(value, first, last, encode = false) }
    }

    /** Fills a raw body template; values are JSON-escaped when [json] is true. */
    fun buildBody(source: Source, first: String, last: String): String? {
        val template = source.bodyTemplate ?: return null
        val json = source.contentType?.contains("json", ignoreCase = true) == true
        fun esc(s: String) = if (json) s.replace("\\", "\\\\").replace("\"", "\\\"") else URLEncoder.encode(s, "UTF-8")
        return template.replace("{first}", esc(first)).replace("{last}", esc(last))
            .replace("{FIRST}", esc(first.uppercase())).replace("{LAST}", esc(last.uppercase()))
    }

    private fun fillSlots(template: String, first: String, last: String, encode: Boolean = true): String {
        val firstSlot = if (encode) URLEncoder.encode(first, "UTF-8") else first
        val lastSlot = if (encode) URLEncoder.encode(last, "UTF-8") else last
        // {FIRST}/{LAST}: uppercase, for searches that require it.
        val upperFirst = if (encode) URLEncoder.encode(first.uppercase(), "UTF-8") else first.uppercase()
        val upperLast = if (encode) URLEncoder.encode(last.uppercase(), "UTF-8") else last.uppercase()
        return template
            .replace("{first}", firstSlot)
            .replace("{last}", lastSlot)
            .replace("{FIRST}", upperFirst)
            .replace("{LAST}", upperLast)
    }
}
