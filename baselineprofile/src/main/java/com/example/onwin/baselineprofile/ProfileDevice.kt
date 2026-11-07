package com.example.onwin.baselineprofile

import android.content.Intent
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val PROFILE_PACKAGE = "com.example.onwin.profile"
private const val MAIN_ACTIVITY = "com.example.onwin.MainActivity"
internal const val UI_TIMEOUT_MS = 10_000L
internal const val WELCOME_BUTTON = "Начать"
internal const val IMPORT_BUTTON = "Импортировать с устройства"

internal fun profileLaunchIntent(): Intent = Intent(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_LAUNCHER)
    .setClassName(PROFILE_PACKAGE, MAIN_ACTIVITY)

internal fun UiDevice.openRecordings() {
    val launch = executeShellCommand("am start -W -n $PROFILE_PACKAGE/$MAIN_ACTIVITY")
    val welcome = By.text(WELCOME_BUTTON).pkg(PROFILE_PACKAGE)
    val recordings = By.text(IMPORT_BUTTON).pkg(PROFILE_PACKAGE)
    val eitherScreen = By.text(Pattern.compile("$WELCOME_BUTTON|$IMPORT_BUTTON")).pkg(PROFILE_PACKAGE)
    check(wait(Until.hasObject(eitherScreen), UI_TIMEOUT_MS)) {
        "Neither the welcome screen nor the recordings screen is available in $PROFILE_PACKAGE. Launch: $launch"
    }
    if (hasObject(welcome)) clickWhenAvailable(welcome)
    check(wait(Until.hasObject(recordings), UI_TIMEOUT_MS)) { "The recordings screen did not open." }
}

internal fun UiDevice.awaitText(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
    check(wait(Until.hasObject(By.text(text)), timeoutMs)) { "Could not find \"$text\"." }
}

internal fun UiDevice.clickWhenAvailable(selector: BySelector) = actWhenAvailable(selector) { click() }

internal fun UiDevice.actWhenAvailable(selector: BySelector, action: UiObject2.() -> Unit) {
    val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
    while (SystemClock.uptimeMillis() < deadline) {
        val remaining = deadline - SystemClock.uptimeMillis()
        val node = wait(Until.findObject(selector), remaining.coerceAtLeast(1L)) ?: break
        try {
            node.action()
            return
        } catch (_: StaleObjectException) {
            // Documents UI and Compose can replace the node during screen transitions.
        }
    }
    error("Could not interact with $selector.")
}
