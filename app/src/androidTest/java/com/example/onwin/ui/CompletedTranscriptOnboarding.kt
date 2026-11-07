package com.example.onwin.ui

import com.example.onwin.domain.repository.TranscriptOnboardingRepository
import com.example.onwin.domain.repository.TranscriptOnboardingStep
import kotlinx.coroutines.flow.flowOf

internal val completedTranscriptOnboarding = object : TranscriptOnboardingRepository {
    override val progress = flowOf(TranscriptOnboardingStep.COMPLETE)

    override suspend fun advance(from: TranscriptOnboardingStep) = error("Onboarding is already complete")
}
