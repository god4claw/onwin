package com.example.onwin.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.onwin.data.database.dao.SessionDao
import com.example.onwin.data.database.entity.SegmentEntity
import com.example.onwin.data.database.entity.SessionEntity
import com.example.onwin.data.database.entity.SessionNotesEntity
import com.example.onwin.data.database.entity.SpeakerNameEntity
import com.example.onwin.data.database.entity.SpeakerSuggestionEntity

/** БД приложения. Схема каждой версии лежит в `app/schemas`. */
@Database(
    entities = [
        SessionEntity::class,
        SegmentEntity::class,
        SpeakerNameEntity::class,
        SessionNotesEntity::class,
        SpeakerSuggestionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    /** DAO сессий. */
    abstract fun sessionDao(): SessionDao

    companion object {
        private const val NAME = "sessions.db"

        /**
         * v1 → v2: заметки LLM и предложенные имена спикеров.
         *
         * Обе таблицы новые, данные v1 не трогаются: миграция только создаёт их
         * и индексы по внешнему ключу.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_notes` (
                        `sessionId` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `speaker_suggestions` (
                        `sessionId` TEXT NOT NULL,
                        `speaker` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        PRIMARY KEY(`sessionId`, `speaker`),
                        FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Открывает БД в стандартной папке приложения. */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
