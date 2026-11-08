package com.example.onwin.data.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.example.onwin.data.database.entity.SegmentEntity
import com.example.onwin.data.database.entity.SessionEntity
import com.example.onwin.data.database.entity.SessionNotesEntity
import com.example.onwin.data.database.entity.SessionProtocolEntity
import com.example.onwin.data.database.entity.SpeakerNameEntity
import com.example.onwin.data.database.entity.SpeakerSuggestionEntity

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
 * Сессия со всеми сегментами, именами спикеров и заметками LLM.
 *
 * @property session сама сессия.
 * @property segments сегменты по возрастанию `index`.
 * @property speakerNames имена, которые пользователь дал спикерам.
 * @property notes резюме от LLM; `null`, пока анализа не было.
 * @property suggestions имена, которые предложила LLM, но пользователь ещё не применил.
 */
data class SessionDetailRow(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val segments: List<SegmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val speakerNames: List<SpeakerNameEntity>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val notes: SessionNotesEntity?,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val suggestions: List<SpeakerSuggestionEntity>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val protocol: SessionProtocolEntity?,
)
