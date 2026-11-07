package com.example.onwin.data.database

import androidx.room.TypeConverter
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus

/** Перечисления хранятся по имени: так БД не зависит от порядка констант. */
class Converters {
    /** [SessionStatus] → TEXT. */
    @TypeConverter
    fun fromStatus(value: SessionStatus): String = value.name

    /** TEXT → [SessionStatus]. */
    @TypeConverter
    fun toStatus(value: String): SessionStatus = SessionStatus.valueOf(value)

    /** [SessionSource] → TEXT. */
    @TypeConverter
    fun fromSource(value: SessionSource): String = value.name

    /** TEXT → [SessionSource]. */
    @TypeConverter
    fun toSource(value: String): SessionSource = SessionSource.valueOf(value)

    /** [ErrorCode] → TEXT. */
    @TypeConverter
    fun fromErrorCode(value: ErrorCode?): String? = value?.name

    /** TEXT → [ErrorCode]; неизвестное имя (код из будущей версии) читается как [ErrorCode.UNKNOWN]. */
    @TypeConverter
    fun toErrorCode(value: String?): ErrorCode? =
        value?.let { name -> ErrorCode.entries.firstOrNull { it.name == name } ?: ErrorCode.UNKNOWN }
}
