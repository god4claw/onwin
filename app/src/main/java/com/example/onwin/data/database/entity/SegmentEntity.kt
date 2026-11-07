package com.example.onwin.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Реплика сессии. Первичный ключ `(sessionId, index)` заодно служит индексом для выборки по сессии.
 *
 * @property sessionId сессия; при её удалении сегменты удаляются каскадно.
 * @property index порядковый номер в сессии, с 0, по времени.
 * @property speaker номер спикера из `:speech`, с 0.
 * @property startMs начало реплики от начала записи.
 * @property endMs конец реплики.
 * @property text текст с пунктуацией.
 */
@Entity(
    tableName = "segments",
    primaryKeys = ["sessionId", "index"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SegmentEntity(
    val sessionId: String,
    val index: Int,
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
