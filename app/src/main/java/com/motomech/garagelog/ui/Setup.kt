package com.motomech.garagelog.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.motomech.garagelog.data.AppData

fun Context.has(perm: String) = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

fun Context.hasBtPermission() = Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT)

private class Step(val title: String, val why: String, val done: Boolean, val button: String, val act: () -> Unit)

/**
 * The Android permissions trip tracking needs, with a button for each one that's missing.
 * Shown on the Garage screen once a vehicle is linked to Bluetooth.
 */
@SuppressLint("BatteryLife")
@Composable
fun TrackingSetup(d: AppData) {
    val ctx = LocalContext.current
    LocalResumeTick.current // re-check whenever the app comes back to the front
    var tick by remember { mutableStateOf(0) }
    val refresh: (Any?) -> Unit = { tick++ }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), refresh)
    val many = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), refresh)
    var hide by remember { mutableStateOf(false) }
    if (d.vehicles.none { it.btAddress != null }) return
    if (tick < 0) return // reading tick re-runs the checks after a permission answer

    val pm = ctx.getSystemService(PowerManager::class.java)
    val fine = ctx.has(Manifest.permission.ACCESS_FINE_LOCATION)
    val steps = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(Step("Nearby devices (Bluetooth)", "To notice when the car connects.",
            ctx.has(Manifest.permission.BLUETOOTH_CONNECT), "Allow") { one.launch(Manifest.permission.BLUETOOTH_CONNECT) })
        add(Step("Location", "GPS measures the miles of each trip.", fine, "Allow") {
            many.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        })
        if (Build.VERSION.SDK_INT >= 29) add(Step("Location all the time",
            "Trips start while the phone is in your pocket. Pick \"Allow all the time\".",
            ctx.has(Manifest.permission.ACCESS_BACKGROUND_LOCATION), if (fine) "Open" else "After Location") {
            if (fine) one.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        })
        if (Build.VERSION.SDK_INT >= 33) add(Step("Notifications", "Trip in progress, trip saved, and service reminders.",
            ctx.has(Manifest.permission.POST_NOTIFICATIONS), "Allow") { one.launch(Manifest.permission.POST_NOTIFICATIONS) })
        add(Step("Battery: unrestricted", "So Android doesn't block trips from starting in the background.",
            pm.isIgnoringBatteryOptimizations(ctx.packageName), "Open") {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
    }
    val missing = steps.count { !it.done }
    if (missing == 0) return
    GCard(bg = G.warnBg, border = G.warnBg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Finish trip tracking setup", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = G.warn, modifier = Modifier.weight(1f))
            TextButton(onClick = { hide = !hide }) { Text(if (hide) "Show" else "Hide", color = G.warn) }
        }
        if (hide) {
            Text("$missing step${if (missing == 1) "" else "s"} left", color = G.warn, fontSize = 13.sp)
            return@GCard
        }
        steps.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (s.done) "✓ " else "") + s.title, fontWeight = FontWeight.SemiBold, color = G.ink, fontSize = 15.sp)
                    Text(s.why, color = G.muted, fontSize = 13.sp)
                }
                if (!s.done) Button(onClick = s.act, colors = ButtonDefaults.buttonColors(containerColor = G.accent)) { Text(s.button) }
            }
        }
        TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }) { Text("Open app settings", color = G.warn) }
    }
}
