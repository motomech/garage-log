package com.motomech.garagelog.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.round

// Import from MotoLog's Excel exports. The main file is the vehicle's Share export, one sheet with
// #Vehicle, #FillUp, #Trip and #Bill sections. The Fuel and Trip reports are optional extras: they
// carry make, model, year, plate and VIN, and the "Full after skipped" fill-up type.

private const val L_PER_GAL = 3.785411784
private const val KM_PER_MI = 1.609344

data class MlFill(
    val at: LocalDateTime, val odo: Double, val gal: Double, val price: Double, val full: Boolean,
    val station: String, val addr: String, val note: String,
)

data class MlTrip(
    val start: LocalDateTime, val end: LocalDateTime?, val miles: Double,
    val from: String, val through: String, val to: String, val topMph: Double?,
)

data class MlBill(val at: LocalDateTime, val odo: Double, val desc: String, val cost: Double)

data class MlVehicle(
    val name: String,
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val plate: String = "",
    val vin: String = "",
    val engine: String = "",
    val fills: List<MlFill> = emptyList(),
    val trips: List<MlTrip> = emptyList(),
    val bills: List<MlBill> = emptyList(),
    /** From the Fuel report: fill-ups (to the minute) marked "Full after skipped". */
    val missedAt: Set<LocalDateTime> = emptySet(),
) {
    val first: LocalDateTime? get() = (fills.map { it.at } + trips.map { it.start } + bills.map { it.at }).minOrNull()
    val last: LocalDateTime? get() = (fills.map { it.at } + trips.map { it.start } + bills.map { it.at }).maxOrNull()
}

object MotoLog {
    /** Reads the picked files (each one sheet of rows) into vehicles. Throws with a readable message. */
    fun read(sheets: List<List<List<Any?>>>): List<MlVehicle> {
        val shares = ArrayList<MlVehicle>()
        val reports = ArrayList<MlVehicle>()
        for (rows in sheets) {
            val a1 = rows.firstOrNull()?.firstOrNull()
            when {
                a1 is String && a1.trim() == "#Vehicle" -> shares += readShare(rows)
                a1 is String && a1.contains("Name:") -> reports += readReport(rows)
                else -> throw IllegalArgumentException("One of the files isn't a MotoLog export.")
            }
        }
        if (shares.isEmpty()) throw IllegalArgumentException("Pick the vehicle's Share export too. The Fuel and Trip reports only add details to it.")
        // Fold each report into the Share vehicle of the same name.
        return shares.map { s ->
            reports.filter { it.name.equals(s.name, ignoreCase = true) }.fold(s) { v, r ->
                v.copy(
                    make = v.make.ifBlank { r.make }, model = v.model.ifBlank { r.model }, year = v.year.ifBlank { r.year },
                    plate = v.plate.ifBlank { r.plate }, vin = v.vin.ifBlank { r.vin }, engine = v.engine.ifBlank { r.engine },
                    missedAt = v.missedAt + r.missedAt,
                )
            }
        }
    }

    /** Column positions by heading; a heading used twice (Trip has Start time and Start address) keeps both. */
    private class Head(row: List<Any?>) {
        private val cols = row.mapIndexedNotNull { i, v -> (v as? String)?.trim()?.lowercase()?.let { it to i } }
        fun at(name: String, nth: Int = 0): Int? = cols.filter { it.first == name.lowercase() }.getOrNull(nth)?.second
    }

    private fun List<Any?>.cell(i: Int?): Any? = if (i == null) null else getOrNull(i)

    private fun isSection(r: List<Any?>) = (r.firstOrNull() as? String)?.trim()?.startsWith("#") == true

