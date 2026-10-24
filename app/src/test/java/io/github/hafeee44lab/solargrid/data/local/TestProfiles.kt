package io.github.hafeee44lab.solargrid.data.local

import java.io.File

/** The bundled profiles, read straight from src/main/assets (unit tests run in the module folder). */
object TestProfiles {
    val directory = File("src/main/assets/${ProfileCatalog.DIRECTORY}")

    val files: Map<String, String> by lazy {
        directory.listFiles { f -> f.name.endsWith(".json") }!!.associate { it.name to it.readText() }
    }

    val catalog: ProfileCatalog by lazy { ProfileCatalog.load(files) }

    fun profile(id: String): InverterProfile = catalog.byId(id) ?: error("no profile $id")

    /** Holding registers by address, keyed the way [InverterProfile.read] expects. */
    fun holding(registers: Map<Int, Int>) = registers.mapKeys { InverterProfile.key(ModbusRtu.READ_HOLDING, it.key) }

    fun input(registers: Map<Int, Int>) = registers.mapKeys { InverterProfile.key(ModbusRtu.READ_INPUT, it.key) }
}
