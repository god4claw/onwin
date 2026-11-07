package com.example.onwin.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
internal data object WelcomeDestination

@Serializable
internal data object RecordingsDestination

@Serializable
internal data class ActiveRecordingDestination(val sessionId: String)
