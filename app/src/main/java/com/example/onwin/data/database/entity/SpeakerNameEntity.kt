package com.example.onwin.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Имя, которое пользователь дал спикеру в сессии.
 *
 * @property sessionId сессия; при её удалении имена удаляются каскадно.
 * @property speaker номер спикера из `:speech`, с 0.
 * @property name имя для показа.
 */
@Entity(
    tableName = "speaker_names",
    primaryKeys = ["sessionId", "speaker"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SpeakerNameEntity(
    val sessionId: String,
    val speaker: Int,
    val name: String
)
