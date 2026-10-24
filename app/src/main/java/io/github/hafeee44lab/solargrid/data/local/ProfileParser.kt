package io.github.hafeee44lab.solargrid.data.local

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** A bundled inverter profile is broken. The message names the file and the field. */
class ProfileException(message: String) : IllegalArgumentException(message)

/**
 * Reads one assets/inverters/<id>.json profile. Strict on purpose: an unknown field is almost
 * always a typo ("scael"), and a typo in a register map means a wrong alarm, so it fails the
 * build's tests instead of being ignored. Addresses may be numbers or "0x…" strings.
 *
 * {
 *   "id": "deye-3p-lv", "label": "Deye three-phase LV hybrid",
 *   "models": [{ "brand": "Deye", "name": "SUN-12K-SG04LP3" }],
 *   "source": "…", "verified": "device" | "documentation",
 *   "function": 3,                  default Modbus function: 3 holding, 4 input
 *   "maxBlock": 125,                longest request when blocks are worked out
 *   "detect": [5, 1280],            Deye device-type codes (register 0) that pick this profile
 *   "blocks": [{ "start": "0x0202", "count": 39, "optional": false, "function": 3 }],
 *   "metrics": [{
 *     "key": "PG_Pt1", "name": "Total Grid Power", "unit": "W",
 *     "reg": ["0x0271", "0x02B2"], "signed": true, "abs": false, "bit": 2, "offset": 1000, "scale": 0.1,
 *     "sum": [{ "reg": [...], "signed": true, "scale": 1, "subtract": true }],
 *     "text": { "0": "Open", "other": "Closed" },   "other" covers every number not listed
 *     "negateWhen": { "reg": "0x816F", "is": [0] },
 *     "floor": 0, "optional": false, "function": 4
 *   }]
 * }
 * Without "blocks", the requests are worked out from the metrics: registers less than
 * [MAX_GAP] apart share a request, up to maxBlock long. Optional metrics get requests of their
 * own that may fail without failing the poll.
 */
object ProfileParser {

    const val MAX_GAP = 25
    private const val OTHER = "other"
    const val MAX_REQUEST = 125

