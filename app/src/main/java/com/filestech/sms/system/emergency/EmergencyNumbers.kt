package com.filestech.sms.system.emergency

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import timber.log.Timber

/**
 * v1.28.12 — Les numéros d'urgence du PAYS OÙ SE TROUVE LE TÉLÉPHONE.
 *
 * Pourquoi ce fichier existe
 * --------------------------
 * Jusqu'ici les quatre tuiles d'appel étaient françaises en dur — 15 SAMU, 17 Police,
 * 18 Pompiers — et [EmergencyCallHelper] le documentait comme une limite assumée. Tant que
 * l'application ne parlait que français et anglais, c'était une approximation. Le jour où elle
 * est traduite en allemand, en italien et en espagnol, elle met une tuile « Polizei » qui
 * compose le 17 dans les mains de quelqu'un pour qui la police est le 110. Ce n'est plus une
 * approximation, c'est un défaut sur la fonction la plus critique de l'application.
 *
 * LE PAYS NE VIENT PAS DE LA LANGUE, et c'est le point à ne pas inverser
 * ----------------------------------------------------------------------
 * Un germanophone à Paris doit voir le 17, pas le 110 : il faut le numéro du pays où il SE
 * TROUVE, pas celui de la langue qu'il lit. La source est donc, dans cet ordre :
 *
 *   1. [TelephonyManager.getNetworkCountryIso] — le réseau sur lequel le téléphone est
 *      enregistré MAINTENANT. C'est le seul signal qui suit l'utilisateur quand il voyage.
 *      Aucune permission requise.
 *   2. [TelephonyManager.getSimCountryIso] — le pays de la carte SIM, quand il n'y a pas de
 *      réseau (mode avion, pas de couverture). Approximation raisonnable : on est plus souvent
 *      chez soi qu'ailleurs.
 *   3. Rien du tout — pas de téléphonie, pas de SIM, pas de réseau : [PAR_DEFAUT], c'est-à-dire
 *      le 112 seul, qui est correct dans toute l'Union européenne et dans une grande partie du
 *      monde, et qui est de toute façon le seul numéro joignable sans SIM.
 *
 * Ce que la table contient, et ce qu'elle ne contient pas
 * -------------------------------------------------------
 * Un numéro d'urgence faux coûte des secondes à quelqu'un qui n'en a pas. La table ne porte donc
 * QUE des numéros nationalement uniformes et vérifiables, et le 112 partout ailleurs. Les
 * numéros qui varient d'une région à l'autre en sont volontairement absents : en Espagne le 061
 * (sanitaire) et le 080 (pompiers) dépendent de la communauté autonome, le 112 les couvre tous.
 *
 * ⚠️ **N'ajouter un pays qu'après vérification sur une source officielle.** Une entrée de cette
 * table est une instruction donnée à quelqu'un en situation d'urgence.
 *
 * Le filet de sécurité de l'OS
 * -----------------------------
 * À partir d'Android 10, le système connaît lui-même les numéros d'urgence du réseau courant
 * ([TelephonyManager.isEmergencyNumber]). Chaque numéro NON européen de la table lui est soumis
 * et retiré s'il n'est pas reconnu : une erreur de table ne peut donc pas produire une tuile
 * morte sur un appareil récent. Le 112 n'est JAMAIS retiré — c'est le dernier recours, et une
 * réponse négative de l'OS (pas de SIM, pas de réseau) ne doit pas faire disparaître le seul
 * numéro qui fonctionne quand même. En cas d'exception, on garde ce que dit la table : masquer
 * une tuile par prudence serait la prudence retournée contre l'utilisateur.
 */
object EmergencyNumbers {

    /**
     * Le service appelé, qui porte le libellé. Le NUMÉRO, lui, dépend du pays.
     *
     * [NATIONAL] est le numéro d'urgence GÉNÉRAL d'un pays, celui qu'on y a appris par cœur :
     * le 999 britannique et irlandais. Il n'appelle pas un service en particulier — il appelle
     * le standard, qui demande ensuite lequel. Il a sa propre valeur parce que l'étiqueter
     * POLICE annonçait ce qu'il ne fait pas, et le retirer aurait privé ces pays du seul numéro
     * que leurs habitants connaissent.
     */
    enum class Service { EUROPEAN, NATIONAL, POLICE, FIRE, MEDICAL }

    data class Dial(val service: Service, val number: String)

    private val EU = Dial(Service.EUROPEAN, "112")