    private fun readShare(rows: List<List<Any?>>): List<MlVehicle> {
        val out = ArrayList<MlVehicle>()
        var i = 0
        while (i < rows.size) {
            val r = rows[i]
            if (!isSection(r)) { i++; continue }
            val section = (r[0] as String).trim()
            val head = Head(rows.getOrNull(i + 1) ?: emptyList())
            var j = i + 2
            val body = ArrayList<List<Any?>>()
            while (j < rows.size && !isSection(rows[j])) { if (rows[j].any { it != null }) body += rows[j]; j++ }
            when (section) {
                "#Vehicle" -> body.forEach { b -> Xlsx.text(b.cell(head.at("name"))).takeIf { it.isNotBlank() }?.let { out += MlVehicle(it) } }
                "#FillUp" -> if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(fills = body.mapNotNull { fill(it, head) })
                "#Trip" -> if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(trips = body.mapNotNull { trip(it, head) })
                "#Bill" -> if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(bills = bills(body, head))
            }
            i = j
        }
        if (out.isEmpty()) throw IllegalArgumentException("The Share file has no vehicle in it.")
        return out
    }

    private fun fill(r: List<Any?>, h: Head): MlFill? {
        val at = Xlsx.dateTime(r.cell(h.at("date"))) ?: return null
        var gal = 0.0
        var cost = 0.0
        var full = false
        // Up to three fuels per fill-up; they're added together.
        for (n in 1..3) {
            val q = Xlsx.num(r.cell(h.at("quantity $n"))) ?: continue
            val p = Xlsx.num(r.cell(h.at("price $n"))) ?: 0.0
            val litres = Xlsx.text(r.cell(h.at("unit $n"))).lowercase().startsWith("l")
            gal += if (litres) q / L_PER_GAL else q
            cost += q * p
            if (Xlsx.text(r.cell(h.at("full $n"))).equals("yes", ignoreCase = true)) full = true
        }
        if (gal <= 0) return null
        return MlFill(
            at, Xlsx.num(r.cell(h.at("mileage"))) ?: 0.0, gal, cost / gal, full,
            Xlsx.text(r.cell(h.at("gas station name"))), Xlsx.text(r.cell(h.at("gas station description"))),
            Xlsx.text(r.cell(h.at("note"))),
        )
    }

    private fun trip(r: List<Any?>, h: Head): MlTrip? {
        val start = Xlsx.dateTime(r.cell(h.at("start"))) ?: return null
        val km = Xlsx.text(r.cell(h.at("distance unit"))).lowercase().startsWith("km")
        val miles = (Xlsx.num(r.cell(h.at("distance"))) ?: return null).let { if (km) it / KM_PER_MI else it }
        val kmh = Xlsx.text(r.cell(h.at("speed unit"))).lowercase().startsWith("km")
        val top = Xlsx.num(r.cell(h.at("max speed")))?.let { if (kmh) it / KM_PER_MI else it }
        return MlTrip(
            start, Xlsx.dateTime(r.cell(h.at("end"))), miles,
            Xlsx.text(r.cell(h.at("start", 1))), Xlsx.text(r.cell(h.at("through"))), Xlsx.text(r.cell(h.at("finish"))),
            top?.takeIf { it > 0 },
        )
    }

    /** A bill row (with a date) is followed by its item rows (no date). Revenues are left out. */
    private fun bills(body: List<List<Any?>>, h: Head): List<MlBill> {
        val out = ArrayList<MlBill>()
        var cur: MlBill? = null
        var items = ArrayList<String>()
        var revenue = false
        fun flush() {
            cur?.let { b -> if (!revenue) out += b.copy(desc = items.joinToString("; ").ifBlank { b.desc }) }
            cur = null; items = ArrayList(); revenue = false
        }
        for (r in body) {
            val at = Xlsx.dateTime(r.cell(h.at("date")))
            if (at != null) {
                flush()
                val km = Xlsx.text(r.cell(h.at("mileage unit"))).lowercase().startsWith("km")
                val odo = (Xlsx.num(r.cell(h.at("mileage"))) ?: 0.0).let { if (km) it / KM_PER_MI else it }
                cur = MlBill(at, odo, Xlsx.text(r.cell(h.at("note"))), Xlsx.num(r.cell(h.at("total cost"))) ?: 0.0)
                revenue = Xlsx.text(r.cell(h.at("revenue"))).equals("yes", ignoreCase = true)
            } else if (cur != null) {
                val name = Xlsx.text(r.cell(h.at("item name")))
                val qty = Xlsx.num(r.cell(h.at("item quantity"))) ?: 1.0
                if (name.isNotBlank()) items += if (qty > 1) "$name x${Xlsx.text(qty)}" else name
            }
        }
        flush()
        return out
    }

