package com.filestech.sms.data.sms

import android.telephony.PhoneNumberUtils
import com.filestech.sms.core.ext.WireAddress
import com.filestech.sms.core.ext.blockKey
import com.filestech.sms.core.ext.phoneAddressesMatch
import com.filestech.sms.core.ext.stripMmsAddressSuffix
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.27.2 (audit Codex du 2026-08-05, C-07 / C-08) — **l'identité d'un correspondant**, et le seul
 * endroit du dépôt qui décide si deux écritures désignent la même personne.
 *
 * # Le défaut que cette classe ferme
 *
 * Le rapprochement se faisait sur les **neuf derniers chiffres**. Neuf chiffres ne portent aucune
 * information de pays :
 *
 * ```
 * "+1 212 345 6789"  →  123456789
 * "01 23 45 67 89"   →  123456789
 * ```
 *
 * Conséquences constatées, toutes réelles :
 *  - composer vers l'un ouvrait la conversation de l'autre, et **le message partait au mauvais
 *    destinataire** ;
 *  - à l'import, les deux historiques fusionnaient ;
 *  - 🔴 côté liste noire, bloquer `+33612345678` faisait rejeter les SMS de `+15612345678` — et le
 *    curseur d'import avançait quand même sur la ligne rejetée, donc **le message d'un tiers non
 *    bloqué était perdu définitivement**, pas seulement retardé.
 *
 * # Pourquoi ici, et une seule fois
 *
 * La règle a besoin de la **région** (SIM, ou le réglage « Indicatif pays par défaut ») pour
 * canonicaliser une forme nationale. `core` doit rester sans dépendance Android, et le
 * rapprochement est utilisé depuis six endroits — quatre replis de conversation, la réception, les
 * imports. En câbler cinq sur six est le motif de défaut qui revient le plus souvent sur ce dépôt.
 *
 * # Aucune migration de données
 *
 * `blocked_numbers` conserve déjà `raw_number` à côté de sa clé. La désambiguïsation se fait donc
 * **à la comparaison**, sur la forme brute stockée : rien à réécrire dans la base de l'utilisateur,
 * et les entrées existantes deviennent correctes sans qu'il ait à retoucher sa liste.
 */
@Singleton
class PhoneIdentity @Inject constructor(
    private val wireFormatter: PhoneNumberWireFormatter,
) {

    /**
     * Forme canonique E.164 de [raw], ou `null` si elle ne peut pas être établie avec certitude —
     * région indéterminable, numéro invalide pour cette région, expéditeur alphanumérique, code
     * court.
     *
     * ⚠️ `null` n'est pas un repli permissif : les appelants **échouent fermé** dessus.
     */
    fun canonical(raw: String): String? {
        val trimmed = raw.stripMmsAddressSuffix().trim()
        if (trimmed.isEmpty()) return null
        return WireAddress.toE164OrRaw(trimmed, wireFormatter.defaultRegionIso()) { number, region ->
            PhoneNumberUtils.formatNumberToE164(number, region)
        }.takeIf { it.startsWith("+") }
    }

    /** Ces deux écritures désignent-elles le **même** correspondant ? */
    fun matches(a: String, b: String): Boolean = phoneAddressesMatch(a, b) { canonical(it) }

    /**
     * Construit un prédicat « cette adresse est-elle bloquée ? » à partir des formes **brutes**
     * enregistrées, en résolvant la région **une seule fois**.
     *
     * Le seau [blockKey] sert d'index : sans lui, chaque message entrant comparerait son adresse à
     * toute la liste noire. Avec lui, on ne canonicalise que les candidats du même seau — au plus
     * un ou deux.
     */
    fun blockedMatcher(blockedRaw: Collection<String>): (String) -> Boolean {
        if (blockedRaw.isEmpty()) return { false }
        val byKey = HashMap<String, MutableList<String>>(blockedRaw.size)
        for (raw in blockedRaw) {
            val key = raw.stripMmsAddressSuffix().blockKey()
            if (key.isEmpty()) continue
            byKey.getOrPut(key) { mutableListOf() } += raw
        }
        if (byKey.isEmpty()) return { false }
        return { address ->
            val key = address.stripMmsAddressSuffix().blockKey()
            byKey[key]?.any { matches(it, address) } == true
        }
    }
}
