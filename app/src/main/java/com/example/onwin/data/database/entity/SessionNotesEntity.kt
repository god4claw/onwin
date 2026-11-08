package com.example.onwin.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Заметки LLM по всей сессии: краткое резюме разговора.
 *
 * Отдельная таблица, а не поле в `sessions`: заметки появляются позже
 * расшифровки, их можно стереть при повторе, и они не должны утяжелять
 * список записей.
 *
 * @property sessionId сессия; при её удалении заметки удаляются каскадно.
 * @property summary резюме на 3–5 предложений.
 * @property createdAt когда заметки сохранены, мс с эпохи.
 */
@Entity(
    tableName = "session_notes",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionNotesEntity(
    @PrimaryKey val sessionId: String,
    val summary: String,
    val createdAt: Long,
)