    /** A Fuel or Trip report: vehicle details in A1, report name in row 2, headings in row 4. */
    private fun readReport(rows: List<List<Any?>>): MlVehicle {
        val info = (rows[0][0] as String).lines().mapNotNull { line ->
            val k = line.substringBefore(":", "").trim()
            if (k.isEmpty()) null else k.lowercase() to line.substringAfter(":").trim()
        }.toMap()
        fun info(prefix: String) = info.entries.firstOrNull { it.key.startsWith(prefix) }?.value.orEmpty()
        val cc = info("displacement").replace(",", "").toDoubleOrNull()
        val year = info("year").let { y -> if (y.length == 2 && y.all { it.isDigit() }) twoDigitYear(y.toInt()) else y }
        val base = MlVehicle(
            info("name"), info("manufacturer"), info("model"), year, info("license plate"), info("vin"),
            cc?.let { "%,d cc".format(it.toLong()) } ?: "",
        )
        val kind = Xlsx.text(rows.getOrNull(1)?.firstOrNull())
        val head = rows.indexOfFirst { Xlsx.text(it.firstOrNull()) == "#" }
        if (head < 0) return base
        val h = Head(rows[head])
        val body = rows.drop(head + 1).filter { Xlsx.num(it.firstOrNull()) != null }
        return when {
            kind.startsWith("Fuel", ignoreCase = true) -> base.copy(missedAt = body.filter {
                Xlsx.text(it.cell(h.at("type"))).contains("skipped", ignoreCase = true)
            }.mapNotNull { Xlsx.dateTime(it.cell(h.at("date")))?.truncatedTo(ChronoUnit.MINUTES) }.toSet())
            else -> base
        }
    }

    private fun twoDigitYear(y: Int): String {
        val now = LocalDateTime.now().year % 100
        return (if (y > now) 1900 + y else 2000 + y).toString()
    }
}

/* ---------- putting it into Garage Log ---------- */

data class MlChoice(val trips: Boolean = true, val fuel: Boolean = true, val bills: Boolean = true)

data class MlResult(val vid: Long, val trips: Int, val fills: Int, val bills: Int, val skipped: Int)

/** An existing vehicle that looks like this one: same VIN, else plate, else name. */
fun AppData.motoLogMatch(m: MlVehicle): Long? {
    fun same(a: String, b: String) = a.isNotBlank() && a.replace(" ", "").equals(b.replace(" ", ""), ignoreCase = true)
    return (vehicles.firstOrNull { same(m.vin, it.vin) } ?: vehicles.firstOrNull { same(m.plate, it.plate) }
        ?: vehicles.firstOrNull { same(m.name, it.label) || same(m.name, it.model) })?.id
}

/** "6451, 71st Street North, Pinellas Park, ..." → "6451 71st Street North"; "Citgo – Pinellas Park, Park Blvd" → "Citgo – Pinellas Park". */
fun shortAddress(s: String): String {
    val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return ""
    return if (parts[0].all { it.isDigit() } && parts.size > 1) "${parts[0]} ${parts[1]}" else parts[0]
}

private fun r1(n: Double) = round(n * 10) / 10

/**
 * Adds a MotoLog vehicle's records to Garage Log, into vehicle [target] or a new one when null.
 * Anything already there (same trip start, same fill-up date and gallons, same bill) is skipped,
 * so importing the same file twice adds nothing.
 */
