package com.filestech.sms.data.local.db

/**
 * SQLite plafonne `IN (…)` à 999 paramètres hôtes sur les versions anciennes ; on reste dessous.
 *
 * v1.28.9 — sortie de `TelephonySyncManager`, où elle était privée, pour servir aussi à
 * [com.filestech.sms.data.repository.FichiersDePiecesJointes] sans être recopiée.
 */
internal const val SQLITE_HOST_PARAM_LIMIT = 900
