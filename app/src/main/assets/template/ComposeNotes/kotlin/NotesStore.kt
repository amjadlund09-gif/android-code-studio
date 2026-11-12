package __PACKAGE_NAME__

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.dataStore by preferencesDataStore(name = "notes")

/** Notes are saved as a JSON array of strings in DataStore (survives app restarts). */
class NotesStore(private val context: Context) {

    private val key = stringPreferencesKey("notes_json")

    val notes: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[key] ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    suspend fun save(list: List<String>) {
        context.dataStore.edit { prefs ->
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            prefs[key] = arr.toString()
        }
    }
}
