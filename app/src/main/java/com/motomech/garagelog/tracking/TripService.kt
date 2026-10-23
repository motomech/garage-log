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
import com.motomech.garagelog.data.PumpWait
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.addTripMiles
import com.motomech.garagelog.data.endTrip
import com.motomech.garagelog.data.mi1
import com.motomech.garagelog.data.setTripPoint
import com.motomech.garagelog.data.startTrip
import com.motomech.garagelog.data.vehicle

/**
 * Measures a trip with GPS while the vehicle's Bluetooth is connected. Runs as a foreground
 * service (Android requires the "Trip in progress" notification for this).
 *
 * After a pump stop (Log fill-up opened mid-trip with the engine running) it stays on, watching
 * for the car to move faster than 5 mph, and starts the next trip then or when the fill-up is saved.
 */
class TripService : Service() {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var last: Location? = null
    private var latest: Location? = null // newest good fix, moving or not: where the trip ends
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
            ACTION_END -> { endCurrent(ask = true); stopAll(); return START_NOT_STICKY }
            ACTION_PAUSE -> pauseForFuel()
            ACTION_RESUME -> resumeAfterFuel()
            ACTION_CANCEL_WAIT -> { Repo.update { it.copy(pumpWait = null) }; stopAll(); return START_NOT_STICKY }
            ACTION_START -> {
                val vid = intent.getLongExtra(EXTRA_VID, -1)
                val addr = intent.getStringExtra(EXTRA_ADDR) ?: ""
                if (Repo.current.vehicle(vid) != null) Repo.update { it.copy(pumpWait = null).startTrip(vid, addr, System.currentTimeMillis()) }
            }
            // null intent: the system restarted us after killing the process; carry on with the saved trip.
        }
        if (Repo.current.trip == null && Repo.current.pumpWait == null) { stopAll(); return START_NOT_STICKY }
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
        Repo.update { it.copy(trip = null, pumpWait = null) }
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
            endCurrent(ask = true)
            stopAll()
        }
    }

    private fun onLocation(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        if (Repo.current.trip == null) { watchForDriveOff(loc); return }
        if (latest == null) Repo.update { it.setTripPoint(loc.latitude, loc.longitude) } // the trip's start point
        latest = loc
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
        val at = latest
        Repo.update { d -> d.addTripMiles(miles).let { if (at != null) it.setTripPoint(at.latitude, at.longitude) else it } }
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        if (Notifs.canPost(this)) NotificationManagerCompat.from(this).notify(Notifs.ID_TRIP, buildNotification())
    }

    /**
     * Saves the running trip, ending where the car is now, and queues the gas station lookup there.
     * ask=false when the user is already logging a fill-up, so no "Log a fill-up?" prompt follows.
     */
    private fun endCurrent(ask: Boolean) {
        flush()
        val before = Repo.current
        val t = before.trip ?: return
        val end = latest
        Repo.update { it.endTrip(end?.latitude, end?.longitude) }
        val saved = Repo.current.trips.lastOrNull()?.takeIf { it.id >= before.nextId }
        val v = before.vehicle(t.vid)
        if (saved != null && v != null && ask) {
            Notifs.post(this, Notifs.ID_TRIP_DONE, Notifs.CH_ALERT, "Trip saved · ${v.label}",
                "${mi1(saved.miles)} mi added to the odometer")
        }
        val toLat = saved?.toLat
        val toLon = saved?.toLon
        if (saved != null && toLat != null && toLon != null) StopWorker.enqueue(this, saved.id, toLat, toLon, ask)
        Notifs.checkReminders(this)
        last = null; latest = null; pendingMeters = 0.0
    }

    /** Log fill-up opened mid-trip: end the trip at the pump, then wait for the car to move on. */
    private fun pauseForFuel() {
        val t = Repo.current.trip ?: return
        endCurrent(ask = false)
        if (Repo.current.connectedAddress.equals(t.address, ignoreCase = true)) {
            Repo.update { it.copy(pumpWait = PumpWait(t.vid, t.address, System.currentTimeMillis())) }
            updateNotification()
        }
    }

    private fun resumeAfterFuel() {
        val w = Repo.current.pumpWait ?: return
        Repo.update { it.copy(pumpWait = null).startTrip(w.vid, w.address, System.currentTimeMillis()) }
        last = null; latest = null; pendingMeters = 0.0
        updateNotification()
    }

    /** While waiting at the pump: driving off (over 5 mph) starts the next trip; a long wait gives up. */
    private fun watchForDriveOff(loc: Location) {
        val w = Repo.current.pumpWait
        if (w == null) { stopAll(); return }
        if (System.currentTimeMillis() - w.since > MAX_PUMP_WAIT_MS) {
            Repo.update { it.copy(pumpWait = null) }
            stopAll(); return
        }
        val prev = last
        last = loc
        val speed = when {
            loc.hasSpeed() -> loc.speed.toDouble()
            prev != null -> {
                val secs = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
                if (secs > 0) prev.distanceTo(loc) / secs else 0.0
            }
            else -> 0.0
        }
        if (speed > DRIVE_OFF_MPS) resumeAfterFuel()
    }

    private fun stopAll() {
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

    private fun buildNotification(): android.app.Notification {
        val d = Repo.current
        val w = d.pumpWait
        val b = NotificationCompat.Builder(this, Notifs.CH_TRIP)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifs.openAppIntent(this))
        if (d.trip == null && w != null) {
            b.setContentTitle("Stopped for fuel · " + (d.vehicle(w.vid)?.label ?: ""))
                .setContentText("Next trip starts when you drive off or save the fill-up")
        } else {
            b.setContentTitle("Trip in progress · " + (d.trip?.let { d.vehicle(it.vid)?.label } ?: ""))
                .setContentText("${mi1(d.trip?.miles ?: 0.0)} mi · ends when Bluetooth disconnects")
                .addAction(0, "End trip", PendingIntent.getService(this, 1,
                    Intent(this, TripService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE))
        }
        return b.build()
    }

    companion object {
        const val ACTION_START = "com.motomech.garagelog.START_TRIP"
        const val ACTION_END = "com.motomech.garagelog.END_TRIP"
        const val ACTION_PAUSE = "com.motomech.garagelog.PAUSE_FOR_FUEL"
        const val ACTION_RESUME = "com.motomech.garagelog.RESUME_AFTER_FUEL"
        const val ACTION_CANCEL_WAIT = "com.motomech.garagelog.CANCEL_PUMP_WAIT"
        const val EXTRA_VID = "vid"
        const val EXTRA_ADDR = "addr"
        private const val METERS_PER_MILE = 1609.344
        private const val MAX_ACCURACY_M = 30f
        private const val MIN_STEP_M = 12.0
        private const val MAX_SPEED_MPS = 70.0 // about 155 mph
        private const val FLUSH_M = 160.0 // save about every 0.1 mi
        private const val DRIVE_OFF_MPS = 2.24 // 5 mph
        private const val MAX_PUMP_WAIT_MS = 45 * 60_000L

        /** Returns false if Android wouldn't let the trip start. */
        fun start(context: Context, vid: Long, addr: String): Boolean = try {
            ContextCompat.startForegroundService(context,
                Intent(context, TripService::class.java).setAction(ACTION_START).putExtra(EXTRA_VID, vid).putExtra(EXTRA_ADDR, addr))
            true
        } catch (e: Exception) {
            false
        }

        /** Log fill-up opened while a trip is running: end the trip here. */
        fun pauseForFuel(context: Context) {
            if (Repo.current.trip == null) return
            runCatching { context.startService(Intent(context, TripService::class.java).setAction(ACTION_PAUSE)) }
        }

        /** Fill-up saved: start the next trip right away if the car is still connected. */
        fun resumeAfterFuel(context: Context) {
            if (Repo.current.pumpWait == null) return
            runCatching { context.startService(Intent(context, TripService::class.java).setAction(ACTION_RESUME)) }
        }

        /** The car's Bluetooth went away while waiting at the pump: nothing to restart. */
        fun cancelWait(context: Context) {
            if (Repo.current.pumpWait == null) return
            Repo.update { it.copy(pumpWait = null) }
            runCatching { context.startService(Intent(context, TripService::class.java).setAction(ACTION_CANCEL_WAIT)) }
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
