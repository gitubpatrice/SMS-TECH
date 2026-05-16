package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * v1.3.1 — envoie une réaction emoji à **l'expéditeur du message réagi** comme un SMS
 * texte standard contenant uniquement l'emoji (ex. "❤️"). Délègue à [SendSmsUseCase]
 * pour la dispatch réelle (mêmes guards : default SMS app, blocklist, side-effects
 * provider + Room mirror).
 *
 * Sécurité (audit v1.3.1) :
 *
 *   - **F1** : refuse les réactions sur messages **sortants**. Si l'utilisateur réagit
 *     à son propre message, on n'envoie RIEN au destinataire d'origine (ce serait un
 *     SMS sorti de nulle part pour lui : "❤️" sans contexte).
 *   - **F2** : cible **uniquement le numéro qui a envoyé le message réagi**
 *     ([com.filestech.sms.domain.model.Message.address]), pas toute la conversation.
 *     Sans cette garde, réagir à un message dans un groupe (Alice/Bob/Charlie) enverrait
 *     "❤️" aux 3 → spam + leak de comportement intra-groupe + facturation ×N.
 *   - **F3** : passe `appendSignature = false` à [SendSmsUseCase] pour qu'un emoji
 *     reste un emoji seul (sinon "❤️\n--\nPat" bascule en SMS multi-part et pollue
 *     le fil du correspondant).
 *
 * Le caller (typiquement [com.filestech.sms.ui.screens.thread.ThreadViewModel]) reste
 * responsable :
 *
 *   - de vérifier que [com.filestech.sms.data.local.datastore.SendingSettings
 *     .sendReactionsToRecipient] est activée,
 *   - d'avoir affiché le dialog de confirmation au premier envoi si nécessaire,
 *   - de ne PAS invoquer ce use case pour un retrait
 *     ([com.filestech.sms.domain.repository.SetReactionResult.Removed]) ni un changement
 *     ([com.filestech.sms.domain.repository.SetReactionResult.Changed]).
 *
 * Erreurs propagées sans transformation depuis [SendSmsUseCase].
 */
class SendReactionUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val conversationRepo: ConversationRepository,
) {
    /**
     * Envoie [emoji] à l'expéditeur du message [messageId]. Retourne :
     *
     *   - [Outcome.Failure] avec [AppError.Validation] si le message n'existe plus
     *     (purgé/supprimé entre-temps), s'il est sortant (cas F1), si son adresse est
     *     vide/invalide, ou si [emoji] est vide.
     *   - Le [Outcome] de [SendSmsUseCase] (Success avec l'id Room du SMS envoyé, ou
     *     Failure propagée).
     *
     * @param emojiOnly v1.3.6 — si `true`, envoie l'emoji seul (ex. "❤️") au lieu du
     *   format Apple/Google Tapback "Reacted ❤️ to «aperçu»". Voir
     *   [com.filestech.sms.data.local.datastore.SendingSettings.reactionEmojiOnly] pour
     *   le trade-off (compat iPhone/Google récent vs propreté legacy).
     */
    suspend operator fun invoke(
        messageId: Long,
        emoji: String,
        emojiOnly: Boolean = false,
    ): Outcome<List<Long>> {
        if (emoji.isBlank()) return Outcome.Failure(AppError.Validation("empty reaction emoji"))

        val message = conversationRepo.findMessageById(messageId)
            ?: return Outcome.Failure(AppError.Validation("message not found: $messageId"))

        // F1 — pas d'écho sur un message qu'on a soi-même envoyé : le destinataire ne
        // verrait qu'un emoji isolé sans aucun lien causal.
        if (message.isOutgoing) {
            return Outcome.Failure(AppError.Validation("cannot send reaction for outgoing message"))
        }

        // F2 — l'expéditeur du message est `message.address` (en SMS, c'est le numéro qui
        // a écrit le message qu'on lit). Pour un message reçu dans une conv 1-1 ou de
        // groupe, c'est cohérent : on ne notifie QUE cet expéditeur, jamais les autres
        // participants du groupe.
        val senderRaw = message.address.trim()
        if (senderRaw.isEmpty()) {
            return Outcome.Failure(AppError.Validation("message has no sender address"))
        }

        // X1 (CRITICAL audit v1.3.1) — refuser les expéditeurs alphanumériques (banques,
        // Free, INFO, AMAZON…) ET les short codes <4 chiffres. Sans ce garde, l'envoi
        // partirait soit vers un destinataire indéfini (rejet stack SmsManager), soit
        // vers le short code de notification facturé PREMIUM (1,50 €+). En France les
        // SMS bancaires / livraison / 2FA sont presque tous alpha → réagir à un SMS de
        // sa banque avec ❤️ ne doit JAMAIS déclencher d'envoi.
        if (!senderRaw.isDialablePhoneNumber()) {
            return Outcome.Failure(
                AppError.Validation("sender is not a dialable phone number: $senderRaw"),
            )
        }

        val target = PhoneAddress.of(senderRaw)
        if (target.normalized.isEmpty()) {
            return Outcome.Failure(AppError.Validation("invalid sender address: $senderRaw"))
        }

        return sendSms(
            recipients = listOf(target),
            // v1.3.2 — format Apple/Google Tapback : "Reacted ❤️ to «aperçu»". Détecté
            // automatiquement par iMessage (iPhone) et Google Messages récent qui
            // l'affichent comme une bulle réaction visuelle attachée au message d'origine.
            // Les autres apps SMS affichent le texte brut, qui reste compréhensible.
            //
            // v1.3.6 — quand [emojiOnly], on envoie l'emoji nu (ex. "❤️") à la place :
            // plus propre côté apps legacy (Mi Messages, vieux Samsung) mais on perd la
            // fusion bulle native sur iPhone/Google récent — chez eux l'emoji apparaît
            // comme un message isolé suivant le message d'origine. Choix exposé en
            // Réglages → Envoi → "Format des réactions".
            body = if (emojiOnly) emoji else buildTapbackBody(emoji, message.body),
            // F3 — pas de signature pour une réaction.
            appendSignature = false,
            // Pas de réponse contextuelle (la réaction est ponctuelle, ajouter un
            // replyTo polluerait le fil côté correspondant avec une quote inutile).
            replyToMessageId = null,
        )
    }


    /**
     * X1 — vrai numéro téléphonique dialable. Refuse :
     *
     *   - alphanumériques (banque, Free, INFO, AMAZON, livraisons),
     *   - short codes < 4 chiffres (numéros premium 5 chiffres : 1,50 € le SMS),
     *   - chaînes contenant des lettres ou symboles non téléphoniques.
     *
     * Acceptés : `+33612345678`, `0612345678`, `+1 (415) 555-2671`, `06.12.34.56.78`,
     * formats internationaux avec espaces/tirets/parenthèses standards.
     *
     * **Pourquoi pas `Telephony.PhoneNumberUtils.isWellFormedSmsAddress` ?** Cette API
     * accepte aussi les alphanumériques (`return isValidGsmAlphabet || isValidPhoneNumber`)
     * et ne distingue pas un sender alpha d'un dest. Notre besoin est plus strict côté
     * envoi : on veut un destinataire **dialable**, pas juste "syntaxiquement valide".
     */
    private fun String.isDialablePhoneNumber(): Boolean {
        // Refuse toute lettre ASCII (alphanumeric senders) — un vrai numéro n'en a pas.
        if (any { it in 'A'..'Z' || it in 'a'..'z' }) return false
        // Tous les caractères restants doivent être : digits, +, espace, tiret, point,
        // parenthèses. Refuse tout autre symbole exotique.
        if (!matches(Regex("^[+0-9 .()\\-]+$"))) return false
        // Au moins 4 digits pour exclure les short codes premium courts (32665, etc.).
        // Les vrais numéros mobiles font ≥10 digits ; on garde une marge confortable à 4
        // pour ne pas exclure des numéros internationaux courts légitimes (exotiques).
        return count { it.isDigit() } >= 4
    }

}

/**
 * v1.3.2 — cap UCS-2 d'un segment SMS unique. Les guillemets typographiques «» forcent
 * l'encodage UCS-2 (cap 70 chars/segment vs 160 GSM-7). Au-delà, le SMS bascule en
 * multi-part = facturation ×2 chez certains opérateurs et perte du parsing Apple/Google
 * qui attend une seule trame.
 */
private const val SMS_UCS2_SEGMENT_CAP = 70

/** v1.3.2 — wrap fixe `"Reacted "` (8) + `" to «"` (5) + `"»"` (1) + `"…"` éventuel (1). */
private const val TAPBACK_WRAP_LENGTH = 8 + 5 + 1 + 1

/**
 * v1.3.2 — borne SUPÉRIEURE stricte de l'aperçu, indépendante de l'emoji. Limite
 * conservatrice : un emoji multi-codepoint (drapeau composé = 4 UTF-16 units) ne doit
 * jamais faire déborder le segment. On calcule le cap réel via [previewBudget].
 */
private const val PREVIEW_HARD_MAX = 50

