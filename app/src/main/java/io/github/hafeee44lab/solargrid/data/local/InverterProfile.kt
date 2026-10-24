package io.github.hafeee44lab.solargrid.data.local

import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import java.util.Locale
import kotlin.math.abs

/** A run of registers read in one Modbus request. */
data class RegisterBlock(
    val start: Int,
    val count: Int,
    val required: Boolean = true,
    val function: Int = ModbusRtu.READ_HOLDING
) {
    val end: Int get() = start + count - 1
}

/** One line in the model picker: a brand and the models a profile covers. */
data class InverterModel(val brand: String, val name: String)

/**
 * Where a raw number comes from. Registers are listed low word first, the order ha-solarman's
 * definitions use, so a 32-bit value whose high word sits at the lower address is written
 * [low, high] = [0x0101, 0x0100].
 */
data class RegisterValue(
    val registers: List<Int>,
    val function: Int,
    val signed: Boolean = false,
    val bit: Int? = null,
    val offset: Double = 0.0,
    val scale: Double = 1.0,
    val subtract: Boolean = false,
    /** Drop the sign, for a magnitude whose direction lives in another register ([NegateWhen]). */
    val abs: Boolean = false
)

/** Flip the sign while another register holds one of [values], e.g. Solis's battery direction. */
data class NegateWhen(val register: Int, val function: Int, val values: Set<Int>)

/**
 * One Roshni metric. Either [value] or [sum] is set. With [text], the raw number (after [RegisterValue.bit])
 * is looked up instead of scaled; numbers missing from the table produce no metric at all (unless
 * [textOther] says otherwise), so a status the profile doesn't understand never pretends to be
 * "grid on".
 */
data class MetricSpec(
    val key: String,
    val name: String,
    val unit: String,
    val value: RegisterValue?,
    val sum: List<RegisterValue>,
    val scale: Double,
    val text: Map<Int, String>?,
    /** The word for any number [text] doesn't list; null leaves such numbers out. */
    val textOther: String?,
    val negateWhen: NegateWhen?,
    val floor: Double?,
    val optional: Boolean
) {
    val registers: List<Pair<Int, Int>>
        get() = (listOfNotNull(value) + sum).flatMap { v -> v.registers.map { v.function to it } } +
            listOfNotNull(negateWhen).map { it.function to it.register }

    /** Whole numbers for steps of 1 or more, two decimals otherwise. */
    val decimals: Int
        get() {
            val step = abs(scale) * ((listOfNotNull(value) + sum).minOfOrNull { abs(it.scale) } ?: 1.0)
            return if (step >= 1.0) 0 else 2
        }
}

/**
 * How to read one family of inverters through a Solarman logger, loaded from a bundled
 * assets/inverters/<id>.json file by [ProfileParser]. [detectCodes] are values of Deye's device
 * type register (0) that select this profile automatically.
 */
data class InverterProfile(
    val id: String,
    val label: String,
    val models: List<InverterModel>,
    val source: String,
    val verified: Verification,
    val detectCodes: Set<Int>,
    val blocks: List<RegisterBlock>,
    val metrics: List<MetricSpec>
) {
    enum class Verification { DEVICE, DOCUMENTATION }

    /** Turns raw registers ((function shl 16) or address → value) into Solarman-style metrics. */
    fun read(registers: Map<Int, Int>): List<SolarMetric> {
        val out = mutableListOf(SolarMetric(key = "LOCAL_MODEL", name = "Inverter (read on the local network)", value = label, unit = ""))
        for (m in metrics) {
            evaluate(m, registers)?.let { out += it }
        }
        return out
    }

    private fun evaluate(m: MetricSpec, registers: Map<Int, Int>): SolarMetric? {
        if (m.text != null) {
            val raw = m.value?.let { raw(it, registers) } ?: return null
            val label = m.text[raw.toInt()] ?: m.textOther ?: return null
            return SolarMetric(key = m.key, name = m.name, value = label, unit = "")
        }

        var number = if (m.value != null) {
            scaled(m.value, registers) ?: return null
        } else {
            m.sum.fold(0.0) { total, part ->
                val v = scaled(part, registers) ?: return null
                if (part.subtract) total - v else total + v
            }
        }
        number *= m.scale
        m.negateWhen?.let { n ->
            val flag = registers[key(n.function, n.register)] ?: return null
            if (flag and 0xFFFF in n.values) number = -number
        }
        m.floor?.let { number = number.coerceAtLeast(it) }
        if (number == 0.0) number = 0.0 // no "-0"

        // Always formatted with dots: an Urdu phone would otherwise write Eastern Arabic digits.
        val text = "%.${m.decimals}f".format(Locale.US, number)
        return SolarMetric(key = m.key, name = m.name, value = text, unit = m.unit)
    }

    private fun scaled(v: RegisterValue, registers: Map<Int, Int>): Double? =
        raw(v, registers)?.let { (it - v.offset) * v.scale }

    /** The words combined low first, then the bit or the two's-complement sign applied. */
    private fun raw(v: RegisterValue, registers: Map<Int, Int>): Double? {
        var combined = 0L
        v.registers.forEachIndexed { i, address ->
            val word = registers[key(v.function, address)] ?: return null
            combined = combined or ((word.toLong() and 0xFFFF) shl (16 * i))
        }
        v.bit?.let { return ((combined ushr it) and 1L).toDouble() }
        val bits = 16 * v.registers.size
        if (v.signed && combined >= 1L shl (bits - 1)) combined -= 1L shl bits
        return if (v.abs) abs(combined).toDouble() else combined.toDouble()
    }

    companion object {
        /** Keys GridAnalyzer needs to tell grid on from grid off and to estimate runtime. */
        val GRID_KEYS = setOf("GRID_RELAY_ST1", "G_V_L1", "PG_F1")
        val ESSENTIAL_KEYS = setOf("PG_Pt1", "B_left_cap1", "B_P1")

        fun key(function: Int, address: Int) = (function shl 16) or address
    }
}
