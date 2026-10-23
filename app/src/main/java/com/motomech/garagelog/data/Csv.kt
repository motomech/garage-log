package com.motomech.garagelog.data

import java.util.Locale

data class ExportChoice(
    val vid: Long? = null, // null = all vehicles
    val mileage: Boolean = true,
    val fuel: Boolean = true,
    val service: Boolean = true,
    val parts: Boolean = true,
    val expenses: Boolean = true,
)

private fun q(s: String) = if (s.any { it == '"' || it == ',' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
private fun n2(n: Double) = String.format(Locale.US, "%.2f", n)
private fun n0(n: Double) = Math.round(n).toString()
private fun num(n: Double) = if (n == Math.floor(n)) n0(n) else n.toString()

/** One flat CSV of everything, oldest first, same columns as the simulator's export. */
fun AppData.toCsv(c: ExportChoice): Pair<String, Int> {
    fun ok(vid: Long) = c.vid == null || c.vid == vid
    fun name(vid: Long) = vehicle(vid)?.label ?: ""
    val rows = mutableListOf<List<String>>()
    if (c.mileage) trips.filter { ok(it.vid) }.forEach {
        val kind = when (it.kind) { TripKind.AUTO -> "trip"; TripKind.MANUAL -> "reading"; else -> "correction" }
        rows += listOf(name(it.vid), it.date, kind, n0(it.odo), num(it.miles), "", "", "", "")
    }
    if (c.fuel) fuel.filter { ok(it.vid) }.forEach {
        rows += listOf(name(it.vid), it.date, "fuel", n0(it.odo), "", if (it.full) "full tank" else "partial", num(it.gal), n2(it.price), n2(it.total))
    }
    if (c.service) service.filter { ok(it.vid) }.forEach {
        rows += listOf(name(it.vid), it.date, "service", n0(it.odo), "", it.type + if (it.notes.isNotBlank()) ": " + it.notes else "", "", "", n2(it.cost))
    }
    if (c.parts) parts.filter { ok(it.vid) }.forEach {
        val desc = it.name + (if (it.partNo.isNotBlank()) " (${it.partNo})" else "") +
            (if (it.store.isNotBlank()) " from ${it.store}" else "") + (if (it.qty > 1) " x${it.qty}" else "") +
            (if (it.installed) "" else " [on shelf]")
        rows += listOf(name(it.vid), it.date, "part", n0(it.instOdo ?: it.odo), "", desc, "", "", n2(it.cost))
    }
    if (c.expenses) expenses.filter { ok(it.vid) }.forEach {
        rows += listOf(name(it.vid), it.date, "expense", if (it.odo > 0) n0(it.odo) else "", "", it.cat + if (it.desc.isNotBlank()) ": " + it.desc else "", "", "", n2(it.cost))
    }
    rows.sortBy { it[1] }
    val text = buildString {
        append("vehicle,date,record_type,odometer,miles,description,gallons,price_per_gal,cost\n")
        rows.forEach { append(it.joinToString(",") { f -> q(f) }).append('\n') }
    }
    return text to rows.size
}
