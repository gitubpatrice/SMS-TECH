package com.filestech.sms.data.sms

/**
 * v1.27.11 — forme canonique d'un URI de message du fournisseur systeme.
 *
 * # Le defaut, mesure et non deduit
 *
 * `ContentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, …)` rend `content://sms/sent/9164` sur
 * un Galaxy S9 sous Android 10 (mesure du 2026-09-07), et `content://sms/9164` sur un Galaxy S24
 * sous Android 16. La forme depend donc de la plateforme, et [com.filestech.sms.data.sms
 * .TelephonyReader] enregistrait telle quelle celle qu'on lui rendait.
 *
 * Deux consequences, l'une et l'autre silencieuses :
 *
 *  1. **la suppression systeme echouait.** Le fournisseur REFUSE la forme avec dossier —
 *     `IllegalArgumentException: Unknown URL` — et l'exception etait absorbee. Les messages que
 *     SMS Tech avait lui-meme ecrits ne quittaient jamais `content://sms` ;
 *  2. **la deduplication ne dedoublonnait pas.** L'import construit la forme canonique
 *     (`TelephonyReader`), l'application enregistrait la forme avec dossier, et l'index
 *     `UNIQUE(telephony_uri)` compare des CHAINES : deux ecritures designant la meme ligne du
 *     systeme ne se rencontraient jamais. Une resynchronisation complete reimportait donc chaque
 *     message ecrit par l'application comme s'il etait nouveau.
 *
 * # Pourquoi une chaine et non un `android.net.Uri`
 *
 * Pour que la regle soit verifiable par un test JVM ordinaire, sans appareil ni Robolectric. Elle
 * a TROIS appelants :
 *  - le chemin d'ECRITURE (`TelephonyReader`), pour qu'aucune ligne divergente n'apparaisse ;
 *  - le chemin de SUPPRESSION (`TelephonySystemCopyEraser`), qui rattrape les lignes deja
 *    enregistrees dans les bases non encore migrees ;
 *  - la migration Room `7 -> 8` (`Migrations.kt`), qui normalise l'existant en base.
 *
 * Une regle appliquee a trois endroits doit etre ecrite une seule fois. Ce KDoc en annoncait deux
 * et oubliait la migration : qui evalue l'impact d'un changement d'ici doit relire les trois.
 *
 * La regle est structurelle plutot qu'une liste de dossiers : un segment de tete non numerique
 * suivi d'un identifiant numerique est un dossier (`sent`, `inbox`, `draft`, `outbox`, `queued`,
 * `failed`), et seul l'identifiant compte. Tout le reste traverse inchange — y compris ce qu'on
 * ne sait pas interpreter, car deformer un URI inconnu serait pire que le laisser passer.
 */
internal fun canonicalTelephonyUri(raw: String): String {
    val separator = "://"
    val schemeEnd = raw.indexOf(separator)
    if (schemeEnd <= 0) return raw
    val segments = raw.substring(schemeEnd + separator.length).split('/')
    // <autorite>/<dossier>/<id> — trois segments au moins.
    if (segments.size < 3) return raw
    val id = segments.last()
    val folder = segments[segments.size - 2]
    // Un identifiant numerique en queue, precede d'un segment qui n'en est pas un.
    val porteUnDossier = id.isNotEmpty() && id.all { it.isDigit() } &&
        folder.isNotEmpty() && !folder.all { it.isDigit() }
    return if (porteUnDossier) {
        raw.substring(0, schemeEnd) + separator + segments.first() + "/" + id
    } else {
        raw
    }
}