/**
 * v1.3.2 — caractères Unicode strippés du body avant injection dans le tapback :
 *
 *   - **C0/C1 controls** (` -`, `-`) : CR/LF/NUL/BEL etc.
 *     pourraient splitter la trame SMS ou injecter un préfixe trompeur.
 *   - **Line/Paragraph separators** (` `, ` `) : sauts de ligne Unicode
 *     que `\\s+` ne capturait pas seul.
 *   - **Bidi controls** (`‎`/`‏`, `‪-‮`, `⁦-⁩`) : un
 *     `‮` RLO injecté inverse visuellement le rendu — `Reacted ❤️ to «‮Hello»`
 *     s'affiche `«olleH»` côté correspondant, défaisant le parsing iMessage.
 *   - **BOM** (`﻿`) : zero-width invisible.
 */
private val FORBIDDEN_BODY_CHARS = Regex(
    "[\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"
)

/**
 * v1.3.2 — construit le corps SMS au format Apple/Google Tapback. Fonction top-level
 * pour être testable sans instancier [SendReactionUseCase] (dont les dépendances
 * nécessiteraient des mocks). `internal` pour rester invisible hors du module.
 *
 * Garanties :
 *
 *   - Si [originalBody] est vide (cas MMS image pure), fallback compact
 *     `Reacted <emoji>` — la chaîne reste détectable par les parseurs Apple/Google.
 *   - Les caractères dangereux (cf. [FORBIDDEN_BODY_CHARS] : C0/C1, line separators
 *     Unicode, bidi controls RLO/LRO, BOM) sont remplacés par un espace simple ;
 *     les espaces consécutifs sont ensuite compressés (rendu propre).
 *   - L'aperçu est tronqué à un budget calculé dynamiquement
 *     (`SMS_UCS2_SEGMENT_CAP - TAPBACK_WRAP_LENGTH - emoji.length`, plafonné à
 *     [PREVIEW_HARD_MAX]) — assure 1 segment SMS UCS-2 même avec un emoji multi-
 *     codepoint (drapeau, famille ZWJ).
 *   - La troncature est **grapheme-safe** : si l'index de coupe tombe sur un high
 *     surrogate ou en plein cluster ZWJ, on recule jusqu'à un boundary propre pour
 *     ne jamais émettre un caractère corrompu côté correspondant.
 *   - Le format texte est ASCII pur côté wrap (`Reacted` / `to`) pour matcher
 *     EXACTEMENT la signature parsée par Apple/Google ; traduire en français
 *     casserait la détection automatique côté iPhone.
 */
internal fun buildTapbackBody(emoji: String, originalBody: String): String {
    val sanitized = originalBody
        .replace(FORBIDDEN_BODY_CHARS, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (sanitized.isEmpty()) {
        return "Reacted $emoji"
    }
    val budget = previewBudget(emoji)
    val preview = if (sanitized.length <= budget) {
        sanitized
    } else {
        sanitized.safeTake(budget).trimEnd() + "…"
    }
    return "Reacted $emoji to «$preview»"
}

/**
 * v1.3.2 — budget chars UTF-16 pour l'aperçu, calculé pour que le SMS final tienne
 * dans 1 segment UCS-2. Plafonné à [PREVIEW_HARD_MAX] pour préserver une marge
 * sécurité même si un futur emoji descendrait `emoji.length` à 0.
 */
private fun previewBudget(emoji: String): Int =
    (SMS_UCS2_SEGMENT_CAP - TAPBACK_WRAP_LENGTH - emoji.length)
        .coerceAtMost(PREVIEW_HARD_MAX)
        .coerceAtLeast(8) // garantie absolue de garder un peu de contexte

/**
 * v1.3.2 — `String.take` grapheme-safe : recule l'index si on coupe en plein milieu
 * d'un surrogate pair (emoji 4-byte) ou immédiatement après un ZWJ (cluster non
 * terminé). Évite de produire un `�` ou un emoji visuellement cassé côté
 * destinataire — ce qui ferait basculer le SMS en 2 segments ET ferait échouer le
 * parsing iMessage qui attend une trame intacte.
 */
private fun String.safeTake(maxChars: Int): String {
    if (length <= maxChars) return this
    var cut = maxChars
    // 1) si on coupe pile entre les deux moitiés d'un surrogate pair, recule d'1.
    if (cut > 0 && Character.isHighSurrogate(this[cut - 1])) cut--
    // 2) si le dernier caractère est un ZWJ ou un Variation Selector, le cluster
    //    n'est pas terminé — recule jusqu'à un boundary propre (au pire, 4 cran).
    var safetyHops = 0
    while (cut > 0 && safetyHops < 4) {
        val ch = this[cut - 1]
        val code = ch.code
        val isJoinerOrSelector = code == 0x200D || code in 0xFE00..0xFE0F
        if (!isJoinerOrSelector) break
        cut--
        safetyHops++
    }
    return substring(0, cut)
}
