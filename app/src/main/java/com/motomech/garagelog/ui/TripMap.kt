package com.motomech.garagelog.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.motomech.garagelog.data.AppData
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.Route
import com.motomech.garagelog.data.Routes
import com.motomech.garagelog.data.TripKind
import com.motomech.garagelog.data.deleteTrip
import com.motomech.garagelog.data.endLabel
import com.motomech.garagelog.data.fdate
import com.motomech.garagelog.data.mi
import com.motomech.garagelog.data.mi1
import com.motomech.garagelog.data.relday
import com.motomech.garagelog.data.routeLabel
import com.motomech.garagelog.data.startLabel
import com.motomech.garagelog.data.vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

// Trip route map (MapLibre with OpenFreeMap's free OpenStreetMap tiles; no API key), the small
// route previews in the trip list, and the Trip routes settings page.

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_COLOR = "#C2410C" // G.accent
private const val START_COLOR = "#0B5E57" // G.ok
private const val END_COLOR = "#1C1B19" // G.ink

/** Loads a trip's route off the main thread. Null while loading or when the trip has none. */
@Composable
fun rememberRoute(tripId: Long): Route? {
    val r by produceState<Route?>(null, tripId) { value = withContext(Dispatchers.IO) { Routes.load(tripId) } }
    return r
}

private fun timeOf(ms: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))

@Composable
fun TripScreen(d: AppData, tripId: Long) {
    val nav = LocalNav.current
    val t = d.trips.firstOrNull { it.id == tripId }
    if (t == null) {
        LaunchedEffect(tripId) { nav.open(null) }
        return
    }
    val v = d.vehicle(t.vid)
    var reload by remember { mutableIntStateOf(0) }
    // (loaded, route): the note about a missing route waits until the file has been checked.
    val state by produceState<Pair<Boolean, Route?>>(false to null, tripId, reload) {
        value = true to withContext(Dispatchers.IO) { Routes.load(tripId) }
    }
    val (loaded, r) = state
    var confirm by remember { mutableStateOf<String?>(null) }
    val from = d.startLabel(t)
    val to = d.endLabel(t)

    TextButton(onClick = { nav.open(Page.VehicleP(t.vid)) }) { Text("‹ ${v?.label ?: "Garage"}", color = G.ink) }
    ScreenTitle(d.routeLabel(t) ?: "Trip",
        listOfNotNull(relday(t.date), if (t.start != null && t.end != null) "${timeOf(t.start)} to ${timeOf(t.end)}" else null)
            .joinToString(" · "))

    if (r != null) {
        RouteMap(r, from, to, Modifier.fillMaxWidth().height(320.dp))
    } else if (loaded) {
        Note(if (t.kind == TripKind.AUTO) "No route for this trip. Trips recorded before route maps, or while route saving was off, have miles only."
            else "Entered by hand, so there's no route.", null)
    }

    val mins = if (t.start != null && t.end != null) ((t.end - t.start) / 60_000.0) else null
    Tiles {
        Tile("Distance", mi1(t.miles), "mi")
        Tile("Time", mins?.roundToInt()?.toString() ?: "—", "min")
        val avg = mins?.takeIf { it >= 1 }?.let { (t.miles / (it / 60)).roundToInt().toString() } ?: "—"
        Tile("Avg / top", "$avg / ${r?.topMph?.takeIf { it > 0 }?.roundToInt() ?: "—"}", "mph")
    }
    ListBox {
        ListRow("Started at ${from ?: "an unknown spot"}", t.start?.let { timeOf(it) }, first = true)
        ListRow("Ended at ${to ?: "an unknown spot"}", listOfNotNull(t.end?.let { timeOf(it) }, t.toAddr).joinToString(" · ").ifBlank { null },
            extra = {
                if (t.toStation != null) {
                    Button(onClick = { nav.sheet(SheetReq.Fuel(null, t.vid)) },
                        colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text("Log fill-up here") }
                }
            })
        ListRow("Odometer", "${mi1(t.odo - t.miles)} → ${mi1(t.odo)} mi")
    }
    if (r != null) GhostButton("Delete route (keep the miles)", Modifier.fillMaxWidth()) { confirm = "route" }
    GhostButton("Delete trip", Modifier.fillMaxWidth()) { confirm = "trip" }
    TextButton(onClick = { nav.open(Page.RoutesP) }) { Text("Route settings", color = G.accent) }

    when (confirm) {
        "route" -> Confirm("Delete this route?", "The map goes away. The trip and its ${mi1(t.miles)} mi stay.", "Delete route",
            onYes = { Routes.delete(t.id); reload++; nav.toast("Route deleted") }, onClose = { confirm = null })
        "trip" -> Confirm("Remove this trip?", "${mi1(t.miles)} mi from ${fdate(t.date)} comes back off the odometer.", "Remove",
            onYes = { Repo.update { it.deleteTrip(t) }; Routes.delete(t.id); nav.open(Page.VehicleP(t.vid)); nav.toast("Trip removed") },
            onClose = { confirm = null })
    }
}

/** The interactive map: route line with start and end pins, zoomed to fit. */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun RouteMap(route: Route, startName: String?, endName: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(ctx.applicationContext)
        MapView(ctx).apply {
            onCreate(null)
            // Let the map have its drags instead of the page scrolling underneath it.
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
    }
    DisposableEffect(lifecycle, mapView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView, route) {
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                runCatching { drawRoute(style, route, startName, endName) }
                runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds(route), 70)) }
            }
        }
    }
    AndroidView({ mapView }, modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, G.line, RoundedCornerShape(14.dp)))
}

