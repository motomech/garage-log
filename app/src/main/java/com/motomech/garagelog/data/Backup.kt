package com.motomech.garagelog.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.motomech.garagelog.tracking.Notifs
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Automatic backup: one zip (garage.json plus every document file) saved over the same file each night.
 * The user picks that file once with Android's own "Save to" picker, usually in Google Drive, and the app
 * keeps permission to write it. Nothing here signs in to Google; the Drive app does the uploading.
 * Settings live in the phone's preferences, not in garage.json, so restoring on a new phone doesn't
 * carry over a file permission that only the old phone had.
 */
object Backup {
    private const val PREFS = "backup"
    private const val WORK = "backup"

    data class State(
        val uri: Uri?,
        val name: String?,
        val auto: Boolean,
        val lastAt: Long,
        val lastBytes: Long,
        val lastError: String?,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context): State {
        val p = prefs(context)
        return State(
            p.getString("uri", null)?.let(Uri::parse), p.getString("name", null), p.getBoolean("auto", true),
            p.getLong("lastAt", 0), p.getLong("lastBytes", 0), p.getString("lastError", null),
        )
    }

    /** Saves the file the user picked, keeps permission to it, and makes the first backup there. */
    fun setTarget(context: Context, uri: Uri, name: String?): Result<Long> {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs(context).edit().putString("uri", uri.toString()).putString("name", name).putBoolean("auto", true)
            .putLong("lastStamp", 0).apply()
        schedule(context)
        return run(context, force = true)
    }

    fun setAuto(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("auto", on).apply()
        if (on) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Daily, starting around 3 AM. Re-run on every app start; KEEP leaves a scheduled one alone. */
    fun schedule(context: Context) {
        val st = state(context)
        if (st.uri == null || !st.auto) return
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(3, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** What has changed since: the data file, or the documents folder (a file added or removed). */
    private fun stamp(context: Context): Long = maxOf(Repo.savedAt, DocFiles.dir(context).lastModified())

    /** Writes the backup. Without [force] it does nothing when nothing changed since the last one. Returns its size. */
    fun run(context: Context, force: Boolean): Result<Long> {
        Repo.init(context)
        val st = state(context)
        val uri = st.uri ?: return Result.failure(IllegalStateException("No backup file chosen"))
        val stamp = stamp(context)
        if (!force && st.lastError == null && stamp <= prefs(context).getLong("lastStamp", 0)) return Result.success(st.lastBytes)
        val res = runCatching {
            val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: context.contentResolver.openOutputStream(uri, "w")
                ?: error("Couldn't open the backup file")
            val counter = CountingStream(out)
            counter.use { writeZip(context, it) }
            counter.count
        }
        val e = prefs(context).edit()
        res.onSuccess { bytes ->
            e.putLong("lastAt", System.currentTimeMillis()).putLong("lastBytes", bytes).putLong("lastStamp", stamp).remove("lastError")
        }.onFailure { err ->
            e.putString("lastError", err.message ?: err.javaClass.simpleName)
        }
        e.apply()
        return res
    }

    fun writeZip(context: Context, out: OutputStream) {
        val d = Repo.current
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("garage.json"))
            zip.write(Repo.snapshot().toByteArray())
            zip.closeEntry()
            d.docs.flatMap { it.pages }.distinct().forEach { p ->
                val f = DocFiles.file(context, p)
                if (!f.exists()) return@forEach
                zip.putNextEntry(ZipEntry("docs/" + p.file))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    data class Restored(val vehicles: Int, val docs: Int)

    /** Replaces everything in the app with the contents of a backup zip. Nothing changes if the zip can't be read. */
    fun restore(context: Context, uri: Uri): Result<Restored> = runCatching {
        Repo.init(context)
        val tmp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var jsonText: String? = null
        context.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == "garage.json" -> jsonText = zip.readBytes().decodeToString()
                        name.startsWith("docs/") && !entry.isDirectory -> {
                            val base = name.removePrefix("docs/")
                            // Only plain file names; never let a zip entry point outside the folder.
                            if (base.isNotEmpty() && '/' !in base && '\\' !in base && base != "..") {
                                File(tmp, base).outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                }
            }
        }
        val data = Repo.decode(jsonText ?: error("This file isn't a Garage Log backup"))
        val dir = DocFiles.dir(context)
        dir.listFiles()?.forEach { it.delete() }
        tmp.listFiles()?.forEach { f ->
            val target = File(dir, f.name)
            if (!f.renameTo(target)) f.copyTo(target, overwrite = true)
        }
        tmp.deleteRecursively()
        // A trip in progress or a connection on the old phone doesn't apply here.
        Repo.replaceAll(data.copy(trip = null, connectedAddress = null))
        Restored(data.vehicles.size, data.docs.size)
    }

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val had = Backup.state(applicationContext).lastError
        Backup.run(applicationContext, force = false).onFailure {
            // Say so once per new problem, not every night.
            if (had == null) Notifs.post(
                applicationContext, Notifs.ID_BACKUP_FAILED, Notifs.CH_ALERT, "Backup didn't save",
                "Open Garage Log › Import and export to check the backup file.",
            )
        }
        return Result.success()
    }
}
