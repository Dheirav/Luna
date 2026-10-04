package com.dheirav.cycletracker.core

import java.time.LocalDate

/**
 * The figures the user supplies, gathered into one value so no caller can pass some and forget the
 * rest.
 *
 * Forgetting is not hypothetical. The reminder worker called the engine with neither length while
 * Today passed both, and since the prediction ledger keeps one row per day, the worker's row replaced
 * the one Today had shown. A function that takes this whole value has no way to drop one field.
 */
data class UserCycleSettings(
    val typicalCycleLength: Int? = null,
    val typicalPeriodLength: Int? = null,
    /** [WindowWidth.multiplier]. A coverage preference: it widens the window and changes nothing else. */
    val windowSpread: Double = WindowWidth.BALANCED.multiplier,
)

/**
 * Everything a surface shows about the cycle, computed once from the logs and the settings.
 *
 * Before this, Today, History, the phase guide, the doctor summary, both widgets and the reminder
 * each rebuilt the projection themselves, each choosing its own inputs and its own moment to run.
 * They agreed only as long as every copy was kept in step by hand, and one was not. Building the
 * snapshot here, in the tested module, makes "every surface shows the same answer" a property of
 * the code rather than of everyone's memory.
 *
 * Pure like the rest of the engine (§1.1): the logs stay authoritative, and this is rebuilt whole
 * whenever they or the settings change, never patched.
 */
class CycleSnapshot private constructor(
    val today: LocalDate,
    val projection: Projection,
    val bleedingDays: Set<LocalDate>,
    /** Days answered "no bleeding"; they close a period (§5.1 as amended). */
    val noBleedingDays: Set<LocalDate>,
    val settings: UserCycleSettings,
    /** Where the cycle stands today. */
    val state: CycleState,
    /** The next period as a range, or null with no cycle to project from (§6). */
    val window: PeriodWindow?,
    /** Why the expected length is what it is: the observed/assumed split behind it. */
    val basis: PredictionBasis,
    val flags: List<HealthFlag>,
    /** Never-logged stretches that split a period, oldest first; see [UnloggedGaps]. */
    val unloggedGaps: List<UnloggedGap>,
    private val engine: CycleEngine,
) {

    /**
     * The state on any other date, resolved with the same settings as [state].
     *
     * The phase guide, the mood widget and the doctor summary file past days under phases, and
     * filing them with different inputs from the hero would let the same day sit in two phases.
     */
    fun stateOn(date: LocalDate): CycleState =
        if (date == today) state else engine.stateFor(
            date,
            projection,
            bleedingDays = bleedingDays,
            userTypicalCycleLength = settings.typicalCycleLength,
            userTypicalPeriodLength = settings.typicalPeriodLength,
            noBleedingDays = noBleedingDays,
        )

    companion object {
        fun build(
            bleedingDays: Collection<LocalDate>,
            assumedDays: Set<LocalDate>,
            settings: UserCycleSettings,
            today: LocalDate,
            config: CycleConfig = CycleConfig.Default,
            noBleedingDays: Set<LocalDate> = emptySet(),
            /** Every day with a row. Needed to tell a forgotten day from one answered "no". */
            loggedDays: Set<LocalDate> = bleedingDays.toSet() + noBleedingDays,
        ): CycleSnapshot {
            val engine = CycleEngine(config)
            val bleeding = bleedingDays.toSet()
            val projection = CycleProjector.project(bleeding, config, assumedDays)
            val state = engine.stateFor(
                today,
                projection,
                bleedingDays = bleeding,
                userTypicalCycleLength = settings.typicalCycleLength,
                userTypicalPeriodLength = settings.typicalPeriodLength,
                noBleedingDays = noBleedingDays,
            )

            val basis = Forecast.basis(projection.cycles, settings.typicalCycleLength, config)
            val gaps = UnloggedGaps.find(bleeding, loggedDays, config)

            return CycleSnapshot(
                today = today,
                projection = projection,
                bleedingDays = bleeding,
                noBleedingDays = noBleedingDays,
                settings = settings,
                state = state,
                window = Forecast.periodWindow(
                    cycleStart = state.cycleStart,
                    expectedCycleLength = state.expectedCycleLength,
                    cycles = projection.cycles,
                    config = config,
                    forecastConfig = ForecastConfig(spreadMultiplier = settings.windowSpread),
                ),
                basis = basis,
                flags = HealthFlags.evaluate(
                    projection = projection,
                    today = today,
                    expectedCycleLength = state.expectedCycleLength,
                    cycleConfig = config,
                    lengthSource = basis.source,
                    heldSpotting = gaps.map { it.resumesOn }.toSet(),
                ),
                unloggedGaps = gaps,
                engine = engine,
            )
        }
    }
}
