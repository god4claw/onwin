package com.example.onwin.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus

/**
 * Сессия: одна запись и её обработка.
 *
 * @property id UUID.
 * @property title имя выбранного файла без расширения.
 * @property source откуда взялась запись.
 * @property originalName имя исходного файла, как его вернул провайдер.
 * @property sourceUri URI исходника; нужен, чтобы повторить конвертацию после перезапуска процесса.
 * @property createdAt время создания, мс с эпохи.
 * @property updatedAt время последнего изменения строки, мс с эпохи.
 * @property wavFileName имя WAV в `filesDir/recordings`; заполняется после конвертации.
 * @property durationMs длительность WAV; заполняется вместе с [wavFileName].
 * @property status текущий статус.
 * @property errorCode причина ошибки для [com.example.onwin.domain.model.SessionStatus.FAILED].
 * @property errorMessage техническое описание ошибки (для логов и отладки, не для пользователя).
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val source: SessionSource,
    val originalName: String,
    val sourceUri: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val wavFileName: String? = null,
    val durationMs: Long? = null,
    val status: SessionStatus,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null
)
