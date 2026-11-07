package com.example.onwin.ui.screens.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.onwin.domain.repository.WelcomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal enum class WelcomeState {
    Ready,
    Saving,
    Completed,
    SaveFailed,
}

@HiltViewModel
internal class WelcomeViewModel @Inject constructor(
    private val preferences: WelcomeRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WelcomeState.Ready)
    val state = _state.asStateFlow()

    private val _navigateToRecordings = Channel<Unit>(Channel.BUFFERED)
    val navigateToRecordings = _navigateToRecordings.receiveAsFlow()

    fun onStart() {
        if (state.value == WelcomeState.Saving || state.value == WelcomeState.Completed) return

        _state.value = WelcomeState.Saving
        viewModelScope.launch {
            try {
                preferences.complete()
                _state.value = WelcomeState.Completed
                _navigateToRecordings.send(Unit)
            } catch (_: IOException) {
                _state.value = WelcomeState.SaveFailed
            }
        }
    }
}
