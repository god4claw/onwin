package com.example.onwin

import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.repository.SessionRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Gives instrumentation tests access to the same graph as the foreground service and screens. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProcessingTestEntryPoint {
    fun processingController(): ProcessingController
    fun sessionRepository(): SessionRepository
}
