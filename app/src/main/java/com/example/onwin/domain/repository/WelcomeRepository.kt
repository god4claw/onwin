package com.example.onwin.domain.repository

interface WelcomeRepository {
    suspend fun isCompleted(): Boolean
    suspend fun complete()
}
