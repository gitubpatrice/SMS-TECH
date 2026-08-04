package com.filestech.sms.pdu

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * v1.27.2 — robustesse du parseur PDU face à une entrée hostile.
 *
 * ⚠️ **Ce parseur lit une entrée que personne ne contrôle.** Le PDU arrive du réseau de
 * l'opérateur, via un receveur, et l'expéditeur choisit son contenu. Une longueur annoncée n'est
 * donc qu'une *prétention* : la croire sur parole, c'est laisser un tiers décider de la mémoire
 * que l'application alloue.
 *
 * Le défaut fermé en v1.27.2 : `parseParts` allouait `new byte[headersLength]` et
 * `new byte[dataLength]` AVANT toute vérification, sur deux valeurs lues dans le PDU lui-même.
 * Un MMS malformé — ou piégé — annonçant deux milliards d'octets faisait tomber le processus en
 * `OutOfMemoryError`, emportant le message au passage. Les deux longueurs sont désormais bornées
 * par ce qui reste réellement à lire.
 *
 * Ces tests sont **déterministes** : le générateur pseudo-aléatoire part d'une graine fixe, donc
 * un échec est reproductible à l'identique.
 */
class PduParserRobustnessTest {

    private companion object {
        /** `PduHeaders.MESSAGE_TYPE` (0x8C) suivi de `MESSAGE_TYPE_RETRIEVE_CONF` (0x84). */
        val RETRIEVE_CONF_HEADER = byteArrayOf(0x8C.toByte(), 0x84.toByte())

        /** Graine fixe : le fuzz doit être rejouable, pas surprenant. */
        const val SEED = 20260804L
        const val FUZZ_ROUNDS = 3_000
    }

    /**
     * Encode un entier en `uintvar` : 7 bits utiles par octet, bit de poids fort à 1 tant qu'il
     * reste des octets. C'est le format que `parseUintvarInteger` décode.
     */
    private fun uintvar(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        val septets = ArrayList<Int>()
        var v = value
        while (v > 0) {
            septets.add((v and 0x7F).toInt())
            v = v shr 7
        }
        septets.reverse()
        return ByteArray(septets.size) { i ->
            val last = i == septets.size - 1
            (if (last) septets[i] else septets[i] or 0x80).toByte()
        }
    }

    // ──────────── La régression exacte : une longueur annoncée démesurée ────────────

    @Test fun `a part claiming two billion header bytes does not exhaust memory`() {
        // RetrieveConf + 1 partie + headersLength = 2 147 483 647 + dataLength = 0.
        // Avant le correctif : `new byte[2147483647]` → OutOfMemoryError immédiat.
        val pdu = RETRIEVE_CONF_HEADER +
            uintvar(1) +
            uintvar(Int.MAX_VALUE.toLong()) +
            uintvar(0)

        // L'assertion est l'ABSENCE de OutOfMemoryError : si le parseur alloue ce qu'on lui
        // annonce, ce test tombe le processus au lieu d'échouer proprement — ce qui reste un
        // signal, et c'est bien le comportement qu'on interdit.
        val parsed = PduParser(pdu).parse()

        // Le PDU est tronqué : le parseur doit rendre quelque chose d'exploitable ou rien,
        // jamais une partie fabriquée à partir d'octets qui n'existent pas.
        if (parsed is RetrieveConf) {
            assertThat(parsed.body?.partsNum ?: 0).isEqualTo(0)
        }
    }

    @Test fun `a part claiming two billion data bytes does not exhaust memory`() {
        // Le jumeau : `dataLength` était tout aussi peu borné que `headersLength`, deux lignes
        // plus bas. Le rapport externe n'avait signalé que le premier.
        val pdu = RETRIEVE_CONF_HEADER +
            uintvar(1) +
            uintvar(1) +
            byteArrayOf(0x00) +
            uintvar(Int.MAX_VALUE.toLong())

        val parsed = PduParser(pdu).parse()

        if (parsed is RetrieveConf) {
            assertThat(parsed.body?.partsNum ?: 0).isEqualTo(0)
        }
    }

    @Test fun `an absurd part count terminates instead of spinning`() {
        // `parts` vient lui aussi du PDU. Combiné à des longueurs nulles, un compteur démesuré
        // faisait tourner la boucle de lecture sans jamais progresser.
        val pdu = RETRIEVE_CONF_HEADER + uintvar(Int.MAX_VALUE.toLong())

        val parsed = PduParser(pdu).parse()

        assertThat(parsed).isNotNull()
    }

    // ──────────── Troncature : tout préfixe d'un PDU valide doit être digéré ────────────

    @Test fun `every truncation of a well-formed pdu is handled`() {
        val full = RETRIEVE_CONF_HEADER +
            uintvar(1) +
            uintvar(3) +
            uintvar(4) +
            byteArrayOf(0x01, 0x02, 0x03) +
            byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)

        // Chaque préfixe simule une coupure réseau ou un PDU tronqué par la passerelle.
        for (len in 0..full.size) {
            val truncated = full.copyOfRange(0, len)
            // Aucune exception ne doit s'échapper : le receveur enveloppe déjà l'appel, mais
            // une erreur non gérée ici signifierait qu'un MMS coupé fait perdre le message.
            PduParser(truncated).parse()
        }
    }

    // ──────────── Fuzz : des octets arbitraires ne doivent rien casser ────────────

    @Test fun `random byte arrays never throw an unhandled exception`() {
        val random = Random(SEED)
        repeat(FUZZ_ROUNDS) { round ->
            val size = random.nextInt(64)
            val bytes = ByteArray(size).also(random::nextBytes)
            try {
                PduParser(bytes).parse()
            } catch (t: Throwable) {
                throw AssertionError(
                    "Tour $round : ${t.javaClass.simpleName} sur ${bytes.size} octets " +
                        "(${bytes.joinToString(" ") { "%02x".format(it) }})",
                    t,
                )
            }
        }
    }

    @Test fun `random payloads behind a valid RetrieveConf header never throw`() {
        // Plus ciblé que le fuzz nu : l'en-tête est valide, donc le corps est réellement
        // atteint. C'est là que vivent les allocations bornées en v1.27.2.
        val random = Random(SEED + 1)
        repeat(FUZZ_ROUNDS) { round ->
            val payload = ByteArray(random.nextInt(48)).also(random::nextBytes)
            val pdu = RETRIEVE_CONF_HEADER + payload
            try {
                PduParser(pdu).parse()
            } catch (t: Throwable) {
                throw AssertionError(
                    "Tour $round : ${t.javaClass.simpleName} sur corps de ${payload.size} octets " +
                        "(${payload.joinToString(" ") { "%02x".format(it) }})",
                    t,
                )
            }
        }
    }

    @Test fun `empty and single byte inputs are handled`() {
        // Un flux vide traverse `parseHeaders` sans rien lire (la boucle est gardée par
        // `available() > 0`) et ressort en `GenericPdu` sans type de message. On vérifie donc
        // qu'il ne lève rien et ne fabrique pas de corps, pas qu'il rend `null`.
        val empty = PduParser(ByteArray(0)).parse()
        assertThat(empty).isNotInstanceOf(RetrieveConf::class.java)

        for (b in 0..255) {
            PduParser(byteArrayOf(b.toByte())).parse()
        }
    }
}
