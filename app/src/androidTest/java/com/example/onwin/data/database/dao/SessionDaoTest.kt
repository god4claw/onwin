package com.example.onwin.data.database.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.database.entity.SegmentEntity
import com.example.onwin.data.database.dao.SessionDao
import com.example.onwin.data.database.entity.SessionEntity
import com.example.onwin.data.database.entity.SpeakerNameEntity
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.data.database.model.SessionDetailRow
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DAO на настоящем SQLite в памяти: наблюдение, транзакции, каскады, счётчики. */
@RunWith(AndroidJUnit4::class)
class SessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeSessions_newestFirst_withSegmentAndSpeakerCountsFromSql() = runBlocking {
        dao.insertSession(session("old", createdAt = 1_000))
        dao.insertSession(session("new", createdAt = 2_000))
        dao.replaceSegments("old", listOf(segment("old", 0, speaker = 0), segment("old", 1, speaker = 1), segment("old", 2, speaker = 0)))

        val items = dao.observeSessions().first()

        assertEquals(listOf("new", "old"), items.map { it.session.id })
        assertEquals(0 to 0, items[0].segmentCount to items[0].speakerCount)
        assertEquals(3 to 2, items[1].segmentCount to items[1].speakerCount)
    }

    @Test
    fun observeSession_emitsLiveUpdates_withSegmentsInOrder(): Unit = runBlocking {
        val emissions = Channel<SessionDetailRow?>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { dao.observeSession("s").collect { emissions.send(it) } }
        try {
            assertNull(emissions.awaitUntil { true })

            dao.insertSession(session("s"))
            emissions.awaitUntil { it?.session?.status == SessionStatus.QUEUED }

            dao.appendSegment(segment("s", 1))
            dao.appendSegment(segment("s", 0))
            val withSegments = emissions.awaitUntil { it?.segments?.size == 2 }
            assertEquals(listOf(0, 1), withSegments!!.segments.map { it.index })

            dao.updateStatus("s", SessionStatus.FAILED, ErrorCode.TRANSCRIPTION_FAILED, "boom", 5_000)
            val failed = emissions.awaitUntil { it?.session?.status == SessionStatus.FAILED }!!.session
            assertEquals(ErrorCode.TRANSCRIPTION_FAILED, failed.errorCode)
            assertEquals(5_000L, failed.updatedAt)

            dao.upsertSpeakerName(SpeakerNameEntity("s", 0, "Анна"))
            assertEquals("Анна", emissions.awaitUntil { it?.speakerNames?.size == 1 }!!.speakerNames.single().name)

            dao.delete("s")
            emissions.awaitUntil { it == null }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun replaceSegments_isAtomic() = runBlocking {
        dao.insertSession(session("s"))
        val original = listOf(segment("s", 0, text = "раз"), segment("s", 1, text = "два"))
        dao.replaceSegments("s", original)

        // Дубликат ключа на второй вставке: удаление старых сегментов должно откатиться.
        val broken = listOf(segment("s", 0, text = "новый"), segment("s", 0, text = "дубликат"))
        val error = runCatching { dao.replaceSegments("s", broken) }.exceptionOrNull()

        assertTrue("$error", error is SQLiteConstraintException)
        assertEquals(original, dao.observeSession("s").first()!!.segments)

        val replacement = listOf(segment("s", 0, text = "итог"))
        dao.replaceSegments("s", replacement)
        assertEquals(replacement, dao.observeSession("s").first()!!.segments)
    }

    @Test
    fun deleteSession_cascadesToSegmentsAndSpeakerNames() = runBlocking {
        dao.insertSession(session("gone"))
        dao.insertSession(session("kept"))
        for (id in listOf("gone", "kept")) {
            dao.replaceSegments(id, listOf(segment(id, 0), segment(id, 1)))
            dao.upsertSpeakerName(SpeakerNameEntity(id, 0, "Имя"))
        }

        dao.delete("gone")

        assertNull(dao.getSession("gone"))
        assertEquals(2, count("SELECT COUNT(*) FROM segments"))
        assertEquals(0, count("SELECT COUNT(*) FROM segments WHERE sessionId = 'gone'"))
        assertEquals(1, count("SELECT COUNT(*) FROM speaker_names"))
        assertEquals(2, dao.observeSession("kept").first()!!.segments.size)
    }

    @Test
    fun failUnfinished_failsOnlyActiveSessions() = runBlocking {
        val activeStatuses = setOf(
            SessionStatus.RECORDING,
            SessionStatus.QUEUED,
            SessionStatus.CONVERTING,
            SessionStatus.TRANSCRIBING,
            SessionStatus.CORRECTING,
        )
        for (status in SessionStatus.entries) dao.insertSession(session(status.name, status = status))

        val changed = dao.failUnfinished(ErrorCode.INTERRUPTED, updatedAt = 9_000)

        assertEquals(activeStatuses.size, changed)
        for (status in SessionStatus.entries) {
            val session = dao.getSession(status.name)!!
            if (status in activeStatuses) {
                assertEquals(SessionStatus.FAILED, session.status)
                assertEquals(ErrorCode.INTERRUPTED, session.errorCode)
                assertEquals(9_000L, session.updatedAt)
            } else {
                assertEquals(status, session.status)
            }
        }
    }

    @Test
    fun unknownErrorCodeFromDb_readsAsUnknown() = runBlocking {
        dao.insertSession(session("s"))
        db.openHelper.writableDatabase.execSQL("UPDATE sessions SET errorCode = 'FROM_THE_FUTURE' WHERE id = 's'")

        assertEquals(ErrorCode.UNKNOWN, dao.getSession("s")!!.errorCode)
    }

    private fun count(sql: String): Int =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private suspend fun <T> Channel<T>.awaitUntil(predicate: (T) -> Boolean): T = withTimeout(5_000) {
        var value = receive()
        while (!predicate(value)) value = receive()
        value
    }

    private fun session(id: String, createdAt: Long = 1_000, status: SessionStatus = SessionStatus.QUEUED) =
        SessionEntity(
            id = id,
            title = id,
            source = SessionSource.IMPORT,
            originalName = "$id.mp3",
            sourceUri = "content://test/$id.mp3",
            createdAt = createdAt,
            updatedAt = createdAt,
            status = status,
        )

    private fun segment(sessionId: String, index: Int, speaker: Int = 0, text: String = "реплика $index") =
        SegmentEntity(sessionId, index, speaker, index * 1_000L, index * 1_000L + 900, text)
}