fun AppData.importMotoLog(m: MlVehicle, target: Long?, c: MlChoice): Pair<AppData, MlResult> {
    var d = this
    val vid: Long
    if (target == null || d.vehicle(target) == null) {
        vid = d.nextId
        val type = if (m.model.contains("convertible", ignoreCase = true)) "conv" else "car"
        d = d.withNewId { id ->
            copy(vehicles = vehicles + Vehicle(id, m.year, m.make, m.name, 0.0, type = type, plate = m.plate, vin = m.vin, engine = m.engine),
                lastActive = lastActive ?: id)
        }
    } else {
        vid = target
        val v = d.vehicle(target)!!
        d = d.replaceVehicle(v.copy(
            year = v.year.ifBlank { m.year }, make = v.make.ifBlank { m.make }, plate = v.plate.ifBlank { m.plate },
            vin = v.vin.ifBlank { m.vin }, engine = v.engine.ifBlank { m.engine },
        ))
    }
    val zone = ZoneId.systemDefault()
    fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    // MotoLog trips carry no odometer: count it from the latest fill-up's typed odometer. (The Trip
    // report's odometer can't be trusted: it starts at 0 before the real reading was entered.)
    val mts = m.trips.sortedBy { it.start }
    val cum = mts.runningFold(0.0) { acc, t -> acc + t.miles }.drop(1)
    fun milesBy(at: LocalDateTime) = mts.indices.lastOrNull { (mts[it].end ?: mts[it].start) <= at }?.let { cum[it] } ?: 0.0
    val anchor = m.fills.filter { it.odo > 0 }.maxByOrNull { it.at }
    val offset = when {
        anchor != null -> anchor.odo - milesBy(anchor.at)
        target != null -> maxOf(0.0, (d.vehicle(vid)?.let { d.liveOdo(it) } ?: 0.0) - (cum.lastOrNull() ?: 0.0))
        else -> 0.0
    }
    fun odoAt(at: LocalDateTime) = offset + milesBy(at)

    var added = Triple(0, 0, 0)
    var skipped = 0
    var top = 0.0

    if (c.trips) for ((i, t) in mts.withIndex()) {
        val start = millis(t.start)
        if (d.trips.any { it.vid == vid && it.start != null && abs(it.start - start) < 1000 }) { skipped++; continue }
        val odo = r1(offset + cum[i])
        top = maxOf(top, odo)
        d = d.withNewId { id ->
            copy(trips = trips + Trip(
                id, vid, t.start.toLocalDate().toString(), r1(t.miles), odo, TripKind.AUTO,
                toAddr = shortAddress(t.to).ifBlank { null }, start = start, end = t.end?.let { millis(it) },
                fromAddr = shortAddress(t.from).ifBlank { null }, topMph = t.topMph,
                stops = t.through.lines().map { it.trim() }.filter { it.isNotEmpty() && it != t.from && it != t.to }.map(::shortAddress),
                src = "motolog",
            ))
        }
        added = added.copy(first = added.first + 1)
    }

    if (c.fuel) {
        var prevOdo = 0.0
        for (f in m.fills.sortedBy { it.at }) {
            val odo = if (f.odo > 0) f.odo else if (mts.isNotEmpty()) round(odoAt(f.at)) else prevOdo
            prevOdo = odo
            val date = f.at.toLocalDate().toString()
            if (d.fuel.any { it.vid == vid && it.date == date && abs(it.gal - f.gal) < 0.01 }) { skipped++; continue }
            top = maxOf(top, odo)
            d = d.withNewId { id ->
                copy(fuel = fuel + FuelEntry(
                    id, vid, date, odo, round(f.gal * 1000) / 1000, round(f.price * 1000) / 1000, f.full, f.station, f.addr,
                    missed = f.at.truncatedTo(ChronoUnit.MINUTES) in m.missedAt,
                ))
            }
            added = added.copy(second = added.second + 1)
        }
    }

    if (c.bills) for (b in m.bills) {
        val date = b.at.toLocalDate().toString()
        if (d.expenses.any { it.vid == vid && it.cat == CAT_BILLS && it.date == date && abs(it.cost - b.cost) < 0.005 }) { skipped++; continue }
        top = maxOf(top, b.odo)
        d = d.withNewId { id -> copy(expenses = expenses + Expense(id, vid, date, b.odo, CAT_BILLS, b.desc, b.cost)) }
        added = added.copy(third = added.third + 1)
    }

    d = d.setOdoAtLeast(vid, top)
    return d to MlResult(vid, added.first, added.second, added.third, skipped)
}
