package com.motomech.garagelog.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Saved places, trip start/end labels and visit counts.

/** Straight-line distance in meters. */
fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/** The closest saved place whose circle contains the point. */
fun AppData.placeAt(lat: Double?, lon: Double?): Place? {
    if (lat == null || lon == null) return null
    return places.map { it to distanceM(lat, lon, it.lat, it.lon) }
        .filter { (p, d) -> d <= p.radiusM }.minByOrNull { it.second }?.first
}

fun AppData.startLabel(t: Trip): String? =
    placeAt(t.fromLat, t.fromLon)?.name ?: endLabelAt(t.fromLat, t.fromLon) ?: t.fromAddr

fun AppData.endLabel(t: Trip): String? =
    placeAt(t.toLat, t.toLon)?.name ?: t.toStation ?: t.toAddr?.let { if (t.src != null) it else "near $it" } ?: if (t.toLat != null) "unsaved spot" else null

/** A trip start has no lookup of its own; borrow the label of an earlier trip that ended at the same spot. */
private fun AppData.endLabelAt(lat: Double?, lon: Double?): String? {
    if (lat == null || lon == null) return null
    val t = trips.filter { it.toLat != null && distanceM(lat, lon, it.toLat, it.toLon!!) <= 150 }.maxByOrNull { it.id }
    return t?.let { it.toStation ?: it.toAddr?.let { a -> "near $a" } } ?: "unsaved spot"
}

/** "Home → Work", or null when the trip has no GPS points (older trips, manual entries). */
fun AppData.routeLabel(t: Trip): String? {
    val a = startLabel(t)
    val b = endLabel(t)
    if (a == null && b == null) return null
    return "${a ?: "?"} → ${b ?: "?"}"
}

data class Visits(val last: String?, val count30: Int)

/** Trips that ended at the place: when it was last visited and how often in the last 30 days. */
fun AppData.visits(p: Place): Visits {
    val since = addDays(today(), -30)
    val ends = trips.filter { it.toLat != null && distanceM(p.lat, p.lon, it.toLat, it.toLon!!) <= p.radiusM }
    return Visits(ends.maxOfOrNull { it.date }, ends.count { it.date >= since })
}

data class Spot(val lat: Double, val lon: Double, val label: String, val count: Int)

/** Places you keep stopping at that aren't saved and aren't gas stations, most visited first. */
fun AppData.unsavedSpots(limit: Int = 5): List<Spot> {
    val since = addDays(today(), -60)
    val ends = trips.filter { it.date >= since && it.toLat != null && it.toStation == null && placeAt(it.toLat, it.toLon) == null }
    val groups = mutableListOf<MutableList<Trip>>()
    ends.forEach { t ->
        val g = groups.firstOrNull { g -> distanceM(g[0].toLat!!, g[0].toLon!!, t.toLat!!, t.toLon!!) <= 150 }
        if (g != null) g += t else groups += mutableListOf(t)
    }
    return groups.sortedByDescending { it.size }.take(limit).map { g ->
        val newest = g.maxBy { it.id }
        Spot(newest.toLat!!, newest.toLon!!, newest.toAddr?.let { "near $it" } ?: "Unnamed spot", g.size)
    }
}