private fun drawRoute(style: Style, route: Route, startName: String?, endName: String?) {
    val pts = route.points.map { Point.fromLngLat(it.lng, it.lat) }
    style.addSource(GeoJsonSource("route", Feature.fromGeometry(LineString.fromLngLats(pts))))
    style.addLayer(LineLayer("route-casing", "route").withProperties(
        PropertyFactory.lineColor(AColor.WHITE), PropertyFactory.lineWidth(9f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("route-line", "route").withProperties(
        PropertyFactory.lineColor(AColor.parseColor(ROUTE_COLOR)), PropertyFactory.lineWidth(5f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    pin(style, "start", pts.first(), START_COLOR, startName)
    pin(style, "end", pts.last(), END_COLOR, endName)
}

private fun pin(style: Style, id: String, at: Point, color: String, name: String?) {
    style.addSource(GeoJsonSource(id, Feature.fromGeometry(at)))
    style.addLayer(CircleLayer("$id-pin", id).withProperties(
        PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(AColor.parseColor(color)),
        PropertyFactory.circleStrokeColor(AColor.WHITE), PropertyFactory.circleStrokeWidth(3f)))
    if (name != null) style.addLayer(SymbolLayer("$id-label", id).withProperties(
        PropertyFactory.textField(name), PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
        PropertyFactory.textSize(13f), PropertyFactory.textOffset(arrayOf(0f, -1.5f)),
        PropertyFactory.textColor(AColor.parseColor(END_COLOR)),
        PropertyFactory.textHaloColor(AColor.WHITE), PropertyFactory.textHaloWidth(2f),
        PropertyFactory.textAllowOverlap(true)))
}

private fun bounds(route: Route): LatLngBounds {
    var n = route.points.maxOf { it.lat }; var s = route.points.minOf { it.lat }
    var e = route.points.maxOf { it.lng }; var w = route.points.minOf { it.lng }
    // A very short trip still gets a street-level view rather than maximum zoom.
    if (n - s < 0.002) { val c = (n + s) / 2; n = c + 0.001; s = c - 0.001 }
    if (e - w < 0.002) { val c = (e + w) / 2; e = c + 0.001; w = c - 0.001 }
    return LatLngBounds.Builder().include(LatLng(n, e)).include(LatLng(s, w)).build()
}

/** Small route preview for the trip list: just the line, drawn on the phone (no map download). */
@Composable
fun RouteThumb(tripId: Long) {
    val r = rememberRoute(tripId) ?: return
    val accent = G.accent
    Box(Modifier.size(52.dp, 44.dp).clip(RoundedCornerShape(8.dp)).background(G.chip).border(1.dp, G.line, RoundedCornerShape(8.dp))) {
        Canvas(Modifier.size(52.dp, 44.dp)) {
            val k = cos(Math.toRadians(r.points.first().lat))
            val xs = r.points.map { it.lng * k }; val ys = r.points.map { -it.lat }
            val x0 = xs.min(); val y0 = ys.min()
            val span = maxOf(xs.max() - x0, ys.max() - y0, 1e-6)
            val pad = 6.dp.toPx()
            val scale = minOf(size.width - 2 * pad, size.height - 2 * pad) / span
            val ox = (size.width - (xs.max() - x0) * scale) / 2
            val oy = (size.height - (ys.max() - y0) * scale) / 2
            val path = Path()
            xs.indices.forEach { i ->
                val x = (ox + (xs[i] - x0) * scale).toFloat(); val y = (oy + (ys[i] - y0) * scale).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun RoutesScreen(d: AppData) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val used by produceState(0L to 0, tick) { value = withContext(Dispatchers.IO) { Routes.bytesUsed() to Routes.count() } }
    var confirmAll by remember { mutableStateOf(false) }

    TextButton(onClick = { nav.open(null) }) { Text("‹ Garage", color = G.ink) }
    ScreenTitle("Trip routes")
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Save trip routes", color = G.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(d.routes.save, { on -> Repo.update { it.copy(routes = it.routes.copy(save = on)) } },
                colors = SwitchDefaults.colors(checkedTrackColor = G.ok))
        }
        Gap(4.dp)
        Sub("Keep routes for")
        val opts = listOf(0 to "Forever", 365 to "1 year", 90 to "90 days")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            opts.forEachIndexed { i, (days, label) ->
                SegmentedButton(selected = d.routes.keepDays == days, shape = SegmentedButtonDefaults.itemShape(i, opts.size), onClick = {
                    Repo.update { it.copy(routes = it.routes.copy(keepDays = days)) }
                    Routes.prune(Repo.current); tick++
                }) { Text(label) }
            }
        }
        Gap(4.dp)
        Row {
            Text("Space used", color = G.ink, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.1f MB", used.first / 1_048_576.0), color = G.ink, fontWeight = FontWeight.SemiBold)
        }
        Hint("${mi(used.second.toDouble())} trip${if (used.second == 1) "" else "s"} with a route. A year of driving takes about 5 MB.")
    }
    GhostButton("Delete all routes", Modifier.fillMaxWidth()) { confirmAll = true }
    GhostButton("Clear downloaded map images", Modifier.fillMaxWidth()) {
        runCatching {
            MapLibre.getInstance(ctx.applicationContext)
            OfflineManager.getInstance(ctx).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = nav.toast("Map images cleared")
                override fun onError(message: String) = nav.toast("Couldn't clear map images")
            })
        }
    }
    Hint("Deleting a route keeps the trip and its miles. Routes stay on this phone and are included in Android's backup. " +
        "Map images are downloaded only when you open a trip's map. Map data © OpenStreetMap contributors, tiles by OpenFreeMap.")
    if (confirmAll) Confirm("Delete all routes?", "Every trip keeps its miles, but no trip will have a map.", "Delete all",
        onYes = { Routes.deleteAll(); tick++; nav.toast("All routes deleted") }, onClose = { confirmAll = false })
}
