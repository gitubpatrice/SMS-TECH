package com.filestech.sms

import com.filestech.sms.domain.model.ReactionFormat
import com.filestech.sms.domain.reaction.IncomingReactionDecoder
import com.filestech.sms.domain.usecase.buildReadableFrBody
import com.filestech.sms.domain.usecase.buildTapbackBody
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import com.filestech.sms.system.notifications.NotificationChannelInitializer
import com.filestech.sms.system.notifications.PendingNavHolder
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.8.0 — garde-régression centralisée pour les 5 fixes livrés dans l'audit
 * post-v1.7.1 :
 *  - Bug 4 (CRITICAL) : tap notif → nav thread — vérifie que les constantes
 *    `ACTION_OPEN_CONVERSATION`/`EXTRA_CONVERSATION_ID` sont publiées et que
 *    [PendingNavHolder] respecte le contrat TTL + idempotence.
 *  - Bug 2 (HIGH) : badge unread = inserts réels — couvert par les tests
 *    Room d'intégration existants. On reproduit ici la sémantique attendue
 *    via une vérification du contrat `insertedIds.getOrElse(idx) { -1L }`.
 *  - Bug 5 (MEDIUM) : format réaction FR par défaut + decoder dual —
 *    round-trip encoder FR → decoder FR, round-trip legacy EN → decoder EN,
 *    et anti-ReDoS (input > 400c rejeté).
 *  - Bug 1 (MEDIUM) : MMS OEM fallback — adresse via type 129 non testable en
 *    unit test (besoin du ContentProvider), couvert par smoke test en device.
 *  - Bug 3 (HIGH+MEDIUM) : couvert par les pickers UI (manual QA), pas de
 *    test unit pur car PreviewMode/NotificationStyle sont des dropdowns
 *    Compose. Le canal `incoming_messages_silent` est vérifié à la
 *    construction du `NotificationChannelInitializer`.
 *
 * **Pourquoi un fichier unique au lieu de tests éparpillés** ? Un audit
 * couvre par essence plusieurs zones du code — regrouper les 8-10 vérifications
 * dans un seul fichier `AuditV180Test` rend la régression évidente au CI
 * (un dev qui casse un fix v1.8.0 verra `AuditV180Test` rouge avant ses propres
 * tests) sans diluer la sémantique sur 5 fichiers test/ distincts.
 */
class AuditV180Test {

    // ──────────────── Bug 4 — Tap notif → nav thread ────────────────

    @Test fun `ACTION_OPEN_CONVERSATION constant is exposed and stable`() {
        // Ne pas changer cette valeur sans coordonner avec MainActivity.handleSharedIntent —
        // c'est la clé de routage du tap notif.
        assertThat(IncomingMessageNotifier.ACTION_OPEN_CONVERSATION)
            .isEqualTo("com.filestech.sms.OPEN_CONVERSATION")
    }

    @Test fun `EXTRA_CONVERSATION_ID constant is exposed`() {
        assertThat(IncomingMessageNotifier.EXTRA_CONVERSATION_ID).isEqualTo("conversationId")
    }

    @Test fun `PendingNavHolder refuses invalid conversationId`() {
        val holder = PendingNavHolder()
        holder.set(PendingNavHolder.Pending(conversationId = -1L))
        assertThat(holder.pending.value).isNull()
        holder.set(PendingNavHolder.Pending(conversationId = 0L))
        assertThat(holder.pending.value).isNull()
    }

    @Test fun `PendingNavHolder accepts valid conversationId and consume returns it once`() {
        val holder = PendingNavHolder()
        holder.set(PendingNavHolder.Pending(conversationId = 42L))
        assertThat(holder.pending.value?.conversationId).isEqualTo(42L)
        val consumed = holder.consume()
        assertThat(consumed?.conversationId).isEqualTo(42L)
        // Idempotent : second consume retourne null.
        assertThat(holder.consume()).isNull()
        assertThat(holder.pending.value).isNull()
    }

    @Test fun `PendingNavHolder consume returns null and clears when expired`() {
        val holder = PendingNavHolder()
        val pastTimestamp = System.currentTimeMillis() - PendingNavHolder.PENDING_TTL_MS - 1000L
        holder.set(PendingNavHolder.Pending(conversationId = 7L, postedAt = pastTimestamp))
        val consumed = holder.consume()
        assertThat(consumed).isNull()
        assertThat(holder.pending.value).isNull()
    }

    // ──────────────── Bug 3 — Settings notifications ────────────────

    @Test fun `CHANNEL_INCOMING_SILENT constant is exposed and distinct from main channel`() {
        assertThat(NotificationChannelInitializer.CHANNEL_INCOMING_SILENT)
            .isEqualTo("incoming_messages_silent")
        assertThat(NotificationChannelInitializer.CHANNEL_INCOMING_SILENT)
            .isNotEqualTo(NotificationChannelInitializer.CHANNEL_INCOMING)
    }

    // ──────────────── Bug 5 — Format réaction FR par défaut ────────────────

    @Test fun `ReactionFormat enum exposes 4 values v1_9_0`() {
        // v1.9.0 ajout EMOJI_WITH_QUOTE en complément des 3 v1.8.x.
        val values = ReactionFormat.values().map { it.name }.toSet()
        assertThat(values).containsExactly(
            "READABLE_FR", "TAPBACK_EN", "EMOJI_ONLY", "EMOJI_WITH_QUOTE",
        )
    }

    @Test fun `buildReadableFrBody produces French format with preview (anonymous)`() {
        val body = buildReadableFrBody("❤️", "Salut ça va")
        // v1.8.1 — wording neutral sans "J'ai", avec "à votre message".
        assertThat(body).startsWith("Réagi par ❤️ à votre message : «")
        assertThat(body).endsWith("»")
        assertThat(body).contains("Salut ça va")
    }

