package com.motomech.garagelog.tracking

import android.content.Context
import android.location.Geocoder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.distanceM
import com.motomech.garagelog.data.labelTripEnd
import com.motomech.garagelog.data.placeAt
import com.motomech.garagelog.data.vehicle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Station(val name: String, val addr: String, val lat: Double, val lon: Double, val distM: Double)

/**
 * Gas stations from OpenStreetMap, through the public Overpass API (free, no key). Only the
 * point being looked up is sent. Call from a background thread.
 */
object Stations {
    private const val URL_OVERPASS = "https://overpass-api.de/api/interpreter"
    private val json = Json { ignoreUnknownKeys = true }

    /** Stations within radiusM of the point, closest first. Throws on network trouble. */
    fun near(lat: Double, lon: Double, radiusM: Int): List<Station> {
        val ll = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val q = "[out:json][timeout:15];(node[\"amenity\"=\"fuel\"](around:$radiusM,$ll);" +
            "way[\"amenity\"=\"fuel\"](around:$radiusM,$ll););out center tags;"
        val conn = URL(URL_OVERPASS).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "GarageLog/0.1 (personal vehicle log; github.com/motomech/garage-log)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            if (conn.responseCode != 200) error("Overpass answered ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(body, lat, lon)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, lat: Double, lon: Double): List<Station> {
        val elements = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray ?: return emptyList()
        return elements.mapNotNull { el ->
            val o = el.jsonObject
            val center = o["center"]?.jsonObject ?: o
            val slat = center["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val slon = center["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val tags = o["tags"]?.jsonObject
            fun tag(k: String) = tags?.get(k)?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            val name = tag("name") ?: tag("brand") ?: "Gas station"
            val addr = listOfNotNull(tag("addr:housenumber"), tag("addr:street")).joinToString(" ")
            Station(name, addr, slat, slon, distanceM(lat, lon, slat, slon))
        }.sortedBy { it.distM }.distinctBy { it.name to it.addr }
    }

    /** Street address of a point from Android's geocoder, if it has one. */
    @Suppress("DEPRECATION")
    fun address(context: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(context, Locale.US).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").ifBlank { a.locality }
    }.getOrNull()
}

/**
 * Runs after a trip ends away from a saved place: asks for gas stations within 100 m of where the
 * car stopped. On a match the trip is labeled with the station and the user is offered a fill-up.
 */
class StopWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Repo.init(applicationContext)
        val tripId = inputData.getLong(K_TRIP, -1)
        val lat = inputData.getDouble(K_LAT, Double.NaN)
        val lon = inputData.getDouble(K_LON, Double.NaN)
        val endedAt = inputData.getLong(K_AT, 0)
        val ask = inputData.getBoolean(K_ASK, true)
        if (lat.isNaN() || lon.isNaN()) return Result.success()
        val station = try {
            Stations.near(lat, lon, MATCH_RADIUS_M).firstOrNull()
        } catch (e: Exception) {
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }
        val addr = if (station == null) Stations.address(applicationContext, lat, lon) else null
        Repo.update { it.labelTripEnd(tripId, station?.name, station?.addr?.ifBlank { null }, addr) }
        val trip = Repo.current.trips.firstOrNull { it.id == tripId }
        val v = trip?.let { Repo.current.vehicle(it.vid) }
        // A late answer (no signal at the pump, say) still labels the trip, but the moment to ask has passed.
        if (ask && station != null && v != null && System.currentTimeMillis() - endedAt < ASK_WITHIN_MS) {
            val where = if (station.addr.isNotBlank()) " at ${station.addr}" else ""
            Notifs.post(applicationContext, Notifs.ID_TRIP_DONE, Notifs.CH_ALERT, "At ${station.name}?",
                "${v.label} stopped$where. Getting gas? Tap to log a fill-up.", Notifs.ACTION_LOG_FUEL)
        }
        return Result.success()
    }

    companion object {
        private const val K_TRIP = "trip"
        private const val K_LAT = "lat"
        private const val K_LON = "lon"
        private const val K_AT = "at"
        private const val K_ASK = "ask"
        private const val MATCH_RADIUS_M = 100
        private const val ASK_WITHIN_MS = 30 * 60_000L

        /**
         * Queues the lookup for a trip that just ended, unless it ended at a saved place.
         * ask=false labels the trip without offering a fill-up (the user is already logging one).
         */
        fun enqueue(context: Context, tripId: Long, lat: Double, lon: Double, ask: Boolean) {
            if (Repo.current.placeAt(lat, lon) != null) return
            val req = OneTimeWorkRequestBuilder<StopWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(K_TRIP to tripId, K_LAT to lat, K_LON to lon, K_AT to System.currentTimeMillis(), K_ASK to ask))
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
