package com.motomech.garagelog.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motomech.garagelog.R
import com.motomech.garagelog.data.AppData
import com.motomech.garagelog.data.Backup
import com.motomech.garagelog.data.DocFiles
import com.motomech.garagelog.data.ExportChoice
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.Updates
import com.motomech.garagelog.data.addVehicle
import com.motomech.garagelog.data.deleteVehicle
import com.motomech.garagelog.data.replaceVehicle
import com.motomech.garagelog.data.toCsv
import com.motomech.garagelog.data.today
import com.motomech.garagelog.data.vehicle
import com.motomech.garagelog.data.Level
import com.motomech.garagelog.data.MlChoice
import com.motomech.garagelog.data.MlResult
import com.motomech.garagelog.data.MlVehicle
import com.motomech.garagelog.data.MotoLog
import com.motomech.garagelog.data.Xlsx
import com.motomech.garagelog.data.importMotoLog
import com.motomech.garagelog.data.mi
import com.motomech.garagelog.data.mi1
import com.motomech.garagelog.data.motoLogMatch
import com.motomech.garagelog.data.usd
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.motomech.garagelog.data.relday
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/* ---------------- Add a vehicle / link Bluetooth ---------------- */

private data class BtDev(val address: String, val name: String, val kind: String, val isHeadphones: Boolean)

@SuppressLint("MissingPermission")
private fun pairedDevices(ctx: Context): List<BtDev>? {
    if (!ctx.hasBtPermission()) return null
    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return runCatching {
        adapter.bondedDevices.map { dev ->
            val cls = dev.bluetoothClass?.deviceClass
            val kind = when (cls) {
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "Car audio"
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "Hands-free"
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "Headphones"
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> "Speaker"
                else -> when (dev.bluetoothClass?.majorDeviceClass) {
                    BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio"
                    BluetoothClass.Device.Major.PHONE -> "Phone"
                    BluetoothClass.Device.Major.COMPUTER -> "Computer"
                    BluetoothClass.Device.Major.WEARABLE -> "Wearable"
                    else -> "Bluetooth device"
                }
            }
            BtDev(dev.address, dev.name ?: dev.address, kind, kind == "Headphones")
        }.sortedWith(compareBy({ !it.kind.startsWith("Car") && it.kind != "Hands-free" }, { it.name.lowercase() }))
    }.getOrDefault(emptyList())
}

