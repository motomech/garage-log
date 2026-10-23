package com.motomech.garagelog.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import com.motomech.garagelog.data.AppData
import com.motomech.garagelog.data.Doc
import com.motomech.garagelog.data.EXPENSE_CATS
import com.motomech.garagelog.data.Expense
import com.motomech.garagelog.data.FuelEntry
import com.motomech.garagelog.data.PART_NAMES
import com.motomech.garagelog.data.Part
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.SERVICE_TYPES
import com.motomech.garagelog.data.ServiceEntry
import com.motomech.garagelog.data.activeId
import com.motomech.garagelog.data.addReading
import com.motomech.garagelog.data.correctOdometer
import com.motomech.garagelog.data.deleteExpense
import com.motomech.garagelog.data.deleteFuel
import com.motomech.garagelog.data.deletePart
import com.motomech.garagelog.data.deleteService
import com.motomech.garagelog.data.isConnected
import com.motomech.garagelog.data.liveOdo
import com.motomech.garagelog.data.mi
import com.motomech.garagelog.data.parseDate
import com.motomech.garagelog.data.remStatus
import com.motomech.garagelog.data.saveExpense
import com.motomech.garagelog.data.saveFuel
import com.motomech.garagelog.data.savePart
import com.motomech.garagelog.data.saveService
import com.motomech.garagelog.data.today
import com.motomech.garagelog.data.usd
import com.motomech.garagelog.data.vehicle
import com.motomech.garagelog.tracking.Notifs
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.round

/** What the bottom sheet is open for. An entry means editing it; null means adding a new one. */
sealed interface SheetReq {
    data class Fuel(val entry: FuelEntry?, val vid: Long) : SheetReq
    data class Service(val entry: ServiceEntry?, val vid: Long, val presetType: String? = null) : SheetReq
    data class PartS(val entry: Part?, val vid: Long) : SheetReq
    data class ExpenseS(val entry: Expense?, val vid: Long) : SheetReq
    data class Correct(val vid: Long) : SheetReq
    data class Reading(val vid: Long) : SheetReq
    data class Details(val vid: Long) : SheetReq
    data class DocS(val doc: Doc?, val vid: Long) : SheetReq
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(d: AppData, req: SheetReq, onClose: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = G.card) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (req) {
                is SheetReq.Fuel -> FuelForm(d, req, onClose)
                is SheetReq.Service -> ServiceForm(d, req, onClose)
                is SheetReq.PartS -> PartForm(d, req, onClose)
                is SheetReq.ExpenseS -> ExpenseForm(d, req, onClose)
                is SheetReq.Correct -> OdoForm(d, req.vid, correct = true, onClose)
                is SheetReq.Reading -> OdoForm(d, req.vid, correct = false, onClose)
                is SheetReq.Details -> DetailsForm(d, req.vid, onClose)
                is SheetReq.DocS -> DocForm(d, req, onClose)
            }
        }
    }
}

@Composable
internal fun SheetTitle(text: String) = Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = G.ink)

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = G.accent)) {
        Text(text, fontSize = 16.sp)
    }

