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
     */
    suspend operator fun invoke(messageId: Long, emoji: String): Outcome<List<Long>> {
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
            body = emoji,
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