@Composable
fun LinkScreen(d: AppData, vid: Long?) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val existing = d.vehicle(vid)
    var year by remember { mutableStateOf(existing?.year ?: "") }
    var make by remember { mutableStateOf(existing?.make ?: "") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var odo by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(existing?.type ?: VType.CAR.key) }
    var sel by remember { mutableStateOf(existing?.btAddress) }
    var selName by remember { mutableStateOf(existing?.btName) }
    var askDelete by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val devices = remember(tick, LocalResumeTick.current) { pairedDevices(ctx) }

    TextButton(onClick = { nav.open(existing?.let { Page.VehicleP(it.id) }) }) {
        Text("‹ " + (existing?.label ?: "Garage"), color = G.ink)
    }
    ScreenTitle(if (existing == null) "Add a vehicle" else "Edit ${existing.label}")
    GCard {
        H2("The vehicle")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumField("Year", year, { year = it.take(4) }, Modifier.width(100.dp), decimal = false)
            TextField("Make", make, { make = it }, Modifier.weight(1f), "Chevrolet")
        }
        TextField("Model or nickname", model, { model = it }, Modifier.fillMaxWidth(), "Silverado")
        if (existing == null) NumField("Current odometer (mi)", odo, { odo = it }, Modifier.fillMaxWidth(), decimal = false)
    }
    GCard {
        H2("Type")
        TypePicker(type) { type = it }
        Hint("Shown next to the name in the Garage, on the vehicle screen, and in the vehicle picker on entry forms.")
    }
    GCard {
        H2("Its Bluetooth")
        Hint("Devices already paired with this phone. Pick the one that connects when this vehicle starts: the radio, hands-free, or a plug-in adapter.")
        when {
            devices == null -> {
                Sub("Garage Log needs the Nearby devices permission to see your paired Bluetooth devices.")
                Button(onClick = { btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text("Allow Bluetooth access") }
            }
            devices.isEmpty() -> Sub("No paired devices found. Turn Bluetooth on and pair the car first.")
            else -> devices.forEach { dev ->
                val owner = d.vehicles.firstOrNull { it.btAddress.equals(dev.address, true) && it.id != vid }
                DevOption(
                    dev.name,
                    dev.kind + (owner?.let { " · linked to ${it.label}" } ?: "") + if (dev.isHeadphones) " · not a car, trips would be wrong" else "",
                    selected = sel.equals(dev.address, true), enabled = owner == null,
                ) { sel = dev.address; selName = dev.name }
            }
        }
        DevOption("No Bluetooth", "I'll enter miles by hand", selected = sel == null, enabled = true, icon = false) { sel = null; selName = null }
        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) {
            Text("Don't see your car? Pair it in Bluetooth settings", color = G.accent)
        }
    }
    Button(
        onClick = {
            if (model.isBlank() && make.isBlank()) { nav.toast("Give the vehicle a model or nickname."); return@Button }
            if (existing == null) {
                val o = odo.toNum()
                if (o == null) { nav.toast("Enter the current odometer."); return@Button }
                Repo.update { it.addVehicle(year.trim(), make.trim(), model.trim(), o, sel, selName, type) }
                nav.toast("Vehicle added")
                nav.open(null)
            } else {
                Repo.update { it.replaceVehicle(existing.copy(year = year.trim(), make = make.trim(), model = model.trim(), type = type, btAddress = sel, btName = selName)) }
                nav.toast("Saved")
                nav.open(Page.VehicleP(existing.id))
            }
        },
        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = G.accent),
    ) { Text(if (existing == null) "Add vehicle" else "Save", fontSize = 16.sp) }
    if (existing != null) {
        TextButton(onClick = { askDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete vehicle", color = G.bad) }
        if (askDelete) Confirm("Delete ${existing.label}?", "This removes the vehicle and all its trips, fill-ups, service records, parts, expenses and documents. Export a CSV first if you want a copy.",
            "Delete", onYes = {
                val files = Repo.current.docs.filter { it.vid == existing.id }.flatMap { it.pages }
                Repo.update { it.deleteVehicle(existing.id) }
                DocFiles.delete(ctx, files)
                nav.toast("Vehicle deleted"); nav.open(null)
            }) { askDelete = false }
    }
}

@Composable
private fun DevOption(title: String, sub: String, selected: Boolean, enabled: Boolean, icon: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = if (selected) G.okBg else G.card,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) G.ok else G.line),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon) GIcon(R.drawable.ic_bt, 20.dp, if (enabled) G.ink else G.muted)
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, color = if (enabled) G.ink else G.muted)
                Text(sub, fontSize = 13.sp, color = G.muted)
            }
        }
    }
}

