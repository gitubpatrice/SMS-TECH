package com.filestech.sms.data.sms

import android.telephony.PhoneNumberUtils
import com.filestech.sms.core.ext.phoneIdentityKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.27.2 — **l'identité d'un correspondant**, et le seul endroit du dépôt qui décide si deux
 * écritures désignent la même personne.
 *
 * # Le défaut d'origine (audit Codex, C-07 / C-08)
 *
 * Le rapprochement se faisait sur les **neuf derniers chiffres**, qui ne portent aucune
 * information de pays : `+33 6 12 34 56 78` et `+1 561 234 5678` partageaient leur clé. Composer
 * vers l'un ouvrait la conversation de l'autre — **message au mauvais destinataire** — et côté
 * liste noire, bloquer l'un faisait rejeter les messages de l'autre, **définitivement**, le
 * curseur d'import avançant sur la ligne écartée.
 *
 * # 🔴 Pourquoi il faut un INSTANTANÉ, et pas une lecture à la volée (audit Codex final, F-01)
 *
 * La première version appelait `defaultRegionIso()`, qui lit `settings.state.value`. Or ce
 * `StateFlow` démarre sur la configuration **par défaut** et n'est hydraté qu'ensuite, de façon
 * asynchrone. C'est très exactement le piège qui rendait le Safety call muet : un chemin réveillé
 * par le système lisait des valeurs par défaut en croyant lire les réglages.
 *
 * Conséquence ici : sur un import à froid, le réglage « Indicatif pays par défaut » pouvait être
 * ignoré au profit du pays de la SIM. Une SIM étrangère avec un override français canonicalisait
 * alors ses `06…` avec la mauvaise région — doublons de conversation **permanents**, et expéditeur
 * réellement bloqué traité comme non bloqué.
 *
 * [snapshot] franchit donc la barrière `hydratedOrNull()` **une seule fois**, puis fige la région
 * pour toute l'opération. Les appelants prennent un instantané en tête de passe et ne consultent
 * plus rien : la même décision vaut du premier au dernier message.
 */
@Singleton
class PhoneIdentity @Inject constructor(
    private val wireFormatter: PhoneNumberWireFormatter,
) {

    /**
     * Résout la région **après hydratation** des réglages, puis rend un instantané figé.
     *
     * ⚠️ Suspend, et c'est le point : toute décision d'identité doit attendre cette barrière.
     */
    suspend fun snapshot(): Snapshot = Snapshot(wireFormatter.hydratedDefaultRegionIso())

    /**
     * Décision d'identité à région **figée**. Pure une fois construite : aucune lecture de
     * réglages, donc aucun risque de changer d'avis au milieu d'un import.
     */
    class Snapshot(private val regionIso: String?) {

        /**
         * `true` si la région a pu être établie. Quand elle ne l'est pas, les formes nationales ne
         * se canonicalisent pas et le rapprochement se dégrade vers un refus — un doublon de
         * conversation visible plutôt qu'un message au mauvais destinataire.
         */
        val regionKnown: Boolean get() = !regionIso.isNullOrBlank()

        /** Forme canonique E.164, ou `null` si elle ne peut pas être établie avec certitude. */
        fun canonical(raw: String): String? {
            // 🔴 v1.27.2 (audit Codex du 2026-08-05, LP-04) — NE PAS REUTILISER `toE164OrRaw` ICI.
            //
            // Son contrat est celui du chemin d'ENVOI : « ne jamais casser un envoi », donc elle
            // retombe volontairement sur la chaine BRUTE quand la conversion echoue. Je m'en
            // servais en la filtrant sur `startsWith("+")` — et j'acceptais donc le brut ponctue
            // comme s'il etait une canonicalisation reussie :
            //
            //     Snapshot(null).key("+33 6 12 34 56 78") = "+33 6 12 34 56 78"
            //     Snapshot(null).key("+33612345678")      = "+33612345678"
            //
            // Deux cles DIFFERENTES pour le meme numero, sur un appareil ou ni la SIM ni le reseau
            // ne donnent la region. Le repli de `phoneIdentityKey` — qui ne garde que les chiffres,
            // justement pour eviter ca — n'etait jamais atteint, puisque je lui presentais un
            // succes.
            //
            // Deux contrats differents demandent deux fonctions differentes. Ici on rend `null` des
            // que la conversion n'a pas REELLEMENT eu lieu, et le repli fait son travail.
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            // ISO 3166-1 alpha-2 stricte : tout le reste signifie « aucune region fiable ».
            val region = regionIso?.trim()?.uppercase()
                ?.takeIf { iso -> iso.length == 2 && iso.all { it in 'A'..'Z' } }
                ?: return null
            return runCatching { PhoneNumberUtils.formatNumberToE164(trimmed, region) }
                .getOrNull()
                ?.takeIf { it.length > 1 && it.startsWith("+") }
        }

        /** Clé d'identité stockable et comparable. Voir [phoneIdentityKey]. */
        fun key(raw: String): String = phoneIdentityKey(raw) { canonical(it) }

        /** Ces deux écritures désignent-elles le **même** correspondant ? */
        fun matches(a: String, b: String): Boolean {
            val ka = key(a)
            return ka.isNotEmpty() && ka == key(b)
        }

        /**
         * Prédicat « cette adresse est-elle bloquée ? », construit une fois par passe.
         *
         * v1.27.2 (audit Codex final, F-02 / F-04) — l'index est désormais dérivé de la **clé
         * d'identité**, plus du seau de neuf chiffres. Deux gains : la comparaison ne peut plus
         * refuser une équivalence E.164 valide hors de France, et l'adresse entrante n'est
         * canonicalisée **qu'une seule fois** au lieu d'une fois par candidat.
         */
        fun blockedMatcher(blockedRaw: Collection<String>): (String) -> Boolean {
            if (blockedRaw.isEmpty()) return { false }
            val keys = HashSet<String>(blockedRaw.size)
            for (raw in blockedRaw) key(raw).takeIf { it.isNotEmpty() }?.let { keys += it }
            if (keys.isEmpty()) return { false }
            return { address -> key(address).takeIf { it.isNotEmpty() } in keys }
        }
    }
}
