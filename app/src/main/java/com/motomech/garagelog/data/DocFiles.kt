package com.motomech.garagelog.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Document files (photos and PDFs) live in the app's private "docs" folder; garage.json only holds their names.
 * They are left out of Android's own backup (its 25 MB limit would make it skip everything) and go into
 * the Google Drive backup zip instead (see Backup).
 */
object DocFiles {
    private const val MAX_SIDE = 2000
    private val pdfLock = Any()

    fun dir(context: Context): File = File(context.filesDir, "docs").apply { mkdirs() }
    fun file(context: Context, p: DocPage): File = File(dir(context), p.file)

    /** A place for the camera to write a photo before it is shrunk and stored. */
    fun cameraUri(context: Context): Uri {
        val f = File(File(context.cacheDir, "camera").apply { mkdirs() }, "shot.jpg")
        f.delete()
        return FileProvider.getUriForFile(context, authority(context), f)
    }

    fun authority(context: Context) = context.packageName + ".files"

    private fun newName(context: Context, ext: String): String {
        var n = System.currentTimeMillis()
        while (File(dir(context), "$n.$ext").exists()) n++
        return "$n.$ext"
    }

    /** Copies a picked or shot file into the docs folder. Photos are turned upright and shrunk to 2000 px. */
    fun import(context: Context, uri: Uri): DocPage? = runCatching {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: ""
        val isPdf = mime == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")
        if (isPdf) {
            val name = newName(context, "pdf")
            cr.openInputStream(uri)!!.use { input -> File(dir(context), name).outputStream().use { input.copyTo(it) } }
            DocPage(name, pdf = true)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            var bmp = cr.openInputStream(uri)!!.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val rotation = runCatching { cr.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
            val scale = MAX_SIDE.toFloat() / max(bmp.width, bmp.height)
            if (rotation != 0 || scale < 1f) {
                val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val name = newName(context, "jpg")
            File(dir(context), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            DocPage(name)
        }
    }.getOrNull()

    fun delete(context: Context, pages: List<DocPage>) = pages.forEach { file(context, it).delete() }

    /**
     * Removes files no document points to, such as photos added to a form that was then cancelled.
     * Only files older than an hour, so a form that is open right now keeps its new photos.
     */
    fun cleanOrphans(context: Context, d: AppData) {
        val used = d.docs.flatMap { doc -> doc.pages.map { it.file } }.toSet()
        val cutoff = System.currentTimeMillis() - 3_600_000
        dir(context).listFiles()?.forEach { if (it.name !in used && it.lastModified() < cutoff) it.delete() }
    }

    fun pdfPageCount(context: Context, p: DocPage): Int = synchronized(pdfLock) {
        runCatching {
            ParcelFileDescriptor.open(file(context, p), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
        }.getOrDefault(0)
    }

    /** Draws one page (a photo, or page [index] of a PDF) about [width] px wide. */
    fun render(context: Context, p: DocPage, index: Int, width: Int): Bitmap? = runCatching {
        val f = file(context, p)
        if (p.pdf) synchronized(pdfLock) {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    r.openPage(index).use { page ->
                        val h = (width.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** Every page of a document in order, PDFs expanded: (file, page index inside it). */
    fun allPages(context: Context, doc: Doc): List<Pair<DocPage, Int>> = doc.pages.flatMap { p ->
        if (p.pdf) (0 until pdfPageCount(context, p).coerceAtLeast(1)).map { p to it } else listOf(p to 0)
    }

    fun shareUris(context: Context, doc: Doc): List<Uri> =
        doc.pages.map { FileProvider.getUriForFile(context, authority(context), file(context, it)) }
}
