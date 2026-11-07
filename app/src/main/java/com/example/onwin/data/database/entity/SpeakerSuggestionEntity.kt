package com.example.onwin.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Имя спикера, которое предложила LLM по тексту диалога.
 *
 * Это не имя пользователя ([SpeakerNameEntity]): предложение только показывается
 * в UI, пока пользователь не нажмёт «применить». Хранится отдельно, чтобы
 * повторный анализ не затирал имена, данные вручную.
 *
 * @property sessionId сессия; при её удалении предложения удаляются каскадно.
 * @property speaker номер спикера из `:speech`, с 0.
 * @property name предложенное имя.
 */
@Entity(
    tableName = "speaker_suggestions",
    primaryKeys = ["sessionId", "speaker"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SpeakerSuggestionEntity(
    val sessionId: String,
    val speaker: Int,
    val name: String,
)
