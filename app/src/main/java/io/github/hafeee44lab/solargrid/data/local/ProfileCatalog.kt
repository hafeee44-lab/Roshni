package io.github.hafeee44lab.solargrid.data.local

import android.content.res.AssetManager

/**
 * Every inverter profile bundled in assets/inverters. Deye and its OEM brands (Sunsynk, Sol-Ark)
 * report a device type in holding register 0, so those can be picked automatically; everything
 * else has to be chosen by the user.
 */
class ProfileCatalog(val profiles: List<InverterProfile>) {

    init {
        profiles.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw ProfileException("Two profiles are called $it")
        }
        val codes = profiles.flatMap { p -> p.detectCodes.map { it to p.id } }
        codes.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.entries.firstOrNull()?.let { (code, ids) ->
            throw ProfileException("Device type 0x%04X picks both %s".format(code, ids.joinToString(" and ")))
        }
    }

    fun byId(id: String): InverterProfile? = profiles.firstOrNull { it.id == id }

    /** The profile Deye's device-type code selects, or null for codes we have no map for. */
    fun detect(deviceType: Int): InverterProfile? = profiles.firstOrNull { deviceType in it.detectCodes }

    /** One row per brand and model, sorted by brand, for the picker. */
    val entries: List<Entry>
        get() = profiles.flatMap { p -> p.models.map { Entry(it.brand, it.name, p) } }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Entry::brand).thenBy(String.CASE_INSENSITIVE_ORDER, Entry::model))

    data class Entry(val brand: String, val model: String, val profile: InverterProfile) {
        fun matches(query: String): Boolean {
            val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val haystack = "$brand $model ${profile.label}".lowercase()
            return words.all { it in haystack }
        }
    }

    companion object {
        const val DIRECTORY = "inverters"
        const val DEVICE_TYPE_REGISTER = 0x0000

        fun load(files: Map<String, String>): ProfileCatalog =
            ProfileCatalog(files.toSortedMap().map { (name, text) -> ProfileParser.parse(name, text) })

        fun fromAssets(assets: AssetManager): ProfileCatalog {
            val names = assets.list(DIRECTORY).orEmpty().filter { it.endsWith(".json") }
            return load(names.associateWith { name -> assets.open("$DIRECTORY/$name").bufferedReader().use { it.readText() } })
        }
    }
}
