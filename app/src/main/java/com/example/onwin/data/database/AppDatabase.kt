package com.example.onwin.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.onwin.data.database.dao.SessionDao
import com.example.onwin.data.database.entity.SegmentEntity
import com.example.onwin.data.database.entity.SessionEntity
import com.example.onwin.data.database.entity.SpeakerNameEntity

@Database(
    entities = [SessionEntity::class, SegmentEntity::class, SpeakerNameEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    /** DAO сессий. */
    abstract fun sessionDao(): SessionDao

    companion object {
        private const val NAME = "sessions.db"

        /** Открывает БД в стандартной папке приложения. */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
