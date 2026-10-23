package com.motomech.garagelog.data

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

// Calculations shared by the screens, ported from the simulator.

fun today(): String = LocalDate.now().toString()
fun parseDate(s: String): LocalDate = runCatching { LocalDate.parse(s) }.getOrElse { LocalDate.now() }
fun addMonths(s: String, n: Int): String = parseDate(s).plusMonths(n.toLong()).toString()
fun addDays(s: String, n: Int): String = parseDate(s).plusDays(n.toLong()).toString()
fun daysBetween(a: String, b: String): Long = ChronoUnit.DAYS.between(parseDate(a), parseDate(b))

private val US = Locale.US
private val monthDay = DateTimeFormatter.ofPattern("MMM d", US)
private val monthDayYear = DateTimeFormatter.ofPattern("MMM d, yyyy", US)

fun fdate(s: String): String {
    val d = parseDate(s)
    return if (d.year == LocalDate.now().year) d.format(monthDay) else d.format(monthDayYear)
}

fun relday(s: String): String = when (daysBetween(s, today())) {
    0L -> "Today"
    1L -> "Yesterday"
    else -> fdate(s)
}

fun mi(n: Double): String = NumberFormat.getIntegerInstance(US).format(n.roundToLong())
fun mi1(n: Double): String = NumberFormat.getNumberInstance(US).apply {
    minimumFractionDigits = 1; maximumFractionDigits = 1
}.format(n)
fun usd(n: Double): String = NumberFormat.getCurrencyInstance(US).format(n)
fun usd0(n: Double): String = "$" + NumberFormat.getIntegerInstance(US).format(n.roundToLong())

fun AppData.vehicle(id: Long?): Vehicle? = vehicles.firstOrNull { it.id == id }
fun AppData.vehicleForAddress(addr: String?): Vehicle? =
    if (addr == null) null else vehicles.firstOrNull { it.btAddress.equals(addr, ignoreCase = true) }

/** The connected vehicle, else the last one used, else the first. */
fun AppData.activeId(): Long? =
    vehicleForAddress(connectedAddress)?.id ?: vehicle(lastActive)?.id ?: vehicles.firstOrNull()?.id

fun AppData.isConnected(vid: Long): Boolean = vehicleForAddress(connectedAddress)?.id == vid

fun AppData.liveOdo(v: Vehicle): Double = v.odo + (trip?.takeIf { it.vid == v.id }?.miles ?: 0.0)

/* ---------- reminders ---------- */

enum class Level { OK, WARN, BAD }

data class RemStatus(val level: Level, val text: String, val leftMi: Double?, val dueDate: String?, val rank: Double)

fun AppData.remStatus(r: Reminder): RemStatus {
    val v = vehicle(r.vid)
    val odo = if (v != null) liveOdo(v) else r.lastOdo
    val leftMi = if (r.everyMi > 0) r.lastOdo + r.everyMi - odo else null
    val dueDate = if (r.everyMo > 0) addMonths(r.lastDate, r.everyMo) else null
    val leftDays = dueDate?.let { daysBetween(today(), it) }
    val level = when {
        (leftMi != null && leftMi <= 0) || (leftDays != null && leftDays <= 0) -> Level.BAD
        (leftMi != null && leftMi <= 500) || (leftDays != null && leftDays <= 30) -> Level.WARN
        else -> Level.OK
    }
    val text = if (level == Level.BAD) {
        if (leftMi != null && leftMi <= 0) "Overdue by ${mi(-leftMi)} mi" else "Overdue since ${fdate(dueDate!!)}"
    } else {
        val parts = buildList {
            if (leftMi != null) add("in ${mi(leftMi)} mi")
            if (dueDate != null) add("by ${fdate(dueDate)}")
        }
        "Due " + parts.joinToString(" or ")
    }
    val rank = if (level == Level.BAD) -1e9 + (leftMi ?: 0.0)
    else minOf(leftMi ?: 1e9, (leftDays ?: 1_000_000_000L).toDouble() * 30)
    return RemStatus(level, text, leftMi, dueDate, rank)
}

fun AppData.vehicleReminders(vid: Long): List<Pair<Reminder, RemStatus>> =
    reminders.filter { it.vid == vid }.map { it to remStatus(it) }.sortedBy { it.second.rank }

/* ---------- documents ---------- */

private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", US)

/** Expiry badge for a document; null when it has no expiry date. Warns 30 days ahead. */
fun docStatus(doc: Doc): Pair<Level, String>? {
    val e = doc.expires ?: return null
    val left = daysBetween(today(), e)
    return when {
        left < 0 -> Level.BAD to "Expired ${fdate(e)}"
        left == 0L -> Level.BAD to "Expires today"
        left <= 30 -> Level.WARN to "Expires in $left day${if (left == 1L) "" else "s"}"
        else -> Level.OK to "Good to ${parseDate(e).format(monthYear)}"
    }
}

