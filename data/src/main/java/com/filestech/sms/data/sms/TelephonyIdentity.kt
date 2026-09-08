package com.filestech.sms.data.sms

import android.content.ContentResolver
import android.net.Uri
import android.provider.Telephony
import com.filestech.sms.domain.model.MessageDirection

/**
 * v1.28.1 (revue externe GitLab !38458, constat 3 de la 3e passe) — comment le fournisseur du
 * systeme exprime le SENS d'un message, et comment on le traduit.
 *
 * Ces deux fonctions vivaient dans [TelephonyReader], qui est le seul a les avoir eues tant que
 * l'identite d'une ligne systeme se resumait a sa date. Depuis que
 * [com.filestech.sms.data.repository.ConversationRepositoryImpl] verifie cette identite avant de
 * SUPPRIMER, deux appelants ont besoin de la meme regle — et deux copies d'une meme regle
 * derivent : c'est exactement la famille de defaut « correctif asymetrique entre jumeaux ». Une
 * seule definition, donc, partagee.
 *
 * Le sens plutot que le type brut : la pile du systeme fait passer un SMS sortant par
 * `QUEUED`, `OUTBOX` puis `SENT` sans que rien ne bouge chez nous. Comparer le type brut
 * refuserait de supprimer un message que l'on vient d'ecrire ; comparer le sens est stable.
 */
internal fun smsTypeToDirection(type: Int): MessageDirection = when (type) {
    Telephony.Sms.MESSAGE_TYPE_SENT,
    Telephony.Sms.MESSAGE_TYPE_OUTBOX,
    Telephony.Sms.MESSAGE_TYPE_QUEUED,
    Telephony.Sms.MESSAGE_TYPE_FAILED,
    Telephony.Sms.MESSAGE_TYPE_DRAFT,
    -> MessageDirection.OUTGOING
    else -> MessageDirection.INCOMING
}

/** Voir [smsTypeToDirection] : meme role, cote `content://mms`. */
internal fun mmsBoxToDirection(box: Int): MessageDirection =
    if (box == MMS_MSG_BOX_INBOX) MessageDirection.INCOMING else MessageDirection.OUTGOING

// Telephony.Mms.MESSAGE_BOX_* — gardes en entiers pour ne pas dependre des constantes @hide.
internal const val MMS_MSG_BOX_INBOX = 1
internal const val MMS_MSG_BOX_SENT = 2
internal const val MMS_MSG_BOX_DRAFT = 3
internal const val MMS_MSG_BOX_OUTBOX = 4
internal const val MMS_MSG_BOX_FAILED = 5

/** Colonne `msg_box` de `content://mms`, absente de l'API publique. */
internal const val MMS_BOX_COLUMN = "msg_box"

/**
 * Picks the relevant address from `content://mms/{id}/addr`:
 *  - FROM (type 137) for incoming
 *  - the first TO (type 151) for outgoing
 *  - fallback to "FROM-generic" (type 129) when neither 137 nor 151 yields
 *    anything — observed on some OEM ROMs (older Samsung One UI, Xiaomi MIUI
 *    legacy) that store the originator under the generic AOSP type 129
 *    instead of the standard 137. Without this fallback, those MMS were
 *    silently skipped at import time (v1.8.0 bug 1).
 *
 * Skips the AOSP placeholder `insert-address-token`.
 *
 * v1.16.0 — parametre `direction` type enum (etait Int).
 *
 * v1.27.2 (audit Codex, C-05) — `null` = la requete d'adresse a ECHOUE, distinct de « vide ».
 *
 * v1.28.1 — cette regle vivait en `private` dans [TelephonyReader], qui l'utilisait a l'import.
 * [TelephonySystemCopyEraser] en a besoin a son tour : c'est la SEULE chose qui separe deux MMS
 * voisins avant une suppression, `content://mms` ne portant ni corps ni adresse dans la table que
 * designe l'URI. La recopier aurait ete la faute classique du correctif asymetrique entre
 * jumeaux — d'autant qu'elle encode une connaissance chere : le repli sur le type 129, mesure sur
 * un Galaxy S9 dont la ROM range l'expediteur ailleurs que la norme.
 *
 * ⚠ Trois blocs de commentaires etaient EMPILES ici apres le deplacement, et Dokka comme la
 * Quick Documentation ne retiennent que le dernier colle a la declaration : le repli OEM, la
 * connaissance la plus chere du lot, etait devenu invisible. Un seul bloc, donc.
 */
internal fun readMmsAddress(
    resolver: ContentResolver,
    mmsId: Long,
    direction: MessageDirection,
): String? {
    var from = ""
    var firstTo = ""
    var fallbackGeneric = ""
    val cursor = resolver.query(
        Uri.parse("content://mms/$mmsId/addr"),
        arrayOf("address", "type"),
        null,
        null,
        null,
    ) ?: return null
    cursor.use { c ->
        while (c.moveToNext()) {
            // Un seul point de sortie : `null` (colonne vide) et le jeton reserve d'AOSP
            // n'apportent ni l'un ni l'autre d'adresse exploitable.
            val addr = c.getString(0)
            if (addr == null || addr == "insert-address-token") continue
            val type = c.getInt(1)
            when (type) {
                137 -> if (from.isBlank()) from = addr
                151 -> if (firstTo.isBlank()) firstTo = addr
                // v1.8.0 (bug 1 fix) — type 129 = "FROM" générique AOSP
                // (constante non publique `PduHeaders.FROM` = 0x89 = 129).
                // Certains OEM (Samsung One UI < 5 sur S9 d'après le retour
                // user, MIUI legacy) y stockent l'originateur au lieu du
                // type 137. Capturé en fallback : utilisé uniquement si
                // ni 137 (FROM) ni 151 (TO) ne donnent rien.
                129 -> if (fallbackGeneric.isBlank()) fallbackGeneric = addr
            }
        }
    }
    // Pour l'incoming, ordre de préférence : 137 (FROM standard) → 129 (FROM générique OEM).
    // Pour l'outgoing, ordre : 151 (TO premier) → 137 (FROM de l'utilisateur lui-même) → 129.
    return if (direction == MessageDirection.INCOMING) {
        from.ifBlank { fallbackGeneric }
    } else {
        firstTo.ifBlank { from.ifBlank { fallbackGeneric } }
    }
}