/** Grid of vehicle types, four to a row; the picked one is outlined in the accent color. */
@Composable
private fun TypePicker(picked: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VType.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    val on = t.key == picked
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = if (on) G.warnBg else G.card,
                        border = BorderStroke(if (on) 2.dp else 1.dp, if (on) G.accent else G.line),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onPick(t.key) },
                    ) {
                        Column(Modifier.padding(vertical = 10.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            GIcon(t.icon, 30.dp, if (on) G.warn else G.ink)
                            Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) G.warn else G.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/* ---------------- Import and export ---------------- */

@Composable
fun DataScreen(d: AppData) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var choice by remember { mutableStateOf(ExportChoice()) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (text, rows) = Repo.current.toCsv(choice)
        runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            .onSuccess { nav.toast("Exported $rows rows") }
            .onFailure { nav.toast("Couldn't save the file") }
    }

    TextButton(onClick = { nav.open(null) }) { Text("‹ Garage", color = G.ink) }
    ScreenTitle("Import and export")
    AppUpdatesCard(d)
    BackupCard()
    GCard {
        H2("Export to CSV")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Sub("Vehicle", Modifier.weight(1f))
            VehicleChip(d, choice.vid, allowAll = true) { choice = choice.copy(vid = it) }
        }
        Check("Mileage and trips", choice.mileage) { choice = choice.copy(mileage = it) }
        Check("Fuel fill-ups", choice.fuel) { choice = choice.copy(fuel = it) }
        Check("Service records", choice.service) { choice = choice.copy(service = it) }
        Check("Parts", choice.parts) { choice = choice.copy(parts = it) }
        Check("Other expenses", choice.expenses) { choice = choice.copy(expenses = it) }
        Button(onClick = { save.launch("garage-log-${today()}.csv") }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text("Export CSV") }
        Hint("You choose where to save it: Downloads, Google Drive, or anywhere else the phone offers.")
    }
    MotoLogCard(d)
}

/* ---------------- Import from MotoLog ---------------- */

@Composable
private fun MotoLogCard(d: AppData) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<MlVehicle>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<List<Pair<String, MlResult>>>(emptyList()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; problem = null; done = emptyList()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    MotoLog.read(uris.map { u ->
                        ctx.contentResolver.openInputStream(u)?.use { Xlsx.firstSheet(it) } ?: throw IllegalArgumentException("Couldn't open a file.")
                    })
                }
            }
            busy = false
            r.onSuccess { found = it }.onFailure {
                found = emptyList()
                problem = (it as? IllegalArgumentException)?.message ?: "Couldn't read those files. Pick the Excel (.xlsx) files MotoLog exported."
            }
        }
    }

    GCard {
        H2("Import from MotoLog")
        if (found.isEmpty()) {
            Hint("In MotoLog, open the vehicle, tap Share and save the Excel file to the phone or Google Drive. Then pick it here. " +
                "You can pick several vehicles at once, and add the Fuel and Trip reports for make, model, plate and VIN.")
            problem?.let { Note(it, Level.WARN) }
            done.forEach { (name, r) ->
                Note("$name: added ${r.trips} trips, ${r.fills} fill-ups and ${r.bills} bills" +
                    (if (r.skipped > 0) ". Skipped ${r.skipped} already in Garage Log." else "."), Level.OK)
                TextButton(onClick = { nav.open(Page.VehicleP(r.vid)) }) { Text("Open $name", color = G.accent) }
            }
            Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text(if (busy) "Reading…" else "Pick MotoLog files") }
        }
    }
    found.forEach { m ->
        MotoLogPreview(d, m, onSkip = { found = found - m }) { target, choice ->
            var res: MlResult? = null
            Repo.update { val (next, r) = it.importMotoLog(m, target, choice); res = r; next }
            res?.let { done = done + (m.name to it) }
            found = found - m
            nav.toast("Imported ${m.name}")
        }
    }
}

@Composable
private fun MotoLogPreview(d: AppData, m: MlVehicle, onSkip: () -> Unit, onImport: (Long?, MlChoice) -> Unit) {
    var target by remember(m) { mutableStateOf(d.motoLogMatch(m)) }
    var c by remember(m) { mutableStateOf(MlChoice()) }
    val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    GCard {
        H2(m.name)
        Sub(listOf(m.year, m.make, m.plate).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "From MotoLog" })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Sub("Import into", Modifier.weight(1f))
            ImportTarget(d, target) { target = it }
        }
        Check("Trips (${m.trips.size}, ${mi(m.trips.sumOf { it.miles })} mi)", c.trips) { c = c.copy(trips = it) }
        Check("Fill-ups (${m.fills.size}, ${mi1(m.fills.sumOf { it.gal })} gal)", c.fuel) { c = c.copy(fuel = it) }
        Check("Bills (${m.bills.size}, ${usd(m.bills.sumOf { it.cost })})", c.bills) { c = c.copy(bills = it) }
        val first = m.first; val last = m.last
        if (first != null && last != null) Hint("${first.format(fmt)} to ${last.format(fmt)}. Bills go to Costs under Bills. " +
            "Anything already in Garage Log is skipped, so importing again adds nothing twice.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Skip", Modifier.weight(1f), onSkip)
            Button(onClick = { onImport(target, c) }, enabled = c.trips || c.fuel || c.bills, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text("Import") }
        }
    }
}

