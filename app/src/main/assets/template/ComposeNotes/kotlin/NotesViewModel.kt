package __PACKAGE_NAME__

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(app: Application) : AndroidViewModel(app) {

    private val store = NotesStore(app)

    val notes: StateFlow<List<String>> =
        store.notes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch { store.save(listOf(t) + notes.value) }
    }

    fun delete(index: Int) {
        viewModelScope.launch { store.save(notes.value.filterIndexed { i, _ -> i != index }) }
    }
}
