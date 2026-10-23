package com.motomech.garagelog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motomech.garagelog.R
import com.motomech.garagelog.data.AppData
import com.motomech.garagelog.data.CAT_FUEL
import com.motomech.garagelog.data.CAT_OTHER
import com.motomech.garagelog.data.CAT_PARTS
import com.motomech.garagelog.data.CAT_SERVICE
import com.motomech.garagelog.data.Level
import com.motomech.garagelog.data.Range
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.Reminder
import com.motomech.garagelog.data.Trip
import com.motomech.garagelog.data.TripKind
import com.motomech.garagelog.data.Vehicle
import com.motomech.garagelog.data.activeId
import com.motomech.garagelog.data.addDays
import com.motomech.garagelog.data.costItems
import com.motomech.garagelog.data.deleteReminder
import com.motomech.garagelog.data.deleteTrip
import com.motomech.garagelog.data.fdate
import com.motomech.garagelog.data.fmtSigned
import com.motomech.garagelog.data.fuelRows
import com.motomech.garagelog.data.fuelStats
import com.motomech.garagelog.data.installPart
import com.motomech.garagelog.data.isConnected
import com.motomech.garagelog.data.liveOdo
import com.motomech.garagelog.data.mi
import com.motomech.garagelog.data.mi1
import com.motomech.garagelog.data.milesLast30
import com.motomech.garagelog.data.milesSince
import com.motomech.garagelog.data.relday
import com.motomech.garagelog.data.start
import com.motomech.garagelog.data.today
import com.motomech.garagelog.data.usd
import com.motomech.garagelog.data.usd0
import com.motomech.garagelog.data.vehicle
import com.motomech.garagelog.data.vehicleReminders
import com.motomech.garagelog.tracking.TripService
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

private fun String.lowerFirst() = replaceFirstChar { it.lowercase(Locale.US) }

@Composable
private fun endTrip(): () -> Unit {
    val ctx = LocalContext.current
    return { TripService.end(ctx) }
}

/* ---------------- Garage ---------------- */

@Composable
fun GarageScreen(d: AppData) {
    val nav = LocalNav.current
    val active = d.activeId()
    val due = d.vehicles.sumOf { v -> d.vehicleReminders(v.id).count { it.second.level != Level.OK } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScreenTitle(
            "Garage",
            "${d.vehicles.size} vehicle${if (d.vehicles.size == 1) "" else "s"}" +
                if (due > 0) " · $due service${if (due == 1) "" else "s"} due soon" else "",
            Modifier.weight(1f),
        )
        FilledIconButton(onClick = { nav.open(Page.DataP) },
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = G.chip, contentColor = G.ink)) {
            GIcon(R.drawable.ic_swap)
        }
        FilledIconButton(onClick = { nav.open(Page.LinkP(null)) },
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = G.accent, contentColor = G.bg)) {
            Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
    TripBar(d, endTrip())
    TrackingSetup(d)
    if (d.vehicles.isEmpty()) {
        GCard {
            Text("Welcome to Garage Log", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = G.ink)
            Sub("Add your first vehicle and pick the Bluetooth device it connects to (radio or hands-free). " +
                "Trips are then measured automatically and added to its odometer.")
            Gap()
            Button(onClick = { nav.open(Page.LinkP(null)) }, colors = ButtonDefaults.buttonColors(containerColor = G.accent)) {
                Text("Add a vehicle")
            }
        }
        return
    }
    d.vehicles.forEach { v -> VehicleCard(d, v, v.id == active) }
    val spent = d.costItems(null, addDays(today(), -30)).sumOf { it.amt }
    Tiles {
        Tile("Miles, last 30 days", mi(d.milesLast30()))
        Tile("Spent, last 30 days", usd0(spent))
    }
}

