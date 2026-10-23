package com.motomech.garagelog.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.motomech.garagelog.tracking.Notifs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * App updates from GitHub Releases. CI publishes every merge to main as release "v0.1.N" with
 * garage-log.apk attached, and N is also the APK's version code, so "newer" is a number compare.
 * The app downloads the APK into its own cache, checks it is signed with the same key as the
 * installed app, and hands it to Android's installer, which shows its "Update this app?" screen.
 * On Android 12+ that screen may be skipped once Garage Log installed its own previous update.
 */
object Updates {
    private const val PREFS = "updates"
    private const val WORK = "update-check"
    private const val API = "https://api.github.com/repos/motomech/garage-log/releases/latest"
    private const val APK_NAME = "garage-log.apk"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    const val ACTION_INSTALL_STATUS = "com.motomech.garagelog.INSTALL_STATUS"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Release(val code: Long, val name: String, val apkUrl: String, val size: Long, val notes: List<String>)

    data class State(
        val installed: Long = 0,
        val installedName: String = "",
        val auto: Boolean = true,
        val lastCheck: Long = 0,
        val latest: Release? = null,
        val dismissed: Long = 0,
        val checking: Boolean = false,
        /** Bytes downloaded and total while a download runs, else null. */
        val progress: Pair<Long, Long>? = null,
        /** The APK is downloaded and handed to Android's installer. */
        val installing: Boolean = false,
        val error: String? = null,
    ) {
        val available: Release? get() = latest?.takeIf { it.code > installed }
        /** Shown on Garage: a newer version the user hasn't put off with Later. */
        val banner: Release? get() = available?.takeIf { it.code != dismissed || progress != null || installing }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    @Volatile private var cancelled = false
    private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun packageInfo(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    /** Reads saved settings and the installed version. Safe to call often. */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        val info = packageInfo(context)
        val latest = p.getString("latest", null)?.let { runCatching { json.decodeFromString<Release>(it) }.getOrNull() }
        _state.value = State(
            installed = PackageInfoCompat.getLongVersionCode(info), installedName = info.versionName ?: "",
            auto = p.getBoolean("auto", true), lastCheck = p.getLong("lastCheck", 0),
            latest = latest, dismissed = p.getLong("dismissed", 0), error = p.getString("lastError", null),
        )
        // An APK left over from an update that has since installed (or an older one) is no longer needed.
        apkDir(context).listFiles()?.forEach { f ->
            val code = f.name.removePrefix("garage-log-").removeSuffix(".apk").toLongOrNull()
            if (code == null || code <= _state.value.installed) f.delete()
        }
    }

    fun setAuto(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("auto", on).apply()
        _state.update { it.copy(auto = on) }
        if (on) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** "Later" on the Garage card: hide it until a newer version comes out. */
    fun dismiss(context: Context) {
        val code = _state.value.available?.code ?: return
        prefs(context).edit().putLong("dismissed", code).apply()
        _state.update { it.copy(dismissed = code) }
    }

    /** Once a day with a network connection. Re-run on every app start; KEEP leaves a scheduled one alone. */
    fun schedule(context: Context) {
        init(context)
        if (!_state.value.auto) return
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** On app open: checks in the background if the last check is more than 6 hours old. */
    fun checkIfDue(context: Context) {
        init(context)
        val s = _state.value
        if (!s.auto || s.checking || System.currentTimeMillis() - s.lastCheck < CHECK_EVERY_MS) return
        val app = context.applicationContext
        Thread { check(app) }.start()
    }

    /** Asks GitHub for the latest release. Returns it, or null if there is none with an APK. */
    fun check(context: Context): Result<Release?> {
        init(context)
        _state.update { it.copy(checking = true) }
        val res = runCatching { fetchLatest() }
        val e = prefs(context).edit()
        res.onSuccess { r ->
            val now = System.currentTimeMillis()
            e.putLong("lastCheck", now).remove("lastError")
            if (r != null) e.putString("latest", json.encodeToString(Release.serializer(), r)) else e.remove("latest")
            _state.update { it.copy(checking = false, lastCheck = now, latest = r, error = null) }
        }.onFailure { err ->
            val msg = err.message ?: err.javaClass.simpleName
            e.putString("lastError", msg)
            _state.update { it.copy(checking = false, error = msg) }
        }
        e.apply()
        return res
    }

    private fun fetchLatest(): Release? {
        val conn = URL(API).openConnection() as HttpURLConnection
        val body = try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "GarageLog (github.com/motomech/garage-log)")
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val o = json.parseToJsonElement(body).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
        val code = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val asset = o["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content == APK_NAME } ?: return null
        val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return null
        val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0
        return Release(code, tag.removePrefix("v"), url, size, notes(o["body"]?.jsonPrimitive?.content.orEmpty()))
    }

    /**
     * GitHub's generated notes list each merged pull request as
     * "* Add app updates by @motomech in https://github.com/…/pull/6". Keeps just the titles.
     */
    internal fun notes(body: String): List<String> = body.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("* ") || it.startsWith("- ") }
        .map { it.drop(2).replace(Regex("\\s+by @\\S+ in https://\\S+$"), "").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("@") }
        .toList()

    /** Daily check: one notification per new version. */
    fun backgroundCheck(context: Context) {
        val r = check(context).getOrNull() ?: return
        val s = _state.value
        if (r.code <= s.installed) return
        val p = prefs(context)
        if (p.getLong("notified", 0) >= r.code) return
        p.edit().putLong("notified", r.code).apply()
        val what = r.notes.take(3).joinToString("; ").ifEmpty { "Tap to update." }
        Notifs.post(context, Notifs.ID_UPDATE, Notifs.CH_UPDATE, "Garage Log ${r.name} is ready", "$what Your data stays.")
    }

    private fun apkDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    /** True while a trip or a pump stop is running: installing closes the app and would stop tracking. */
    fun blockedByTrip(): Boolean = Repo.current.trip != null || Repo.current.pumpWait != null

    /** Whether Android lets Garage Log hand APKs to the installer. If not, the user has to switch it on once. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun cancel() { cancelled = true }

    /** Android sometimes doesn't report back when its install screen is closed; lets the user start over. */
    fun reset() { _state.update { it.copy(installing = false, progress = null) } }

    /** Downloads the newest APK (or reuses one already downloaded), checks it, and starts the install. Blocking. */
    fun downloadAndInstall(context: Context) {
        init(context)
        val r = _state.value.available ?: return
        if (_state.value.progress != null || _state.value.installing) return
        cancelled = false
        _state.update { it.copy(progress = 0L to r.size, error = null) }
        val res = runCatching {
            val file = File(apkDir(context), "garage-log-${r.code}.apk")
            if (!file.exists() || (r.size > 0 && file.length() != r.size)) download(r, file)
            verify(context, file, r)
            _state.update { it.copy(progress = null, installing = true) }
            install(context, file)
        }
        res.onFailure { err ->
            val msg = if (cancelled) null else (err.message ?: err.javaClass.simpleName)
            _state.update { it.copy(progress = null, installing = false, error = msg) }
        }
    }

    private fun download(r: Release, file: File) {
        val part = File(file.parentFile, file.name + ".part")
        val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "GarageLog (github.com/motomech/garage-log)")
            if (conn.responseCode != 200) error("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
            var done = 0L
            var shown = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) error("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - shown > 256 * 1024) { shown = done; _state.update { it.copy(progress = done to total) } }
                    }
                }
            }
            if (total > 0 && done != total) error("Download stopped early")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(file)) error("Couldn't save the download")
    }

    /** Same app, newer version, same signing key. Anything else is refused before Android sees it. */
    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File, r: Release) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.path, flags)
        if (apk == null) { file.delete(); error("The download is damaged") }
        if (apk.packageName != context.packageName) { file.delete(); error("The download isn't Garage Log") }
        if (PackageInfoCompat.getLongVersionCode(apk) < r.code) { file.delete(); error("The download is an older version") }
        val mine = pm.getPackageInfo(context.packageName, flags)
        if (signatures(apk) != signatures(mine)) { file.delete(); error("The download is signed with a different key") }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs.orEmpty().map { it.toCharsString() }.toSet()
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Lets Android skip its confirm screen when Garage Log installed the current version itself.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite(APK_NAME, 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val i = Intent(context, InstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, i, flags).intentSender)
        }
    }

    internal fun onInstallStatus(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.update { it.copy(installing = false) }
            else -> {
                val msg = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) null
                else "Android didn't install it: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status")
                _state.update { it.copy(installing = false, error = msg) }
            }
        }
    }
}

/** Android's installer reports here: ask the user to confirm, done, or failed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Updates.ACTION_INSTALL_STATUS) Updates.onInstallStatus(context, intent)
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Repo.init(applicationContext)
        Updates.backgroundCheck(applicationContext)
        return Result.success()
    }
}
