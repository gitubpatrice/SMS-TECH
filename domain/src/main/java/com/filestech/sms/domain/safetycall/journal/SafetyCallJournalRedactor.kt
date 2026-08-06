package com.filestech.sms.domain.safetycall.journal

import com.filestech.sms.core.ext.phoneIdentityKey
import java.security.MessageDigest

/**
 * v1.28.0 — réduit un numéro de destinataire à un jeton **comparable mais non lisible**, pour le
 * journal technique du Safety call.
 *
 * ⚠️ **Non câblé à ce stade** — voir [SafetyCallJournalEvent].
 *
 * # Pourquoi une empreinte salée, et non une simple troncature
 *
 * La question à laquelle le journal doit répondre est : **« la ligne du dessus visait-elle le même
 * contact ? »** — c'est ainsi, et uniquement ainsi, qu'on prouve qu'aucun proche n'a reçu deux fois
 * la même relance. Il faut donc de l'**identité**.
 *
 * Une troncature seule échouerait des deux côtés à la fois :
 *  - deux numéros différents partageant leurs derniers chiffres seraient **confondus**, et un vrai
 *    doublon passerait pour deux contacts distincts — ou l'inverse ;
 *  - et les chiffres conservés resteraient lisibles.
 *
 * L'empreinte donne l'identité sans la divulgation. Les deux derniers chiffres sont conservés
 * **en plus**, parce qu'un jeton entièrement opaque rend le journal illisible à l'œil : reconnaître
 * « celui qui finit par 41 » est ce qui permet de suivre une séquence sans dérouler la liste des
 * contacts.
 *
 * # Normalisation : [phoneIdentityKey], et surtout PAS `blockKey()`
 *
 * L'identité repose sur [phoneIdentityKey], la clé d'identité de correspondant du dépôt : libellé en
 * minuscules pour un expéditeur alphanumérique, sinon **égalité E.164**, avec repli fermé quand la
 * canonicalisation est impossible.
 *
 * 🔴 **Une première version utilisait `blockKey()`, et c'était un défaut** (relecture Gemini du
 * 2026-08-06). `blockKey()` est la clé canonique de la **liste noire**, qui répond à une autre
 * question : « ai-je déjà vu cet expéditeur ? ». Elle ne retient que les neuf derniers chiffres, qui
 * ne portent **aucune information de pays** — le dépôt le documente lui-même :
 *
 * ```
 * "+33 6 12 34 56 78"  →  612345678
 * "+1 561 234 5678"    →  612345678     ← MÊME CLÉ, DEUX PERSONNES
 * ```
 *
 * Sur un journal dont la raison d'être est de prouver qu'**aucun proche n'a reçu deux fois la même
 * relance**, cette collision produit exactement le contresens à ne pas avoir : deux destinataires
 * distincts portant le même jeton se liraient comme un doublon. Et dans l'autre sens, hors du plan de
 * numérotation français, deux écritures du même numéro se seraient scindées en deux jetons.
 *
 * La leçon dépasse ce fichier : réutiliser « la clé canonique » ne suffit pas, il faut réutiliser
 * celle qui répond à **la même question**. Ce dépôt en a deux, et j'avais pris l'autre.
 *
 * @see phoneIdentityKey
 *
 * # Ce que le sel protège, et ce qu'il ne protège pas
 *
 * Le sel vit dans le bac à sable de l'application, **au même endroit que le journal** : il ne protège
 * donc rien contre quelqu'un qui a déjà accès à ce bac à sable. Ce qu'il protège, c'est le journal
 * **exporté** — celui qui sort par l'action de partage et cesse d'être sous la garde de
 * l'application. Sans le sel, un jeton n'est pas inversible.
 *
 * Chiffrage honnête si le sel fuitait *aussi* : 16 bits d'empreinte et 2 chiffres de queue laissent,
 * sur l'espace des mobiles français, de l'ordre de **quelques dizaines de candidats** par jeton. Ce
 * n'est pas une garantie cryptographique, et la documentation ne doit pas la présenter comme telle.
 *
 * # Le repli échoue du bon côté
 *
 * Un sel absent ou trop court **n'entraîne pas** un repli en clair : [redact] rend alors
 * [OPAQUE_TOKEN], qui ne contient aucun chiffre du numéro. C'est le motif de défaut le plus fréquent
 * du dépôt — *le repli qui échoue du mauvais côté* — et sur une fonction de contrainte, un journal
 * dégradé en clair serait exactement l'accident à ne pas avoir.
 */
object SafetyCallJournalRedactor {

    /**
     * Longueur minimale du sel, en caractères. Un sel de 16 octets aléatoires encodés en hexadécimal
     * en fait 32 ; la borne est volontairement basse pour ne rejeter que l'absence et l'erreur de
     * câblage, pas un encodage plus compact.
     */
    const val SALT_MIN_LENGTH: Int = 16

    /** Jeton rendu quand le numéro ou le sel manque. **Aucun chiffre du numéro n'y figure.** */
    const val OPAQUE_TOKEN: String = "????‥??"

    /** Séparateur visuel entre l'empreinte et la queue. Ni un `|` ni un caractère de contrôle : il
     * traverse l'assainissement de [SafetyCallJournalEntry] sans être retiré. */
    private const val JOIN = '‥'

    /**
     * Ferme la frontière sel/identité dans la donnée hachée : sans lui, `("ab", "cd")` et
     * `("a", "bcd")` produiraient la même empreinte.
     *
     * Le dièse est choisi parce qu'il **ne peut apparaître d'aucun côté** — ni dans un sel
     * hexadécimal (`[0-9a-f]`), ni dans une clé rendue par `blockKey()`, qui ne produit que des
     * chiffres ou des minuscules. La frontière est donc sans ambiguïté, et vérifiable à la lecture
     * sans avoir à faire confiance à un échappement invisible.
     */
    private const val SALT_IDENTITY_SEPARATOR = "#"

    /** Octets d'empreinte conservés → 4 caractères hexadécimaux. Assez pour distinguer 1 à 4
     * contacts sans ambiguïté, assez court pour qu'une ligne reste lisible. */
    private const val DIGEST_BYTES = 2

    private const val TAIL_LENGTH = 2

    /**
     * @param address numéro ou expéditeur, sous n'importe quelle forme brute.
     * @param salt sel propre à l'installation. **Ne doit jamais être exporté avec le journal.**
     * @param toE164 canonicalisation vers E.164, injectée — `domain` et `core` restent sans
     *   dépendance Android. Rend `null` quand elle est impossible : région inconnue, numéro invalide.
     *   Côté `data`, c'est le canonicaliseur de `PhoneIdentity` qu'il faut passer, jamais une
     *   n-ième variante locale. Signature alignée sur [phoneIdentityKey] et `phoneAddressesMatch`,
     *   qui prennent déjà ce résolveur en paramètre.
     * @return un jeton de la forme `a3f1‥41`, ou [OPAQUE_TOKEN] si l'entrée ne permet pas mieux.
     */
    fun redact(address: String, salt: String, toE164: (String) -> String?): String {
        val identity = phoneIdentityKey(address, toE164)
        if (identity.isBlank() || salt.length < SALT_MIN_LENGTH) return OPAQUE_TOKEN
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((salt + SALT_IDENTITY_SEPARATOR + identity).toByteArray(Charsets.UTF_8))
        val prefix = digest.take(DIGEST_BYTES).joinToString("") { b -> "%02x".format(b) }
        val tail = identity.takeLast(TAIL_LENGTH).padStart(TAIL_LENGTH, '?')
        return "$prefix$JOIN$tail"
    }
}
