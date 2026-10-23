package com.motomech.garagelog.tracking

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.vehicleForAddress

/**
 * Hears every Bluetooth connect and disconnect on the phone. When the device is one linked to a
 * vehicle, a connect starts a trip and makes that vehicle active; a disconnect ends the trip.
 */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val addr = device?.address ?: return
        Repo.init(context)
        val v = Repo.current.vehicleForAddress(addr) ?: return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Repo.update { it.copy(connectedAddress = addr, lastActive = v.id) }
                if (!TripService.start(context, v.id, addr)) {
                    Notifs.post(context, Notifs.ID_START_FAILED, Notifs.CH_ALERT, "Tap to track this trip",
                        "${v.label} connected, but Android didn't let Garage Log start in the background. " +
                            "Tap to start the trip, and check trip tracking setup in the app.", ACTION_TAP_START_TRIP)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Repo.update { if (it.connectedAddress.equals(addr, true)) it.copy(connectedAddress = null) else it }
                if (Repo.current.trip?.address.equals(addr, true)) TripService.end(context)
            }
        }
    }

    companion object {
        /** Intent action MainActivity handles by starting a trip for the connected vehicle. */
        const val ACTION_TAP_START_TRIP = "com.motomech.garagelog.TAP_START_TRIP"
    }
}
