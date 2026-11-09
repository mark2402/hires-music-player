package com.hiresplayer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hiresplayer.library.LibraryRepository
import com.hiresplayer.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    val permissionGranted: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val folderCount: Int = 0,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = LibraryRepository(app)
    private val mutable = MutableStateFlow(
        LibraryUiState(permissionGranted = repository.hasAudioPermission()),
    )
    val state: StateFlow<LibraryUiState> = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = true, permissionGranted = repository.hasAudioPermission()) }
            val tracks = repository.load()
            mutable.update {
                it.copy(
                    loading = false,
                    tracks = tracks,
                    folderCount = repository.savedFolderCount(),
                    permissionGranted = repository.hasAudioPermission(),
                )
            }
        }
    }

    fun addFolder(uri: Uri) {
        repository.addTree(uri)
        refresh()
    }
}