@Composable
internal fun DeleteButton(what: String, onYes: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    TextButton(onClick = { ask = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete $what", color = G.bad) }
    if (ask) Confirm("Delete this $what?", "This can't be undone.", "Delete", onYes) { ask = false }
}

/** Vehicle dropdown plus the note explaining why it defaulted to the active vehicle. */
@Composable
private fun VehicleField(d: AppData, vid: Long, editing: Boolean, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = d.activeId()
    Box {
        OutlinedTextField(
            value = d.vehicle(vid)?.label ?: "", onValueChange = {}, readOnly = true, label = { Text("Vehicle") },
            modifier = Modifier.fillMaxWidth(), trailingIcon = { Text("▾  ") },
            interactionSource = clickSource { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            d.vehicles.forEach { v ->
                val tag = if (v.id == active) (if (d.isConnected(v.id)) " (connected)" else " (active)") else ""
                DropdownMenuItem(text = { Text(v.label + tag) }, onClick = { onPick(v.id); open = false })
            }
        }
    }
    val a = d.vehicle(active)
    if (!editing && a != null && d.vehicles.size > 1) {
        Hint("Defaults to ${a.label} because it's ${if (d.isConnected(a.id)) "connected right now" else "the last vehicle you used"}. Change it if this is for another vehicle.")
    }
}

/** Makes a read-only text field open something when tapped. */
@Composable
private fun clickSource(onClick: () -> Unit): MutableInteractionSource {
    val src = remember { MutableInteractionSource() }
    LaunchedEffect(src) {
        src.interactions.collect { if (it is PressInteraction.Release) onClick() }
    }
    return src
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = "Date") {
    var open by remember { mutableStateOf(false) }
    val shown = runCatching { com.motomech.garagelog.data.fdate(value) }.getOrDefault(value)
    OutlinedTextField(value = shown, onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
        modifier = modifier, interactionSource = clickSource { open = true })
    if (open) {
        val millis = parseDate(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val st = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(st) }
    }
}

@Composable
internal fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(text, color = G.ink, fontSize = 15.sp)
    }
}

private fun Double.odoText() = round(this).toLong().toString()
private fun money(n: Double) = String.format(Locale.US, "%.2f", n)

/* ---------- fuel ---------- */

@Composable
private fun FuelForm(d: AppData, req: SheetReq.Fuel, onClose: () -> Unit) {
    val nav = LocalNav.current
    val e = req.entry
    var vid by remember { mutableStateOf(req.vid) }
    var date by remember { mutableStateOf(e?.date ?: today()) }
    var odo by remember { mutableStateOf(e?.odo?.odoText() ?: d.vehicle(vid)?.let { d.liveOdo(it).odoText() } ?: "") }
    var gal by remember { mutableStateOf(e?.gal?.plain() ?: "") }
    var price by remember { mutableStateOf(e?.price?.let { money(it) } ?: lastPrice(d)) }
    var full by remember { mutableStateOf(e?.full ?: true) }

    SheetTitle(if (e == null) "Log fill-up" else "Edit fill-up")
    VehicleField(d, vid, e != null) {
        vid = it
        if (e == null) odo = d.vehicle(it)?.let { v -> d.liveOdo(v).odoText() } ?: odo
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DateField(date, { date = it }, Modifier.weight(1f))
        NumField("Odometer", odo, { odo = it }, Modifier.weight(1f), decimal = false)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumField("Gallons", gal, { gal = it }, Modifier.weight(1f))
        NumField("Price per gal", price, { price = it }, Modifier.weight(1f))
    }
    val g = gal.toNum()
    val p = price.toNum()
    if (g != null && p != null) Sub("Total ${usd(g * p)}")
    CheckRow("Filled the tank (needed for MPG)", full) { full = it }
    PrimaryButton(if (e == null) "Save fill-up" else "Save changes") {
        val o = odo.toNum()
        if (g == null || g <= 0 || p == null || o == null) { nav.toast("Enter odometer, gallons and price."); return@PrimaryButton }
        Repo.update { it.saveFuel(FuelEntry(e?.id ?: 0, vid, date, o, g, p, full)) }
        nav.toast("Fill-up saved"); onClose()
    }
    if (e != null) DeleteButton("fill-up") { Repo.update { it.deleteFuel(e.id) }; nav.toast("Fill-up deleted"); onClose() }
}

private fun lastPrice(d: AppData) = d.fuel.maxByOrNull { it.date }?.let { money(it.price) } ?: ""

/* ---------- service ---------- */

@Composable
private fun ServiceForm(d: AppData, req: SheetReq.Service, onClose: () -> Unit) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val e = req.entry
    var vid by remember { mutableStateOf(req.vid) }
    var type by remember { mutableStateOf(e?.type ?: req.presetType ?: "") }
    var date by remember { mutableStateOf(e?.date ?: today()) }
    var odo by remember { mutableStateOf(e?.odo?.odoText() ?: d.vehicle(vid)?.let { d.liveOdo(it).odoText() } ?: "") }
    var cost by remember { mutableStateOf(e?.cost?.let { money(it) } ?: "") }
    var notes by remember { mutableStateOf(e?.notes ?: "") }
    val existing = d.reminders.firstOrNull { it.vid == vid && it.type.equals(type.trim(), true) }
    var remind by remember { mutableStateOf(e == null || existing != null) }
    var everyMi by remember(existing?.id) { mutableStateOf(existing?.everyMi?.takeIf { it > 0 }?.toString() ?: if (existing == null) "5000" else "") }
    var everyMo by remember(existing?.id) { mutableStateOf(existing?.everyMo?.takeIf { it > 0 }?.toString() ?: if (existing == null) "6" else "") }

    SheetTitle(if (e == null) "Add service record" else "Edit service record")
    VehicleField(d, vid, e != null) {
        vid = it
        if (e == null) odo = d.vehicle(it)?.let { v -> d.liveOdo(v).odoText() } ?: odo
    }
    SuggestField("What was done", type, SERVICE_TYPES + d.service.map { it.type }.distinct(), { type = it }, Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DateField(date, { date = it }, Modifier.weight(1f))
        NumField("Odometer", odo, { odo = it }, Modifier.weight(1f), decimal = false)
    }
    NumField("Cost ($)", cost, { cost = it }, Modifier.fillMaxWidth())
    TextField("Notes (optional)", notes, { notes = it }, Modifier.fillMaxWidth(), "Shop, parts used…")
    CheckRow("Remind me next time", remind) { remind = it }
    if (remind) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumField("Every (miles)", everyMi, { everyMi = it }, Modifier.weight(1f), decimal = false)
            NumField("or every (months)", everyMo, { everyMo = it }, Modifier.weight(1f), decimal = false)
        }
        Hint("Whichever comes first. Leave one blank to use only the other.")
    }
    PrimaryButton(if (e == null) "Save record" else "Save changes") {
        val o = odo.toNum()
        val c = cost.toNum() ?: 0.0
        if (type.isBlank() || o == null) { nav.toast("Enter what was done and the odometer."); return@PrimaryButton }
        val entry = ServiceEntry(e?.id ?: 0, vid, date, o, type.trim(), c, notes.trim())
        Repo.update { it.saveService(entry, remind, everyMi.toNum()?.toInt() ?: 0, everyMo.toNum()?.toInt() ?: 0) }
        val r = Repo.current.reminders.firstOrNull { it.vid == vid && it.type.equals(entry.type, true) }
        nav.toast("Record saved" + (r?.let { " · next " + Repo.current.remStatus(it).text.lowercase() } ?: ""))
        Notifs.checkReminders(ctx)
        onClose()
    }
    if (e != null) DeleteButton("record") { Repo.update { it.deleteService(e.id) }; nav.toast("Record deleted"); onClose() }
}