    /**
     * Les pays dont les numéros sont nationalement uniformes. Clés en minuscules, comme les
     * rend [TelephonyManager.getNetworkCountryIso].
     *
     * Sources : numéros d'urgence officiels publiés par chaque État. Le 112 est l'appel
     * d'urgence unique européen et fonctionne dans les 27 États membres.
     */
    private val PAR_PAYS: Map<String, List<Dial>> = mapOf(
        // France — les quatre numéros déjà servis par l'application avant la v1.28.12.
        "fr" to listOf(EU, Dial(Service.MEDICAL, "15"), Dial(Service.POLICE, "17"), Dial(Service.FIRE, "18")),
        // Allemagne — 112 pompiers et secours, 110 police. Les deux sont universels.
        "de" to listOf(EU, Dial(Service.POLICE, "110")),
        // Autriche — 133 police, 122 pompiers, 144 secours.
        "at" to listOf(EU, Dial(Service.POLICE, "133"), Dial(Service.FIRE, "122"), Dial(Service.MEDICAL, "144")),
        // Suisse — 117 police, 118 pompiers, 144 secours.
        "ch" to listOf(EU, Dial(Service.POLICE, "117"), Dial(Service.FIRE, "118"), Dial(Service.MEDICAL, "144")),
        // Italie — le 112 est le numéro unique (NUE) ; 113, 115 et 118 restent joignables.
        "it" to listOf(EU, Dial(Service.POLICE, "113"), Dial(Service.FIRE, "115"), Dial(Service.MEDICAL, "118")),
        // Espagne — 091 Police nationale. Le sanitaire (061) et les pompiers (080) varient selon
        // la communauté autonome : le 112 les couvre, ils ne sont donc pas listés.
        "es" to listOf(EU, Dial(Service.POLICE, "091")),
        // Belgique — 101 police ; le 112 couvre secours et pompiers.
        "be" to listOf(EU, Dial(Service.POLICE, "101")),
        // Luxembourg — 113 police, le 112 pour le reste.
        "lu" to listOf(EU, Dial(Service.POLICE, "113")),
        // Portugal — 112 unique.
        "pt" to listOf(EU),
        // Royaume-Uni et Irlande — 999, le numéro que tout le monde y connaît, sous [NATIONAL].
        //
        // Il a d'abord été étiqueté POLICE : une vérification externe (2026-09-17) a montré que
        // c'était faux. Le 999 n'est pas la ligne de la police, c'est le numéro d'urgence GÉNÉRAL,
        // strictement équivalent au 112 — même standard, mêmes opérateurs, qui demandent ensuite
        // quel service on veut. (Le numéro spécifique de la police britannique est le 101, et il
        // n'a rien à faire ici : il est NON URGENT.)
        //
        // Il a ensuite été retiré, puisque le 112 aboutit au même endroit. C'était une erreur de
        // sens inverse : un Britannique cherche le 999, pas le 112, et une table « par pays » qui
        // n'affiche rien de national au Royaume-Uni ne fait pas son travail. Il revient donc,
        // avec un libellé qui dit ce qu'il fait.
        "gb" to listOf(EU, Dial(Service.NATIONAL, "999")),
        "ie" to listOf(EU, Dial(Service.NATIONAL, "999")),
    )

    /** Ce que voit un appareil sans téléphonie, sans SIM ou dans un pays non listé. */
    val PAR_DEFAUT: List<Dial> = listOf(EU)

    /** Les pays que la table couvre nommément. Le reste reçoit [PAR_DEFAUT]. */
    val PAYS_COUVERTS: Set<String> get() = PAR_PAYS.keys

    /**
     * La partie PURE de la résolution : un code pays en entrée, la liste en sortie, sans
     * Android ni téléphonie. C'est elle que les tests unitaires éprouvent — le reste n'est
     * qu'une question de savoir d'où vient le code pays.
     */
    fun pourLePays(code: String): List<Dial> = PAR_PAYS[code.lowercase()] ?: PAR_DEFAUT

    /**
     * L'ensemble FERMÉ de tous les numéros que l'application pourra composer.
     *
     * C'est lui qui remplace la liste blanche en dur d'[EmergencyCallHelper] : la propriété de
     * sécurité est conservée — aucun numéro venant d'un intent extra, de DataStore ou d'une
     * autre source non vérifiable ne peut être composé — mais elle couvre désormais tous les
     * pays de la table au lieu des seuls numéros français.
     */
    val NUMEROS_AUTORISES: Set<String> =
        (PAR_PAYS.values.flatten() + PAR_DEFAUT).map { it.number }.toSet()