/** A new vehicle (null) or one already in the garage. */
@Composable
private fun ImportTarget(d: AppData, vid: Long?, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text((d.vehicle(vid)?.label ?: "New vehicle") + "  ▾", color = G.ink, fontWeight = FontWeight.SemiBold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("New vehicle") }, onClick = { open = false; onPick(null) })
            d.vehicles.forEach { v -> DropdownMenuItem(text = { Text(v.label) }, onClick = { open = false; onPick(v.id) }) }
        }
    }
}

@Composable
private fun Check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked, onChange)
        Text(text, color = G.ink)
    }
}

/* ---------------- Automatic backup ---------------- */

@Composable
private fun BackupCard() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val st = remember(tick, LocalResumeTick.current) { Backup.state(ctx) }

    fun work(label: String, block: () -> Result<*>, done: (Any?) -> String) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            busy = false; tick++
            nav.toast(r.fold({ done(it) }, { "$label didn't work: ${it.message ?: "unknown problem"}" }))
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        work("Backup", { Backup.setTarget(ctx, uri, name) }) { "Backed up. It will save there every night." }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatic backup", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = G.ink, modifier = Modifier.weight(1f))
            if (st.uri != null) Switch(st.auto, { Backup.setAuto(ctx, it); tick++ },
                colors = SwitchDefaults.colors(checkedTrackColor = G.accent))
        }
        Sub("One zip with all vehicles, logs, details, photos and PDFs. Saved every night if anything changed.")
        if (st.uri == null) {
            Gap(4.dp)
            Button(onClick = { pick.launch("garage-log-backup.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text("Choose where backups go") }
            Hint("Pick Google Drive in the list, then a folder. You only do this once.")
        } else {
            Gap(4.dp)
            Sub("Goes to: ${st.name ?: "the file you picked"}")
            when {
                st.lastError != null -> Note("Last backup failed: ${st.lastError}. Tap Back up now to try again, or choose the file again.", Level.BAD)
                st.lastAt > 0 -> Sub("Last backup: ${whenText(st.lastAt)} · ${sizeText(st.lastBytes)}", color = G.ok)
            }
            if (!st.auto) Note("Automatic backup is off. Backups happen only when you tap Back up now.", Level.WARN)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { work("Backup", { Backup.run(ctx, force = true) }) { "Backed up (${sizeText(it as Long)})" } },
                    enabled = !busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = G.accent)) {
                    Text(if (busy) "Working…" else "Back up now")
                }
                GhostButton("Change file", Modifier.weight(1f)) { pick.launch("garage-log-backup.zip") }
            }
        }
        TextButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy) {
            Text("Restore from a backup…", color = G.accent)
        }
    }
    restoreUri?.let { uri ->
        Confirm("Restore this backup?", "Everything in the app now is replaced by what's in the backup: vehicles, logs, details and documents.",
            "Restore", onYes = {
                work("Restore", { Backup.restore(ctx, uri) }) { r ->
                    val x = r as Backup.Restored
                    "Restored ${x.vehicles} vehicle${if (x.vehicles == 1) "" else "s"} and ${x.docs} document${if (x.docs == 1) "" else "s"}"
                }
            }) { restoreUri = null }
    }
}

private fun whenText(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val time = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    return relday(t.toLocalDate().toString()) + " " + time
}

private fun sizeText(b: Long): String = when {
    b >= 1_000_000 -> String.format(Locale.US, "%.1f MB", b / 1_000_000.0)
    b >= 1_000 -> "${b / 1_000} KB"
    else -> "$b bytes"
}

