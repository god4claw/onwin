package com.example.onwin.ui.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.onwin.domain.repository.WelcomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
internal class LaunchViewModel @Inject constructor(
    preferences: WelcomeRepository,
) : ViewModel() {
    private val _showWelcome = MutableStateFlow<Boolean?>(null)
    val showWelcome = _showWelcome.asStateFlow()

    init {
        viewModelScope.launch {
            _showWelcome.value = try {
                !preferences.isCompleted()
            } catch (error: IOException) {
                Log.w("LaunchViewModel", "Could not read welcome preferences", error)
                true
            }
        }
    }
}
