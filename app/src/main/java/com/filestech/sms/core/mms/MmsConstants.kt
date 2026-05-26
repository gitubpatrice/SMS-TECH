package com.filestech.sms.core.mms

/**
 * Audit C3 (v1.14.8) — Constantes MMS partagées. Avant : `280L * 1024L` était dupliqué dans
 * [com.filestech.sms.ui.screens.thread.ThreadViewModel.CARRIER_PAYLOAD_CAP_BYTES] et dans
 * [com.filestech.sms.data.voice.VoiceRecorder.MAX_SIZE_BYTES]. Même valeur (cap MMSC tightest
 * empiriquement observé : 280 KB), noms différents, packages différents → un futur ajustement
 * sur un côté laissait l'autre désynchronisé silencieusement. Désormais : SOURCE UNIQUE,
 * référencée par les deux call sites.
 */
object MmsConstants {

    /**
     * Cap maximal du payload MMS (PDU complet : SMIL + media + headers) accepté par la majorité
     * des MMSCs opérateurs. Au-delà, le carrier rejette silencieusement OU tronque. Valeur
     * empirique conservative (la plage observée 300-1024 KB selon opérateurs ; on prend le
     * minimum pour assurer la délivrance partout sans tester carrier-par-carrier).
     */
    const val CARRIER_PAYLOAD_CAP_BYTES: Long = 280L * 1024L
}
