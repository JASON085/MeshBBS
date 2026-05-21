package com.meshtastic.bbs.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object FavoriteNodesStore {

    private const val PREFS = "mesh_bbs_prefs"
    private const val KEY   = "favorite_servers"
    private const val MAX   = 10

    fun load(context: Context): List<NodeEntry> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                arr.getJSONObject(it).run {
                    NodeEntry(optString("nodeId"), optString("name"), optLong("lastUsed"))
                }
            }.sortedByDescending { it.lastUsed }
        } catch (_: Exception) { emptyList() }
    }

    fun addOrUpdate(context: Context, entry: NodeEntry) {
        val list = load(context).toMutableList()
        list.removeAll { it.nodeId == entry.nodeId }
        list.add(0, entry.copy(lastUsed = System.currentTimeMillis()))
        save(context, list.take(MAX))
    }

    fun remove(context: Context, nodeId: String) {
        save(context, load(context).filter { it.nodeId != nodeId })
    }

    private fun save(context: Context, entries: List<NodeEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("nodeId",   e.nodeId)
                put("name",     e.name)
                put("lastUsed", e.lastUsed)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
