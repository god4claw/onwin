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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    val afterMicrophone = {
        if (shouldAskNotifications(context)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else start()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicrophone()
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
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> afterMicrophone()
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

private fun shouldAskNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) return false
    val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    if (prefs.getBoolean("notifications_asked", false)) return false
    prefs.edit { putBoolean("notifications_asked", true) }
    return true
}