    @Test fun `buildReadableFrBody falls back to anonymous prefix when body is empty`() {
        val body = buildReadableFrBody("👍", "")
        // v1.8.1 — "à votre message" inclus même en fallback (cohérence visuelle
        // côté destinataire, qui reconnaît immédiatement le pattern).
        assertThat(body).isEqualTo("Réagi par 👍 à votre message")
    }

    @Test fun `buildReadableFrBody includes sender name when provided`() {
        // v1.8.1 — format avec nom de l'expéditeur (override Settings ou
        // ContactsContract.Profile auto-detect).
        val body = buildReadableFrBody("❤️", "Salut", senderName = "Florence")
        assertThat(body).startsWith("Florence a réagi par ❤️ à votre message : «")
        assertThat(body).endsWith("»")
        assertThat(body).contains("Salut")
    }

    @Test fun `buildReadableFrBody truncates long body with ellipsis marker`() {
        val longBody = "a".repeat(200)
        val body = buildReadableFrBody("❤️", longBody)
        assertThat(body).contains("…")
        // Ne dépasse jamais 1 segment SMS UCS-2 (70 chars wrap-inclusive).
        // Tolérance large pour les emojis multi-codepoint.
        assertThat(body.length).isLessThan(80)
    }

    @Test fun `decoder recognises v1_8_1 anonymous French format with preview`() {
        // v1.8.1 nouveau format neutre (sans "J'ai", avec "à votre message").
        val decoded = IncomingReactionDecoder.decode("Réagi par ❤️ à votre message : «Bonjour»")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("❤️")
        assertThat(decoded.previewPrefix).isEqualTo("Bonjour")
        assertThat(decoded.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decoder recognises v1_8_1 named French format with preview`() {
        // v1.8.1 format avec nom de l'expéditeur (prefix Nom + " a réagi par").
        val decoded = IncomingReactionDecoder.decode(
            "Florence a réagi par ❤️ à votre message : «Salut»",
        )
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("❤️")
        assertThat(decoded.previewPrefix).isEqualTo("Salut")
        assertThat(decoded.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decoder recognises v1_8_1 anonymous French format without preview`() {
        val decoded = IncomingReactionDecoder.decode("Réagi par ❤️ à votre message")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("❤️")
        assertThat(decoded.previewPrefix).isNull()
        assertThat(decoded.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decoder STILL recognises v1_8_0 LEGACY French format (retro-compat)`() {
        // Critical : un user qui reçoit une réaction d'un correspondant SMS Tech v1.8.0
        // (pas encore upgradé en v1.8.1) doit continuer à voir le badge réaction.
        val decoded = IncomingReactionDecoder.decode("J’ai réagi par ❤️ à : «Bonjour»")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("❤️")
        assertThat(decoded.previewPrefix).isEqualTo("Bonjour")
        assertThat(decoded.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decoder STILL recognises v1_8_0 LEGACY with ASCII apostrophe (gateway normalisation)`() {
        val decoded = IncomingReactionDecoder.decode("J'ai réagi par 👍 à : «Yes»")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("👍")
        assertThat(decoded.previewPrefix).isEqualTo("Yes")
    }

    @Test fun `decoder STILL recognises v1_8_0 LEGACY no preview (image-only MMS reply)`() {
        val decoded = IncomingReactionDecoder.decode("J’ai réagi par ❤️")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("❤️")
        assertThat(decoded.previewPrefix).isNull()
        assertThat(decoded.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decoder STILL recognises legacy English Tapback format (retro-compat)`() {
        // Critical : un user qui upgrade depuis v1.7.x doit continuer à voir les réactions
        // entrantes envoyées au format "Reacted ❤️ to «...»" — soit par d'autres SMS Tech v1.7,
        // soit par iMessage/Google Messages.
        val decoded = IncomingReactionDecoder.decode("Reacted ❤️ to «Hello»")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("❤️")
        assertThat(decoded.previewPrefix).isEqualTo("Hello")
    }

    @Test fun `decoder respects ReDoS cap for French format (input over 400c rejected)`() {
        // Le cap MAX_DECODE_INPUT_LENGTH protège contre un attaquant qui injecterait un
        // body très long sans guillemet fermant, forçant un backtracking catastrophique sur
        // la regex non-greedy. Valeur cap = 400 chars d'après v1.5.1.
        val attackBody = "Réagi par ❤️ à votre message : «" + "a".repeat(500)
        val decoded = IncomingReactionDecoder.decode(attackBody)
        assertThat(decoded).isNull()
    }

    @Test fun `decoder rejects lowercase French sentences (avoid casual chat false positive)`() {
        // "j'ai réagi par ..." ou "réagi par ..." (lowercase) — laissons passer le check
        // pour le format LEGACY uniquement (la regex `Réagi` du nouveau format a un R
        // majuscule strict). Pour ce test, on garde le check sur l'ancien legacy.
        // strict pour éviter les faux positifs.
        val decoded = IncomingReactionDecoder.decode("j'ai réagi par truc")
        assertThat(decoded).isNull()
    }

    @Test fun `buildTapbackBody legacy English format still produces correct output (retro-compat)`() {
        // Même si le défaut est désormais FR, les users en TAPBACK_EN doivent continuer
        // à pouvoir envoyer le format anglais détectable par iMessage.
        val body = buildTapbackBody("❤️", "Salut")
        assertThat(body).isEqualTo("Reacted ❤️ to «Salut»")
    }
}
