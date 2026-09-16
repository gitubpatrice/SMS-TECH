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

    /** Le service appelé, qui porte le libellé. Le NUMÉRO, lui, dépend du pays. */
    enum class Service { EUROPEAN, POLICE, FIRE, MEDICAL }

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
        // Royaume-Uni — 999 historique, 112 reconnu partout.
        "gb" to listOf(EU, Dial(Service.POLICE, "999")),
        // Irlande — idem.
        "ie" to listOf(EU, Dial(Service.POLICE, "999")),
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
        return pourLePays(code)
            .filter { it.service == Service.EUROPEAN || reconnuParLOS(context, it.number) }
    }

    /** Le numéro de police du pays courant, ou le 112 quand le pays n'en publie pas d'autre. */
    fun police(context: Context): String =
        pour(context).firstOrNull { it.service == Service.POLICE }?.number ?: EU.number

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