/* ---------------- App updates ---------------- */

/** On Garage while a newer version is out and the user hasn't tapped Later. */
@Composable
fun UpdateBanner(d: AppData) {
    val s by Updates.state.collectAsStateWithLifecycle()
    val r = s.banner ?: return
    val ctx = LocalContext.current
    GCard(bg = G.warnBg) {
        Text("Update ready: ${r.name}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = G.warn)
        if (r.notes.isNotEmpty()) {
            Sub("What's new", color = G.ink)
            r.notes.take(4).forEach { Sub("• $it", color = G.ink) }
        }
        Gap(4.dp)
        UpdateActions(d, s, onLater = { Updates.dismiss(ctx) })
    }
}

/** Update button and everything around it: trip in progress, install permission, download, Android's screen. */
@Composable
private fun UpdateActions(d: AppData, s: Updates.State, onLater: (() -> Unit)?) {
    val ctx = LocalContext.current
    val canInstall = remember(LocalResumeTick.current) { Updates.canInstall(ctx) }
    s.error?.let { Note("Update didn't work: $it", Level.BAD) }
    val prog = s.progress
    when {
        prog != null -> {
            val (done, total) = prog
            LinearProgressIndicator(
                progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = G.accent, trackColor = G.card,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sub("Downloading · ${sizeText(done)} of ${sizeText(total)}", Modifier.weight(1f), color = G.ink)
                TextButton(onClick = { Updates.cancel() }) { Text("Cancel", color = G.accent) }
            }
        }
        s.installing -> Row(verticalAlignment = Alignment.CenterVertically) {
            Sub("Finishing on Android's install screen…", Modifier.weight(1f), color = G.ink)
            TextButton(onClick = { Updates.reset() }) { Text("Start over", color = G.accent) }
        }
        d.trip != null || d.pumpWait != null -> Note(
            "Update after this trip. Installing closes the app, which would stop trip tracking.", null)
        !canInstall -> {
            Sub("First, let Garage Log install its updates. Android asks only once: switch on \"Allow from this source\", then come back.", color = G.ink)
            Button(onClick = { runCatching { ctx.startActivity(Updates.installPermissionIntent(ctx)) } },
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text("Allow updates") }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onLater != null) GhostButton("Later", Modifier.weight(1f), onLater)
            Button(onClick = { val app = ctx.applicationContext; Thread { Updates.downloadAndInstall(app) }.start() },
                modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text("Update") }
        }
    }
}

/** On Import and export: installed version, daily check switch, Check now. */
@Composable
fun AppUpdatesCard(d: AppData) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Updates.state.collectAsStateWithLifecycle()
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("App updates", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = G.ink, modifier = Modifier.weight(1f))
            Switch(s.auto, { Updates.setAuto(ctx, it) }, colors = SwitchDefaults.colors(checkedTrackColor = G.accent))
        }
        Sub("Installed: ${s.installedName.ifEmpty { "unknown" }}. " +
            if (s.auto) "Checks for a new version every day." else "Daily check is off; tap Check now.")
        val r = s.available
        when {
            s.checking -> Sub("Checking…")
            r != null -> {
                Note("Version ${r.name} is ready.", Level.WARN)
                UpdateActions(d, s, onLater = null)
            }
            s.error != null -> Note("Couldn't check: ${s.error}", Level.BAD)
            s.lastCheck > 0 -> Sub("Up to date. Last checked: ${whenText(s.lastCheck)}", color = G.ok)
        }
        if (r == null) GhostButton("Check now", Modifier.fillMaxWidth()) {
            scope.launch {
                val res = withContext(Dispatchers.IO) { Updates.check(ctx) }
                val latest = res.getOrNull()
                nav.toast(when {
                    res.isFailure -> "Couldn't reach GitHub. Check the connection and try again."
                    latest != null && latest.code > Updates.state.value.installed -> "Version ${latest.name} is ready"
                    else -> "You have the newest version"
                })
            }
        }
    }
}
