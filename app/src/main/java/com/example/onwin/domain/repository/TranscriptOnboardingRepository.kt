package com.example.onwin.domain.repository

import kotlinx.coroutines.flow.Flow

enum class TranscriptOnboardingStep {
    TEXT, SPEAKER, COMPLETE,
}

interface TranscriptOnboardingRepository {
    val progress: Flow<TranscriptOnboardingStep>

    suspend fun advance(from: TranscriptOnboardingStep)
}
