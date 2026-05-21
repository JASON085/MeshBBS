package com.meshtastic.bbs.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists all discovered mesh nodes across sessions for instant display on next launch. */
object NodeCacheStore {

    private const val PREFS = "mesh_bbs_prefs"
    private const val KEY   = "node_cache"
    private const val MAX   = 300

    fun load(context: Context): List<MeshNode> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                arr.getJSONObject(it).run {
                    MeshNode(
                        nodeId      = optString("nodeId"),
                        displayName = optString("displayName"),
                        shortName   = optString("shortName"),
                        lastSeen    = optLong("lastSeen", 0L),
                    )
                }
            }.filter { it.nodeId.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, nodes: Collection<MeshNode>) {
        val arr = JSONArray()
        nodes.take(MAX).forEach { n ->
            arr.put(JSONObject().apply {
                put("nodeId",      n.nodeId)
                put("displayName", n.displayName)
                put("shortName",   n.shortName)
                put("lastSeen",    n.lastSeen)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun merge(context: Context, incoming: Collection<MeshNode>) {
        val existing = load(context).associateBy { it.nodeId }.toMutableMap()
        incoming.forEach { n ->
            val prev = existing[n.nodeId]
            existing[n.nodeId] = when {
                prev == null -> n
                n.displayName != n.nodeId ->
                    n.copy(lastSeen = maxOf(n.lastSeen, prev.lastSeen))
                prev.displayName != prev.nodeId ->
                    prev.copy(lastSeen = maxOf(n.lastSeen, prev.lastSeen))
                else -> n
            }
        }
        save(context, existing.values)
    }
}