    /**
     * Le code pays à deux lettres, en minuscules, ou une chaîne vide si rien ne le dit.
     * Réseau d'abord, SIM ensuite — voir l'en-tête pour le pourquoi de cet ordre.
     */
    fun pays(context: Context): String {
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
            ?: return ""
        val reseau = runCatching { tm.networkCountryIso }.getOrNull().orEmpty()
        if (reseau.isNotBlank()) return reseau.lowercase()
        val sim = runCatching { tm.simCountryIso }.getOrNull().orEmpty()
        return sim.lowercase()
    }

    /**
     * Les numéros à proposer sur cet appareil, ici et maintenant. Le 112 y est toujours, en
     * premier.
     */
    fun pour(context: Context): List<Dial> {
        val code = pays(context)
        if (code.isNotBlank() && code !in PAR_PAYS) {
            Timber.i("EmergencyNumbers: pays %s absent de la table, 112 seul", code)
        }
        return filtrerParLOS(pourLePays(code)) { reconnuParLOS(context, it) }
    }

    /**
     * Le filtre de l'OS, séparé de tout ce qui touche à Android pour qu'un test JVM pur puisse
     * l'exercer.
     *
     * **Ce qu'il garantit, et pourquoi c'est ici et pas dans [pour] :** le 112 traverse le filtre
     * QUOI QU'IL ARRIVE, parce que son test passe avant celui de l'OS et court-circuite. Cette
     * garantie tenait jusqu'ici à l'ordre des deux opérandes d'un `||` à l'intérieur de [pour] —
     * correct, mais qu'aucun test ne pouvait atteindre, [pour] exigeant un `Context`. Un refactor
     * qui aurait inversé cet ordre, ou remplacé le `||` par un `&&`, n'aurait fait rougir
     * personne. Deux audits indépendants l'ont relevé le 2026-09-17.
     */
    internal fun filtrerParLOS(dials: List<Dial>, reconnu: (String) -> Boolean): List<Dial> =
        dials.filter { it.service == Service.EUROPEAN || reconnu(it.number) }

    /**
     * Le raccourci « forces de l'ordre » du pays courant : son numéro ET le service qu'il
     * nomme réellement, pour que l'appelant puisse étiqueter ce qu'il compose.
     *
     * ⚠️ Cette fonction rendait un simple numéro, avec un repli DIRECT sur le 112. En
     * déplaçant le 999 britannique et irlandais de [Service.POLICE] vers
     * [Service.NATIONAL] — il est la ligne d'urgence générale, pas la police — ce repli
     * est devenu un mensonge silencieux : au Royaume-Uni l'action étiquetée « Police »
     * composait le 112, et le 999 n'était plus atteignable depuis l'écran verrouillé.
     * Relevé par un audit de sécurité le 2026-09-17, et c'est précisément la classe de
     * défaut que le commit précédent disait supprimer — un libellé qui ment sur son
     * propre bouton — reproduite d'un cran plus loin.
     *
     * Le repli suit maintenant le pays au lieu de l'enjamber : police du pays, sinon sa
     * ligne nationale, sinon le 112. Il n'y a aucun pays où l'on compose moins bien
     * qu'avant, et deux où l'on compose enfin le bon numéro.
     */
    fun raccourciForcesDeLOrdre(context: Context): Dial = raccourciDans(pour(context))

    /**
     * La partie pure de [raccourciForcesDeLOrdre], extraite pour être testable sans
     * `Context` ni téléphonie — une propriété de sécurité qu'on ne peut pas tester n'en
     * est pas une. Même geste que [filtrerParLOS].
     */
    internal fun raccourciDans(dials: List<Dial>): Dial =
        dials.firstOrNull { it.service == Service.POLICE }
            ?: dials.firstOrNull { it.service == Service.NATIONAL }
            ?: EU

    /**
     * L'avis du système sur un numéro, quand il peut le donner. Voir l'en-tête : en cas de
     * doute on garde le numéro — masquer une tuile d'urgence par prudence serait la prudence
     * retournée contre l'utilisateur.
     */
    @SuppressLint("MissingPermission") // isEmergencyNumber n'exige aucune permission.
    private fun reconnuParLOS(context: Context, numero: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
            ?: return true
        return runCatching { tm.isEmergencyNumber(numero) }
            .onFailure { Timber.d(it, "EmergencyNumbers: l'OS n'a pas pu juger %s", numero) }
            .getOrDefault(true)
    }
}