@Composable
private fun VehicleCard(d: AppData, v: Vehicle, isActive: Boolean) {
    val nav = LocalNav.current
    val rem = d.vehicleReminders(v.id).firstOrNull()
    val last = d.trips.filter { it.vid == v.id }.maxWithOrNull(compareBy<Trip>({ it.date }, { it.id }))
    val sub = when {
        d.trip?.vid == v.id -> "Trip in progress · +${mi1(d.trip?.miles ?: 0.0)} mi"
        last == null -> "No trips yet"
        last.kind == TripKind.AUTO -> "Last trip ${relday(last.date).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }} · ${mi1(last.miles)} mi"
        else -> "Last updated ${fdate(last.date)}"
    }
    GCard(border = if (isActive) G.ok else G.line, onClick = { nav.open(Page.VehicleP(v.id)) }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (v.subtitle.isNotBlank()) Sub(v.subtitle)
                Text(v.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = G.ink)
            }
            StatusPill(d, v)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(mi(d.liveOdo(v)), style = Big.copy(fontSize = 44.sp), color = G.ink)
            Sub(" mi", Modifier.padding(bottom = 8.dp).weight(1f))
            if (isActive) Pill("Active", Level.OK)
        }
        Sub(sub)
        Gap(4.dp)
        if (rem != null) Note("${rem.first.type}: ${rem.second.text.lowerFirst()}", rem.second.level)
        else Note("No reminders set", Level.OK)
    }
}

@Composable
private fun StatusPill(d: AppData, v: Vehicle) = when {
    d.isConnected(v.id) -> Pill("Connected", live = true)
    v.btAddress != null -> Pill("Auto-tracking", Level.OK, icon = R.drawable.ic_bt)
    else -> Pill("Manual entry")
}

/* ---------------- Vehicle ---------------- */

@Composable
fun VehicleScreen(d: AppData, vid: Long) {
    val nav = LocalNav.current
    val v = d.vehicle(vid)
    if (v == null) {
        LaunchedEffect(vid) { nav.open(null) }
        return
    }
    val conn = d.isConnected(v.id)
    var tripToDelete by remember { mutableStateOf<Trip?>(null) }
    var remToDelete by remember { mutableStateOf<Reminder?>(null) }

    TextButton(onClick = { nav.open(null) }) { Text("‹ Garage", color = G.ink) }
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (v.subtitle.isNotBlank()) Sub(v.subtitle)
            Text(v.label, style = Big.copy(fontSize = 36.sp), color = G.ink)
        }
        TextButton(onClick = { nav.open(Page.LinkP(v.id)) }) { Text("Edit", color = G.accent) }
    }
    if (d.trip?.vid == v.id) TripBar(d, endTrip())
    if (v.btAddress != null) {
        GCard(bg = G.okBg, border = G.okBg) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GIcon(R.drawable.ic_bt, 24.dp, G.ok)
                Column {
                    Text(if (conn) "Connected now" else "Linked to car Bluetooth", color = G.ok, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${v.btName ?: v.btAddress} · trips tracked automatically", color = G.ok, fontSize = 13.sp)
                }
            }
        }
    } else {
        GCard {
            Text("Manual entry", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = G.ink)
            Sub("No Bluetooth linked. Enter the odometer when you drive it, or link a plug-in Bluetooth adapter that powers on with the ignition.")
            Gap(4.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.sheet(SheetReq.Reading(v.id)) }, colors = ButtonDefaults.buttonColors(containerColor = G.accent)) {
                    Text("Add mileage reading")
                }
                GhostButton("Link Bluetooth") { nav.open(Page.LinkP(v.id)) }
            }
        }
    }
    val monthStart = today().take(7) + "-01"
    val mt = d.trips.filter { it.vid == v.id && it.date >= monthStart && it.kind == TripKind.AUTO }
    GCard {
        Sub("Odometer")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(mi(d.liveOdo(v)), style = Big.copy(fontSize = 52.sp), color = G.ink)
            Sub(" mi", Modifier.padding(bottom = 10.dp))
        }
        if (d.trip?.vid == v.id) Text("+${mi1(d.trip?.miles ?: 0.0)} mi on this trip so far", color = G.ok, fontWeight = FontWeight.SemiBold)
        if (v.btAddress != null) Sub("This month: ${mi(mt.sumOf { it.miles })} mi in ${mt.size} trip${if (mt.size == 1) "" else "s"}")
        Gap(4.dp)
        GhostButton("Correct odometer", Modifier.fillMaxWidth()) { nav.sheet(SheetReq.Correct(v.id)) }
    }
    val rems = d.vehicleReminders(v.id)
    if (rems.isNotEmpty()) {
        H2("Reminders")
        ListBox {
            rems.forEachIndexed { i, (r, st) ->
                ListRow(r.type, "Every " + listOfNotNull(
                    r.everyMi.takeIf { it > 0 }?.let { "${mi(it.toDouble())} mi" },
                    r.everyMo.takeIf { it > 0 }?.let { "$it months" },
                ).joinToString(" or "), first = i == 0, onClick = { remToDelete = r }) { Pill(st.text, st.level) }
            }
        }
    }
    H2(if (v.btAddress != null) "Trips" else "Mileage log")
    val trips = d.trips.filter { it.vid == v.id }.sortedWith(compareByDescending<Trip> { it.date }.thenByDescending { it.id }).take(30)
    ListBox {
        if (trips.isEmpty()) Empty(if (v.btAddress != null) "No trips yet. Connect the linked Bluetooth device to start one." else "No readings yet.")
        trips.forEachIndexed { i, t ->
            val (title, how) = when (t.kind) {
                TripKind.AUTO -> "Trip" to "Bluetooth"
                TripKind.MANUAL -> "Mileage reading" to "entered by hand"
                else -> "Odometer corrected" to "set to ${mi(t.odo)}"
            }
            ListRow(title, "${relday(t.date)} · $how", "${fmtSigned(t.miles)} mi", first = i == 0, onClick = { tripToDelete = t })
        }
    }
    tripToDelete?.let { t ->
        Confirm("Remove this entry?", "${fmtSigned(t.miles)} mi from ${fdate(t.date)} comes back off the odometer.", "Remove",
            onYes = { Repo.update { it.deleteTrip(t) }; nav.toast("Entry removed") }, onClose = { tripToDelete = null })
    }
    remToDelete?.let { r ->
        Confirm("Stop reminding?", "Delete the ${r.type} reminder for ${v.label}. Service history stays.", "Delete reminder",
            onYes = { Repo.update { it.deleteReminder(r.id) }; nav.toast("Reminder deleted") }, onClose = { remToDelete = null })
    }
}

