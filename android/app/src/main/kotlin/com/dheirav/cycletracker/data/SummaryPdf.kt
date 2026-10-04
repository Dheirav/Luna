package com.dheirav.cycletracker.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.dheirav.cycletracker.core.ClinicalSummary
import com.dheirav.cycletracker.core.SummaryDocument
import com.dheirav.cycletracker.core.SummaryItem
import java.io.OutputStream
import java.time.format.DateTimeFormatter

/**
 * Draws the doctor summary as an A4 PDF, on the phone.
 *
 * Asked for on 5 Oct 2026: a document that looks like something to hand over, not a monospaced
 * text file. Uses the platform's own [PdfDocument], so it adds no library, needs no network and
 * nothing leaves the phone until the person shares or saves it.
 *
 * Written for print and a quick read: dark text on white, one accent colour for structure, and
 * ESTIMATED and IN PROGRESS drawn as labels in words, never as colour alone, because the page may
 * be photocopied in black and white. Every page carries the provenance line in its footer, since a
 * single printed page can be separated from the rest.
 */
class SummaryPdf {

    private val pageWidth = 595 // A4 in PostScript points
    private val pageHeight = 842
    private val margin = 48f
    private val contentWidth = pageWidth - 2 * margin
    private val footerSpace = 36f
    private val bottom = pageHeight - margin - footerSpace

    private val ink = Color.rgb(0x2B, 0x21, 0x29)
    private val muted = Color.rgb(0x6B, 0x5A, 0x63)
    private val accent = Color.rgb(0xA8, 0x33, 0x6A)
    private val rule = Color.rgb(0xE8, 0xDC, 0xE2)
    private val panel = Color.rgb(0xF8, 0xF1, 0xF4)
    private val flagFill = Color.rgb(0xFB, 0xF3, 0xE4)
    private val flagBar = Color.rgb(0xB9, 0x7F, 0x16)

