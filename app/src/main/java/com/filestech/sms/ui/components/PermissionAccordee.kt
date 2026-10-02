package com.filestech.sms.ui.components

import androidx.compose.runtime.Composable
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

/**
 * v1.28.13 — l'état d'une permission, relu à chaque retour au premier plan (accompanist le fait),
 * pour un écran qui doit seulement SAVOIR, sans la demander : par exemple dire qu'un réglage reste
 * sans effet tant qu'elle manque.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberPermissionAccordee(permission: String): Boolean =
    rememberPermissionState(permission).status == PermissionStatus.Granted
