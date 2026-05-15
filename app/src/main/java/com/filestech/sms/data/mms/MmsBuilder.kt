package com.filestech.sms.data.mms

import com.google.android.mms.pdu.CharacterSets
import com.google.android.mms.pdu.EncodedStringValue
import com.google.android.mms.pdu.PduBody
import com.google.android.mms.pdu.PduComposer
import com.google.android.mms.pdu.PduHeaders
import com.google.android.mms.pdu.PduPart
import com.google.android.mms.pdu.SendReq
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an MMS `m-send.req` PDU from a recorded voice clip + a list of recipients.
 *
 * Encoding follows OMA-MMS-ENC-V1_3 §6.1.1. The body is a multipart/related document with two
 * parts: a SMIL "page" (root, for clients that expect a layout) plus the audio attachment
 * itself. Even though many modern viewers ignore SMIL, including it keeps compatibility with
 * legacy carrier gateways and feature-phone clients.
 */
@Singleton
class MmsBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Typed description of a single attachment for a multipart MMS. The bytes are read from
     * disk inside the builder so callers don't have to manage allocation timing.
     */
    data class MmsAttachment(
        val file: File,
        val mimeType: String,
        /** Kind drives the SMIL element used for presentation (audio/img/text/ref). */
        val kind: Kind,
    ) {
        enum class Kind { AUDIO, IMAGE, VIDEO, OTHER }
    }

    /**
     * Voice MMS shorthand — preserves the v1.1 API so existing callers compile unchanged.
     * Internally a single-attachment wrapper around [buildMultipartSendReq].
     */
    fun buildVoiceSendReq(
        audioFile: File,
        mimeType: String,
        recipients: List<String>,
        subject: String? = null,
        requestDeliveryReport: Boolean = false,
    ): ByteArray? = buildMultipartSendReq(
        attachments = listOf(MmsAttachment(audioFile, mimeType, MmsAttachment.Kind.AUDIO)),
        textBody = null,
        recipients = recipients,
        subject = subject,
        requestDeliveryReport = requestDeliveryReport,
    )

    /**
     * Encodes a generic SendReq PDU with one or more attachments and an optional text body
     * (#1 + #2). The SMIL document is generated from the attachment list so legacy gateways
     * and feature-phone clients still receive a navigable presentation.
     *
     * Constraints:
     *  - at least one attachment OR a non-blank text body must be present;
     *  - per-recipient validation matches the voice path (rejects email-style routing).
     *
     * @return the encoded PDU bytes, or null if encoding failed (invalid recipient, IO error,
     *   composer rejection). Callers treat null as a hard send failure.
     */
    fun buildMultipartSendReq(
        attachments: List<MmsAttachment>,
        textBody: String?,
        recipients: List<String>,
        subject: String? = null,
        requestDeliveryReport: Boolean = false,
    ): ByteArray? {
        if (recipients.isEmpty()) return null
        val hasText = !textBody.isNullOrBlank()
        if (attachments.isEmpty() && !hasText) return null
        for (a in attachments) {
            if (!a.file.exists() || a.file.length() == 0L) return null
        }

        val req = SendReq()
        // Audit S-P2-7: refuse the build entirely if *any* recipient cannot be normalised to a
        // PLMN-routable phone number. A single bad address can no longer slip through with the
        // others; the caller (MmsSender) interprets the null return as a hard send failure and
        // surfaces it to the UI rather than silently routing some recipients via email.
        //
        // BUG-MMS-Samsung (2026-05-15): Samsung One UI's bundled `SendReq` (under
        // `/system/framework/framework.jar!classes6.dex`) **does NOT expose** the AOSP `addTo(
        // EncodedStringValue)` method that ships with vanilla Android. Calling it throws
        // `NoSuchMethodError` at runtime → the app crashes the moment the user hits Send.
        // The setter `setTo(EncodedStringValue[])` IS present on every OEM (it's the
        // foundational mutator used by AOSP's own `addTo` under the hood), so we pre-build
        // the array and assign it once. Functionally equivalent, portable across Samsung,
        // Pixel, Xiaomi, etc.
        val recipientValues = ArrayList<EncodedStringValue>(recipients.size)
        for (raw in recipients) {
            val formatted = formatAddressForMms(raw) ?: return null
            recipientValues += EncodedStringValue(formatted)
        }
        if (!attachRecipientsCompat(req, recipientValues)) {
            Timber.w("MmsBuilder: neither setTo(array) nor addTo(value) available on this OEM's SendReq")
            return null
        }
        if (!subject.isNullOrBlank()) req.subject = EncodedStringValue(subject)
        req.deliveryReport = if (requestDeliveryReport) PduHeaders.VALUE_YES else PduHeaders.VALUE_NO
        req.readReport = PduHeaders.VALUE_NO

        val body = PduBody()
        var totalSize = 0L

        // Optional text part — encoded as UTF-8 text/plain, named "text.txt".
        val textPart: PduPart? = if (hasText) {
            val bytes = textBody!!.toByteArray(Charsets.UTF_8)
            PduPart().apply {
                contentType = "text/plain".toByteArray()
                contentId = "text".toByteArray()
                contentLocation = "text.txt".toByteArray()
                data = bytes
                charset = CharacterSets.UTF_8
            }.also { totalSize += bytes.size }
        } else null

        // Indexed attachment parts — each gets a stable content-id ("a0", "a1", …) referenced
        // from the SMIL document. Names are passed through `safeAttachmentName` so a future
        // caller feeding arbitrary `file.name` cannot break the multipart envelope.
        val attachmentParts = attachments.mapIndexed { idx, a ->
            val safeName = safeAttachmentName(a.file.name, idx)
            val bytes = runCatching { a.file.readBytes() }.getOrNull() ?: return null
            totalSize += bytes.size
            PduPart().apply {
                contentType = a.mimeType.toByteArray()
                contentLocation = safeName.toByteArray()
                contentId = "a$idx".toByteArray()
                name = safeName.toByteArray()
                data = bytes
            }
        }

        // SMIL part is the root presentation document — must be index 0 in the body. Build the
        // markup with the attachment list (and optional text) so the SMIL stays in sync with
        // what we actually send.
        val smilXml = buildSmil(
            attachments = attachments.mapIndexed { idx, a -> Pair(safeAttachmentName(a.file.name, idx), a.kind) },
            includeText = hasText,
        )
        val smilPart = PduPart().apply {
            contentType = "application/smil".toByteArray()
            contentId = "smil".toByteArray()
            contentLocation = "smil.xml".toByteArray()
            data = smilXml.toByteArray(Charsets.UTF_8)
            charset = CharacterSets.UTF_8
        }
        appendPart(body, 0, smilPart)
        totalSize += smilPart.data.size

        textPart?.let { appendPart(body, it) }
        for (p in attachmentParts) appendPart(body, p)
        req.body = body
        req.messageSize = totalSize

        return try {
            PduComposer(context, req).make()
        } catch (t: Throwable) {
            Timber.w(t, "PduComposer failed")
            null
        }
    }

    /**
     * Builds a minimal SMIL page describing the attachments. Each entry produces one `<par>`
     * with the element matching the [MmsAttachment.Kind] (audio → `<audio>`, image → `<img>`,
     * video → `<video>`, other → `<ref>`). The optional text body precedes the attachments so
     * gateways that render only the SMIL "first paragraph" still surface the user's caption.
     *
     * Audit S-P2-5: every filename is XML-escaped via [xmlEscape] before interpolation so an
     * attacker-controlled name (future share-target intent) cannot break out of the attribute.
     */
    private fun buildSmil(
        attachments: List<Pair<String, MmsAttachment.Kind>>,
        includeText: Boolean,
    ): String {
        val parts = buildString {
            if (includeText) append("""<par dur="3000ms"><text src="text.txt"/></par>""")
            for ((rawName, kind) in attachments) {
                val safe = xmlEscape(rawName)
                val element = when (kind) {
                    MmsAttachment.Kind.AUDIO -> """<audio src="$safe"/>"""
                    MmsAttachment.Kind.IMAGE -> """<img src="$safe"/>"""
                    MmsAttachment.Kind.VIDEO -> """<video src="$safe"/>"""
                    MmsAttachment.Kind.OTHER -> """<ref src="$safe"/>"""
                }
                val dur = if (kind == MmsAttachment.Kind.AUDIO || kind == MmsAttachment.Kind.VIDEO) {
                    "60000ms"
                } else "5000ms"
                append("""<par dur="$dur">$element</par>""")
            }
        }
        return """<smil><head><layout><root-layout/></layout></head><body>$parts</body></smil>"""
    }

    /**
     * Cross-OEM helper to append a [PduPart] at the end of a [PduBody]. Samsung One UI 6 also
     * dropped the 1-arg `addPart(PduPart)` method on top of removing `SendReq.addTo`, so we
     * mirror the same reflection strategy: prefer the 1-arg form, fall back to the 2-arg
     * indexed variant using the current parts count as the insertion position.
     */
    private fun appendPart(body: PduBody, part: PduPart) {
        // 1-arg addPart — AOSP standard, missing on Samsung One UI 6+.
        runCatching {
            val method = body.javaClass.getMethod("addPart", PduPart::class.java)
            method.invoke(body, part)
            return
        }.onFailure { Timber.d("MmsBuilder.addPart(PduPart) reflection miss: %s", it.message) }
        // 2-arg addPart(int, PduPart) — present everywhere (it's the underlying mutator).
        runCatching {
            val countMethod = body.javaClass.getMethod("getPartsNum")
            val count = countMethod.invoke(body) as Int
            val method = body.javaClass.getMethod("addPart", Int::class.javaPrimitiveType, PduPart::class.java)
            method.invoke(body, count, part)
            return
        }.onFailure { Timber.d("MmsBuilder.addPart(int, PduPart) reflection miss: %s", it.message) }
        Timber.w("MmsBuilder: no addPart variant available — PDU will be missing parts")
    }

    /** Indexed variant — only used for inserting the SMIL part at position 0. */
    private fun appendPart(body: PduBody, index: Int, part: PduPart) {
        runCatching {
            val method = body.javaClass.getMethod("addPart", Int::class.javaPrimitiveType, PduPart::class.java)
            method.invoke(body, index, part)
            return
        }.onFailure { Timber.d("MmsBuilder.addPart(int, PduPart) reflection miss: %s", it.message) }
        Timber.w("MmsBuilder: indexed addPart unavailable — SMIL part may end up at wrong position")
    }

    /**
     * Cross-OEM helper to attach the recipients to a [SendReq]. The vanilla AOSP `SendReq`
     * exposes `addTo(EncodedStringValue)`, but Samsung One UI 6+ ships a variant that **only**
     * has the parent-class `setTo(EncodedStringValue[])` — calling `addTo` there throws
     * `NoSuchMethodError` at runtime (the SDK stub claims the method exists, the OEM's
     * `framework.jar` doesn't, the linker is happy at compile but explodes when the bytecode
     * runs). We reach the right setter via reflection and try both routes so the code keeps
     * working on Pixel / AOSP / Samsung / Xiaomi without a build matrix per OEM.
     *
     * Returns `true` if at least one form took. `false` only when the device's framework has
     * **neither** signature, which would be a deep OEM regression — we surface it to the
     * caller as a hard build failure rather than producing a recipientless PDU.
     */
    private fun attachRecipientsCompat(req: SendReq, values: List<EncodedStringValue>): Boolean {
        // Preferred route: setTo([]). Defined on `MultimediaMessagePdu` (parent class of SendReq)
        // and present on every Android version since the API was added in Froyo. Universal.
        runCatching {
            val arr = java.lang.reflect.Array.newInstance(EncodedStringValue::class.java, values.size)
            for ((i, v) in values.withIndex()) java.lang.reflect.Array.set(arr, i, v)
            val method = req.javaClass.getMethod("setTo", arr.javaClass)
            method.invoke(req, arr)
            return true
        }.onFailure { Timber.d("MmsBuilder.setTo reflection miss: %s", it.message) }

        // Fallback: addTo() one-by-one. AOSP standard, missing on Samsung One UI 6+.
        runCatching {
            val method = req.javaClass.getMethod("addTo", EncodedStringValue::class.java)
            for (v in values) method.invoke(req, v)
            return true
        }.onFailure { Timber.d("MmsBuilder.addTo reflection miss: %s", it.message) }

        return false
    }

    /**
     * Sanitises an attachment filename for SMIL/Content-Location use. We strip every character
     * outside the conservative `[A-Za-z0-9._-]` set and fall back to a stable indexed name
     * when the input degenerates to an empty string. Keeps the encoded PDU portable across
     * legacy gateways that choke on Unicode in part headers.
     */
    private fun safeAttachmentName(raw: String, index: Int): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return if (cleaned.isEmpty()) "attachment_$index" else cleaned.take(64)
    }

    /** Minimal XML attribute escape covering the five entities required by XML 1.0 §2.4. */
    private fun xmlEscape(input: String): String = buildString(input.length) {
        for (ch in input) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /**
     * Normalises a recipient phone number for MMS routing. Strips spaces / dashes / parentheses
     * and appends the "/TYPE=PHONE" suffix the MMSC uses to route over PLMN rather than email.
     *
     * Audit S-P2-7: **refuses email-style addresses** (`@`). The MMSC accepts them as routing
     * targets, which would otherwise allow a manipulated composer (future re-forward, share-target
     * intent) to exfiltrate a voice message to `attacker@evil.example` over the operator's MMS
     * gateway — a discreet side-channel the user would never see. Returns `null` so the caller
     * can fail the send loudly rather than silently re-route.
     */
    private fun formatAddressForMms(raw: String): String? {
        val clean = raw.replace(Regex("[\\s\\-()]"), "")
        if (clean.contains('@')) return null
        if (clean.endsWith("/TYPE=PLMN")) return clean
        // Basic E.164-ish sanity: + optional, digits only, 6 to 15 chars.
        if (!clean.matches(Regex("^\\+?\\d{6,15}$"))) return null
        return "$clean/TYPE=PLMN"
    }
}
