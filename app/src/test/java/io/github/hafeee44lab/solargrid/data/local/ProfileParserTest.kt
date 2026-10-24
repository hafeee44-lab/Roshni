package io.github.hafeee44lab.solargrid.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The loader against broken profiles: each mistake must be refused with a message that names it. */
class ProfileParserTest {

    private val essentials = """
        {"key": "G_V_L1", "name": "Grid Voltage L1", "unit": "V", "reg": ["0x0010"], "scale": 0.1},
        {"key": "PG_Pt1", "name": "Total Grid Power", "unit": "W", "reg": ["0x0011"], "signed": true},
        {"key": "B_P1", "name": "Battery Power", "unit": "W", "reg": ["0x0012"], "signed": true},
        {"key": "B_left_cap1", "name": "SoC", "unit": "%", "reg": ["0x0013"]}
    """

    private fun profile(metrics: String = essentials, extra: String = "", id: String = "test-brand") = """
        {"id": "$id", "label": "Test", "models": [{"brand": "Test", "name": "T1"}],
         "source": "test", "verified": "documentation"$extra,
         "metrics": [$metrics]}
    """

    private fun parse(text: String, file: String = "test-brand.json") = ProfileParser.parse(file, text)

    private fun refused(text: String, expected: String, file: String = "test-brand.json") {
        try {
            parse(text, file)
            fail("accepted a profile that should fail with \"$expected\"")
        } catch (e: ProfileException) {
            assertTrue("\"${e.message}\" should mention \"$expected\"", e.message!!.contains(expected))
            assertTrue("names the file", e.message!!.startsWith(file))
        }
    }

    @Test
    fun aMinimalProfileLoads() {
        val p = parse(profile())
        assertEquals("test-brand", p.id)
        assertEquals(listOf(RegisterBlock(0x0010, 4)), p.blocks)
    }

    @Test
    fun brokenJson() = refused("{\"id\": ", "not valid JSON")

    @Test
    fun notAnObject() = refused("[1, 2]", "must be a JSON object")

    @Test
    fun missingFields() {
        refused(profile().replace("\"label\": \"Test\",", ""), "needs label")
        refused(profile().replace("\"models\": [{\"brand\": \"Test\", \"name\": \"T1\"}],", "\"models\": [],"), "lists no models")
        refused(profile(metrics = ""), "has no metrics")
    }

    @Test
    fun idsAndFileNames() {
        refused(profile(id = "Test Brand"), "lower-case words")
        refused(profile(), "must be named test-brand.json", file = "other.json")
    }

    @Test
    fun unknownFieldsAreTypos() {
        refused(profile(extra = ", \"maxBlok\": 50"), "unknown field \"maxBlok\"")
        refused(profile(metrics = essentials + ", {\"key\": \"AC_T\", \"name\": \"t\", \"reg\": [1], \"scael\": 0.1}"), "unknown field \"scael\"")
    }

    @Test
    fun verifiedMustBeKnown() = refused(profile().replace("\"documentation\"", "\"probably\""), "verified must be")

    @Test
    fun writeFunctionsAreRefused() {
        refused(profile(extra = ", \"function\": 6"), "3 (holding) or 4 (input)")
        refused(profile(metrics = essentials + ", {\"key\": \"AC_T\", \"name\": \"t\", \"reg\": [1], \"function\": 16}"), "3 (holding) or 4 (input)")
    }

    @Test
    fun registers() {
        val bad = { reg: String -> profile(metrics = essentials + ", {\"key\": \"AC_T\", \"name\": \"t\", \"reg\": $reg}") }
        refused(bad("[\"0x10000\"]"), "not a register address")
        refused(bad("[\"0xZZ\"]"), "is not hex")
        refused(bad("[1, 2, 3]"), "one or two registers")
        refused(bad("[5, 5]"), "repeats a register")
        refused(bad("[]"), "one or two registers")
        refused(bad("\"0x0001\""), "must be a list")
        refused(bad("[1.5]"), "not a whole number")
    }

