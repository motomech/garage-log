package com.motomech.garagelog.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.motomech.garagelog.data.AppData
import com.motomech.garagelog.data.Place
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.addPlace
import com.motomech.garagelog.data.deletePlace
import com.motomech.garagelog.data.placeAt
import com.motomech.garagelog.data.relday
import com.motomech.garagelog.data.unsavedSpots
import com.motomech.garagelog.data.visits
import com.motomech.garagelog.tracking.Station
import com.motomech.garagelog.tracking.Stations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** One quick location fix for the fill-up form and "Save where I am now". Null without permission or signal. */
@SuppressLint("MissingPermission")
suspend fun currentLocation(ctx: Context): Location? {
    if (!ctx.has(Manifest.permission.ACCESS_FINE_LOCATION) && !ctx.has(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
    return suspendCancellableCoroutine { cont ->
        LocationServices.getFusedLocationProviderClient(ctx)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }
}

fun fmtDist(m: Double): String {
    val miles = m / 1609.344
    return when {
        m < 60 -> "here"
        miles < 0.1 -> "< 0.1 mi"
        else -> String.format(Locale.US, "%.1f mi", miles)
    }
}

/* ---------------- Places ---------------- */

@Composable
fun PlacesScreen(d: AppData) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var finding by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Place?>(null) }

    TextButton(onClick = { nav.open(null) }) { Text("‹ Garage", color = G.ink) }
    ScreenTitle("Places")
    Hint("Saved places name the start and end of your trips (\"Home → Work\") and count your visits. They stay on your phone. No gas station lookup happens at a saved place.")
    Button(
        onClick = {
            finding = true
            scope.launch {
                val loc = currentLocation(ctx)
                finding = false
                if (loc == null) nav.toast("Couldn't get your location. Check that Location is allowed.")
                else nav.sheet(SheetReq.PlaceS(loc.latitude, loc.longitude, "Where you are now"))
            }
        },
        enabled = !finding, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = G.accent),
    ) { Text(if (finding) "Finding you…" else "Save where I am now") }

    H2("Saved places")
    ListBox {
        if (d.places.isEmpty()) Empty("None yet. Save Home and Work first.")
        d.places.forEachIndexed { i, p ->
            val v = d.visits(p)
            val last = v.last?.let { relday(it).let { r -> if (r == "Today" || r == "Yesterday") r.lowercase() else r } }
            ListRow(
                p.name, "Within ${p.radiusM} m · " + (last?.let { "last there $it" } ?: "no visits yet") +
                    if (v.count30 > 0) " · ${v.count30} visit${if (v.count30 == 1) "" else "s"} in 30 days" else "",
                first = i == 0, onClick = { toDelete = p },
            )
        }
    }
    val spots = d.unsavedSpots()
    if (spots.isNotEmpty()) {
        H2("Spots you stop at")
        ListBox {
            spots.forEachIndexed { i, s ->
                ListRow(s.label, "${s.count} stop${if (s.count == 1) "" else "s"} in 60 days", first = i == 0,
                    onClick = { nav.sheet(SheetReq.PlaceS(s.lat, s.lon, s.label)) }) {
                    Text("Save", color = G.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    toDelete?.let { p ->
        Confirm("Remove ${p.name}?", "Trips there lose the name. The trips themselves stay.", "Remove",
            onYes = { Repo.update { it.deletePlace(p.id) }; nav.toast("${p.name} removed") }, onClose = { toDelete = null })
    }
}

private val PLACE_NAMES = listOf("Home", "Work", "Gym", "Shop", "Church", "School")
private val RADII = listOf(100, 150, 300, 500)

@Composable
fun PlaceForm(d: AppData, req: SheetReq.PlaceS, onClose: () -> Unit) {
    val nav = LocalNav.current
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150) }
    Text("Save a place", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = G.ink)
    Sub(req.label)
    SuggestField("Name", name, PLACE_NAMES.filter { n -> d.places.none { it.name.equals(n, true) } }, { name = it }, Modifier.fillMaxWidth())
    Sub("Counts as \"there\" within")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RADII.forEach { r -> FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("$r m") }) }
    }
    Hint("100 m fits a house, 300 m or more a big lot or plant.")
    Button(onClick = {
        if (name.isBlank()) { nav.toast("Give the place a name."); return@Button }
        val clash = d.placeAt(req.lat, req.lon)
        Repo.update { it.addPlace(name.trim(), req.lat, req.lon, radius) }
        nav.toast("${name.trim()} saved" + (clash?.let { ". It overlaps ${it.name}; the closer one wins." } ?: ""))
        onClose()
    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text("Save place", fontSize = 16.sp) }
}

