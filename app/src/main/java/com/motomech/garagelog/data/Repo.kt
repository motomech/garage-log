package com.motomech.garagelog.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.round

/**
 * Holds the app's data in memory and saves it to garage.json in the app's private storage
 * after every change. The UI, the Bluetooth receiver and the trip service all share it.
 */
object Repo {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private lateinit var file: File
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> get() = _data
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        file = File(context.applicationContext.filesDir, "garage.json")
        if (file.exists()) {
            runCatching { _data.value = json.decodeFromString(AppData.serializer(), file.readText()) }
                .onFailure { file.copyTo(File(file.parentFile, "garage-unreadable-${System.currentTimeMillis()}.json")) }
        }
        loaded = true
    }

    val current: AppData get() = _data.value

    @Synchronized
    fun update(block: (AppData) -> AppData) {
        val next = block(_data.value)
        if (next == _data.value) return
        write(next)
    }

    /** Swaps in a whole new data set (restoring a backup). */
    @Synchronized
    fun replaceAll(next: AppData) = write(next)

    /** The data as saved in garage.json, for backups. */
    @Synchronized
    fun snapshot(): String = json.encodeToString(AppData.serializer(), _data.value)

    fun decode(text: String): AppData = json.decodeFromString(AppData.serializer(), text)

    /** When the saved data last changed. */
    val savedAt: Long get() = file.lastModified()

    private fun write(next: AppData) {
        _data.value = next
        val tmp = File(file.parentFile, "garage.json.tmp")
        tmp.writeText(json.encodeToString(AppData.serializer(), next))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

private fun r1(n: Double) = round(n * 10) / 10

/** Runs block with a fresh id and returns the data with nextId moved on. */
inline fun AppData.withNewId(block: AppData.(Long) -> AppData): AppData = copy(nextId = nextId + 1).block(nextId)

fun AppData.replaceVehicle(v: Vehicle) = copy(vehicles = vehicles.map { if (it.id == v.id) v else it })

/** Entries can carry a higher odometer than the vehicle has (a fill-up typed in later, say): move it up. */
fun AppData.setOdoAtLeast(vid: Long, odo: Double): AppData {
    val v = vehicle(vid) ?: return this
    return if (odo > liveOdo(v)) replaceVehicle(v.copy(odo = odo - (liveOdo(v) - v.odo))) else this
}

fun AppData.addVehicle(year: String, make: String, model: String, odo: Double, addr: String?, name: String?, type: String) =
    withNewId { id -> copy(vehicles = vehicles + Vehicle(id, year, make, model, odo, addr, name, type), lastActive = lastActive ?: id) }

fun AppData.deleteVehicle(vid: Long) = copy(
    vehicles = vehicles.filter { it.id != vid },
    trips = trips.filter { it.vid != vid },
    fuel = fuel.filter { it.vid != vid },
    service = service.filter { it.vid != vid },
    reminders = reminders.filter { it.vid != vid },
    expenses = expenses.filter { it.vid != vid },
    parts = parts.filter { it.vid != vid },
    docs = docs.filter { it.vid != vid },
    trip = trip?.takeIf { it.vid != vid },
    lastActive = if (lastActive == vid) null else lastActive,
)

fun AppData.correctOdometer(vid: Long, value: Double): AppData {
    val v = vehicle(vid) ?: return this
    val delta = r1(value - liveOdo(v))
    if (delta == 0.0) return this
    val nv = v.copy(odo = v.odo + delta)
    return replaceVehicle(nv).withNewId { id -> copy(trips = trips + Trip(id, vid, today(), delta, nv.odo, TripKind.CORRECTION)) }
}

fun AppData.addReading(vid: Long, value: Double): AppData {
    val v = vehicle(vid) ?: return this
    val d = value - v.odo
    if (d <= 0) return this
    return replaceVehicle(v.copy(odo = value)).withNewId { id ->
        copy(trips = trips + Trip(id, vid, today(), d, value, TripKind.MANUAL), lastActive = vid)
    }
}

fun AppData.deleteTrip(t: Trip): AppData {
    // Taking a trip off also takes its miles back off the odometer.
    val v = vehicle(t.vid)
    val base = if (v != null) replaceVehicle(v.copy(odo = v.odo - t.miles)) else this
    return base.copy(trips = trips.filter { it.id != t.id })
}

fun AppData.saveFuel(e: FuelEntry): AppData {
    val withId = if (e.id == 0L) withNewId { id -> copy(fuel = fuel + e.copy(id = id)) }
    else copy(fuel = fuel.map { if (it.id == e.id) e else it })
    return withId.setOdoAtLeast(e.vid, e.odo)
}

fun AppData.saveService(e: ServiceEntry, remind: Boolean, everyMi: Int, everyMo: Int): AppData {
    var d = if (e.id == 0L) withNewId { id -> copy(service = service + e.copy(id = id)) }
    else copy(service = service.map { if (it.id == e.id) e else it })
    d = d.setOdoAtLeast(e.vid, e.odo)
    val existing = d.reminders.firstOrNull { it.vid == e.vid && it.type.equals(e.type, ignoreCase = true) }
    if (remind && (everyMi > 0 || everyMo > 0)) {
        d = if (existing == null) d.withNewId { id ->
            copy(reminders = reminders + Reminder(id, e.vid, e.type, everyMi, everyMo, e.odo, e.date))
        } else {
            // Only move the reminder forward if this is the latest record of that service.
            val newer = e.date >= existing.lastDate
            val r = existing.copy(
                everyMi = everyMi, everyMo = everyMo,
                lastOdo = if (newer) e.odo else existing.lastOdo, lastDate = if (newer) e.date else existing.lastDate,
            )
            d.copy(reminders = d.reminders.map { if (it.id == r.id) r else it })
        }
    } else if (existing != null && e.date >= existing.lastDate) {
        val r = existing.copy(lastOdo = e.odo, lastDate = e.date)
        d = d.copy(reminders = d.reminders.map { if (it.id == r.id) r else it })
    }
    return d
}

fun AppData.deleteReminder(id: Long) = copy(reminders = reminders.filter { it.id != id })

fun AppData.savePart(p: Part): AppData {
    val d = if (p.id == 0L) withNewId { id -> copy(parts = parts + p.copy(id = id)) }
    else copy(parts = parts.map { if (it.id == p.id) p else it })
    return p.instOdo?.let { d.setOdoAtLeast(p.vid, it) } ?: d
}

fun AppData.installPart(pid: Long): AppData {
    val p = parts.firstOrNull { it.id == pid } ?: return this
    val v = vehicle(p.vid)
    val odo = if (v != null) round(liveOdo(v)) else p.odo
    return copy(parts = parts.map { if (it.id == pid) it.copy(installed = true, instOdo = odo, instDate = today()) else it })
}

fun AppData.saveExpense(e: Expense): AppData =
    if (e.id == 0L) withNewId { id -> copy(expenses = expenses + e.copy(id = id)) }
    else copy(expenses = expenses.map { if (it.id == e.id) e else it })

fun AppData.saveDoc(doc: Doc): AppData =
    if (doc.id == 0L) withNewId { id -> copy(docs = docs + doc.copy(id = id)) }
    else copy(docs = docs.map { if (it.id == doc.id) doc else it })

fun AppData.deleteDoc(id: Long) = copy(docs = docs.filter { it.id != id })

fun AppData.deleteFuel(id: Long) = copy(fuel = fuel.filter { it.id != id })
fun AppData.deleteService(id: Long) = copy(service = service.filter { it.id != id })
fun AppData.deletePart(id: Long) = copy(parts = parts.filter { it.id != id })
fun AppData.deleteExpense(id: Long) = copy(expenses = expenses.filter { it.id != id })

/* ---------- trips from the tracking service ---------- */

fun AppData.startTrip(vid: Long, addr: String, now: Long): AppData =
    if (trip != null) this else copy(trip = ActiveTrip(vid, addr, now), lastActive = vid)

fun AppData.addTripMiles(miles: Double): AppData = trip?.let { copy(trip = it.copy(miles = it.miles + miles)) } ?: this

/** Ends the running trip: its miles go onto the odometer and into the trip list. */
fun AppData.endTrip(): AppData {
    val t = trip ?: return this
    val v = vehicle(t.vid) ?: return copy(trip = null)
    val miles = r1(t.miles)
    if (miles < 0.1) return copy(trip = null)
    val nv = v.copy(odo = v.odo + miles)
    return replaceVehicle(nv).copy(trip = null).withNewId { id ->
        copy(trips = trips + Trip(id, v.id, today(), miles, nv.odo, TripKind.AUTO))
    }
}

fun AppData.markNotified(key: String) = copy(notified = notified + key)