    @Test
    fun valueRules() {
        val m = { body: String -> profile(metrics = essentials + ", {\"key\": \"AC_T\", \"name\": \"t\", $body}") }
        refused(m("\"reg\": [1], \"scale\": 0"), "non-zero")
        refused(m("\"reg\": [1], \"bit\": 16"), "bit is out of range")
        refused(m("\"reg\": [1], \"bit\": 1, \"signed\": true"), "both a bit and signed")
        refused(m("\"reg\": [1], \"sum\": [{\"reg\": [2]}]"), "exactly one of reg or sum")
        refused(m("\"scale\": 2"), "exactly one of reg or sum")
        refused(m("\"sum\": []"), "empty sum")
        refused(m("\"sum\": [{\"reg\": [2]}], \"signed\": true"), "put signed on the sum's parts")
        refused(m("\"sum\": [{\"reg\": [2]}], \"text\": {\"1\": \"x\"}"), "text needs reg")
        refused(m("\"reg\": [1], \"text\": {\"one\": \"x\"}"), "is not a number")
        refused(m("\"reg\": [1], \"text\": {\"1\": \"\"}"), "must be a word")
        refused(m("\"reg\": [1], \"text\": {}"), "is empty")
        refused(m("\"reg\": [1], \"text\": {\"other\": \"x\"}"), "lists no numbers")
        refused(m("\"reg\": [1], \"text\": {\"1\": \"x\"}, \"scale\": 2"), "can't be scaled")
        refused(m("\"reg\": [1], \"negateWhen\": {\"reg\": 2, \"is\": []}"), "is empty")
        refused(m("\"reg\": [1], \"signed\": \"yes\""), "true or false")
    }

    @Test
    fun theAlarmsNeedsAreEnforced() {
        refused(profile(metrics = essentials.replace("\"B_P1\"", "\"B_P2\"")), "needs B_P1")
        refused(profile(metrics = essentials.replace("\"G_V_L1\"", "\"X_V\"")), "needs a grid signal")
        refused(profile(metrics = essentials.replace("\"reg\": [\"0x0013\"]}", "\"reg\": [\"0x0013\"], \"optional\": true}")), "needs B_left_cap1, and not as optional")
        refused(profile(metrics = "$essentials, {\"key\": \"PG_Pt1\", \"name\": \"again\", \"reg\": [9]}"), "defines PG_Pt1 twice")
    }

    @Test
    fun explicitBlocksMustCoverEveryRegister() {
        refused(profile(extra = ", \"blocks\": [{\"start\": 16, \"count\": 3}]"), "PG_Pt1".let { "B_left_cap1 reads 0x0013" })
        refused(profile(extra = ", \"blocks\": [{\"start\": 16, \"count\": 4, \"function\": 4}]"), "which no block requests")
        refused(profile(extra = ", \"blocks\": [{\"start\": 16, \"count\": 4, \"optional\": true}]"), "but its block is optional")
        refused(profile(extra = ", \"blocks\": [{\"start\": 16, \"count\": 4}, {\"start\": 18, \"count\": 2}]"), "overlapping blocks")
        refused(profile(extra = ", \"blocks\": [{\"start\": 16, \"count\": 126}]"), "count must be 1–125")
        refused(profile(extra = ", \"blocks\": [{\"start\": \"0xFFFF\", \"count\": 2}]"), "runs past register 0xFFFF")
        refused(profile(extra = ", \"maxBlock\": 0"), "maxBlock must be")
    }