@Composable
fun Confirm(title: String, text: String, yes: String, onYes: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onYes(); onClose() }) { Text(yes, color = G.bad) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun Header(title: String, d: AppData, vid: Long?, allowAll: Boolean = false, onPick: (Long?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScreenTitle(title, modifier = Modifier.weight(1f))
        VehicleChip(d, vid, allowAll, onPick)
    }
    TripBar(d, endTrip())
}

/* ---------------- Fuel ---------------- */

@Composable
fun FuelScreen(d: AppData, vid: Long, onPick: (Long?) -> Unit) {
    val nav = LocalNav.current
    val st = d.fuelStats(vid)
    Header("Fuel", d, vid) { onPick(it) }
    Tiles {
        Tile("Average", st.avg?.let { String.format(Locale.US, "%.1f", it) } ?: "—", "mpg")
        Tile("Fuel cost", st.cpm?.let { String.format(Locale.US, "$%.2f", it) } ?: "—", "/mi")
    }
    if (st.series.size > 1) MpgChart(st.series)
    H2("Fill-ups")
    val rows = d.fuelRows(vid).reversed()
    ListBox {
        if (rows.isEmpty()) Empty("No fill-ups for this vehicle yet. Tap Log fill-up.")
        rows.forEachIndexed { i, r ->
            val f = r.f
            ListRow(
                "${fdate(f.date)} · ${String.format(Locale.US, "%.1f", f.gal)} gal",
                "${mi(f.odo)} mi · ${String.format(Locale.US, "$%.2f", f.price)}/gal${if (f.full) "" else " · partial"}",
                usd(f.total), r.mpg?.let { String.format(Locale.US, "%.1f mpg", it) } ?: "—",
                first = i == 0, onClick = { nav.sheet(SheetReq.Fuel(f, f.vid)) },
            )
        }
    }
}

@Composable
private fun MpgChart(series: List<Double>) {
    val lo = floor(series.min()) - 1
    val hi = ceil(series.max()) + 1
    val line = G.accent
    val base = G.line
    GCard {
        Row {
            Sub("MPG per fill-up", Modifier.weight(1f))
            Sub("${lo.toInt()}–${hi.toInt()} mpg")
        }
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val w = size.width
            val h = size.height
            val pts = series.mapIndexed { i, m ->
                Offset(8f + i * (w - 16f) / (series.size - 1), (h - 6f - ((m - lo) / (hi - lo) * (h - 12f))).toFloat())
            }
            drawLine(base, Offset(0f, h - 1f), Offset(w, h - 1f), 2f)
            val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
            drawPath(path, line, style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(line, 12f, pts.last())
        }
    }
}

/* ---------------- Service ---------------- */

@Composable
fun ServiceScreen(d: AppData, vid: Long, onPick: (Long?) -> Unit) {
    val nav = LocalNav.current
    var remToDelete by remember { mutableStateOf<Reminder?>(null) }
    Header("Service", d, vid) { onPick(it) }
    H2("Coming up")
    val rems = d.vehicleReminders(vid)
    if (rems.isEmpty()) GCard { Sub("No reminders yet. Add a service record and turn on \"Remind me next time\".") }
    rems.forEach { (r, st) ->
        if (st.level == Level.OK) {
            GCard(onClick = { remToDelete = r }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.type, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = G.ink)
                        Sub(st.text)
                    }
                    Pill("On track", Level.OK)
                }
            }
        } else {
            val bg = if (st.level == Level.BAD) G.badBg else G.warnBg
            val fg = if (st.level == Level.BAD) G.bad else G.warn
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.type, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
                    Text(st.text, fontSize = 13.sp, color = fg)
                }
                Button(onClick = { nav.sheet(SheetReq.Service(null, vid, r.type)) },
                    colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text("Log it") }
            }
        }
    }
    H2("History")
    val hist = d.service.filter { it.vid == vid }.sortedByDescending { it.date }
    ListBox {
        if (hist.isEmpty()) Empty("No service records yet.")
        hist.forEachIndexed { i, s ->
            ListRow(s.type, "${fdate(s.date)} · ${mi(s.odo)} mi" + if (s.notes.isNotBlank()) " · ${s.notes}" else "",
                usd(s.cost), first = i == 0, onClick = { nav.sheet(SheetReq.Service(s, s.vid)) })
        }
    }
    remToDelete?.let { r ->
        Confirm("Stop reminding?", "Delete the ${r.type} reminder. Service history stays.", "Delete reminder",
            onYes = { Repo.update { it.deleteReminder(r.id) }; nav.toast("Reminder deleted") }, onClose = { remToDelete = null })
    }
}