    private val PROFILE_FIELDS = setOf("id", "label", "models", "source", "verified", "function", "maxBlock", "detect", "blocks", "metrics", "notes")
    private val MODEL_FIELDS = setOf("brand", "name")
    private val BLOCK_FIELDS = setOf("start", "count", "optional", "function")
    private val METRIC_FIELDS = setOf("key", "name", "unit", "reg", "signed", "abs", "bit", "offset", "scale", "sum", "text", "negateWhen", "floor", "optional", "function")
    private val PART_FIELDS = setOf("reg", "signed", "abs", "offset", "scale", "subtract", "function")
    private val NEGATE_FIELDS = setOf("reg", "is", "function")
    private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    fun parse(file: String, text: String): InverterProfile {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: JsonParseException) {
            fail(file, "is not valid JSON: ${e.message}")
        }
        if (!root.isJsonObject) fail(file, "must be a JSON object")
        return Reader(file).profile(root.asJsonObject)
    }

    private fun fail(file: String, message: String): Nothing = throw ProfileException("$file $message")

    private class Reader(val file: String) {
        fun fail(message: String): Nothing = fail(file, message)

        fun profile(o: JsonObject): InverterProfile {
            only(o, PROFILE_FIELDS, "profile")
            val id = string(o, "id")
            if (!ID.matches(id)) fail("id \"$id\" must be lower-case words joined by dashes")
            if (file.endsWith(".json") && file.substringAfterLast('/') != "$id.json") fail("must be named $id.json")
            val function = function(o, "function", ModbusRtu.READ_HOLDING)
            val maxBlock = int(o, "maxBlock", MAX_REQUEST)
            if (maxBlock !in 1..MAX_REQUEST) fail("maxBlock must be 1–$MAX_REQUEST")

            val models = array(o, "models").mapIndexed { i, e ->
                val m = obj(e, "models[$i]")
                only(m, MODEL_FIELDS, "models[$i]")
                InverterModel(string(m, "brand"), string(m, "name"))
            }
            if (models.isEmpty()) fail("lists no models")

            val verified = when (val v = string(o, "verified")) {
                "device" -> InverterProfile.Verification.DEVICE
                "documentation" -> InverterProfile.Verification.DOCUMENTATION
                else -> fail("verified must be \"device\" or \"documentation\", not \"$v\"")
            }

            val metrics = array(o, "metrics").mapIndexed { i, e -> metric(obj(e, "metrics[$i]"), "metrics[$i]", function) }
            if (metrics.isEmpty()) fail("has no metrics")
            metrics.groupBy { it.key }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { fail("defines $it twice") }

            val required = metrics.filterNot { it.optional }.map { it.key }.toSet()
            if (InverterProfile.GRID_KEYS.none { it in required }) fail("needs a grid signal: one of ${InverterProfile.GRID_KEYS}")
            (InverterProfile.ESSENTIAL_KEYS - required).firstOrNull()?.let { fail("needs $it, and not as optional") }

            val blocks = if (o.has("blocks")) {
                array(o, "blocks").mapIndexed { i, e -> block(obj(e, "blocks[$i]"), "blocks[$i]", function) }
                    .also { checkCovered(it, metrics) }
            } else {
                derive(metrics, maxBlock)
            }
            if (blocks.isEmpty()) fail("has no blocks")

            val detect = if (o.has("detect")) array(o, "detect").map { code(it, "detect") }.toSet() else emptySet()
            return InverterProfile(id, string(o, "label"), models, string(o, "source"), verified, detect, blocks, metrics)
        }

        fun block(o: JsonObject, where: String, defaultFunction: Int): RegisterBlock {
            only(o, BLOCK_FIELDS, where)
            val start = address(o.get("start") ?: fail("$where needs start"), "$where.start")
            val count = int(o, "count", -1)
            if (count !in 1..MAX_REQUEST) fail("$where.count must be 1–$MAX_REQUEST")
            if (start + count - 1 > 0xFFFF) fail("$where runs past register 0xFFFF")
            return RegisterBlock(start, count, required = !bool(o, "optional"), function = function(o, "function", defaultFunction))
        }

        fun metric(o: JsonObject, where: String, defaultFunction: Int): MetricSpec {
            only(o, METRIC_FIELDS, where)
            val key = string(o, "key")
            val at = "$where ($key)"
            val function = function(o, "function", defaultFunction)
            val hasReg = o.has("reg")
            val hasSum = o.has("sum")
            if (hasReg == hasSum) fail("$at needs exactly one of reg or sum")

            val value = if (hasReg) value(o, at, function, part = false) else null
            val sum = if (hasSum) {
                array(o, "sum").mapIndexed { i, e ->
                    val p = obj(e, "$at.sum[$i]")
                    only(p, PART_FIELDS, "$at.sum[$i]")
                    value(p, "$at.sum[$i]", function(p, "function", function), part = true)
                }.also { if (it.isEmpty()) fail("$at has an empty sum") }
            } else {
                emptyList()
            }
            if (hasSum) listOf("signed", "abs", "bit", "offset").firstOrNull { o.has(it) }?.let { fail("$at: put $it on the sum's parts") }

            val text = if (o.has("text")) {
                if (!hasReg) fail("$at: text needs reg")
                if (o.has("scale") || o.has("offset") || o.has("floor")) fail("$at: text can't be scaled")
                val t = obj(o.get("text"), "$at.text")
                if (t.size() == 0) fail("$at.text is empty")
                t.entrySet().filter { it.key != OTHER }.associate { (k, v) ->
                    val code = k.toIntOrNull() ?: fail("$at.text key \"$k\" is not a number")
                    code to word(v, "$at.text \"$k\"")
                }.also { if (it.isEmpty()) fail("$at.text lists no numbers") }
            } else {
                null
            }

            val textOther = o.get("text")?.takeIf { it.isJsonObject }?.asJsonObject?.get(OTHER)?.let { word(it, "$at.text \"$OTHER\"") }

            val negate = if (o.has("negateWhen")) {
                val n = obj(o.get("negateWhen"), "$at.negateWhen")
                only(n, NEGATE_FIELDS, "$at.negateWhen")
                val values = array(n, "is").map { code(it, "$at.negateWhen.is") }.toSet()
                if (values.isEmpty()) fail("$at.negateWhen.is is empty")
                NegateWhen(address(n.get("reg") ?: fail("$at.negateWhen needs reg"), "$at.negateWhen.reg"), function(n, "function", function), values)
            } else {
                null
            }

            return MetricSpec(
                key = key,
                name = string(o, "name"),
                unit = if (o.has("unit")) string(o, "unit") else "",
                value = value?.copy(scale = 1.0),
                sum = sum,
                scale = if (hasReg) value!!.scale else number(o, "scale", 1.0),
                text = text,
                textOther = textOther,
                negateWhen = negate,
                floor = if (o.has("floor")) number(o, "floor", 0.0) else null,
                optional = bool(o, "optional")
            )
        }

        fun value(o: JsonObject, at: String, function: Int, part: Boolean): RegisterValue {
            val regs = o.get("reg") ?: fail("$at needs reg")
            if (!regs.isJsonArray) fail("$at.reg must be a list")
            val addresses = regs.asJsonArray.map { address(it, "$at.reg") }
            if (addresses.size !in 1..2) fail("$at.reg must hold one or two registers")
            if (addresses.toSet().size != addresses.size) fail("$at.reg repeats a register")
            val bit = if (o.has("bit")) int(o, "bit", -1).also { if (it !in 0 until 16 * addresses.size) fail("$at.bit is out of range") } else null
            val signed = bool(o, "signed")
            if (bit != null && signed) fail("$at can't be both a bit and signed")
            return RegisterValue(
                registers = addresses,
                function = function,
                signed = signed,
                bit = bit,
                offset = number(o, "offset", 0.0),
                scale = number(o, "scale", 1.0),
                subtract = part && bool(o, "subtract"),
                abs = bool(o, "abs")
            )
        }

        fun checkCovered(blocks: List<RegisterBlock>, metrics: List<MetricSpec>) {
            for (b in blocks) {
                if (blocks.any { it !== b && it.function == b.function && it.start <= b.end && b.start <= it.end }) fail("has overlapping blocks at ${hex(b.start)}")
            }
            for (m in metrics) {
                for ((function, address) in m.registers) {
                    val block = blocks.firstOrNull { it.function == function && address in it.start..it.end }
                        ?: fail("${m.key} reads ${hex(address)} (function $function), which no block requests")
                    if (!block.required && !m.optional) fail("${m.key} needs ${hex(address)}, but its block is optional")
                }
            }
        }

        fun derive(metrics: List<MetricSpec>, maxBlock: Int): List<RegisterBlock> {
            val required = metrics.filterNot { it.optional }.flatMap { it.registers }.toSet()
            val optional = metrics.filter { it.optional }.flatMap { it.registers }.toSet() - required
            return group(required, maxBlock, required = true) + group(optional, maxBlock, required = false)
        }

        fun group(registers: Set<Pair<Int, Int>>, maxBlock: Int, required: Boolean): List<RegisterBlock> {
            val out = mutableListOf<RegisterBlock>()
            for ((function, list) in registers.groupBy({ it.first }, { it.second }).toSortedMap()) {
                var start = -1
                var end = -1
                for (address in list.sorted()) {
                    if (start >= 0 && address - end <= MAX_GAP && address - start < maxBlock) {
                        end = address
                    } else {
                        if (start >= 0) out += RegisterBlock(start, end - start + 1, required, function)
                        start = address
                        end = address
                    }
                }
                if (start >= 0) out += RegisterBlock(start, end - start + 1, required, function)
            }
            return out
        }

        fun word(v: JsonElement, where: String): String {
            if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString || v.asString.isBlank()) fail("$where must be a word")
            return v.asString
        }

        fun only(o: JsonObject, allowed: Set<String>, where: String) {
            (o.keySet() - allowed).firstOrNull()?.let { fail("$where has an unknown field \"$it\"") }
        }

        fun obj(e: JsonElement, where: String): JsonObject = if (e.isJsonObject) e.asJsonObject else fail("$where must be an object")

        fun array(o: JsonObject, name: String): JsonArray {
            val e = o.get(name) ?: fail("needs $name")
            return if (e.isJsonArray) e.asJsonArray else fail("$name must be a list")
        }

        fun string(o: JsonObject, name: String): String {
            val e = o.get(name) ?: fail("needs $name")
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString || e.asString.isBlank()) fail("$name must be non-empty text")
            return e.asString
        }

        fun bool(o: JsonObject, name: String): Boolean {
            val e = o.get(name) ?: return false
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isBoolean) fail("$name must be true or false")
            return e.asBoolean
        }

        fun number(o: JsonObject, name: String, default: Double): Double {
            val e = o.get(name) ?: return default
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) fail("$name must be a number")
            val d = e.asDouble
            if (!d.isFinite() || (name == "scale" && d == 0.0)) fail("$name must be a finite, non-zero number")
            return d
        }

        fun int(o: JsonObject, name: String, default: Int): Int {
            val e = o.get(name) ?: return default
            val p = e as? JsonPrimitive
            if (p == null || !p.isNumber || p.asDouble != p.asDouble.toInt().toDouble()) fail("$name must be a whole number")
            return p.asInt
        }

        fun function(o: JsonObject, name: String, default: Int): Int {
            val f = int(o, name, default)
            if (f != ModbusRtu.READ_HOLDING && f != ModbusRtu.READ_INPUT) fail("$name must be 3 (holding) or 4 (input), not $f")
            return f
        }

        fun address(e: JsonElement, where: String): Int {
            val a = code(e, where)
            if (a !in 0..0xFFFF) fail("$where: $a is not a register address")
            return a
        }

        /** A whole number written as 1280 or "0x0500". */
        fun code(e: JsonElement, where: String): Int {
            val p = e as? JsonPrimitive ?: fail("$where must hold numbers")
            return when {
                p.isNumber && p.asDouble == p.asDouble.toInt().toDouble() -> p.asInt
                p.isString && p.asString.startsWith("0x") -> p.asString.drop(2).toIntOrNull(16) ?: fail("$where: \"${p.asString}\" is not hex")
                else -> fail("$where: $p is not a whole number")
            }
        }

        fun hex(a: Int) = "0x%04X".format(a)
    }
}
