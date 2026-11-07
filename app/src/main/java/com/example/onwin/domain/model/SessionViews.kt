package com.example.onwin.domain.model

/** Строка списка сессий: сессия и сводные счётчики. */
data class SessionListItem(
    val session: Session,
    val segmentCount: Int,
    val speakerCount: Int
)

/** Полная карточка сессии: сегменты по порядку и имена спикеров. */
data class SessionDetail(
    val session: Session,
    val segments: List<TranscriptSegment>,
    val speakerNames: List<SpeakerName>
)

/** Один сегмент расшифровки с таймкодами. */
data class TranscriptSegment(
    val index: Int,
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/** Имя спикера, заданное пользователем. */
data class SpeakerName(
    val speaker: Int,
    val name: String
)
