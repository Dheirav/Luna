package com.dheirav.cycletracker.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Runs spec/cycle_fixtures.json against the engine.
 *
 * The fixture is the acceptance spec, hand-authored from docs/CYCLE_RULES.md rather than
 * generated from the old Python — you cannot derive golden values from a defective reference.
 * If a case here fails, the engine disagrees with the spec; fix one or the other deliberately.
 */
class FixtureTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val fixture: JsonObject = json.parseToJsonElement(
        requireNotNull(javaClass.classLoader.getResourceAsStream("cycle_fixtures.json")) {
            "cycle_fixtures.json not on the test classpath — check the resources srcDir in core/build.gradle.kts"
        }.bufferedReader().readText()
    ).jsonObject

    private val config = CycleConfig.Default
    private val engine = CycleEngine(config)

    private fun group(name: String): List<JsonObject> =
        fixture[name]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.date(key: String) = LocalDate.parse(this[key]!!.jsonPrimitive.content)
    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int
    private fun JsonObject.intOrNull(key: String) =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
    private fun JsonObject.doubleOrNull(key: String) =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.dates(key: String) =
        this[key]!!.jsonArray.map { LocalDate.parse(it.jsonPrimitive.content) }

    // -- the config in code must match the config in the fixture ---------

    @Test
    fun `config matches the fixture`() {
        val c = fixture["config"]!!.jsonObject
        assertEquals(config.maxIntraPeriodGapDays, c.int("maxIntraPeriodGapDays"))
        assertEquals(config.minDaysBetweenCycleStarts, c.int("minDaysBetweenCycleStarts"))
        assertEquals(config.defaultLutealLength, c.int("defaultLutealLength"))
        assertEquals(config.defaultCycleLength, c.int("defaultCycleLength"))
        assertEquals(config.defaultPeriodLength, c.int("defaultPeriodLength"))
        assertEquals(config.cycleLengthSampleSize, c.int("cycleLengthSampleSize"))
        assertEquals(config.confidenceFloorRegularity, c.doubleOrNull("confidenceFloorRegularity")!!, 1e-9)
        assertEquals(config.variabilityScaleDays, c.doubleOrNull("variabilityScaleDays")!!, 1e-9)
        assertEquals(config.boundarySoftnessDays, c.doubleOrNull("boundarySoftnessDays")!!, 1e-9)
        val range = c["plausibleCycleRange"]!!.jsonArray
        assertEquals(config.plausibleCycleRange.first, range[0].jsonPrimitive.int)
        assertEquals(config.plausibleCycleRange.last, range[1].jsonPrimitive.int)
    }

    // -- §2 --------------------------------------------------------------

    @Test
    fun `period grouping`() {
        for (case in group("periodGrouping")) {
            val id = case.str("id")
            val projection = CycleProjector.project(case["given"]!!.jsonObject.dates("bleedingDays"), config)
            val expect = case["expect"]!!.jsonObject

            assertPeriods(id, expect["periods"]!!.jsonArray, projection.periods)
            assertSpotting(id, expect["spotting"]!!.jsonArray, projection.spotting)
            expect["cycles"]?.let { assertCycles(id, it.jsonArray, projection.cycles) }
        }
    }

    // -- §3 --------------------------------------------------------------

    @Test
    fun `cycle derivation and length statistics`() {
        for (case in group("cycleDerivation")) {
            val id = case.str("id")
            val given = case["given"]!!.jsonObject
            val expect = case["expect"]!!.jsonObject

            if (given.containsKey("periodStarts")) {
                val span = given.int("periodSpanDays")
                val bleeding = given.dates("periodStarts").flatMap { s -> (0 until span).map { s.plusDays(it.toLong()) } }
                assertCycles(id, expect["cycles"]!!.jsonArray, CycleProjector.project(bleeding, config).cycles)
                continue
            }

            val lengths = given["cycleLengths"]!!.jsonArray.map { it.jsonPrimitive.int }
            val sources = given["cycleSources"]?.jsonArray?.map {
                if (it.jsonPrimitive.content == "observed") Source.OBSERVED else Source.ASSUMED
            }
            val cycles = syntheticCycles(lengths, sources, openTail = given["openCycle"]?.jsonPrimitive?.boolean == true)

            assertEquals(
                "$id expectedCycleLength",
                expect.int("expectedCycleLength"),
                CycleStats.expectedCycleLength(cycles, given.intOrNull("userTypicalCycleLength"), config),
            )

            val variability = CycleStats.cycleLengthVariability(cycles, config)
            val wantVariability = expect.doubleOrNull("cycleLengthVariability")
            if (wantVariability == null) assertNull("$id variability", variability)
            else assertEquals("$id variability", wantVariability, variability!!, 0.001)

            expect.intOrNull("observedCompletedCount")?.let {
                assertEquals("$id observedCompletedCount", it,
                    cycles.count { c -> c.source == Source.OBSERVED && c.length != null })
            }
            expect.doubleOrNull("regularity")?.let {
                assertEquals("$id regularity", it, CycleStats.regularity(variability, config), 0.001)
            }
            expect.doubleOrNull("medianRaw")?.let {
                assertEquals("$id medianRaw", it, CycleStats.medianCycleLength(cycles, config)!!, 0.001)
            }
        }
    }

    // -- §4 --------------------------------------------------------------

    @Test
    fun `cycle day does not wrap`() {
        for (case in group("cycleDay")) {
            val id = case.str("id")
            val given = case["given"]!!.jsonObject
            val expect = case["expect"]!!.jsonObject

            val start = given.date("cycleStart")
            val target = given.date("target")
            val expected = given.int("expectedCycleLength")

            val projection = CycleProjector.fromPeriods(
                listOf(Period(start, start, spanDays = 1, bleedingDayCount = 1)),
            )
            val state = engine.stateFor(target, projection, userTypicalCycleLength = expected)

            assertEquals("$id cycleDay", expect.int("cycleDay"), state.cycleDay)
            assertEquals("$id daysLate", expect.int("daysLate"), state.daysLate)
        }
    }

    // -- §5 --------------------------------------------------------------

    @Test
    fun `phase anchoring`() {
        for (case in group("phaseAnchoring")) {
            val id = case.str("id")
            val given = case["given"]!!.jsonObject
            val expect = case["expect"]!!.jsonObject

            val boundaries = PhaseAnchor.boundaries(
                given.int("expectedCycleLength"),
                given.int("periodLength"),
                given.intOrNull("lutealLength") ?: config.defaultLutealLength,
            )

            expect.intOrNull("ovulationDay")?.let {
                assertEquals("$id ovulationDay", it, boundaries.ovulationDay)
            }

            expect["boundaries"]?.jsonObject?.forEach { (phase, range) ->
                val want = range.jsonArray
                val got = boundaries.ranges.getValue(Phase.valueOf(phase))
                assertEquals("$id $phase start", want[0].jsonPrimitive.int, got.start)
                if (want[1] is JsonNull) assertNull("$id $phase end", got.endInclusive)
                else assertEquals("$id $phase end", want[1].jsonPrimitive.int, got.endInclusive)
            }

            expect["samples"]?.jsonArray?.forEach { sample ->
                val s = sample.jsonObject
                val day = s.int("cycleDay")
                assertEquals("$id day $day", Phase.valueOf(s.str("phase")), boundaries.phaseFor(day))
            }

            // §5.2 — a logged bleed overrides whatever the ranges compute.
            given.intOrNull("cycleDay")?.let { day ->
                val bleeding = given["isBleedingOnTargetDate"]?.jsonPrimitive?.boolean == true
                val phase = if (bleeding) Phase.MENSTRUATION else boundaries.phaseFor(day)
                assertEquals("$id phase", Phase.valueOf(expect.str("phase")), phase)
                expect["computedPhaseWouldBe"]?.let {
                    assertEquals("$id computed", Phase.valueOf(it.jsonPrimitive.content), boundaries.phaseFor(day))
                }
                expect.intOrNull("daysLate")?.let {
                    assertEquals("$id daysLate", it, maxOf(0, day - given.int("expectedCycleLength")))
                }
            }
        }
    }

    @Test
    fun `phase confidence`() {
        for (case in group("phaseConfidence")) {
            val id = case.str("id")
            val given = case["given"]!!.jsonObject
            val expect = case["expect"]!!.jsonObject

            if (given["isBleedingOnTargetDate"]?.jsonPrimitive?.boolean == true) {
                assertEquals("$id observed bleed", 1.0, expect.doubleOrNull("phaseConfidence")!!, 1e-9)
                continue
            }

            val rangeArr = given["phaseRange"]!!.jsonArray
            val range = PhaseRange(
                rangeArr[0].jsonPrimitive.int,
                if (rangeArr[1] is JsonNull) null else rangeArr[1].jsonPrimitive.int,
            )
            val variability = given.doubleOrNull("cycleLengthVariability")

            assertEquals(
                "$id regularity", expect.doubleOrNull("regularity")!!,
                CycleStats.regularity(variability, config), 0.001,
            )
            assertEquals(
                "$id phaseConfidence", expect.doubleOrNull("phaseConfidence")!!,
                PhaseAnchor.confidence(range, given.int("cycleDay"), variability, given.int("daysLate"), config),
                0.002,
            )
        }
    }

    // -- §6 and projection recomputation ---------------------------------

    @Test
    fun `edge cases`() {
        val cases = group("edgeCases").associateBy { it.str("id") }

        // EC-1: no data is not day 14.
        cases.getValue("EC-1").let { case ->
            val expect = case["expect"]!!.jsonObject
            val state = engine.stateFor(LocalDate.of(2026, 8, 10), Projection.Empty)
            assertNull("EC-1 cycleDay", state.cycleDay)
            assertNull("EC-1 phase", state.phase)
            assertEquals("EC-1 confidence", 0.0, state.phaseConfidence, 1e-9)
            assertEquals("EC-1 expectedCycleLength", expect.int("expectedCycleLength"), state.expectedCycleLength)
            assertTrue("EC-1 predictions withheld", !state.hasData)
        }

        // EC-2: one period, no completed cycle — estimate, but report honest confidence.
        cases.getValue("EC-2").let { case ->
            val given = case["given"]!!.jsonObject
            val expect = case["expect"]!!.jsonObject
            val bleeding = given.dates("bleedingDays")
            val projection = CycleProjector.project(bleeding, config)
            val state = engine.stateFor(given.date("target"), projection, bleeding.toSet())

            assertEquals("EC-2 cycleDay", expect.int("cycleDay"), state.cycleDay)
            assertEquals("EC-2 expectedCycleLength", expect.int("expectedCycleLength"), state.expectedCycleLength)
            assertNull("EC-2 variability", state.cycleLengthVariability)
            assertEquals("EC-2 ovulationDay", expect.int("ovulationDay"), state.ovulationDay)
            assertEquals("EC-2 phase", Phase.valueOf(expect.str("phase")), state.phase)
            assertEquals("EC-2 confidence", expect.doubleOrNull("phaseConfidence")!!, state.phaseConfidence, 0.001)
        }

        // EC-3 / EC-4: the projection is recomputed, never mutated. Incremental writes cannot do this.
        cases.getValue("EC-3").let { case ->
            val given = case["given"]!!.jsonObject
            val expect = case["expect"]!!.jsonObject
            val days = given.dates("bleedingDaysBefore") + given.date("thenLogBleeding")
            val projection = CycleProjector.project(days, config)
            assertPeriods("EC-3", expect["periods"]!!.jsonArray, projection.periods)
            assertSpotting("EC-3", expect["spotting"]!!.jsonArray, projection.spotting)
            assertCycles("EC-3", expect["cycles"]!!.jsonArray, projection.cycles)
        }

        cases.getValue("EC-4").let { case ->
            val given = case["given"]!!.jsonObject
            val expect = case["expect"]!!.jsonObject
            val deleted = given.dates("thenDelete").toSet()
            val projection = CycleProjector.project(given.dates("bleedingDaysBefore") - deleted, config)
            assertPeriods("EC-4", expect["periods"]!!.jsonArray, projection.periods)
            assertSpotting("EC-4", expect["spotting"]!!.jsonArray, projection.spotting)
        }
    }

    // -- helpers ----------------------------------------------------------

    private fun assertPeriods(id: String, want: JsonArray, got: List<Period>) {
        assertEquals("$id period count", want.size, got.size)
        want.forEachIndexed { i, element ->
            val w = element.jsonObject
            assertEquals("$id period $i start", w.date("start"), got[i].start)
            assertEquals("$id period $i end", w.date("end"), got[i].end)
            assertEquals("$id period $i spanDays", w.int("spanDays"), got[i].spanDays)
            assertEquals("$id period $i bleedingDayCount", w.int("bleedingDayCount"), got[i].bleedingDayCount)
        }
    }

    private fun assertSpotting(id: String, want: JsonArray, got: List<SpottingEvent>) {
        assertEquals("$id spotting count", want.size, got.size)
        want.forEachIndexed { i, element ->
            val w = element.jsonObject
            assertEquals("$id spotting $i start", w.date("start"), got[i].start)
            assertEquals("$id spotting $i end", w.date("end"), got[i].end)
            assertEquals("$id spotting $i spanDays", w.int("spanDays"), got[i].spanDays)
        }
    }

    private fun assertCycles(id: String, want: JsonArray, got: List<Cycle>) {
        assertEquals("$id cycle count", want.size, got.size)
        want.forEachIndexed { i, element ->
            val w = element.jsonObject
            assertEquals("$id cycle $i start", w.date("start"), got[i].start)
            if (w["end"] is JsonNull) assertNull("$id cycle $i end", got[i].end)
            else assertEquals("$id cycle $i end", w.date("end"), got[i].end)
            if (w["length"] is JsonNull) assertNull("$id cycle $i length", got[i].length)
            else assertEquals("$id cycle $i length", w.int("length"), got[i].length)
        }
    }

    /** Builds cycles with the given lengths back-to-back, for statistics cases that supply lengths directly. */
    private fun syntheticCycles(lengths: List<Int>, sources: List<Source>?, openTail: Boolean): List<Cycle> {
        var cursor = LocalDate.of(2025, 1, 1)
        val cycles = lengths.mapIndexed { i, length ->
            val start = cursor
            cursor = cursor.plusDays(length.toLong())
            Cycle(start, start.plusDays((length - 1).toLong()), length, sources?.get(i) ?: Source.OBSERVED)
        }.toMutableList()
        if (openTail) cycles += Cycle(cursor, null, null, Source.OBSERVED)
        return cycles
    }
}
