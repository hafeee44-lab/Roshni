package io.github.hafeee44lab.solargrid.data.cloud

/**
 * Inverter makers whose own cloud Roshni can read. Solarman keeps its original account store;
 * every other brand goes through [BrandAccount] and a source in this package.
 */
enum class CloudBrand(
    /** The brand's own name, never translated. */
    val title: String,
    /** Never ask the brand's cloud more often than this, whatever the poll policy wants. */
    val minPollMs: Long
) {
    SOLARMAN("Solarman", 0L),

    /**
     * Growatt's OpenAPI V1 refuses a second call to the same endpoint within 5 minutes (error
     * 10012), and the ShinePhone API locks accounts that sign in too often (msg 507). See
     * growattServer's exceptions.py and Home Assistant's growatt_server integration.
     */
    GROWATT("Growatt", 5 * 60_000L);

    companion object {
        fun from(name: String?): CloudBrand = entries.firstOrNull { it.name == name } ?: SOLARMAN
    }
}
