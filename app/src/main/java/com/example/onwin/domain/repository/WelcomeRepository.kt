package com.example.onwin.domain.repository

/** Флаг «приветствие показано»: хранится между запусками приложения. */
interface WelcomeRepository {

    /** Показано ли приветствие. */
    suspend fun isCompleted(): Boolean

    /** Помечает приветствие показанным. */
    suspend fun complete()
}
