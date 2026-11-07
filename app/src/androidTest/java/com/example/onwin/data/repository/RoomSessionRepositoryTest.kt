package com.example.onwin.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.database.entity.SegmentEntity
import com.example.onwin.data.database.entity.SessionEntity
import com.example.onwin.data.database.entity.SpeakerNameEntity
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionListItem
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.model.SpeakerName
import com.example.onwin.domain.model.TranscriptSegment
import com.example.speech.api.Segment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.util.UUID

/** [RoomSessionRepository] на настоящих Room и файлах: уборка папки записей. */
@RunWith(AndroidJUnit4::class)
class RoomSessionRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var recordings: File
    private lateinit var repository: RoomSessionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        recordings = File(context.filesDir, "repository-test-${UUID.randomUUID()}").apply { mkdirs() }
        repository = RoomSessionRepository(db.sessionDao(), recordings, Clock.systemUTC())
    }

    @After
    fun tearDown() {
        db.close()
        recordings.deleteRecursively()
    }

    @Test
    fun deleteOrphanFiles_removesUnreferencedWavsAndParts_keepsReferencedAndOtherFiles() = runBlocking {
        repository.create(session("with-wav"))
        repository.setWav("with-wav", "kept.wav", 1_000)
        repository.create(session("without-wav"))
        for (name in listOf("kept.wav", "orphan.wav", "orphan.wav.part", "kept.wav.part", "notes.txt")) {
            File(recordings, name).writeBytes(ByteArray(44))
        }

        val deleted = repository.deleteOrphanFiles()

        assertEquals(3, deleted)
        assertEquals(setOf("kept.wav", "notes.txt"), recordings.list().orEmpty().toSet())
    }

    @Test
    fun deleteOrphanFiles_withoutRecordingsDir_deletesNothing() = runBlocking {
        recordings.deleteRecursively()

        assertEquals(0, repository.deleteOrphanFiles())
    }

    @Test
    fun renameAndDelete_emitUpdatedRecordings() = runBlocking {
        val original = session("original")
        repository.create(original)
        val updates = repository.observeSessions().produceIn(this)
        try {
            assertEquals(original, withTimeout(5_000) { updates.receive() }.single().session)
            assertTrue(repository.rename(original.id, "Новое название"))
            val updated = withTimeout(5_000) { updates.receive() }.single().session
            assertEquals(original.copy(title = "Новое название", updatedAt = updated.updatedAt), updated)

            repository.delete(original.id)
            assertTrue(withTimeout(5_000) { updates.receive() }.isEmpty())
            assertFalse(repository.rename(original.id, "Удалённая запись"))
        } finally {
            updates.cancel()
        }
    }

    @Test
    fun changingSpeakerNameUpdatesOnlyThatSpeakerInThatRecording() = runBlocking {
        val original = session("original")
        val other = session("other")
        repository.create(original)
        repository.create(other)
        repository.setSpeakerName(original.id, 1, "Борис")
        repository.setSpeakerName(other.id, 0, "Вера")

        val updates = repository.observeSession(original.id).produceIn(this)
        try {
            assertEquals(listOf(SpeakerName(1, "Борис")), withTimeout(5_000) { updates.receive() }?.speakerNames)
            repository.setSpeakerName(original.id, 0, "Анна")
            assertEquals(
                setOf(SpeakerName(0, "Анна"), SpeakerName(1, "Борис")),
                withTimeout(5_000) { updates.receive() }?.speakerNames?.toSet(),
            )
            repository.setSpeakerName(original.id, 0, "Мария")
            assertEquals(
                "Мария",
                withTimeout(5_000) { updates.receive() }?.speakerNames?.single { it.speaker == 0 }?.name,
            )
            repository.setSpeakerName(original.id, 0, null)
            assertEquals(listOf(SpeakerName(1, "Борис")), withTimeout(5_000) { updates.receive() }?.speakerNames)
            assertEquals(listOf(SpeakerName(0, "Вера")), repository.observeSession(other.id).first()?.speakerNames)
        } finally {
            updates.cancel()
        }
    }

    @Test
    fun updatingTextPreservesSpeakersTimecodesAndOtherReplies() = runBlocking {
        val id = "edit"
        val untouched = TranscriptSegment(1, 3, 2_000, 3_000, "Без изменений")
        repository.create(session(id))
        repository.appendSegment(id, 0, Segment(0, 250, 1_500, "Исходный текст"))
        repository.appendSegment(id, 1, Segment(3, 2_000, 3_000, untouched.text))
        repository.setSpeakerName(id, 3, "Борис")
        repository.setSpeakerName(id, 0, "Вера")

        assertTrue(repository.updateSegmentText(id, 0, "  Исправление  "))
        val edited = checkNotNull(repository.observeSession(id).first())
        assertEquals(listOf(TranscriptSegment(0, 0, 250, 1_500, "Исправление"), untouched), edited.segments)
        assertEquals(setOf(SpeakerName(0, "Вера"), SpeakerName(3, "Борис")), edited.speakerNames.toSet())
        assertEquals(2, repository.observeSessions().first().single().speakerCount)
    }

    @Test
    fun updatingTextRejectsUnfinishedSessionsMissingSegmentsAndBlankText() = runBlocking {
        val id = "unfinished"
        repository.create(session(id).copy(status = SessionStatus.TRANSCRIBING))
        repository.appendSegment(id, 0, Segment(0, 0, 1_000, "Исходный текст"))

        assertFalse(repository.updateSegmentText(id, 0, "Исправление"))
        repository.updateStatus(id, SessionStatus.DONE)
        val before = repository.observeSession(id).first()
        assertEquals("Исходный текст", before?.segments?.single()?.text)
        assertFalse(repository.updateSegmentText(id, 5, "Исправление"))
        assertFalse(repository.updateSegmentText(id, 0, "  "))
        assertEquals(before, repository.observeSession(id).first())
    }

    @Test
    fun existingRoomRows_areExposedAsDomainModels() = runBlocking {
        val dao = db.sessionDao()
        val id = "existing"
        dao.insertSession(
            SessionEntity(
                id = id,
                title = id,
                source = SessionSource.IMPORT,
                originalName = "$id.mp3",
                sourceUri = "content://test/$id.mp3",
                createdAt = 1_000,
                updatedAt = 2_000,
                wavFileName = "saved.wav",
                durationMs = 3_000,
                status = SessionStatus.FAILED,
                errorCode = ErrorCode.TRANSCRIPTION_FAILED,
                errorMessage = "model crashed",
            ),
        )
        dao.appendSegment(SegmentEntity(id, 1, 0, 1_000, 2_000, "Второй"))
        dao.appendSegment(SegmentEntity(id, 0, 0, 0, 1_000, "Первый"))
        dao.upsertSpeakerName(SpeakerNameEntity(id, 0, "Анна"))

        val expected = session(id).copy(
            updatedAt = 2_000,
            wavFileName = "saved.wav",
            durationMs = 3_000,
            status = SessionStatus.FAILED,
            errorCode = ErrorCode.TRANSCRIPTION_FAILED,
            errorMessage = "model crashed",
        )
        assertEquals(expected, repository.get(id))
        assertEquals(listOf(SessionListItem(expected, 2, 1)), repository.observeSessions().first())
        assertEquals(
            SessionDetail(
                session = expected,
                segments = listOf(
                    TranscriptSegment(0, 0, 0, 1_000, "Первый"),
                    TranscriptSegment(1, 0, 1_000, 2_000, "Второй"),
                ),
                speakerNames = listOf(SpeakerName(0, "Анна")),
            ),
            repository.observeSession(id).first(),
        )
    }

    private fun session(id: String) = Session(
        id = id,
        title = id,
        source = SessionSource.IMPORT,
        originalName = "$id.mp3",
        sourceUri = "content://test/$id.mp3",
        createdAt = 1_000,
        updatedAt = 1_000,
        status = SessionStatus.DONE,
    )
}
