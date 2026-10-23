package com.motomech.garagelog.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motomech.garagelog.R
import com.motomech.garagelog.data.Range
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.Routes
import com.motomech.garagelog.data.activeId
import com.motomech.garagelog.data.vehicle
import com.motomech.garagelog.data.vehicleForAddress
import com.motomech.garagelog.tracking.BtReceiver
import com.motomech.garagelog.tracking.Notifs
import com.motomech.garagelog.tracking.TripService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { GarageTheme { AppUi() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        Notifs.checkReminders(this)
        Thread { Routes.prune(Repo.current) }.start()
    }

    /** From the "Tap to track this trip" notification: the app is in front now, so the trip can start. */
    private fun handle(intent: Intent?) {
        if (intent?.action == Notifs.ACTION_LOG_FUEL) { UiBus.logFuel.value = true; return }
        if (intent?.action != BtReceiver.ACTION_TAP_START_TRIP) return
        val d = Repo.current
        val v = d.vehicleForAddress(d.connectedAddress) ?: return
        if (d.trip == null) TripService.start(this, v.id, d.connectedAddress!!)
    }
}

enum class Tab(val label: String, @DrawableRes val icon: Int) {
    GARAGE("Garage", R.drawable.ic_car),
    FUEL("Fuel", R.drawable.ic_fuel),
    SERVICE("Service", R.drawable.ic_wrench),
    PARTS("Parts", R.drawable.ic_box),
    COSTS("Costs", R.drawable.ic_chart),
}

sealed interface Page {
    data class VehicleP(val vid: Long) : Page
    data class LinkP(val vid: Long?) : Page
    data object DataP : Page
    data class DocP(val id: Long) : Page
    data class TripP(val tripId: Long) : Page
    data object RoutesP : Page
    data object PlacesP : Page
}

/** Things outside the UI (a notification tap) ask the UI to do. */
object UiBus {
    val logFuel = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** Shared UI actions so screens don't each need a long list of callbacks. */
class Nav(
    val open: (Page?) -> Unit,
    val sheet: (SheetReq?) -> Unit,
    val toast: (String) -> Unit,
)

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

/** Bumped on every resume so permission checks re-run after the user comes back from Settings. */
val LocalResumeTick = staticCompositionLocalOf { 0 }

@Composable
fun AppUi() {
    val d by Repo.data.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.GARAGE) }
    var page by remember { mutableStateOf<Page?>(null) }
    var sheet by remember { mutableStateOf<SheetReq?>(null) }
    // Per-tab vehicle choice; null means "follow the active vehicle".
    var fuelVid by remember { mutableStateOf<Long?>(null) }
    var svcVid by remember { mutableStateOf<Long?>(null) }
    var partsVid by remember { mutableStateOf<Long?>(null) }
    var costVid by remember { mutableStateOf<Long?>(null) }
    var range by remember { mutableStateOf(Range.YEAR) }
    var tick by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    BackHandler(page != null) {
        val p = page
        page = when {
            p is Page.LinkP && p.vid != null -> Page.VehicleP(p.vid)
            p is Page.TripP -> d.trips.firstOrNull { it.id == p.tripId }?.let { Page.VehicleP(it.vid) }
            p is Page.DocP -> d.docs.firstOrNull { it.id == p.id }?.let { Page.VehicleP(it.vid) }
            else -> null
        }
    }
    BackHandler(page == null && tab != Tab.GARAGE) { tab = Tab.GARAGE }

    // A vehicle that was deleted can't stay picked.
    fun pick(v: Long?) = v?.takeIf { d.vehicle(it) != null }
    val active = d.activeId()
    val fv = pick(fuelVid) ?: active
    val sv = pick(svcVid) ?: active
    val pv = pick(partsVid) ?: active

    // "At Murphy USA? Tap to log a fill-up" opens the fill-up form for the vehicle that stopped.
    val logFuel by UiBus.logFuel.collectAsStateWithLifecycle()
    LaunchedEffect(logFuel) {
        if (!logFuel) return@LaunchedEffect
        UiBus.logFuel.value = false
        val vid = Repo.current.lastStop?.vid?.takeIf { Repo.current.vehicle(it) != null } ?: Repo.current.activeId()
        if (vid != null) { tab = Tab.FUEL; page = null; fuelVid = null; sheet = SheetReq.Fuel(null, vid) }
    }

    val nav = remember {
        Nav(
            open = { page = it },
            sheet = { sheet = it },
            toast = { msg -> scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(msg) } },
        )
    }

    CompositionLocalProvider(LocalNav provides nav, LocalResumeTick provides tick) {
        Scaffold(
            containerColor = G.bg,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar(containerColor = G.card) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t && (page == null || t == Tab.GARAGE),
                            onClick = { tab = t; page = null },
                            icon = { GIcon(t.icon) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = G.accent, selectedTextColor = G.accent, indicatorColor = G.warnBg,
                                unselectedIconColor = G.muted, unselectedTextColor = G.muted,
                            ),
                        )
                    }
                }
            },
            floatingActionButton = {
                if (page == null && d.vehicles.isNotEmpty()) {
                    val (label, req) = when (tab) {
                        Tab.FUEL -> "Log fill-up" to SheetReq.Fuel(null, fv!!)
                        Tab.SERVICE -> "Add service" to SheetReq.Service(null, sv!!)
                        Tab.PARTS -> "Add part" to SheetReq.PartS(null, pv!!)
                        else -> null to null
                    }
                    if (label != null && req != null) ExtendedFloatingActionButton(
                        onClick = { sheet = req }, containerColor = G.accent, contentColor = G.bg,
                        text = { Text(label) }, icon = { Text("+") },
                    )
                }
            },
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                    .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val p = page) {
                    is Page.VehicleP -> VehicleScreen(d, p.vid)
                    is Page.LinkP -> LinkScreen(d, p.vid)
                    Page.DataP -> DataScreen(d)
                    is Page.TripP -> TripScreen(d, p.tripId)
                    Page.RoutesP -> RoutesScreen(d)
                    Page.PlacesP -> PlacesScreen(d)
                    is Page.DocP -> DocScreen(d, p.id)
                    null -> when (tab) {
                        Tab.GARAGE -> GarageScreen(d)
                        Tab.FUEL -> if (fv == null) NoVehicles() else FuelScreen(d, fv) { fuelVid = it }
                        Tab.SERVICE -> if (sv == null) NoVehicles() else ServiceScreen(d, sv) { svcVid = it }
                        Tab.PARTS -> if (pv == null) NoVehicles() else PartsScreen(d, pv) { partsVid = it }
                        Tab.COSTS -> if (d.vehicles.isEmpty()) NoVehicles() else
                            CostsScreen(d, pick(costVid), range, { costVid = it }, { range = it })
                    }
                }
            }
        }
        sheet?.let { req -> EntrySheet(d, req) { sheet = null } }
    }
}

@Composable
fun NoVehicles() {
    val nav = LocalNav.current
    ScreenTitle("Nothing here yet")
    Sub("Add a vehicle in the Garage first.")
    GhostButton("Add a vehicle") { nav.open(Page.LinkP(null)) }
}
