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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motomech.garagelog.R
import com.motomech.garagelog.data.AppData
import com.motomech.garagelog.data.ExportChoice
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.addVehicle
import com.motomech.garagelog.data.deleteVehicle
import com.motomech.garagelog.data.replaceVehicle
import com.motomech.garagelog.data.toCsv
import com.motomech.garagelog.data.today
import com.motomech.garagelog.data.vehicle

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
                Repo.update { it.addVehicle(year.trim(), make.trim(), model.trim(), o, sel, selName) }
                nav.toast("Vehicle added")
                nav.open(null)
            } else {
                Repo.update { it.replaceVehicle(existing.copy(year = year.trim(), make = make.trim(), model = model.trim(), btAddress = sel, btName = selName)) }
                nav.toast("Saved")
                nav.open(Page.VehicleP(existing.id))
            }
        },
        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = G.accent),
    ) { Text(if (existing == null) "Add vehicle" else "Save", fontSize = 16.sp) }
    if (existing != null) {
        TextButton(onClick = { askDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete vehicle", color = G.bad) }
        if (askDelete) Confirm("Delete ${existing.label}?", "This removes the vehicle and all its trips, fill-ups, service records, parts and expenses. Export a CSV first if you want a copy.",
            "Delete", onYes = { Repo.update { it.deleteVehicle(existing.id) }; nav.toast("Vehicle deleted"); nav.open(null) }) { askDelete = false }
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
    GCard {
        H2("Import from CSV")
        Note("Coming next: import waits until we've looked at MotoLog's export format, so the columns map correctly.", null)
    }
}

@Composable
private fun Check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked, onChange)
        Text(text, color = G.ink)
    }
}