/* ---------------- Parts ---------------- */

@Composable
fun PartsScreen(d: AppData, vid: Long, onPick: (Long?) -> Unit) {
    val nav = LocalNav.current
    Header("Parts", d, vid) { onPick(it) }
    val rows = d.parts.filter { it.vid == vid }.sortedByDescending { it.date }
    val yr = today().take(4) + "-01-01"
    Tiles {
        Tile("Parts this year", usd0(rows.filter { it.date >= yr }.sumOf { it.cost }))
        Tile("On the shelf", rows.count { !it.installed }.toString())
    }
    Hint("Parts you bought outside a shop visit: spares, upgrades, things you put on yourself. They count toward Costs as their own category.")
    H2("Parts bought")
    ListBox {
        if (rows.isEmpty()) Empty("No parts logged for this vehicle yet.")
        rows.forEachIndexed { i, x ->
            ListRow(
                x.name + if (x.qty > 1) " × ${x.qty}" else "",
                listOf(fdate(x.date), x.partNo, x.store).filter { it.isNotBlank() }.joinToString(" · "),
                usd(x.cost), first = i == 0, onClick = { nav.sheet(SheetReq.PartS(x, x.vid)) },
                extra = {
                    if (x.installed) Sub("Installed" + (x.instOdo?.let { " at ${mi(it)} mi" } ?: ""))
                    else GhostButton("Mark installed", Modifier.padding(top = 4.dp)) {
                        Repo.update { it.installPart(x.id) }
                        nav.toast("${x.name} marked installed")
                    }
                },
            ) { if (!x.installed) Box(Modifier.padding(top = 4.dp)) { Pill("On the shelf", Level.WARN) } }
        }
    }
}

