package com.example.onwin.data.repository

import com.example.onwin.data.database.dao.SessionDao
import com.example.onwin.data.database.entity.SegmentEntity
import com.example.onwin.data.database.entity.SessionEntity
import com.example.onwin.data.database.entity.SpeakerNameEntity
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionListItem
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.model.SpeakerName
import com.example.onwin.domain.model.TranscriptSegment
import com.example.onwin.domain.repository.SessionRepository
import com.example.speech.api.Segment
import java.io.File
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [SessionRepository] поверх Room.
 *
 * @param dao DAO сессий.
 * @param recordingsDir папка с WAV; та же, куда пишет `AudioConverter`.
 * @param clock источник `updatedAt`.
 */
class RoomSessionRepository(
    private val dao: SessionDao,
    private val recordingsDir: File,
    private val clock: Clock,
) : SessionRepository {
    override suspend fun rename(id: String, title: String): Boolean =
        dao.rename(id, title, clock.millis()) != 0

    override suspend fun setSpeakerName(id: String, speaker: Int, name: String?) {
        if (name == null) {
            dao.deleteSpeakerName(id, speaker)
        } else {
            dao.upsertSpeakerName(SpeakerNameEntity(id, speaker, name))
        }
    }

    override suspend fun updateSegmentText(id: String, index: Int, text: String): Boolean {
        val trimmedText = text.trim()
        return trimmedText.isNotEmpty() && dao.updateSegmentText(id, index, trimmedText) != 0
    }

    override fun observeSessions(): Flow<List<SessionListItem>> = dao.observeSessions().map { rows ->
        rows.map { row -> SessionListItem(row.session.toDomain(), row.segmentCount, row.speakerCount) }
    }

    override fun observeSession(id: String): Flow<SessionDetail?> = dao.observeSession(id).map { detail ->
        detail?.let { row ->
            SessionDetail(
                session = row.session.toDomain(),
                segments = row.segments.map { segment ->
                    TranscriptSegment(segment.index, segment.speaker, segment.startMs, segment.endMs, segment.text)
                },
                speakerNames = row.speakerNames.map { speaker -> SpeakerName(speaker.speaker, speaker.name) },
                summary = row.notes?.summary,
                suggestedNames = row.suggestions.map { suggestion -> SpeakerName(suggestion.speaker, suggestion.name) },
            )
        }
    }

    override suspend fun get(id: String): Session? = dao.getSession(id)?.toDomain()

    override suspend fun hasImportNeedingSource(sourceUri: String): Boolean = dao.hasImportNeedingSource(sourceUri)

    override suspend fun create(session: Session) = dao.insertSession(session.toEntity())

    override suspend fun updateStatus(id: String, status: SessionStatus, errorCode: ErrorCode?, errorMessage: String?) =
        dao.updateStatus(id, status, errorCode, errorMessage, clock.millis())

    override suspend fun setWav(id: String, wavFileName: String?, durationMs: Long?) =
        dao.setWav(id, wavFileName, durationMs, clock.millis())

    override suspend fun appendSegment(id: String, index: Int, segment: Segment) =
        dao.appendSegment(segment.toEntity(id, index))

    override suspend fun replaceSegments(id: String, segments: List<Segment>) =
        dao.replaceSegments(id, segments.mapIndexed { index, segment -> segment.toEntity(id, index) })

    override suspend fun clearSegments(id: String) = dao.clearSegments(id)

    override suspend fun saveAnalysis(id: String, summary: String, speakerNames: Map<Int, String>) =
        dao.saveAnalysis(id, summary, speakerNames, clock.millis())

    override suspend fun clearAnalysis(id: String) {
        dao.clearNotes(id)
        dao.clearSuggestions(id)
    }

    override suspend fun failUnfinished(errorCode: ErrorCode): Int = dao.failUnfinished(errorCode, clock.millis())

    override suspend fun deleteOrphanFiles(): Int {
        val referenced = dao.wavFileNames().toSet()
        return withContext(Dispatchers.IO) {
            recordingsDir.listFiles().orEmpty().count { file ->
                val orphan = file.name.endsWith(PART_SUFFIX) || (file.name.endsWith(WAV_SUFFIX) && file.name !in referenced)
                orphan && file.isFile && file.delete()
            }
        }
    }

    override suspend fun delete(id: String) {
        // Сначала файл: строка без файла переживёт повтор (WAV пересоздастся), файл без строки — утечка.
        dao.getSession(id)?.wavFileName?.let { wavFile(it).delete() }
        dao.delete(id)
    }

    override fun wavFile(fileName: String): File = File(recordingsDir, fileName)

    private fun SessionEntity.toDomain() = Session(
        id = id,
        title = title,
        source = source,
        originalName = originalName,
        sourceUri = sourceUri,
        createdAt = createdAt,
        updatedAt = updatedAt,
        wavFileName = wavFileName,
        durationMs = durationMs,
        status = status,
        errorCode = errorCode,
        errorMessage = errorMessage,
    )

    private fun Session.toEntity() = SessionEntity(
        id = id,
        title = title,
        source = source,
        originalName = originalName,
        sourceUri = sourceUri,
        createdAt = createdAt,
        updatedAt = updatedAt,
        wavFileName = wavFileName,
        durationMs = durationMs,
        status = status,
        errorCode = errorCode,
        errorMessage = errorMessage,
    )

    private fun Segment.toEntity(sessionId: String, index: Int) =
        SegmentEntity(sessionId, index, speaker, startMs, endMs, text)

    private companion object {
        const val WAV_SUFFIX = ".wav"

        /** `AudioConverter` пишет в `<uuid>.wav.part` и переименовывает только при успехе. */
        const val PART_SUFFIX = ".part"
    }
}
