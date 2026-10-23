package com.motomech.garagelog.data

import org.w3c.dom.Element
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

/**
 * Just enough of an Excel (.xlsx) reader for MotoLog's exports: the first sheet as rows of cells.
 * A cell is a String, a Double (numbers and dates, as Excel stores them) or null when empty.
 */
object Xlsx {
    fun firstSheet(input: InputStream): List<List<Any?>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.endsWith(".xml") || e.name.endsWith(".rels")) files[e.name] = zip.readBytes()
            }
        }
        val sheet = firstSheetPath(files) ?: throw IllegalArgumentException("Not an Excel file")
        val strings = files["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        return readSheet(files.getValue(sheet), strings)
    }

    private fun parse(bytes: ByteArray): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(bytes.inputStream()).documentElement

    private fun Element.kids(name: String): List<Element> {
        val out = ArrayList<Element>()
        val n = childNodes
        for (i in 0 until n.length) (n.item(i) as? Element)?.takeIf { it.localName == name }?.let { out += it }
        return out
    }

    private fun Element.kid(name: String) = kids(name).firstOrNull()

    /** The sheet listed first in the workbook, or else the first worksheet file. */
    private fun firstSheetPath(files: Map<String, ByteArray>): String? {
        runCatching {
            val wb = parse(files.getValue("xl/workbook.xml"))
            val rid = wb.kid("sheets")!!.kids("sheet").first()
                .getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val rels = parse(files.getValue("xl/_rels/workbook.xml.rels"))
            val target = rels.kids("Relationship").first { it.getAttribute("Id") == rid }.getAttribute("Target")
            val path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
            if (path in files) return path
        }
        return files.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.minOrNull()
    }

    private fun sharedStrings(bytes: ByteArray): List<String> = parse(bytes).kids("si").map { si ->
        // Plain <t>, or rich text runs <r><t>; phonetic hints (<rPh>) are left out.
        (si.kids("t") + si.kids("r").flatMap { it.kids("t") }).joinToString("") { it.textContent }.let(::cleanText)
    }

    private fun readSheet(bytes: ByteArray, strings: List<String>): List<List<Any?>> {
        val data = parse(bytes).kid("sheetData") ?: return emptyList()
        val rows = ArrayList<List<Any?>>()
        for (row in data.kids("row")) {
            val at = row.getAttribute("r").toIntOrNull()?.minus(1) ?: rows.size
            while (rows.size < at) rows += emptyList<Any?>()
            val cells = ArrayList<Any?>()
            for (c in row.kids("c")) {
                val col = colIndex(c.getAttribute("r")) ?: cells.size
                while (cells.size < col) cells += null
                val v = c.kid("v")?.textContent
                cells += when (c.getAttribute("t")) {
                    "s" -> v?.toIntOrNull()?.let { strings.getOrNull(it) }
                    "inlineStr" -> c.kid("is")?.let { i -> (i.kids("t") + i.kids("r").flatMap { it.kids("t") }).joinToString("") { it.textContent } }?.let(::cleanText)
                    "str" -> v?.let(::cleanText)
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    "e" -> null
                    else -> v?.toDoubleOrNull()
                }?.takeIf { it !is String || it.isNotEmpty() }
            }
            rows += cells
        }
        return rows
    }

    /** "AB12" → 27 (zero-based column). */
    private fun colIndex(ref: String): Int? {
        var n = 0
        var seen = false
        for (ch in ref) {
            if (ch in 'A'..'Z') { n = n * 26 + (ch - 'A' + 1); seen = true } else break
        }
        return if (seen) n - 1 else null
    }

    private val escape = Regex("_x([0-9A-Fa-f]{4})_")

    /**
     * Undoes Excel's _xHHHH_ escapes, then repairs text that was UTF-8 read as Latin-1
     * (MotoLog's en dash arrives as "â\u0080\u0093").
     */
    fun cleanText(s: String): String {
        val t = escape.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
        if (t.none { it.code in 0x80..0xFF } || t.any { it.code > 0xFF }) return t
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(t.toByteArray(Charsets.ISO_8859_1))).toString()
        } catch (_: CharacterCodingException) {
            t
        }
    }

    private val epoch = LocalDateTime.of(1899, 12, 30, 0, 0)

    /** Excel's day number (with the time as a fraction) as a date and time; strings in ISO form also work. */
    fun dateTime(v: Any?): LocalDateTime? = when (v) {
        is Double -> if (v > 0) epoch.plusNanos((v * 86_400_000).roundToLong() * 1_000_000) else null
        is String -> runCatching { LocalDateTime.parse(v.trim().replace(' ', 'T')) }.getOrNull()
        else -> null
    }

    fun num(v: Any?): Double? = when (v) {
        is Double -> v
        is String -> v.replace(",", "").trim().toDoubleOrNull()
        else -> null
    }

    fun text(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString().trim()
    }
}