    @Test
    fun blocksAreWorkedOutFromTheMetrics() {
        val metrics = essentials + """,
            {"key": "AC_T", "name": "t", "reg": ["0x002A"]},
            {"key": "BRC", "name": "c", "reg": ["0x0100"], "optional": true},
            {"key": "B_V1", "name": "v", "reg": [7], "function": 4},
            {"key": "S_P_T", "name": "s", "sum": [{"reg": ["0x0060", "0x0061"]}, {"reg": [8], "function": 4}]}
        """
        val p = parse(profile(metrics = metrics))
        assertEquals(
            listOf(
                RegisterBlock(0x0010, 0x2A - 0x10 + 1), // 0x13 → 0x2A is 23 apart: one request
                RegisterBlock(0x0060, 2), // 0x2A → 0x60 is too far
                RegisterBlock(7, 2, function = 4),
                RegisterBlock(0x0100, 1, required = false)
            ),
            p.blocks
        )
        val short = parse(profile(metrics = metrics, extra = ", \"maxBlock\": 10"))
        assertEquals(listOf(0x0010 to 4, 0x002A to 1), short.blocks.filter { it.function == 3 && it.required && it.start < 0x60 }.map { it.start to it.count })
    }

    @Test
    fun valuesAreDecodedAsWritten() {
        val metrics = essentials + """,
            {"key": "A", "name": "two words low first", "reg": [1, 2], "signed": true},
            {"key": "B", "name": "offset then scale", "reg": [3], "offset": 1000, "scale": 0.1},
            {"key": "C", "name": "sum minus part, floored", "floor": 0, "sum": [{"reg": [4]}, {"reg": [5], "subtract": true}]},
            {"key": "D", "name": "bit table", "reg": [6], "bit": 3, "text": {"1": "Open"}},
            {"key": "E", "name": "negated", "reg": [7], "abs": true, "signed": true, "negateWhen": {"reg": 8, "is": [0]}},
            {"key": "F", "name": "fallback word", "reg": [9], "text": {"0": "Open", "other": "Closed"}}
        """
        val p = parse(profile(metrics = metrics))
        val regs = TestProfiles.holding(mapOf(0x10 to 2300, 0x11 to 0xFFFF, 0x12 to 5, 0x13 to 60, 1 to 0xFFFE, 2 to 0xFFFF, 3 to 1234, 4 to 5, 5 to 9, 6 to 0b1000, 7 to 0xFFF6, 8 to 0, 9 to 7))
        val values = p.read(regs).associate { it.key to it.value }
        assertEquals("Test", values["LOCAL_MODEL"])
        assertEquals("230.00", values["G_V_L1"])
        assertEquals("-1", values["PG_Pt1"])
        assertEquals("-2", values["A"])
        assertEquals("23.40", values["B"])
        assertEquals("0", values["C"])
        assertEquals("Open", values["D"])
        assertEquals("-10", values["E"])
        assertEquals("Closed", values["F"])

        // A table that doesn't know the number leaves the metric out rather than guess.
        assertNull(p.read(regs + TestProfiles.holding(mapOf(6 to 0))).firstOrNull { it.key == "D" })
        // A missing register leaves its metric out instead of reading zero.
        assertNull(p.read(regs - InverterProfile.key(3, 4)).firstOrNull { it.key == "C" })
    }

    @Test
    fun theCatalogRefusesClashes() {
        val a = profile(id = "brand-a", extra = ", \"detect\": [5]")
        val b = profile(id = "brand-b", extra = ", \"detect\": [5]")
        try {
            ProfileCatalog.load(mapOf("brand-a.json" to a, "brand-b.json" to b))
            fail("two profiles claimed one device type")
        } catch (e: ProfileException) {
            assertTrue(e.message!!.contains("0x0005"))
        }
        try {
            ProfileCatalog(listOf(parse(a, "brand-a.json"), parse(a, "brand-a.json")))
            fail("two profiles share an id")
        } catch (e: ProfileException) {
            assertTrue(e.message!!.contains("brand-a"))
        }
    }

    @Test
    fun everyBundledFileLoads() {
        assertEquals(TestProfiles.files.size, TestProfiles.catalog.profiles.size)
        assertTrue(TestProfiles.files.size >= 16)
    }
}
