package com.example.onwin.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.ui.screens.recordings.DeleteRecordingDialog
import com.example.onwin.ui.screens.recordings.RenameRecordingDialog
import com.example.onwin.ui.theme.RecordingsTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun renameStartsWithoutFocusAndSavesLongName() {
        val submitted = mutableListOf<String>()
        val longTitle = "Название ".repeat(8).trimEnd()
        showRename(onSave = { submitted += it; true })

        compose.onNodeWithText("Переименовать запись").assertIsDisplayed()
        val field = compose.onNodeWithContentDescription("Название записи")
        field.assertIsNotFocused()
        compose.onNodeWithText(Recording.title).assertIsDisplayed()
        field.performTextReplacement(longTitle)
        compose.onNodeWithText("Сохранить").performClick()

        compose.onNodeWithText("Переименовать запись").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(longTitle), submitted) }
    }

    @Test
    fun blankNameDisablesSaveAndCancelDiscardsDraft() {
        showRename(onSave = { error("Blank name must not be saved") })

        compose.onNodeWithContentDescription("Название записи").performTextReplacement("   ")
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").performClick()

        compose.onNodeWithText("Переименовать запись").assertDoesNotExist()
    }

    @Test
    fun saveLocksActionsUntilItFinishesAndCanBeRetriedAfterFailure() {
        val result = CompletableDeferred<Boolean>()
        var attempts = 0
        showRename(onSave = {
            attempts += 1
            if (attempts == 1) result.await() else true
        })

        compose.onNodeWithText("Сохранить").performClick()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").assertIsNotEnabled()
        compose.runOnIdle { result.complete(false) }
        compose.onNodeWithText("Не удалось сохранить название.").assertIsDisplayed()

        compose.onNodeWithText("Сохранить").performClick()
        compose.onNodeWithText("Переименовать запись").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test
    fun deleteShowsSelectedNameAndOnlyConfirmDeletesItsId() {
        val visible = mutableStateOf(true)
        val deleted = mutableListOf<String>()
        compose.setContent {
            RecordingsTheme {
                if (visible.value) {
                    DeleteRecordingDialog(
                        session = Recording,
                        canDelete = true,
                        onDelete = { deleted += it },
                        onDismiss = { visible.value = false },
                    )
                }
            }
        }

        compose.onNodeWithText("Запись «${Recording.title}» будет удалена.").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Удалить запись?").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), deleted)
            visible.value = true
        }

        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Удалить запись?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(Recording.id), deleted) }
    }

    @Test
    fun deleteActionDisablesWhenRecordingBecomesBusy() {
        val canDelete = mutableStateOf(true)
        compose.setContent {
            RecordingsTheme {
                DeleteRecordingDialog(
                    session = Recording,
                    canDelete = canDelete.value,
                    onDelete = { error("Busy recording must not be deleted") },
                    onDismiss = {},
                )
            }
        }
        compose.runOnIdle { canDelete.value = false }
        compose.onNodeWithText("Удалить").assertIsNotEnabled()
    }

    private fun showRename(onSave: suspend (String) -> Boolean) {
        val visible = mutableStateOf(true)
        compose.setContent {
            RecordingsTheme {
                if (visible.value) {
                    RenameRecordingDialog(Recording, onSave, onDismiss = { visible.value = false })
                }
            }
        }
    }

    private companion object {
        val Recording = Session(
            id = "second-recording",
            title = "Вторая запись",
            source = SessionSource.MIC,
            originalName = "",
            sourceUri = null,
            createdAt = 1,
            updatedAt = 1,
            status = SessionStatus.DONE,
        )
    }
}
