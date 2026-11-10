package com.hiresplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hiresplayer.youtube.YtmCompanion
import com.hiresplayer.youtube.YtmUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class YtmViewModel(app: Application) : AndroidViewModel(app) {
    private val companion = YtmCompanion(app)
    val state: StateFlow<YtmUiState> = companion.state

    init {
        companion.refreshPermission()
        viewModelScope.launch {
            while (isActive) {
                if (companion.state.value.track?.playing == true) {
                    companion.tickPosition()
                }
                delay(500)
            }
        }
    }

    fun onVisible() = companion.refreshPermission()
    fun toggle() = companion.toggle()
    fun next() = companion.next()
    fun previous() = companion.previous()
    fun seekTo(positionMs: Long) = companion.seekTo(positionMs)
    fun openApp() = companion.openApp()
    fun search(query: String) = companion.search(query)
    fun openSettings() = companion.openListenerSettings()
    fun openAppInfo() = companion.openAppInfo()

    override fun onCleared() {
        companion.release()
        super.onCleared()
    }
}
