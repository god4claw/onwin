package com.example.onwin.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Опциональный протокол заседания по сессии.
 *
 * Отдельная таблица, а не поля в `session_notes`: протокол появляется позже,
 * его можно пересоздать/удалить, и он не должен утяжелять список записей.
 *
 * @property sessionId сессия; удаляется каскадно вместе с ней.
 * @property payload JSON протокола: участники, summary, решения, поручения, язык.
 * @property createdAt когда протокол сохранён, мс с эпохи.
 */
@Entity(
    tableName = "session_protocols",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionProtocolEntity(
    @PrimaryKey val sessionId: String,
    val payload: String,
    val createdAt: Long,
)
