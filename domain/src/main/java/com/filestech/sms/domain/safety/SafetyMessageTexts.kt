package com.filestech.sms.domain.safety

import com.filestech.sms.domain.emergency.EmergencyTemplate
import com.filestech.sms.domain.safetycall.SafetyCallTemplate

/**
 * v1.28.12 — Les textes qui PARTENT, dans la langue de l'application.
 *
 * Le defaut que ce fichier ferme
 * -------------------------------
 * Jusqu'ici, les corps de SMS du Mode urgence et du Safety Call etaient ecrits EN FRANCAIS, en
 * dur, dans ce module. Pas des libelles d'ecran : les SMS reellement envoyes quand quelqu'un
 * tient le bouton d'urgence trois secondes. Un utilisateur anglophone de la version publiee
 * envoyait donc deja « URGENCE - j'ai besoin d'aide » a des proches anglophones.
 *
 * Aucun controle ne pouvait le voir : ces chaines n'etaient pas dans `strings.xml`, donc ni le
 * controle de parite des traductions, ni lint `HardcodedText` (qui ne regarde que l'UI) ne les
 * atteignaient. Il a fallu parcourir l'application en allemand sur un appareil pour le trouver.
 *
 * Pourquoi une interface, et pas un Context ici
 * ----------------------------------------------
 * C'est le motif deja en place dans ce depot pour tout ce qui a besoin d'Android depuis le
 * domaine : [com.filestech.sms.domain.location.LocationProvider] et `PanicStateProvider` sont
 * declares ici et implementes ailleurs, lies par `@Binds` dans `RepositoryModule`. Les chaines
 * vivent dans `app/src/main/res/values-xx/`, **un seul endroit**, pour que le controle de parite
 * les voie desormais.
 *
 * ⚠️ L'APERCU ET L'ENVOI DOIVENT LIRE LA MEME SOURCE. `EmergencySetupScreen` montre un apercu du
 * message avant de l'armer. S'il lisait autre chose que ce qu'envoie `TriggerEmergencyUseCase`,
 * on retomberait sur le motif qui a deja frappe ce projet trois fois : un correctif pose sur un
 * seul des chemins jumeaux. Les deux passent par cette interface.
 *
 * Contraintes de redaction, heritees et a tenir dans CHAQUE langue
 * -----------------------------------------------------------------
 *  - **Pas de tiret cadratin** (U+2014) : il force UCS-2, donc 70 caracteres par segment au lieu
 *    de 160, donc du multi-segment, donc un risque de perdre le second PDU en zone radio faible —
 *    c'est-a-dire exactement la situation d'un appel a l'aide. Un `-` ASCII suffit.
 *  - Les deux premiers modeles d'urgence portent l'emoji ⚠️ en tete, qui force deja UCS-2 :
 *    compromis assume en v1.14.5 pour que la notification du destinataire soit visible.
 *  - Le modele DISCREET n'en porte PAS, et c'est voulu : il sert a signaler un malaise sans
 *    alarmer, ou sans se trahir devant quelqu'un qui regarde l'ecran.
 *  - Chaque relance doit **nommer l'application** et **se suffire a elle-meme** : le message
 *    initial peut n'etre jamais arrive, et un SMS anonyme recu en pleine nuit ressemble a du
 *    hameconnage. La derniere doit s'annoncer comme la derniere.
 */
interface SafetyMessageTexts {

    /** Le corps du SMS d'urgence. [locationUrl] nul ou vide devient une mention explicite. */
    fun emergencyBody(template: EmergencyTemplate, locationUrl: String?): String

    /**
     * Le corps du SMS de Safety Call. [customMessage] n'est lu que pour
     * [SafetyCallTemplate.CUSTOM], et il est recoupe a
     * [com.filestech.sms.domain.safetycall.SafetyCallConfig.MAX_CUSTOM_MESSAGE_LENGTH].
     */
    fun safetyCallBody(template: SafetyCallTemplate, timeoutMs: Long, customMessage: String): String

    /** Le corps de la relance numero [index] (1 a `SafetyCallConfig.RELANCE_COUNT`). */
    fun safetyCallRelance(index: Int): String

    /** La duree configuree, lisible : « 24 Stunden », « 2 jours », « less than an hour ». */
    fun durationLabel(timeoutMs: Long): String
}
