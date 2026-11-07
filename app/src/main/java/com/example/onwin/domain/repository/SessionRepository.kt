package com.example.onwin.domain.repository

import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionListItem
import com.example.onwin.domain.model.SessionStatus
import com.example.speech.api.Segment
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Хранилище сессий: строки в БД и WAV-файлы в папке записей.
 * Живой прогресс сюда не пишется — только смены статуса и сегменты.
 */
interface SessionRepository {

    /** Все сессии, новые сверху; новый список при каждом изменении. */
    fun observeSessions(): Flow<List<SessionListItem>>

    /** Сессия с сегментами по порядку; `null`, если её нет или её удалили. */
    fun observeSession(id: String): Flow<SessionDetail?>

    /** Сессия без сегментов; `null`, если её нет. */
    suspend fun get(id: String): Session?

    /** Есть сессия, которой ещё может понадобиться разрешение на чтение исходника. */
    suspend fun hasImportNeedingSource(sourceUri: String): Boolean

    /** Добавляет новую сессию. */
    suspend fun create(session: Session)

    /** Меняет название существующей сессии. Возвращает false, если сессия уже удалена. */
    suspend fun rename(id: String, title: String): Boolean

    /** Сохраняет имя спикера в сессии. `null` возвращает стандартное имя. */
    suspend fun setSpeakerName(id: String, speaker: Int, name: String?)

    /** Обновляет текст одной реплики готовой сессии. */
    suspend fun updateSegmentText(id: String, index: Int, text: String): Boolean

    /** Меняет статус; ошибка задаётся только вместе с [SessionStatus.FAILED], иначе стирается. */
    suspend fun updateStatus(
        id: String,
        status: SessionStatus,
        errorCode: ErrorCode? = null,
        errorMessage: String? = null,
    )

    /** Записывает имя WAV и длительность; `null` и `null` — WAV больше нет. */
    suspend fun setWav(id: String, wavFileName: String?, durationMs: Long?)

    /** Добавляет сегмент под номером [index]. */
    suspend fun appendSegment(id: String, index: Int, segment: Segment)

    /** Заменяет все сегменты сессии одной транзакцией; номера — по порядку [segments]. */
    suspend fun replaceSegments(id: String, segments: List<Segment>)

    /** Удаляет все сегменты сессии. */
    suspend fun clearSegments(id: String)

    /**
     * Переводит сессии, оставшиеся в `RECORDING`/`QUEUED`/`CONVERTING`/`TRANSCRIBING`, в `FAILED` с [errorCode].
     *
     * @return сколько сессий изменено.
     */
    suspend fun failUnfinished(errorCode: ErrorCode): Int

    /**
     * Удаляет из папки записей WAV, на которые не ссылается ни одна сессия, и недописанные `.part`.
     * Вызывать, только пока не идёт конвертация: иначе удалится файл, который ещё пишется или не успел попасть в БД.
     *
     * @return сколько файлов удалено.
     */
    suspend fun deleteOrphanFiles(): Int

    /** Удаляет WAV сессии и её строки в БД (вместе с сегментами и именами спикеров). */
    suspend fun delete(id: String)

    /** Файл WAV с именем [fileName] в папке записей. */
    fun wavFile(fileName: String): File
}
