package com.postbox.carrier

/**
 * Stable carrier identifiers. The [id] literals are a persistence contract:
 * they are stored as DB TEXT in `parcels.carrier` and must never change once stored.
 */
enum class CarrierId(val id: String) {
    DHL("dhl"),
    CTT("ctt"),
    POSTNL("postnl"),
    DPD("dpd"),
    PAACK("paack"),
    ECOSCOOTING("ecoscooting");

    companion object {
        /** Maps a stored DB TEXT value back to the enum; null for unknown ids. */
        fun fromId(id: String): CarrierId? = entries.firstOrNull { it.id == id }
    }
}
