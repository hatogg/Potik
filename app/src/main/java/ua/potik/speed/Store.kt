package ua.potik.speed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Налаштування та історія вимірювань (лише на телефоні). */
class Store(context: Context) {
    private val sp = context.getSharedPreferences("potik", Context.MODE_PRIVATE)

    var units: String
        get() = sp.getString("units", "mbps")!!
        set(v) = sp.edit().putString("units", v).apply()

    var theme: String
        get() = sp.getString("theme", "system")!!
        set(v) = sp.edit().putString("theme", v).apply()

    /** "full" або "eco" — режим на мобільному інтернеті. */
    var mobileMode: String
        get() = sp.getString("mobileMode", "full")!!
        set(v) = sp.edit().putString("mobileMode", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    fun prefsJson(): JSONObject = JSONObject()
        .put("units", units).put("theme", theme).put("mobileMode", mobileMode).put("haptics", haptics)

    fun history(): JSONArray = runCatching { JSONArray(sp.getString("history", "[]")) }.getOrElse { JSONArray() }

    fun add(result: JSONObject) {
        val old = history()
        val out = JSONArray().put(result)
        for (i in 0 until minOf(old.length(), MAX - 1)) out.put(old.get(i))
        sp.edit().putString("history", out.toString()).apply()
    }

    fun delete(id: String) {
        val old = history()
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("id") != id) out.put(o)
        }
        sp.edit().putString("history", out.toString()).apply()
    }

    fun clear() = sp.edit().putString("history", "[]").apply()

    fun last(): JSONObject? = history().optJSONObject(0)

    companion object {
        private const val MAX = 300
    }
}
