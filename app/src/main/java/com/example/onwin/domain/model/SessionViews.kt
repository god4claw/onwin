package com.example.onwin.domain.model

data class SessionListItem(
    val session: Session,
    val segmentCount: Int,
    val speakerCount: Int,
)

data class SessionDetail(
    val session: Session,
    val segments: List<TranscriptSegment>,
    val speakerNames: List<SpeakerName>,
)

data class TranscriptSegment(
    val index: Int,
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class SpeakerName(val speaker: Int, val name: String)
