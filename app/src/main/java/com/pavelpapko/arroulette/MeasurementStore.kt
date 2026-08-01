package com.pavelpapko.arroulette

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MeasurementRecord(
    val timestamp: Long,
    val meters: Float
)

class MeasurementStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): MutableList<MeasurementRecord> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                MeasurementRecord(
                    timestamp = item.getLong("timestamp"),
                    meters = item.getDouble("meters").toFloat()
                )
            }
        }.getOrElse { mutableListOf() }
    }

    fun add(record: MeasurementRecord) {
        val records = load()
        records.add(0, record)
        while (records.size > MAX_HISTORY_ITEMS) records.removeLast()
        save(records)
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    private fun save(records: List<MeasurementRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("timestamp", record.timestamp)
                    .put("meters", record.meters.toDouble())
            )
        }
        preferences.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "ar_roulette_preferences"
        private const val KEY_HISTORY = "measurement_history"
        private const val MAX_HISTORY_ITEMS = 100
    }
}