fun AppData.vehicleDocs(vid: Long): List<Doc> =
    docs.filter { it.vid == vid }.sortedWith(compareBy({ DOC_TYPES.indexOf(it.type).let { i -> if (i < 0) 99 else i } }, { it.label.lowercase() }))

/* ---------- fuel ---------- */

data class FuelRow(val f: FuelEntry, val mpg: Double?)

/**
 * Fill-ups oldest first, with MPG worked out between full tanks (partials roll into the next full one).
 * A fill-up marked missed starts over: the fuel of the one that wasn't logged is unknown.
 */
fun AppData.fuelRows(vid: Long): List<FuelRow> {
    val rows = fuel.filter { it.vid == vid }.sortedBy { it.odo }
    var prev: FuelEntry? = null
    var gSince = 0.0
    return rows.map { f ->
        if (f.missed) { prev = null; gSince = 0.0 }
        gSince += f.gal
        val mpg = if (prev != null && f.full && gSince > 0) (f.odo - prev!!.odo) / gSince else null
        if (f.full) { prev = f; gSince = 0.0 }
        FuelRow(f, mpg)
    }
}

data class FuelStats(val avg: Double?, val cpm: Double?, val series: List<Double>)

fun AppData.fuelStats(vid: Long): FuelStats {
    val rows = fuelRows(vid).filter { it.mpg != null && it.mpg > 0 }
    var m = 0.0; var g = 0.0; var c = 0.0
    rows.takeLast(5).forEach { m += it.mpg!! * it.f.gal; g += it.f.gal; c += it.f.total }
    return FuelStats(if (g > 0) m / g else null, if (m > 0) c / m else null, rows.takeLast(8).map { it.mpg!! })
}

/* ---------- costs ---------- */

enum class Range(val label: String, val long: String) { DAYS30("30 days", "last 30 days"), YEAR("This year", "this year"), ALL("All time", "all time") }

fun Range.start(): String = when (this) {
    Range.DAYS30 -> addDays(today(), -30)
    Range.YEAR -> today().take(4) + "-01-01"
    Range.ALL -> "0000-01-01"
}

const val CAT_FUEL = "Fuel"
const val CAT_SERVICE = "Service and repairs"
const val CAT_PARTS = "Parts"
const val CAT_BILLS = "Bills"
const val CAT_OTHER = "Other"

data class CostItem(val cat: String, val amt: Double, val vid: Long, val sub: String? = null)

/** vid null means all vehicles. */
fun AppData.costItems(vid: Long?, start: String): List<CostItem> {
    val end = today()
    fun ok(v: Long, d: String) = (vid == null || v == vid) && d >= start && d <= end
    return buildList {
        fuel.filter { ok(it.vid, it.date) }.forEach { add(CostItem(CAT_FUEL, it.total, it.vid)) }
        service.filter { ok(it.vid, it.date) }.forEach { add(CostItem(CAT_SERVICE, it.cost, it.vid)) }
        parts.filter { ok(it.vid, it.date) }.forEach { add(CostItem(CAT_PARTS, it.cost, it.vid)) }
        expenses.filter { ok(it.vid, it.date) }.forEach {
            add(if (it.cat == CAT_BILLS) CostItem(CAT_BILLS, it.cost, it.vid) else CostItem(CAT_OTHER, it.cost, it.vid, it.cat))
        }
    }
}

/** Miles driven since a date, from the lowest odometer recorded since then up to now. */
fun AppData.milesSince(vid: Long, start: String): Double {
    val v = vehicle(vid) ?: return 0.0
    val odos = mutableListOf<Double>()
    fuel.filter { it.vid == vid && it.date >= start && it.odo > 0 }.forEach { odos += it.odo }
    service.filter { it.vid == vid && it.date >= start && it.odo > 0 }.forEach { odos += it.odo }
    expenses.filter { it.vid == vid && it.date >= start && it.odo > 0 }.forEach { odos += it.odo }
    parts.filter { it.vid == vid && it.date >= start && it.odo > 0 }.forEach { odos += it.odo }
    trips.filter { it.vid == vid && it.date >= start && it.kind != TripKind.CORRECTION }.forEach { odos += it.odo - it.miles }
    if (odos.isEmpty()) return 0.0
    return max(0.0, liveOdo(v) - odos.min())
}

fun AppData.milesLast30(): Double {
    val st = addDays(today(), -30)
    return trips.filter { it.date >= st && it.kind != TripKind.CORRECTION }.sumOf { it.miles } + (trip?.miles ?: 0.0)
}

fun fmtSigned(n: Double) = (if (n >= 0) "+" else "−") + mi1(abs(n))
