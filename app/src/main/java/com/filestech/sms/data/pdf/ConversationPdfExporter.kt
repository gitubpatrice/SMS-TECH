package com.filestech.sms.data.pdf

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
) {

    data class ExportResult(val file: File, val shareUri: android.net.Uri, val pages: Int)

    suspend fun export(conversation: Conversation, messages: List<Message>): Outcome<ExportResult> = withContext(io) {
        runCatchingOutcome(
            block = { renderToFile(conversation, messages) },
            errorMapper = { AppError.Storage(it) },
        )
    }

    private fun renderToFile(conversation: Conversation, messages: List<Message>): ExportResult {
        val doc = PdfDocument()
        val pages = renderPages(doc, conversation, messages)
        val dir = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }
        val safeName = (conversation.displayName ?: conversation.addresses.toCsv()).replace(Regex("[^A-Za-z0-9_-]+"), "_").take(48)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "smstech_conversation_${safeName}_$ts.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
        return ExportResult(out, uri, pages)
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

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        var cursorY = drawHeader(canvas, conversation, titlePaint, subtitlePaint, margin)

        var lastDayKey: String? = null
        for (msg in messages) {
            val dayKey = dateFormatter.format(Date(msg.date))
            if (dayKey != lastDayKey) {
                if (cursorY + DAY_DIVIDER_HEIGHT > pageHeight - margin) {
                    doc.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = doc.startPage(pageInfo)
                    canvas = page.canvas
                    cursorY = margin.toFloat()
                }
                canvas.drawText(dayKey, (pageWidth / 2).toFloat(), cursorY + 12f, datePaint)
                cursorY += DAY_DIVIDER_HEIGHT
                lastDayKey = dayKey
            }

            val text = msg.body.ifBlank { "[…]" }
            val textPaint = if (msg.isOutgoing) outgoingTextPaint else incomingTextPaint
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, bubbleMaxWidth - PAD * 2)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .setIncludePad(false)
                .build()
            val bubbleHeight = layout.height + PAD * 2 + 16
            val bubbleWidth = (layout.width + PAD * 2).coerceAtMost(bubbleMaxWidth)
            if (cursorY + bubbleHeight > pageHeight - margin) {
                doc.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = doc.startPage(pageInfo)
                canvas = page.canvas
                cursorY = margin.toFloat()
            }
            val left = if (msg.isOutgoing) (pageWidth - margin - bubbleWidth).toFloat() else margin.toFloat()
            val rect = RectF(left, cursorY, left + bubbleWidth, cursorY + bubbleHeight - 14)
            if (msg.isOutgoing) {
                canvas.drawRoundRect(rect, 12f, 12f, outgoingBg)
            } else {
                canvas.drawRoundRect(rect, 12f, 12f, incomingBg)
                canvas.drawRoundRect(rect, 12f, 12f, incomingStroke)
            }
            canvas.save()
            canvas.translate(rect.left + PAD, rect.top + PAD)
            layout.draw(canvas)
            canvas.restore()
            val timeText = timeFormatter.format(Date(msg.date))
            val timeWidth = timestampPaint.measureText(timeText)
            val timeX = if (msg.isOutgoing) rect.right - timeWidth else rect.left
            canvas.drawText(timeText, timeX, rect.bottom + 10, timestampPaint)

            cursorY += bubbleHeight + 6
        }

        drawFooter(canvas, pageNumber, pageWidth, pageHeight, margin)
        doc.finishPage(page)
        return pageNumber
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
        const val PAGE_WIDTH = 595 // A4 portrait, 72 dpi
        const val PAGE_HEIGHT = 842
        const val MARGIN = 36
        const val PAD = 8
        const val DAY_DIVIDER_HEIGHT = 22f
    }
}