    private val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) sansBold else sans
    }

    private val titlePaint = text(22f, ink, bold = true)
    private val subtitlePaint = text(9.5f, muted)
    private val headingPaint = text(11.5f, accent, bold = true).apply { letterSpacing = 0.04f }
    private val headingNotePaint = text(8.5f, muted)
    private val labelPaint = text(9.5f, muted)
    private val valuePaint = text(10f, ink, bold = true)
    private val bodyPaint = text(10f, ink)
    private val notePaint = text(9.5f, muted)
    private val groupPaint = text(10f, ink, bold = true)
    private val tagPaint = text(7.5f, muted, bold = true).apply { letterSpacing = 0.06f }
    private val footerPaint = text(7.5f, muted)

    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy")

    /** Column where a figure's value starts, and a row's detail. */
    private val valueColumn = 190f

    private lateinit var pdf: PdfDocument
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = 0f
    private val canvas: Canvas get() = page!!.canvas

    fun write(document: SummaryDocument, out: OutputStream) {
        pdf = PdfDocument()
        pageNumber = 0
        try {
            newPage()
            header(document)
            document.sections.forEach { section(it) }
            finishPage()
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    // -- pages -------------------------------------------------------------------

    private fun newPage() {
        finishPage()
        pageNumber++
        page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        y = margin
    }

    private fun finishPage() {
        val current = page ?: return
        val footerY = pageHeight - margin + 4f
        val line = Paint().apply { color = rule; strokeWidth = 0.75f }
        current.canvas.drawLine(margin, footerY - 14f, pageWidth - margin, footerY - 14f, line)
        current.canvas.drawText(
            "Self-reported by the patient in the Luna app. Not a medical record.",
            margin, footerY, footerPaint,
        )
        val number = "Page $pageNumber"
        current.canvas.drawText(number, pageWidth - margin - footerPaint.measureText(number), footerY, footerPaint)
        pdf.finishPage(current)
        page = null
    }

    /** Starts a new page if [height] would not fit, so nothing is split across a page edge. */
    private fun ensure(height: Float) {
        if (y + height > bottom) newPage()
    }

    // -- blocks ------------------------------------------------------------------

    private fun header(document: SummaryDocument) {
        canvas.drawText(document.title, margin, y + 22f, titlePaint)
        // Room for the title's descenders before the dateline; at 34 the two all but touched.
        y += 44f
        canvas.drawText(
            "Generated ${document.generatedOn.format(dateFormat)} from the Luna app",
            margin, y, subtitlePaint,
        )
        y += 10f
        canvas.drawRect(margin, y, margin + 64f, y + 2.5f, Paint().apply { color = accent })
        y += 14f

        // Provenance in a tinted panel, first thing a reader sees.
        val layouts = document.preamble.map { layout(it, notePaint, contentWidth - 24f) }
        val height = layouts.sumOf { it.height.toDouble() }.toFloat() + 6f * (layouts.size - 1) + 20f
        canvas.drawRoundRect(RectF(margin, y, pageWidth - margin, y + height), 6f, 6f, Paint().apply { color = panel })
        var inner = y + 10f
        layouts.forEach { inner += draw(it, margin + 12f, inner) + 6f }
        y += height + 18f
    }

    private fun section(section: com.dheirav.cycletracker.core.SummarySection) {
        // "CYCLES (most recent last)" becomes a heading "Cycles" with the qualifier beside it.
        val (title, qualifier) = section.title.split(" (", limit = 2).let {
            it[0].lowercase().replaceFirstChar { c -> c.uppercase() } to it.getOrNull(1)?.removeSuffix(")")
        }
        // Keep the heading with at least its first item.
        val firstHeight = section.items.firstOrNull()?.let { measure(it) } ?: 0f
        ensure(28f + firstHeight)

        canvas.drawText(title, margin, y + 12f, headingPaint)
        qualifier?.let {
            canvas.drawText(it, margin + headingPaint.measureText(title) + 8f, y + 12f, headingNotePaint)
        }
        y += 18f
        canvas.drawLine(margin, y, pageWidth - margin, y, Paint().apply { color = rule; strokeWidth = 0.75f })
        y += 9f

        section.items.forEach { item ->
            ensure(measure(item))
            y += drawItem(item)
        }
        y += 14f
    }

    /** Height an item will take, measured the same way it is drawn. */
    private fun measure(item: SummaryItem): Float = when (item) {
        is SummaryItem.Figure -> maxOf(
            layout(item.label, labelPaint, valueColumn - 12f).height.toFloat(),
            layout(figureValue(item), valuePaint, contentWidth - valueColumn - tagsWidth(item.tags)).height.toFloat(),
        ) + 6f
        is SummaryItem.Row -> maxOf(
            layout(item.primary, bodyPaint, valueColumn - 12f).height.toFloat(),
            layout(item.secondary.orEmpty(), notePaint, contentWidth - valueColumn - tagsWidth(item.tags)).height.toFloat(),
        ) + 6f
        is SummaryItem.Note -> layout(item.text, notePaint, contentWidth - indentOf(item.indent)).height + 6f
        is SummaryItem.Group -> 18f
        is SummaryItem.Flag -> flagHeight(item) + 8f
    }

    private fun drawItem(item: SummaryItem): Float = when (item) {
        is SummaryItem.Figure -> {
            val x = margin + indentOf(item.indent)
            val labelHeight = draw(layout(item.label, labelPaint, valueColumn - 12f - (x - margin)), x, y)
            val valueWidth = contentWidth - valueColumn - tagsWidth(item.tags)
            val value = layout(figureValue(item), valuePaint, valueWidth)
            val valueHeight = draw(value, margin + valueColumn, y)
            tags(item.tags, y)
            maxOf(labelHeight, valueHeight) + 6f
        }
        is SummaryItem.Row -> {
            val left = draw(layout(item.primary, bodyPaint, valueColumn - 12f), margin, y)
            val right = item.secondary?.let {
                draw(layout(it, notePaint, contentWidth - valueColumn - tagsWidth(item.tags)), margin + valueColumn, y)
            } ?: 0f
            tags(item.tags, y)
            maxOf(left, right) + 6f
        }
        is SummaryItem.Note -> draw(layout(item.text, notePaint, contentWidth - indentOf(item.indent)), margin + indentOf(item.indent), y) + 6f
        is SummaryItem.Group -> {
            canvas.drawText(item.title, margin, y + 11f, groupPaint)
            18f
        }
        is SummaryItem.Flag -> {
            val height = flagHeight(item)
            canvas.drawRoundRect(RectF(margin, y, pageWidth - margin, y + height), 5f, 5f, Paint().apply { color = flagFill })
            canvas.drawRect(margin, y, margin + 3.5f, y + height, Paint().apply { color = flagBar })
            var inner = y + 9f
            inner += draw(layout(item.headline, valuePaint, contentWidth - 28f), margin + 14f, inner) + 3f
            draw(layout(item.detail, notePaint, contentWidth - 28f), margin + 14f, inner)
            height + 8f
        }
    }

    private fun flagHeight(item: SummaryItem.Flag): Float =
        layout(item.headline, valuePaint, contentWidth - 28f).height +
            layout(item.detail, notePaint, contentWidth - 28f).height + 21f

    private fun figureValue(item: SummaryItem.Figure) = item.value + (item.note?.let { "  ($it)" } ?: "")

    private fun indentOf(textIndent: Int) = (textIndent - 2).coerceAtLeast(0) * 6f

    // -- tags ----------------------------------------------------------------------

    private fun tagsWidth(tags: List<String>): Float =
        if (tags.isEmpty()) 0f else tags.sumOf { (tagPaint.measureText(it) + 14f).toDouble() }.toFloat() + 6f

    /** ESTIMATED and IN PROGRESS as outlined labels at the right edge: words, not colour. */
    private fun tags(tags: List<String>, top: Float) {
        var right = pageWidth - margin
        tags.reversed().forEach { tag ->
            val width = tagPaint.measureText(tag) + 10f
            val box = RectF(right - width, top - 1f, right, top + 12f)
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.8f
                color = if (tag == ClinicalSummary.IN_PROGRESS) accent else muted
            }
            canvas.drawRoundRect(box, 6f, 6f, outline)
            canvas.drawText(tag, box.left + 5f, top + 8.5f, tagPaint)
            right -= width + 4f
        }
    }

    // -- text ----------------------------------------------------------------------

    private fun layout(text: String, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(1.5f, 1f)
            .setIncludePad(false)
            .build()

    /** Draws a laid-out block at ([x], [top]) and returns its height. */
    private fun draw(layout: StaticLayout, x: Float, top: Float): Float {
        canvas.save()
        canvas.translate(x, top)
        layout.draw(canvas)
        canvas.restore()
        return layout.height.toFloat()
    }
}