/* ---------- parts ---------- */

@Composable
private fun PartForm(d: AppData, req: SheetReq.PartS, onClose: () -> Unit) {
    val nav = LocalNav.current
    val e = req.entry
    var vid by remember { mutableStateOf(req.vid) }
    var name by remember { mutableStateOf(e?.name ?: "") }
    var partNo by remember { mutableStateOf(e?.partNo ?: "") }
    var store by remember { mutableStateOf(e?.store ?: "") }
    var date by remember { mutableStateOf(e?.date ?: today()) }
    var qty by remember { mutableStateOf((e?.qty ?: 1).toString()) }
    var cost by remember { mutableStateOf(e?.cost?.let { money(it) } ?: "") }
    var installed by remember { mutableStateOf(e?.installed ?: true) }
    var instOdo by remember { mutableStateOf(e?.instOdo?.odoText() ?: d.vehicle(vid)?.let { d.liveOdo(it).odoText() } ?: "") }

    SheetTitle(if (e == null) "Add part" else "Edit part")
    VehicleField(d, vid, e != null) { vid = it }
    SuggestField("Part", name, (PART_NAMES + d.parts.map { it.name }).distinct(), { name = it }, Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextField("Part number", partNo, { partNo = it }, Modifier.weight(1f))
        SuggestField("Where bought", store, d.parts.map { it.store }.filter { it.isNotBlank() }.distinct(), { store = it }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DateField(date, { date = it }, Modifier.weight(1f))
        NumField("Qty", qty, { qty = it }, Modifier.weight(1f), decimal = false)
    }
    NumField("Total cost ($)", cost, { cost = it }, Modifier.fillMaxWidth())
    CheckRow("Already installed", installed) { installed = it }
    if (installed) NumField("Odometer when installed", instOdo, { instOdo = it }, Modifier.fillMaxWidth(), decimal = false)
    Hint("Uncheck \"Already installed\" for spares. They wait under \"On the shelf\" until you mark them installed.")
    PrimaryButton(if (e == null) "Save part" else "Save changes") {
        val c = cost.toNum()
        if (name.isBlank() || c == null) { nav.toast("Enter the part and what it cost."); return@PrimaryButton }
        val v = d.vehicle(vid)
        val io = if (installed) instOdo.toNum() else null
        val p = Part(
            e?.id ?: 0, vid, date, e?.odo ?: v?.let { round(d.liveOdo(it)) } ?: 0.0, name.trim(), partNo.trim(), store.trim(),
            qty.toNum()?.toInt()?.coerceAtLeast(1) ?: 1, c, installed, io, if (installed) (e?.instDate ?: date) else null,
        )
        Repo.update { it.savePart(p) }
        nav.toast(if (installed) "Part saved" else "Part saved to the shelf"); onClose()
    }
    if (e != null) DeleteButton("part") { Repo.update { it.deletePart(e.id) }; nav.toast("Part deleted"); onClose() }
}

/* ---------- other expenses ---------- */

@Composable
private fun ExpenseForm(d: AppData, req: SheetReq.ExpenseS, onClose: () -> Unit) {
    val nav = LocalNav.current
    val e = req.entry
    var vid by remember { mutableStateOf(req.vid) }
    var cat by remember { mutableStateOf(e?.cat ?: EXPENSE_CATS.first()) }
    var desc by remember { mutableStateOf(e?.desc ?: "") }
    var date by remember { mutableStateOf(e?.date ?: today()) }
    var cost by remember { mutableStateOf(e?.cost?.let { money(it) } ?: "") }

    SheetTitle(if (e == null) "Add other expense" else "Edit expense")
    VehicleField(d, vid, e != null) { vid = it }
    SuggestField("Category", cat, EXPENSE_CATS, { cat = it }, Modifier.fillMaxWidth())
    TextField("Description (optional)", desc, { desc = it }, Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DateField(date, { date = it }, Modifier.weight(1f))
        NumField("Cost ($)", cost, { cost = it }, Modifier.weight(1f))
    }
    PrimaryButton(if (e == null) "Save expense" else "Save changes") {
        val c = cost.toNum()
        if (c == null || c <= 0) { nav.toast("Enter the cost."); return@PrimaryButton }
        val odo = e?.odo ?: d.vehicle(vid)?.let { round(d.liveOdo(it)) } ?: 0.0
        Repo.update { it.saveExpense(Expense(e?.id ?: 0, vid, date, odo, cat.trim().ifBlank { "Other" }, desc.trim(), c)) }
        nav.toast("Expense saved"); onClose()
    }
    if (e != null) DeleteButton("expense") { Repo.update { it.deleteExpense(e.id) }; nav.toast("Expense deleted"); onClose() }
}

/* ---------- odometer ---------- */

@Composable
private fun OdoForm(d: AppData, vid: Long, correct: Boolean, onClose: () -> Unit) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val v = d.vehicle(vid) ?: return
    val cur = d.liveOdo(v)
    var value by remember { mutableStateOf(cur.odoText()) }
    SheetTitle(if (correct) "Correct odometer" else "Add mileage reading")
    Sub(if (correct) "Set the true reading from the dash. The difference is logged so you can see the drift."
        else "Enter what the odometer reads now. The miles since the last reading are added to the log.")
    NumField("Odometer (mi)", value, { value = it }, Modifier.fillMaxWidth())
    PrimaryButton(if (correct) "Set odometer" else "Save reading") {
        val n = value.toNum()
        if (n == null) return@PrimaryButton
        if (!correct && n <= v.odo) { nav.toast("Enter a reading above ${mi(v.odo)}."); return@PrimaryButton }
        Repo.update { if (correct) it.correctOdometer(vid, n) else it.addReading(vid, n) }
        nav.toast(if (correct) "Odometer set to ${mi(n)} mi" else "+${mi(n - v.odo)} mi added to ${v.label}")
        Notifs.checkReminders(ctx)
        onClose()
    }
}
