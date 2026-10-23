package com.motomech.garagelog.data

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** One GPS point on a trip's route. t is epoch seconds. */
data class RoutePoint(val lat: Double, val lng: Double, val t: Long)

/** A saved route: its points (trimmed) and the top speed measured from the full set. */
class Route(val points: List<RoutePoint>, val topMph: Double)

/**
 * Trip routes, one small text file per trip in routes/ (kept out of garage.json so that file stays
 * small). The running trip's points go to routes/active.txt and become routes/<tripId>.txt when
 * the trip is saved.
 */
object Routes {
    private lateinit var dir: File
    private val active get() = File(dir, "active.txt")
    private fun file(tripId: Long) = File(dir, "$tripId.txt")

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, "routes").apply { mkdirs() }
    }

    /** A new trip started: drop anything left over from an unfinished one. */
    @Synchronized
    fun begin() { active.delete() }

    @Synchronized
    fun add(points: List<RoutePoint>) {
        if (points.isEmpty()) return
        runCatching { active.appendText(points.joinToString("") { line(it) }) }
    }

    /** The trip was saved as tripId (null if it was too short to keep): trim and store its route. */
    @Synchronized
    fun finish(tripId: Long?) {
        val raw = if (active.exists()) parse(active.readLines()) else emptyList()
        active.delete()
        if (tripId == null || raw.size < 2) return
        val kept = simplify(raw, TRIM_M)
        runCatching {
            file(tripId).writeText(String.format(Locale.US, "top,%.1f\n", topSpeedMph(raw)) + kept.joinToString("") { line(it) })
        }
    }

    fun has(tripId: Long) = file(tripId).exists()

    fun load(tripId: Long): Route? {
        val f = file(tripId)
        if (!f.exists()) return null
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        val top = lines.firstOrNull()?.takeIf { it.startsWith("top,") }?.substringAfter(",")?.toDoubleOrNull() ?: 0.0
        val pts = parse(lines)
        return if (pts.size < 2) null else Route(pts, top)
    }

    fun delete(tripId: Long) { file(tripId).delete() }

    fun deleteAll() { saved().forEach { it.delete() } }

    fun bytesUsed(): Long = saved().sumOf { it.length() }

    fun count(): Int = saved().size

    /** Removes routes whose trip is gone (deleted trip or vehicle) or older than the keep setting. */
    fun prune(d: AppData) {
        val cutoff = d.routes.keepDays.takeIf { it > 0 }?.let { addDays(today(), -it) }
        val keep = d.trips.filter { cutoff == null || it.date >= cutoff }.map { it.id }.toSet()
        // A route written in the last few minutes may belong to a trip saved after d was read.
        val recent = System.currentTimeMillis() - 10 * 60_000L
        saved().forEach { f -> if (f.nameWithoutExtension.toLongOrNull() !in keep && f.lastModified() < recent) f.delete() }
    }

    private fun saved(): List<File> =
        dir.listFiles()?.filter { it.name != active.name && it.extension == "txt" }.orEmpty()

    private fun line(p: RoutePoint) = String.format(Locale.US, "%.5f,%.5f,%d\n", p.lat, p.lng, p.t)

    private fun parse(lines: List<String>): List<RoutePoint> = lines.mapNotNull { l ->
        val f = l.split(',')
        if (f.size != 3) return@mapNotNull null
        val lat = f[0].toDoubleOrNull(); val lng = f[1].toDoubleOrNull(); val t = f[2].toLongOrNull()
        if (lat == null || lng == null || t == null) null else RoutePoint(lat, lng, t)
    }

    private const val TRIM_M = 6.0
    private const val METERS_PER_DEG = 111_320.0
    private const val MPS_TO_MPH = 2.236936

    private fun meters(a: RoutePoint, b: RoutePoint): Double {
        val x = (b.lng - a.lng) * METERS_PER_DEG * cos(Math.toRadians((a.lat + b.lat) / 2))
        val y = (b.lat - a.lat) * METERS_PER_DEG
        return hypot(x, y)
    }

    /** Fastest speed held over at least 10 seconds, so one jumpy GPS point can't set it. */
    private fun topSpeedMph(pts: List<RoutePoint>): Double {
        var best = 0.0
        var j = 0
        var dist = 0.0
        for (i in pts.indices) {
            while (j < pts.size - 1 && pts[j].t - pts[i].t < 10) { dist += meters(pts[j], pts[j + 1]); j++ }
            val secs = pts[j].t - pts[i].t
            if (secs >= 10) best = maxOf(best, dist / secs * MPS_TO_MPH)
            if (i < j) dist -= meters(pts[i], pts[i + 1])
        }
        return best
    }

    /** Douglas-Peucker: drops points that sit within tol meters of the line through their neighbours. */
    private fun simplify(pts: List<RoutePoint>, tol: Double): List<RoutePoint> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size).also { it[0] = true; it[pts.size - 1] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to pts.size - 1) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var far = -1
            var farD = tol
            for (i in a + 1 until b) {
                val dd = offLine(pts[i], pts[a], pts[b])
                if (dd > farD) { farD = dd; far = i }
            }
            if (far >= 0) { keep[far] = true; stack.add(a to far); stack.add(far to b) }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun offLine(p: RoutePoint, a: RoutePoint, b: RoutePoint): Double {
        val k = METERS_PER_DEG * cos(Math.toRadians(a.lat))
        val bx = (b.lng - a.lng) * k; val by = (b.lat - a.lat) * METERS_PER_DEG
        val px = (p.lng - a.lng) * k; val py = (p.lat - a.lat) * METERS_PER_DEG
        val len = hypot(bx, by)
        if (len < 0.01) return hypot(px, py)
        val u = ((px * bx + py * by) / (len * len)).coerceIn(0.0, 1.0)
        return hypot(px - u * bx, py - u * by).let { abs(it) }
    }
}
