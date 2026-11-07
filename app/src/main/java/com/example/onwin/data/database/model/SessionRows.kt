package com.example.onwin.data.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.example.onwin.data.database.entity.SegmentEntity
import com.example.onwin.data.database.entity.SessionEntity
import com.example.onwin.data.database.entity.SpeakerNameEntity

/**
 * Строка списка сессий. Счётчики считает SQL, сегменты в память не грузятся.
 *
 * @property session сама сессия.
 * @property segmentCount сколько сегментов сохранено.
 * @property speakerCount сколько разных спикеров в сегментах.
 */
data class SessionListRow(
    @Embedded val session: SessionEntity,
    val segmentCount: Int,
    val speakerCount: Int,
)

/**
 * Сессия со всеми сегментами и именами спикеров.
 *
 * @property session сама сессия.
 * @property segments сегменты по возрастанию `index`.
 * @property speakerNames имена, которые пользователь дал спикерам.
 */
data class SessionDetailRow(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val segments: List<SegmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val speakerNames: List<SpeakerNameEntity>,
)