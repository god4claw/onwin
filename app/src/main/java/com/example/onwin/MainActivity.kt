package com.example.onwin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.core.util.Consumer
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.onwin.ui.navigation.LaunchViewModel
import com.example.onwin.ui.navigation.NavGraph
import com.example.onwin.ui.theme.OnWinTheme
import dagger.hilt.android.AndroidEntryPoint

/** Тап по уведомлению обработки открывает запись из [EXTRA_SESSION_ID]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openedFrom = if (savedInstanceState == null) intent.sessionIdExtra() else null
        setContent {
            OnWinTheme {
                var pendingSessionId by rememberSaveable { mutableStateOf(openedFrom) }
                DisposableEffect(Unit) {
                    val listener = Consumer<Intent> { newIntent ->
                        newIntent.sessionIdExtra()?.let { pendingSessionId = it }
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                val launchViewModel: LaunchViewModel = hiltViewModel()
                val showWelcome by launchViewModel.showWelcome.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize().background(colorResource(R.color.onwin_window_background))) {
                    showWelcome?.let { welcome ->
                        // После восстановления процесса сохранённый back stack не должен
                        // вернуть приветствие, если пользователь уже нажал «Начать».
                        key(welcome) {
                            NavGraph(
                                showWelcome = welcome,
                                pendingSessionId = pendingSessionId,
                                onPendingSessionOpened = { pendingSessionId = null },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as OnWinApp).onActivityVisibilityChanged(visible = true)
    }

    override fun onStop() {
        (application as OnWinApp).onActivityVisibilityChanged(visible = false)
        super.onStop()
    }

    companion object {
        /** id сессии, которую открыть (из уведомления). */
        const val EXTRA_SESSION_ID = "com.example.onwin.extra.OPEN_SESSION_ID"
    }
}

private fun Intent.sessionIdExtra(): String? = getStringExtra(MainActivity.EXTRA_SESSION_ID)
