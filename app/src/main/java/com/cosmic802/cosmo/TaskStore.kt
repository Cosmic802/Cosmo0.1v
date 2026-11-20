package com.cosmic802.cosmo

import org.json.JSONArray
import org.json.JSONObject

data class Task(val id: Long, val title: String, val done: Boolean)

object TaskStore {

    private fun arr(m: JSONObject): JSONArray =
        m.optJSONArray("tasks") ?: JSONArray().also { m.put("tasks", it) }

    private fun find(a: JSONArray, id: Long): Int {
        for (i in 0 until a.length()) {
            if (a.optJSONObject(i)?.optLong("id") == id) return i
        }
        return -1
    }

    fun list(): List<Task> {
        val a = CosmoBrain.loadMemory().optJSONArray("tasks") ?: return emptyList()
        val out = ArrayList<Task>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Task(o.optLong("id"), o.optString("title"), o.optBoolean("done")))
        }
        return out.sortedBy { it.done }
    }

    fun add(title: String): Boolean {
        val t = title.trim().take(200)
        if (t.isEmpty()) return false
        return CosmoBrain.updateMemory { m ->
            arr(m).put(
                JSONObject()
                    .put("id", System.currentTimeMillis())
                    .put("title", t)
                    .put("done", false)
            )
        }
    }

    fun toggle(id: Long): Boolean = CosmoBrain.updateMemory { m ->
        val a = arr(m)
        val i = find(a, id)
        if (i >= 0) {
            val o = a.getJSONObject(i)
            o.put("done", !o.optBoolean("done", false))
        }
    }

    fun remove(id: Long): Boolean = CosmoBrain.updateMemory { m ->
        val a = arr(m)
        val i = find(a, id)
        if (i >= 0) a.remove(i)
    }

    fun clearDone(): Boolean = CosmoBrain.updateMemory { m ->
        val a = arr(m)
        for (i in a.length() - 1 downTo 0) {
            if (a.optJSONObject(i)?.optBoolean("done", false) == true) a.remove(i)
        }
    }
}
