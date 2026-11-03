package com.filestech.sms.data.sms

import java.text.BreakIterator

/**
 * v1.29.2 — le découpage d'un texte en SMS Unicode (UCS-2), calculé **ici** quand Android refuse de le faire.
 *
 * ### Le défaut couvert
 *
 * Pour un texte qui sort de l'alphabet GSM (ê, ç, œ, l'apostrophe ’, un émoji…) et dépasse 70 caractères,
 * le calcul du système — `SmsMessage.calculateLength`, et `SmsManager.divideMessage` qui s'appuie dessus —
 * passe par `SmsMessage.hasEmsSupport()`. Dès que la liste `no_ems_support_sim_operators` n'est pas vide
 * (elle porte une entrée dans AOSP même), celui-ci lit le GID1 de la carte SIM, lecture qui exige
 * READ_PHONE_STATE. **L'utilisateur qui refuse la permission Téléphone** — l'application s'en passe, elle ne
 * sert qu'au choix de la SIM — reçoit alors `SecurityException: getGroupIdLevel1`. Levée depuis le compteur
 * de segments, à chaque modification du champ et à l'ouverture d'une conversation dont le brouillon est un
 * tel texte, elle fermait l'application (courriel d'un utilisateur sous GrapheneOS, 2026-11-01 ; reproduit
 * sous Android 17, permission refusée) ; levée depuis `divideMessage`, elle faisait échouer l'envoi.
 *
 * ### La règle reprise
 *
 * Celle d'AOSP (`SmsMessageBase.calcUnicodeEncodingDetails`, EMS disponible) : jusqu'à
 * [UCS2_UNITES_SMS_SEUL] unités UTF-16, un seul SMS ; au-delà, des parties de [UCS2_UNITES_PAR_PARTIE]
 * unités au plus (l'en-tête de concaténation prend 6 octets), sans couper un caractère — un émoji, une
 * lettre et son accent combinant — entre deux parties.
 *
 * ⚠️ Écart assumé : quand l'opérateur ne gère pas l'EMS, Android réserve une unité de plus par partie pour y
 * écrire « x/y ». Le savoir demande justement le GID1 refusé ; ce découpage ne le fait donc pas. Avec la
 * permission accordée, rien ne change : le calcul reste celui du système.
 */
internal fun decouperEnUcs2(texte: String): List<String> {
    if (texte.length <= UCS2_UNITES_SMS_SEUL) return listOf(texte)
    val frontieres = BreakIterator.getCharacterInstance().apply { setText(texte) }
    val parties = ArrayList<String>()
    var debut = 0
    while (debut < texte.length) {
        val fin = finDePartie(texte, debut, frontieres)
        parties += texte.substring(debut, fin)
        debut = fin
    }
    return parties
}

/**
 * Fin de la partie qui commence à [debut] : au plus loin, sur une frontière de caractère. Un seul caractère
 * plus long qu'une partie (une très longue suite d'émojis liés) ne peut pas tenir entier ; on évite alors
 * au moins de couper une paire de substitution, comme AOSP.
 */
private fun finDePartie(texte: String, debut: Int, frontieres: BreakIterator): Int {
    val limite = minOf(debut + UCS2_UNITES_PAR_PARTIE, texte.length)
    if (limite == texte.length || frontieres.isBoundary(limite)) return limite
    val precedente = frontieres.preceding(limite)
    return when {
        precedente > debut -> precedente
        Character.isHighSurrogate(texte[limite - 1]) -> limite - 1
        else -> limite
    }
}

/** Ce que le compteur affiche pour un texte Unicode, avec la même règle que [decouperEnUcs2]. */
internal fun statsUcs2(texte: String): SmsSegmentCounter.Stats {
    val parties = decouperEnUcs2(texte)
    val capacite = if (parties.size == 1) UCS2_UNITES_SMS_SEUL else UCS2_UNITES_PAR_PARTIE
    return SmsSegmentCounter.Stats(
        length = texte.length,
        segmentCount = parties.size,
        charsRemainingInCurrentSegment = capacite - parties.last().length,
        isUnicode = true,
    )
}

/** 140 octets de données utiles, à 2 octets par unité UTF-16. */
internal const val UCS2_UNITES_SMS_SEUL = 70

/** 140 octets moins les 6 de l'en-tête de concaténation, à 2 octets par unité UTF-16. */
internal const val UCS2_UNITES_PAR_PARTIE = 67
