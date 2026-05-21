package com.filestech.sms.ui.navigation

import kotlinx.serialization.Serializable

@Serializable sealed interface Route

@Serializable data class Conversations(val archived: Boolean = false) : Route
@Serializable data object Vault : Route
@Serializable data object Blocked : Route
@Serializable data object Settings : Route
@Serializable data object Backup : Route
@Serializable data object Migration : Route
@Serializable data object About : Route
@Serializable data object Onboarding : Route
@Serializable data object Splash : Route
@Serializable data object Lock : Route
@Serializable data object SafetyCallSetup : Route
@Serializable data object Emergency : Route
@Serializable data object EmergencySetup : Route
@Serializable data class Thread(val conversationId: Long) : Route
@Serializable data class Compose(val initialAddress: String? = null) : Route
