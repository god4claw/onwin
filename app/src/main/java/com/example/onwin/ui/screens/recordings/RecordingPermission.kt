package com.example.onwin.ui.screens.recordings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.onwin.R

/** Permission prompts are tied to an explicit recording action. */
@Composable
internal fun rememberRequestRecording(onRecord: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()
    var rationale by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val start = {
        if (activity is LifecycleOwner && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            !activity.isFinishing && !activity.isDestroyed) onRecord()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start()
        else if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
            settings = true
        }
    }
    if (rationale || settings) AlertDialog(
        onDismissRequest = { rationale = false; settings = false },
        title = { Text(stringResource(R.string.microphone_permission_title)) },
        text = { Text(stringResource(if (settings) R.string.microphone_permission_settings else R.string.microphone_permission_rationale)) },
        confirmButton = {
            TextButton(onClick = {
                if (settings) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                else microphone.launch(Manifest.permission.RECORD_AUDIO)
                rationale = false
                settings = false
            }) { Text(stringResource(if (settings) R.string.action_settings else R.string.action_continue)) }
        },
        dismissButton = { TextButton(onClick = { rationale = false; settings = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    return {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> start()
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO) -> rationale = true
            else -> microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun NotificationPermissionAction(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState == Lifecycle.State.RESUMED
    val prefs = remember(context) { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var requesting by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requesting = false
    }
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    TextButton(
        modifier = modifier,
        enabled = !requesting && resumed,
        onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                (!prefs.getBoolean("notifications_asked", false) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))
            ) {
                requesting = true
                prefs.edit { putBoolean("notifications_asked", true) }
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.notification_enable))
            Text(
                text = stringResource(R.string.notification_enable_description),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
