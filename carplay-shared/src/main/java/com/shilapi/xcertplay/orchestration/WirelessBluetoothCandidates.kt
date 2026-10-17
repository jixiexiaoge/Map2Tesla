package com.shilapi.xcertplay.orchestration

import java.io.IOException

internal data class WirelessBluetoothCandidate(
    val name: String?,
    val phoneClass: Boolean,
    val connected: Boolean,
)

internal fun wirelessBluetoothCandidateIndices(
    bonded: List<WirelessBluetoothCandidate>,
): List<Int> {
    if (bonded.isEmpty()) {
        throw IOException("No paired Bluetooth devices found; pair the iPhone in Android Bluetooth settings")
    }
    val namedIphones = bonded.indices.filter {
        bonded[it].name?.contains("iPhone", ignoreCase = true) == true
    }
    val phones = bonded.indices.filter { bonded[it].phoneClass }
    val eligible = when {
        namedIphones.isNotEmpty() -> namedIphones
        phones.isNotEmpty() -> phones
        bonded.size == 1 -> bonded.indices.toList()
        else -> throw IOException(
            "No identifiable iPhone among ${bonded.size} paired Bluetooth devices; " +
                "rename the paired iPhone to include 'iPhone' or unpair unrelated devices",
        )
    }
    return eligible.sortedWith(compareByDescending<Int> { bonded[it].connected }.thenBy { it })
}