/* ---------------- Costs ---------------- */

@Composable
fun CostsScreen(d: AppData, vid: Long?, range: Range, onPick: (Long?) -> Unit, onRange: (Range) -> Unit) {
    val nav = LocalNav.current
    val start = range.start()
    val items = d.costItems(vid, start)
    val tot = items.sumOf { it.amt }
    val cats = listOf(CAT_FUEL to G.catFuel, CAT_SERVICE to G.catService, CAT_PARTS to G.catParts, CAT_OTHER to G.catOther)
        .map { (c, col) -> Triple(c, col, items.filter { it.cat == c }.sumOf { it.amt }) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScreenTitle("Costs", modifier = Modifier.weight(1f))
        VehicleChip(d, vid, allowAll = true, onPick = onPick)
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Range.entries.forEachIndexed { i, r ->
            SegmentedButton(selected = r == range, onClick = { onRange(r) },
                shape = SegmentedButtonDefaults.itemShape(i, Range.entries.size)) { Text(r.label) }
        }
    }
    GCard {
        Sub("Total spent ${range.long}")
        Text(usd0(tot), style = Big.copy(fontSize = 52.sp), color = G.ink)
        if (tot > 0) Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            cats.filter { it.third > 0 }.forEach { (_, col, amt) ->
                Box(Modifier.weight(amt.toFloat()).height(14.dp).background(col))
            }
        }
    }
    val subs = items.filter { it.cat == CAT_OTHER }.mapNotNull { it.sub }.distinct()
    ListBox {
        cats.forEachIndexed { i, (c, col, amt) ->
            ListRow(if (c == CAT_OTHER && subs.isNotEmpty()) "Other (${subs.joinToString(", ").lowercase()})" else c,
                value = usd0(amt), first = i == 0,
                extra = { Box(Modifier.padding(top = 4.dp).size(28.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(col)) })
        }
    }
    val vs = if (vid == null) d.vehicles else listOfNotNull(d.vehicle(vid))
    vs.chunked(2).forEach { pair ->
        Tiles {
            pair.forEach { v ->
                val c = d.costItems(v.id, start).sumOf { it.amt }
                val m = d.milesSince(v.id, start)
                Tile(v.label, usd0(c), foot = if (m > 0) String.format(Locale.US, "$%.2f per mile · %s mi", c / m, mi(m)) else "Not enough mileage yet")
            }
            if (pair.size == 1 && vs.size > 1) Box(Modifier.weight(1f))
        }
    }
    GhostButton("Add other expense", Modifier.fillMaxWidth()) {
        nav.sheet(SheetReq.ExpenseS(null, vid ?: d.activeId()!!))
    }
    val other = d.expenses.filter { (vid == null || it.vid == vid) && it.date >= start }.sortedByDescending { it.date }
    if (other.isNotEmpty()) {
        H2("Other expenses")
        ListBox {
            other.forEachIndexed { i, e ->
                ListRow(e.cat + if (e.desc.isNotBlank()) ": ${e.desc}" else "",
                    "${fdate(e.date)} · ${d.vehicle(e.vid)?.label ?: ""}", usd(e.cost), first = i == 0,
                    onClick = { nav.sheet(SheetReq.ExpenseS(e, e.vid)) })
            }
        }
    }
}
