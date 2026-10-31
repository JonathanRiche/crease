package app.crease.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("crease", Context.MODE_PRIVATE)

    fun load(state: State, widgets: MutableList<WidgetRef>) {
        val root = runCatching { JSONObject(prefs.getString(KEY, "{}")!!) }.getOrElse { JSONObject() }
        state.theme = root.optString("theme", state.theme)
        state.autoKeyboard = root.optBoolean("autoKeyboard", false)
        root.optJSONArray("pins")?.let { state.pins += it.strings() }
        root.optJSONArray("hidden")?.let { state.hidden += it.strings() }
        root.optJSONObject("aliases")?.let { o ->
            o.keys().forEach { k -> state.aliases[k] = o.getString(k) }
        }
        root.optJSONObject("folders")?.let { o ->
            // Keys in an ordered list: JSONObject ordering isn't guaranteed.
            val order = root.optJSONArray("folderOrder")?.strings() ?: o.keys().asSequence().toList()
            order.filter(o::has).forEach { k -> state.folders[k] = o.getJSONArray(k).strings().toMutableList() }
        }
        root.optJSONArray("widgets")?.let { a ->
            for (i in 0 until a.length()) {
                val w = a.getJSONObject(i)
                widgets += WidgetRef(w.getInt("id"), w.getString("label"), w.getInt("h"), w.getString("screen"))
            }
        }
    }

    fun save(state: State, widgets: List<WidgetRef>) {
        val root = JSONObject()
            .put("theme", state.theme)
            .put("autoKeyboard", state.autoKeyboard)
            .put("pins", JSONArray(state.pins))
            .put("hidden", JSONArray(state.hidden.toList()))
            .put("aliases", JSONObject(state.aliases as Map<*, *>))
            .put("folderOrder", JSONArray(state.folders.keys.toList()))
            .put("folders", JSONObject().also { o -> state.folders.forEach { (k, v) -> o.put(k, JSONArray(v)) } })
            .put("widgets", JSONArray().also { a ->
                widgets.forEach {
                    a.put(JSONObject().put("id", it.id).put("label", it.label).put("h", it.heightDp).put("screen", it.screen))
                }
            })
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    private fun JSONArray.strings() = List(length()) { getString(it) }

    private companion object { const val KEY = "state" }
}
