package com.filestech.sms.system.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.filestech.sms.R
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.core.result.runCatchingOutcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.PhoneAddress.Companion.toCsv
import com.filestech.sms.domain.pdf.PdfExportResult
import com.filestech.sms.domain.pdf.PdfExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a conversation to a single PDF using the framework [PdfDocument].
 * No external dependency — F-Droid friendly.
 *
 * Layout: A4 portrait (595×842 pt), 32 pt margins, header with conversation participants and
 * generation date, then chat bubbles. Outgoing messages right-aligned (filled), incoming
 * left-aligned (outlined). Date dividers in italic between days.
 */
@Singleton
class ConversationPdfExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PdfExporter {

    override suspend fun export(conversation: Conversation, messages: List<Message>): Outcome<PdfExportResult> =
        withContext(io) {
            runCatchingOutcome(
                block = { renderToFile(conversation, messages) },
                errorMapper = { AppError.Storage(it) },
            )
        }

    /**
     * v1.28.3 (F33) — **le document est ferme quoi qu'il arrive, et un fichier a moitie ecrit ne
     * survit pas a l'echec qui l'a produit.**
     *
     * `doc.close()` suivait `writeTo` sans `finally` : la moindre exception du rendu ou de
     * l'ecriture — disque plein, message aberrant, `OutOfMemoryError` sur une conversation
     * enorme — laissait le `PdfDocument` ouvert, donc ses pages natives non liberees, pour toute
     * la duree du processus. Et le `File` deja cree restait sur le disque, tronque, pret a etre
     * partage par un utilisateur qui n'a vu qu'un message d'erreur passer.
     */
    private fun renderToFile(conversation: Conversation, messages: List<Message>): PdfExportResult {
        val doc = PdfDocument()
        val dir = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }
        val safeName = (conversation.displayName ?: conversation.addresses.toCsv())
            .replace(Regex("[^A-Za-z0-9_-]+"), "_").take(48)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "smstech_conversation_${safeName}_$ts.pdf")
        var abouti = false
        try {
            val pages = renderPages(doc, conversation, messages)
            out.outputStream().use { doc.writeTo(it) }
            abouti = true
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
            return PdfExportResult(shareUri = uri.toString(), pages = pages)
        } finally {
            doc.close()
            if (!abouti) out.delete()
        }
    }

    private fun renderPages(doc: PdfDocument, conversation: Conversation, messages: List<Message>): Int {
        val pageWidth = PAGE_WIDTH
        val pageHeight = PAGE_HEIGHT
        val margin = MARGIN
        val contentWidth = pageWidth - margin * 2
        val bubbleMaxWidth = (contentWidth * 0.78f).toInt()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 18f; isFakeBoldText = true
        }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; textSize = 11f
        }
        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; textSize = 10f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        val outgoingTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 11.5f
        }
        val incomingTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 11.5f
        }
        val timestampPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY; textSize = 8.5f
        }
        val outgoingBg = Paint().apply { color = Color.parseColor("#2460AB"); isAntiAlias = true }
        val incomingBg = Paint().apply { color = Color.parseColor("#EAEAEA"); isAntiAlias = true; style = Paint.Style.FILL }
        val incomingStroke = Paint().apply { color = Color.parseColor("#CFCFCF"); isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 0.75f }

        val dateFormatter = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault())
        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

        // v1.28.3 (F33) — la pagination est un objet, et non six variables mutables recopiees a
        // chaque saut de page. Les trois sauts d'origine dupliquaient la meme sequence, et AUCUN
        // n'appelait `drawFooter` : le numero de page n'apparaissait donc que sur la DERNIERE.
        val pagination = Pagination(doc, pageWidth, pageHeight, margin) { c, n ->
            drawFooter(c, n, pageWidth, pageHeight, margin)
        }
        // v1.28.3 (audit du 2026-09-09) — `renderPages` garantit qu'aucune page ne reste ouverte
        // quand elle sort, quelle que soit la facon dont elle sort. C'est ELLE qui possede la
        // pagination ; l'appelant ne peut pas l'atteindre, et fermer le document sur une page
        // ouverte pourrait lever et ecraser la cause reelle. Cf. `Pagination.finirSiOuverte`.
        try {
            return rendreLesBulles(
                pagination, conversation, messages, dateFormatter, timeFormatter,
                titlePaint, subtitlePaint, datePaint,
                outgoingTextPaint, incomingTextPaint, timestampPaint,
                outgoingBg, incomingBg, incomingStroke,
                pageWidth, margin, bubbleMaxWidth,
            )
        } finally {
            pagination.finirSiOuverte()
        }
    }

    @Suppress("LongParameterList", "LongMethod")
    private fun rendreLesBulles(
        pagination: Pagination,
        conversation: Conversation,
        messages: List<Message>,
        dateFormatter: SimpleDateFormat,
        timeFormatter: SimpleDateFormat,
        titlePaint: TextPaint,
        subtitlePaint: TextPaint,
        datePaint: TextPaint,
        outgoingTextPaint: TextPaint,
        incomingTextPaint: TextPaint,
        timestampPaint: TextPaint,
        outgoingBg: Paint,
        incomingBg: Paint,
        incomingStroke: Paint,
        pageWidth: Int,
        margin: Int,
        bubbleMaxWidth: Int,
    ): Int {
        pagination.cursorY = drawHeader(pagination.canvas, conversation, titlePaint, subtitlePaint, margin)

        var lastDayKey: String? = null
        for (msg in messages) {
            val dayKey = dateFormatter.format(Date(msg.date))
            if (dayKey != lastDayKey) {
                if (pagination.cursorY + DAY_DIVIDER_HEIGHT > pagination.bas) pagination.pageSuivante()
                pagination.canvas.drawText(
                    dayKey,
                    (pageWidth / 2).toFloat(),
                    pagination.cursorY + 12f,
                    datePaint,
                )
                pagination.cursorY += DAY_DIVIDER_HEIGHT
                lastDayKey = dayKey
            }

            val text = msg.body.ifBlank { "[…]" }
            val textPaint = if (msg.isOutgoing) outgoingTextPaint else incomingTextPaint
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, bubbleMaxWidth - PAD * 2)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .setIncludePad(false)
                .build()
            val bubbleWidth = (layout.width + PAD * 2).coerceAtMost(bubbleMaxWidth)
            dessinerBulle(
                pagination = pagination,
                layout = layout,
                largeur = bubbleWidth,
                sortant = msg.isOutgoing,
                fond = if (msg.isOutgoing) outgoingBg else incomingBg,
                contour = if (msg.isOutgoing) null else incomingStroke,
                horodatage = timeFormatter.format(Date(msg.date)),
                horodatagePaint = timestampPaint,
                pageWidth = pageWidth,
                margin = margin,
            )
            pagination.cursorY += 6
        }

        pagination.terminer()
        return pagination.pageNumber
    }

    /**
     * v1.28.3 (F33) — **une bulle plus haute qu'une page etait TRONQUEE, en silence.**
     *
     * L'ancien code sautait une page quand la bulle ne tenait pas dans ce qui reste, puis la
     * dessinait telle quelle. Si elle ne tenait pas non plus sur une page VIERGE — un long
     * message colle, un MMS bavard — elle debordait, et le PDF coupe simplement ce qui depasse.
     * L'export annoncait un succes et un nombre de pages ; le texte manquant, lui, ne se voyait
     * qu'en relisant le document. Pour une fonction dont le seul but est de conserver une
     * conversation, perdre du texte sans le dire est le pire mode d'echec possible.
     *
     * La bulle se decoupe donc par LIGNES, aux frontieres que `StaticLayout` a deja calculees :
     * on remplit ce qui reste de la page, on continue sur la suivante. Le decoupage se fait sur
     * les lignes et non sur les pixels, pour ne jamais couper un glyphe en deux.
     */
    @Suppress("LongParameterList")
    private fun dessinerBulle(
        pagination: Pagination,
        layout: StaticLayout,
        largeur: Int,
        sortant: Boolean,
        fond: Paint,
        contour: Paint?,
        horodatage: String,
        horodatagePaint: TextPaint,
        pageWidth: Int,
        margin: Int,
    ) {
        val gauche = if (sortant) (pageWidth - margin - largeur).toFloat() else margin.toFloat()
        var ligne = 0
        while (ligne < layout.lineCount) {
            // Place restante pour du TEXTE : la marge basse, moins les rembourrages de la bulle
            // et la ligne d'horodatage qui la suit.
            var dispo = pagination.bas - pagination.cursorY - PAD * 2 - HAUTEUR_HORODATAGE
            val hautLigne = layout.getLineTop(ligne)
            val hauteurPremiere = layout.getLineBottom(ligne) - hautLigne
            if (dispo < hauteurPremiere) {
                pagination.pageSuivante()
                dispo = pagination.bas - pagination.cursorY - PAD * 2 - HAUTEUR_HORODATAGE
                // Une page vierge qui ne peut pas contenir UNE ligne : la geometrie est absurde
                // (marges plus hautes que la page). On sort plutot que de boucler sans fin.
                if (dispo < hauteurPremiere) return
            }
            var derniere = ligne
            while (derniere + 1 < layout.lineCount &&
                layout.getLineBottom(derniere + 1) - hautLigne <= dispo
            ) {
                derniere++
            }
            val hauteurTexte = (layout.getLineBottom(derniere) - hautLigne).toFloat()
            val rect = RectF(
                gauche,
                pagination.cursorY,
                gauche + largeur,
                pagination.cursorY + hauteurTexte + PAD * 2,
            )
            pagination.canvas.drawRoundRect(rect, 12f, 12f, fond)
            contour?.let { pagination.canvas.drawRoundRect(rect, 12f, 12f, it) }
            pagination.canvas.save()
            // Le clip borne la tranche a la bulle : sans lui, `layout.draw` peindrait le texte
            // ENTIER a chaque passage, et les tranches se superposeraient.
            pagination.canvas.clipRect(rect)
            pagination.canvas.translate(rect.left + PAD, rect.top + PAD - hautLigne)
            layout.draw(pagination.canvas)
            pagination.canvas.restore()
            pagination.cursorY = rect.bottom
            // L'horodatage n'accompagne que la DERNIERE tranche : le repeter donnerait a un long
            // message l'apparence de plusieurs messages envoyes a la meme heure.
            if (derniere == layout.lineCount - 1) {
                val largeurTexte = horodatagePaint.measureText(horodatage)
                val x = if (sortant) rect.right - largeurTexte else rect.left
                pagination.canvas.drawText(horodatage, x, rect.bottom + 10, horodatagePaint)
                pagination.cursorY += HAUTEUR_HORODATAGE
            }
            ligne = derniere + 1
        }
    }

    /**
     * v1.28.3 (F33) — l'etat d'une page en cours, et le seul endroit qui sache en commencer une.
     *
     * Les trois sauts de page d'origine recopiaient la meme sequence de cinq lignes, et aucun
     * n'appelait `drawFooter` — appele une seule fois, apres la boucle, donc sur la derniere page
     * uniquement. Toutes les autres sortaient sans numero. Centraliser le saut fait disparaitre
     * la question.
     */
    private class Pagination(
        private val doc: PdfDocument,
        private val pageWidth: Int,
        private val pageHeight: Int,
        private val margin: Int,
        private val piedDePage: (android.graphics.Canvas, Int) -> Unit,
    ) {
        var pageNumber = 1
            private set
        private var page = doc.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create(),
        )
        var canvas: android.graphics.Canvas = page.canvas
            private set
        var cursorY: Float = margin.toFloat()

        /** v1.28.3 — une page est « ouverte » entre son `startPage` et son `finishPage`. */
        private var ouverte = true

        /** Ordonnee au-dela de laquelle plus rien ne doit etre dessine. */
        val bas: Float get() = (pageHeight - margin).toFloat()

        fun pageSuivante() {
            piedDePage(canvas, pageNumber)
            doc.finishPage(page)
            pageNumber++
            page = doc.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create(),
            )
            canvas = page.canvas
            cursorY = margin.toFloat()
        }

        fun terminer() {
            piedDePage(canvas, pageNumber)
            doc.finishPage(page)
            ouverte = false
        }

        /**
         * v1.28.3 (audit du 2026-09-09) — ferme la page en cours SI elle l'est encore.
         *
         * `renderToFile` appelle `doc.close()` dans un `finally`, y compris quand le rendu a leve
         * au milieu d'une page : celle-ci restait alors OUVERTE. Selon la version d'Android,
         * `close()` peut y lever a son tour — et cette exception secondaire ECRASERAIT la cause
         * reelle, laissant un `AppError.Storage` qui ne dit rien de ce qui s'est passe.
         *
         * Le comportement de `PdfDocument.close()` sur une page ouverte vit dans le framework de
         * l'appareil et n'est pas mesurable ici. Plutot que de parier sur l'une des deux issues,
         * on rend la question sans objet. Idempotent : sans effet apres un `terminer()` normal.
         */
        fun finirSiOuverte() {
            if (!ouverte) return
            runCatching { doc.finishPage(page) }
            ouverte = false
        }
    }

    private fun drawHeader(
        canvas: android.graphics.Canvas,
        conversation: Conversation,
        titlePaint: TextPaint,
        subtitlePaint: TextPaint,
        margin: Int,
    ): Float {
        val title = conversation.displayName ?: conversation.addresses.toCsv()
        canvas.drawText(title, margin.toFloat(), margin + 18f, titlePaint)
        val sub = context.getString(
            R.string.pdf_header_subtitle,
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
            conversation.addresses.size,
        )
        canvas.drawText(sub, margin.toFloat(), margin + 36f, subtitlePaint)
        val rule = Paint().apply { color = Color.parseColor("#DDDDDD"); strokeWidth = 0.5f }
        canvas.drawRect(Rect(margin, margin + 46, PAGE_WIDTH - margin, margin + 47), rule)
        return (margin + 60).toFloat()
    }

    private fun drawFooter(
        canvas: android.graphics.Canvas,
        pageNumber: Int,
        pageWidth: Int,
        pageHeight: Int,
        margin: Int,
    ) {
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY; textSize = 9f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            context.getString(R.string.pdf_footer, pageNumber),
            (pageWidth / 2).toFloat(),
            (pageHeight - margin / 2).toFloat(),
            footerPaint,
        )
    }

    private companion object {
        /** v1.28.3 (F33) — place reservee sous une bulle pour son horodatage. */
        const val HAUTEUR_HORODATAGE = 16

        const val PAGE_WIDTH = 595 // A4 portrait, 72 dpi
        const val PAGE_HEIGHT = 842
        const val MARGIN = 36
        const val PAD = 8
        const val DAY_DIVIDER_HEIGHT = 22f
    }
}