/* ---------------- gas station picker (fill-up form) ---------------- */

private sealed interface Lookup {
    data object Loading : Lookup
    data class Done(val why: String, val list: List<Station>) : Lookup
    data class Failed(val why: String) : Lookup
}

/**
 * Suggests the gas station for a new fill-up: around where the last trip ended (the pump, when the
 * car was shut off or the trip ended for this fill-up) or else where the phone is now.
 */
@Composable
fun StationPicker(d: AppData, station: String, onPick: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val stop = d.lastStop?.takeIf { System.currentTimeMillis() - it.at < RECENT_STOP_MS }
    var state by remember { mutableStateOf<Lookup>(Lookup.Loading) }
    var picked by remember { mutableStateOf(station.isNotBlank()) }

    LaunchedEffect(stop?.lat, stop?.lon) {
        state = Lookup.Loading
        val (lat, lon, why) = if (stop != null) {
            val mins = ((System.currentTimeMillis() - stop.at) / 60_000).coerceAtLeast(0)
            Triple(stop.lat, stop.lon, if (mins < 1) "Your trip just ended here." else "Your trip ended here $mins min ago.")
        } else {
            val loc = currentLocation(ctx)
            if (loc == null) { state = Lookup.Failed("Location is off, so no suggestions. Type the station name."); return@LaunchedEffect }
            Triple(loc.latitude, loc.longitude, "Gas stations near where you are now.")
        }
        state = try {
            Lookup.Done(why, withContext(Dispatchers.IO) { Stations.near(lat, lon, SEARCH_RADIUS_M) }.take(5))
        } catch (e: Exception) {
            Lookup.Failed("Couldn't look up stations (no signal?). Type the name.")
        }
        // Preselect the station the car is at, unless the user already chose.
        val s = state
        if (!picked && s is Lookup.Done) {
            val best = s.list.firstOrNull { it.name == stop?.station } ?: s.list.firstOrNull()?.takeIf { it.distM <= MATCH_M }
            if (best != null) onPick(best.name, best.addr)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Sub("Gas station")
        when (val s = state) {
            Lookup.Loading -> Hint("Looking for gas stations…")
            is Lookup.Failed -> Hint(s.why)
            is Lookup.Done -> {
                Hint(if (s.list.isEmpty()) s.why + " No gas stations within a mile." else s.why)
                s.list.forEach { st ->
                    StationRow(st, selected = st.name == station) { picked = true; onPick(st.name, st.addr) }
                }
            }
        }
        SuggestField("Station name (optional)", station, d.fuel.map { it.station }.filter { it.isNotBlank() }.distinct(),
            { picked = true; onPick(it, "") }, Modifier.fillMaxWidth())
        Hint("Station list from OpenStreetMap. Only this spot's location is sent.")
    }
}

@Composable
private fun StationRow(st: Station, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = if (selected) G.okBg else G.card,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) G.ok else G.line),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.name, fontWeight = FontWeight.SemiBold, color = G.ink)
                if (st.addr.isNotBlank()) Text(st.addr, fontSize = 13.sp, color = G.muted)
            }
            Text(fmtDist(st.distM), fontSize = 13.sp, color = G.muted)
        }
    }
}

private const val RECENT_STOP_MS = 3 * 60 * 60_000L
private const val SEARCH_RADIUS_M = 1600
private const val MATCH_M = 150.0
