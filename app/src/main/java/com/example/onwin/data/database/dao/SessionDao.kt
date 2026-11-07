package com.example.onwin.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.onwin.data.database.entity.SegmentEntity
import com.example.onwin.data.database.entity.SessionEntity
import com.example.onwin.data.database.entity.SpeakerNameEntity
import com.example.onwin.data.database.model.SessionDetailRow
import com.example.onwin.data.database.model.SessionListRow
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Доступ к сессиям, сегментам и именам спикеров. */
@Dao
abstract class SessionDao {
    /** Все сессии, новые сверху, со счётчиками сегментов и спикеров. */
    @Query(
        """
        SELECT s.*,
            (SELECT COUNT(*) FROM segments g WHERE g.sessionId = s.id) AS segmentCount,
            (SELECT COUNT(DISTINCT g.speaker) FROM segments g WHERE g.sessionId = s.id) AS speakerCount
        FROM sessions s
        ORDER BY s.createdAt DESC
        """
    )
    abstract fun observeSessions(): Flow<List<SessionListRow>>

    /** Сессия с сегментами по порядку и именами спикеров; `null`, если сессии нет. */
    fun observeSession(id: String): Flow<SessionDetailRow?> =
        observeSessionUnordered(id).map { detail -> detail?.copy(segments = detail.segments.sortedBy { it.index }) }

    // @Relation не гарантирует порядок, поэтому сортировка — в observeSession.
    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    protected abstract fun observeSessionUnordered(id: String): Flow<SessionDetailRow?>

    /** Сессия без сегментов; `null`, если её нет. */
    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract suspend fun getSession(id: String): SessionEntity?

    /** Добавляет новую сессию; повтор [SessionEntity.id] — ошибка. */
    @Insert
    abstract suspend fun insertSession(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun rename(
        id: String,
        title: String,
        updatedAt: Long
    ): Int

    /** Добавляет один сегмент; повтор `(sessionId, index)` — ошибка. */
    @Insert
    abstract suspend fun appendSegment(segment: SegmentEntity)

    /** Заменяет все сегменты сессии на [segments] одной транзакцией. */
    @Transaction
    open suspend fun replaceSegments(
        sessionId: String,
        segments: List<SegmentEntity>
    ) {
        clearSegments(sessionId)
        insertSegments(segments)
    }

    @Insert
    protected abstract suspend fun insertSegments(segments: List<SegmentEntity>)

    /** Удаляет все сегменты сессии. */
    @Query("DELETE FROM segments WHERE sessionId = :sessionId")
    abstract suspend fun clearSegments(sessionId: String)

    /** Меняет статус и ошибку сессии. */
    @Query(
        """
        UPDATE sessions SET status = :status, errorCode = :errorCode, errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    abstract suspend fun updateStatus(
        id: String,
        status: SessionStatus,
        errorCode: ErrorCode?,
        errorMessage: String?,
        updatedAt: Long
    )

    /** Записывает (или стирает, если `null`) имя WAV и длительность. */
    @Query(
        """
        UPDATE sessions SET wavFileName = :wavFileName, durationMs = :durationMs,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    abstract suspend fun setWav(
        id: String,
        wavFileName: String?,
        durationMs: Long?,
        updatedAt: Long
    )

    /**
     * Переводит все сессии в статусах [from] в `FAILED` с кодом [errorCode].
     *
     * @return сколько сессий изменено.
     */
    @Query(
        """
        UPDATE sessions SET status = :failed, errorCode = :errorCode, errorMessage = NULL, updatedAt = :updatedAt
        WHERE status IN (:from)
        """
    )
    protected abstract suspend fun failAll(
        from: List<SessionStatus>,
        failed: SessionStatus,
        errorCode: ErrorCode,
        updatedAt: Long
    ): Int

    /**
     * Переводит недоделанные сессии ([SessionStatus.Companion.UNFINISHED]) в `FAILED` с кодом [errorCode].
     *
     * @return сколько сессий изменено.
     */
    suspend fun failUnfinished(
        errorCode: ErrorCode,
        updatedAt: Long
    ): Int =
        failAll(SessionStatus.UNFINISHED, SessionStatus.FAILED, errorCode, updatedAt)

    /** Имена WAV, на которые ссылаются сессии. */
    @Query("SELECT wavFileName FROM sessions WHERE wavFileName IS NOT NULL")
    abstract suspend fun wavFileNames(): List<String>

    /** Удаляет сессию; сегменты и имена спикеров удаляются каскадно. */
    @Query("DELETE FROM sessions WHERE id = :id")
    abstract suspend fun delete(id: String)

    /** Задаёт имя спикера. */
    @Upsert
    abstract suspend fun upsertSpeakerName(name: SpeakerNameEntity)

    /** Убирает имя спикера, в UI вернётся «Спикер N». */
    @Query("DELETE FROM speaker_names WHERE sessionId = :sessionId AND speaker = :speaker")
    abstract suspend fun deleteSpeakerName(
        sessionId: String,
        speaker: Int
    )
}
