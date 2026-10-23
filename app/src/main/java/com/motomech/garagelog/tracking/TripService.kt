package com.motomech.garagelog.tracking

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.motomech.garagelog.R
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.addTripMiles
import com.motomech.garagelog.data.endTrip
import com.motomech.garagelog.data.mi1
import com.motomech.garagelog.data.startTrip
import com.motomech.garagelog.data.vehicle

/**
 * Measures a trip with GPS while the vehicle's Bluetooth is connected. Runs as a foreground
 * service (Android requires the "Trip in progress" notification for this).
 */
class TripService : Service() {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var last: Location? = null
    private var pendingMeters = 0.0
    private var tracking = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Repo.init(this)
        when (intent?.action) {
            ACTION_END -> { finishTrip(); return START_NOT_STICKY }
            ACTION_START -> {
                val vid = intent.getLongExtra(EXTRA_VID, -1)
                val addr = intent.getStringExtra(EXTRA_ADDR) ?: ""
                if (Repo.current.vehicle(vid) != null) Repo.update { it.startTrip(vid, addr, System.currentTimeMillis()) }
            }
            // null intent: the system restarted us after killing the process; carry on with the saved trip.
        }
        if (Repo.current.trip == null) { stopSelf(); return START_NOT_STICKY }
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        startLocation()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, Notifs.ID_TRIP, buildNotification(), type)
        true
    } catch (e: Exception) {
        // Android refused (missing permission, or not allowed from the background). Ask the user to tap.
        Notifs.post(this, Notifs.ID_START_FAILED, Notifs.CH_ALERT, "Trip not tracked",
            "Garage Log couldn't start measuring this trip. Tap to open the app and check trip tracking setup.")
        Repo.update { it.copy(trip = null) }
        false
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (tracking) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()
        try {
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            tracking = true
        } catch (e: SecurityException) {
            finishTrip()
        }
    }

    private fun onLocation(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        val prev = last
        if (prev == null) { last = loc; return }
        val d = prev.distanceTo(loc).toDouble()
        val secs = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
        if (d < MIN_STEP_M) return // standing still, or GPS wander
        if (secs > 0 && d / secs > MAX_SPEED_MPS) { last = loc; return } // a jump, not driving
        pendingMeters += d
        last = loc
        if (pendingMeters >= FLUSH_M) flush()
    }

    private fun flush() {
        if (pendingMeters <= 0) return
        val miles = pendingMeters / METERS_PER_MILE
        pendingMeters = 0.0
        Repo.update { it.addTripMiles(miles) }
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        if (Notifs.canPost(this)) NotificationManagerCompat.from(this).notify(Notifs.ID_TRIP, buildNotification())
    }

    private fun finishTrip() {
        flush()
        val before = Repo.current
        val t = before.trip
        Repo.update { it.endTrip() }
        if (t != null) {
            val v = before.vehicle(t.vid)
            if (v != null && t.miles >= 0.1) {
                Notifs.post(this, Notifs.ID_TRIP_DONE, Notifs.CH_ALERT, "Trip saved · ${v.label}",
                    "${mi1(t.miles)} mi added to the odometer")
            }
            Notifs.checkReminders(this)
        }
        stopLocation()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopLocation() {
        if (tracking) client.removeLocationUpdates(callback)
        tracking = false
    }

    override fun onDestroy() {
        flush()
        stopLocation()
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, Notifs.CH_TRIP)
        .setSmallIcon(R.drawable.ic_stat_car)
        .setContentTitle("Trip in progress · " + (Repo.current.trip?.let { Repo.current.vehicle(it.vid)?.label } ?: ""))
        .setContentText("${mi1(Repo.current.trip?.miles ?: 0.0)} mi · ends when Bluetooth disconnects")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(Notifs.openAppIntent(this))
        .addAction(0, "End trip", PendingIntent.getService(this, 1,
            Intent(this, TripService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val ACTION_START = "com.motomech.garagelog.START_TRIP"
        const val ACTION_END = "com.motomech.garagelog.END_TRIP"
        const val EXTRA_VID = "vid"
        const val EXTRA_ADDR = "addr"
        private const val METERS_PER_MILE = 1609.344
        private const val MAX_ACCURACY_M = 30f
        private const val MIN_STEP_M = 12.0
        private const val MAX_SPEED_MPS = 70.0 // about 155 mph
        private const val FLUSH_M = 160.0 // save about every 0.1 mi

        /** Returns false if Android wouldn't let the trip start. */
        fun start(context: Context, vid: Long, addr: String): Boolean = try {
            ContextCompat.startForegroundService(context,
                Intent(context, TripService::class.java).setAction(ACTION_START).putExtra(EXTRA_VID, vid).putExtra(EXTRA_ADDR, addr))
            true
        } catch (e: Exception) {
            false
        }

        fun end(context: Context) {
            if (Repo.current.trip == null) return
            try {
                context.startService(Intent(context, TripService::class.java).setAction(ACTION_END))
            } catch (e: Exception) {
                // Service not running and we're in the background: end the trip directly.
                Repo.update { it.endTrip() }
                Notifs.checkReminders(context)
            }
        }
    }
}
