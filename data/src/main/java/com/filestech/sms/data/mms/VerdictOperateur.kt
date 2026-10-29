package com.filestech.sms.data.mms

import com.filestech.sms.pdu.PduHeaders
import com.filestech.sms.pdu.PduParser

/**
 * 2026-10-29 (issue #53) — ce que le serveur MMS de l'opérateur a répondu à un envoi.
 *
 * # Le défaut
 *
 * `MmsSentReceiver` marquait un MMS « envoyé » sur le seul `RESULT_OK` d'Android. Or ce code ne
 * dit qu'une chose : la requête HTTP vers le serveur MMS de l'opérateur a abouti (lu dans
 * `MmsRequest.execute` d'AOSP : `result = Activity.RESULT_OK` dès que `doHttp` revient). La
 * réponse de l'opérateur — un m-send-conf portant `X-Mms-Response-Status` — est jointe telle
 * quelle dans `SmsManager.EXTRA_MMS_DATA`, sans être examinée. Un MMS REFUSÉ par l'opérateur
 * s'affichait donc envoyé, et n'arrivait jamais : exactement ce que décrit l'issue #53. AOSP
 * lui-même range un tel message en échec (`SendRequest.persistIfRequired` : `RESULT_OK` mais
 * statut différent de `RESPONSE_STATUS_OK` → boîte FAILED).
 *
 * # Le sens de l'échec
 *
 * Seul un refus EXPLICITE (statut présent et différent de 0x80) devient un échec. Une réponse
 * absente, vide, illisible ou qui n'est pas un m-send-conf rend [Inconnu], et l'appelant garde
 * le comportement d'avant (envoyé) : un opérateur qui répond mal ne doit pas fabriquer de faux
 * échecs chez ceux pour qui l'envoi fonctionne.
 */
sealed interface VerdictOperateur {
    /** Statut 0x80 : l'opérateur a accepté le message. */
    data object Accepte : VerdictOperateur

    /** L'opérateur a refusé ; [statut] est l'octet `X-Mms-Response-Status` (0x81 à 0xFF). */
    data class Refuse(val statut: Int) : VerdictOperateur

    /** Pas de réponse exploitable : on ne sait pas. */
    data object Inconnu : VerdictOperateur
}

/** OMA-MMS-ENC : 0x81-0x88 (anciens codes), 0xC0-0xDF (transitoires), 0xE0-0xFF (permanents). */
private val STATUTS_DE_REFUS = 0x81..0xFF

/** Lit le m-send-conf joint au résultat d'un envoi. Ne lève jamais : l'entrée vient du réseau. */
fun lireVerdictOperateur(reponse: ByteArray?): VerdictOperateur {
    if (reponse == null || reponse.isEmpty()) return VerdictOperateur.Inconnu
    val pdu = runCatching { PduParser(reponse).parse() }.getOrNull()
    val headers = pdu?.pduHeaders
    if (headers == null || pdu.messageType != PduHeaders.MESSAGE_TYPE_SEND_CONF) {
        return VerdictOperateur.Inconnu
    }
    return when (val statut = headers.getOctet(PduHeaders.RESPONSE_STATUS)) {
        PduHeaders.RESPONSE_STATUS_OK -> VerdictOperateur.Accepte
        in STATUTS_DE_REFUS -> VerdictOperateur.Refuse(statut)
        // 0 = en-tête absent (`getOctet`) ; sous 0x80 = valeur hors norme, PDU mal formé.
        else -> VerdictOperateur.Inconnu
    }
}
