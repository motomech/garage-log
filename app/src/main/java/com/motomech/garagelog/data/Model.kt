package com.motomech.garagelog.data

import kotlinx.serialization.Serializable

// Everything the app stores, saved as one JSON file on the phone (see Repo).
// Dates are ISO strings (yyyy-MM-dd); distances are miles.

@Serializable
data class Vehicle(
    val id: Long,
    val year: String = "",
    val make: String = "",
    val model: String = "",
    val odo: Double = 0.0,
    val btAddress: String? = null,
    val btName: String? = null,
) {
    val label: String get() = model.ifBlank { listOf(year, make).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Vehicle" } }
    val subtitle: String get() = listOf(year, make).filter { it.isNotBlank() }.joinToString(" ")
}

object TripKind {
    const val AUTO = "auto"
    const val MANUAL = "manual"
    const val CORRECTION = "correction"
}

@Serializable
data class Trip(
    val id: Long,
    val vid: Long,
    val date: String,
    val miles: Double,
    val odo: Double, // odometer at the end of the trip
    val kind: String = TripKind.AUTO,
    // Where the trip started and ended (GPS), and what was found at the end point.
    val fromLat: Double? = null,
    val fromLon: Double? = null,
    val toLat: Double? = null,
    val toLon: Double? = null,
    val toStation: String? = null,
    val toAddr: String? = null,
)

@Serializable
data class FuelEntry(
    val id: Long,
    val vid: Long,
    val date: String,
    val odo: Double,
    val gal: Double,
    val price: Double,
    val full: Boolean = true,
    val station: String = "",
    val stationAddr: String = "",
) {
    val total: Double get() = gal * price
}

/** A spot the user named (Home, Work...). Trips that start or end within radiusM of it carry its name. */
@Serializable
data class Place(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int = 150,
)

/**
 * Set when Log fill-up ended a trip at the pump while the car's Bluetooth stayed connected: the
 * next trip starts when the fill-up is saved or the phone moves faster than 5 mph.
 */
@Serializable
data class PumpWait(
    val vid: Long,
    val address: String,
    val since: Long,
)

/** Where the last trip ended, kept so the fill-up form can suggest the station there. */
@Serializable
data class LastStop(
    val vid: Long,
    val lat: Double,
    val lon: Double,
    val at: Long,
    val station: String? = null,
    val stationAddr: String? = null,
)

@Serializable
data class ServiceEntry(
    val id: Long,
    val vid: Long,
    val date: String,
    val odo: Double,
    val type: String,
    val cost: Double,
    val notes: String = "",
)

@Serializable
data class Reminder(
    val id: Long,
    val vid: Long,
    val type: String,
    val everyMi: Int = 0,
    val everyMo: Int = 0,
    val lastOdo: Double,
    val lastDate: String,
)

@Serializable
data class Expense(
    val id: Long,
    val vid: Long,
    val date: String,
    val odo: Double = 0.0,
    val cat: String,
    val desc: String = "",
    val cost: Double,
)

@Serializable
data class Part(
    val id: Long,
    val vid: Long,
    val date: String,
    val odo: Double = 0.0,
    val name: String,
    val partNo: String = "",
    val store: String = "",
    val qty: Int = 1,
    val cost: Double,
    val installed: Boolean = true,
    val instOdo: Double? = null,
    val instDate: String? = null,
)

/** A trip being measured right now by the tracking service. */
@Serializable
data class ActiveTrip(
    val vid: Long,
    val address: String,
    val startedAt: Long,
    val miles: Double = 0.0,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
)

@Serializable
data class AppData(
    val vehicles: List<Vehicle> = emptyList(),
    val trips: List<Trip> = emptyList(),
    val fuel: List<FuelEntry> = emptyList(),
    val service: List<ServiceEntry> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val parts: List<Part> = emptyList(),
    val places: List<Place> = emptyList(),
    val lastStop: LastStop? = null,
    val nextId: Long = 1,
    val lastActive: Long? = null,
    val connectedAddress: String? = null,
    val trip: ActiveTrip? = null,
    val pumpWait: PumpWait? = null,
    val notified: Set<String> = emptySet(),
)

val SERVICE_TYPES = listOf(
    "Oil and filter", "Tire rotation", "Brake pads", "Air filter", "Cabin air filter", "Coolant flush",
    "Transmission fluid", "Spark plugs", "Battery", "Wiper blades", "Tune-up", "Inspection",
)
val PART_NAMES = listOf(
    "Wiper blades", "Air filter", "Oil filter", "Spark plugs", "Headlight bulb", "Battery", "Brake pads",
    "Serpentine belt", "Floor mats", "Fuses",
)
val EXPENSE_CATS = listOf("Tires", "Insurance", "Registration and fees", "Parking and tolls", "Other")
