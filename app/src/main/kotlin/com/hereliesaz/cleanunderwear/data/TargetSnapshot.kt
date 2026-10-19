package com.hereliesaz.cleanunderwear.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * JSON snapshots of [Target] rows for the merge ledger, and the field-level diff that lets a merge
 * be undone without trampling edits made since.
 *
 * Fields are keyed by Kotlin property name; R8 keeps [Target]'s members (see proguard-rules.pro),
 * so stored snapshots survive upgrades. A column added to [Target] later is simply absent from old
 * snapshots and falls back to whatever Gson leaves in it.
 */
object TargetSnapshot {
    private val gson = Gson()

    fun toJson(target: Target): String = gson.toJson(target)

    fun fromJson(json: String): Target = gson.fromJson(json, Target::class.java)

    /** Every field on which [after] differs from [before], as `{field: {before, after}}`. */
    fun changes(before: Target, after: Target): String {
        val b = gson.toJsonTree(before).asJsonObject
        val a = gson.toJsonTree(after).asJsonObject
        val out = JsonObject()
        for (key in b.keySet() + a.keySet()) {
            val beforeValue = b.get(key) ?: JsonNull.INSTANCE
            val afterValue = a.get(key) ?: JsonNull.INSTANCE
            if (beforeValue != afterValue) {
                out.add(key, JsonObject().apply {
                    add("before", beforeValue)
                    add("after", afterValue)
                })
            }
        }
        return gson.toJson(out)
    }

    /**
     * Put back what a merge overwrote. A field is restored only if it still holds the value the
     * merge gave it; anything the user or a later scrape changed since is left alone.
     */
    fun revert(current: Target, changesJson: String): Target {
        val cur = gson.toJsonTree(current).asJsonObject
        val changes = JsonParser.parseString(changesJson).asJsonObject
        for ((key, change) in changes.entrySet()) {
            val obj = change.asJsonObject
            val after: JsonElement = obj.get("after") ?: JsonNull.INSTANCE
            val now: JsonElement = cur.get(key) ?: JsonNull.INSTANCE
            if (now == after) cur.add(key, obj.get("before") ?: JsonNull.INSTANCE)
        }
        return gson.fromJson(cur, Target::class.java)
    }
}
