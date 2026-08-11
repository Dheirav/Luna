# Luna — Cycle Rules — Corrected Specification

**Status:** authoritative. The Kotlin implementation is built against this document and
`spec/cycle_fixtures.json`. The Python tree in `src/` is reference material only and does
**not** implement these rules.

This spec fixes three defects in the current Python implementation. Each is called out
inline as **DEFECT**.

---

## 0. Config constants

| Name | Value | Meaning |
|---|---|---|
| `maxIntraPeriodGapDays` | 1 | Non-bleeding days tolerated inside one period |
| `minDaysBetweenCycleStarts` | 10 | Bleeding sooner than this after a cycle start is spotting, not a new cycle |
| `defaultLutealLength` | 14 | Days from ovulation to next period |
| `defaultCycleLength` | 28 | Fallback when there is no history and no user setting |
| `defaultPeriodLength` | 5 | Fallback when no period has been observed |
| `plausibleCycleRange` | 15–60 | Cycles outside this are excluded from statistics as logging errors |
| `cycleLengthSampleSize` | 6 | Completed cycles used to estimate expected length |

---

## 1. Entities

**`DailyLog`** — one row per date. Source of truth. Holds `isBleeding`, `flow`, symptoms, notes, tags.

**`Period`** — a derived span of bleeding: `start`, `end`, `spanDays`, `bleedingDayCount`, `flowTrajectory`.
`spanDays = end - start + 1`. Note `spanDays` may exceed `bleedingDayCount` when a gap day is bridged.

**`Cycle`** — derived. `start` = a period's start date. `end` = day before the next cycle's start.
`length = end - start + 1`. The most recent cycle is **open**: `end = null`, `length = null`.

**`SpottingEvent`** — a derived bleeding span that was rejected as a cycle start by rule 2.3.
Recorded, not discarded — intermenstrual spotting is a health-flag input (Phase 5).

### 1.1 Periods and cycles are a projection, never mutable state

**DEFECT 1.** `orchestrator.py:348` calls `register_period_start` on *every* bleeding day, and
`cycle_tracker.py:234` unconditionally inserts a cycle record. Logging 1–3 March as a period
produces three cycles, one of them a single day long, which drags mean cycle length to 14 and
corrupts every downstream number. The live database contains exactly this.

The root cause is incremental mutation of derived state. The fix is structural:

> **Periods, cycles and spotting events are derived from `DailyLog` by a pure function.
> They are never inserted, updated or closed incrementally.**

Recompute the whole projection whenever logs change. It is a few thousand rows at most and runs
in milliseconds. Cache the result if profiling ever demands it, but the logs remain authoritative.

This makes retro-logging, corrections, deletions and bulk backfill all work with no special
handling — which is exactly what incremental mutation cannot do.

---

## 2. Deriving periods and cycles

Input: all `DailyLog` rows where `isBleeding = true`, ascending by date.

### 2.1 Group bleeding days into spans

Walk the bleeding days in order. Start a new span at the first day. For each subsequent
bleeding day `d` with previous bleeding day `p`:

```
gap = daysBetween(p, d) - 1
if gap <= maxIntraPeriodGapDays:  extend the current span to d
else:                             close the current span, open a new one at d
```

So consecutive days form one span, and one missing day is bridged. Two or more consecutive
non-bleeding days close the span.

### 2.2 Promote spans to periods

The first span is always a period. For each later span with start `s`, let `c` be the start of
the most recent accepted period:

```
if daysBetween(c, s) >= minDaysBetweenCycleStarts:  s starts a new Period (and a new Cycle)
else:                                                s is a SpottingEvent
```

### 2.3 Why the minimum gap exists

Without it, spotting three days after a period ends creates a spurious 8-day cycle, which
poisons the length statistics the same way DEFECT 1 does. Ten days is deliberately permissive —
well below any plausible real cycle — so it rejects only obvious spotting, never a short cycle.

### 2.4 Derive cycles

For consecutive periods `P[i]`, `P[i+1]`:

```
Cycle[i].start  = P[i].start
Cycle[i].end    = P[i+1].start - 1 day
Cycle[i].length = daysBetween(Cycle[i].start, Cycle[i].end) + 1
```

The final cycle is open: `end = null`, `length = null`. **An open cycle has no length and must
never be counted in length statistics.**

---

## 3. Expected cycle length

```
completed = cycles where length != null
plausible = completed where plausibleCycleRange.contains(length)
sample    = last cycleLengthSampleSize of plausible

expectedCycleLength =
    roundHalfUp(median(sample))       if sample.size >= 3
    user.typicalCycleLength           else if set
    defaultCycleLength                otherwise
```

Median, not mean: one anomalous cycle (illness, a missed period, a logging error that survived
the plausibility filter) shifts a mean badly and a median barely at all.

`cycleLengthSampleSize` is even, so the median of a full sample is the mean of the two middle
values and can land on a half. Round half up to an integer — `28.5 → 29`. Keep the unrounded
value available for display if you ever want to show "your cycles average 28.5 days".

`cycleLengthVariability = populationStdDev(sample)` when `sample.size >= 3`, else `null`.
This drives phase confidence in §5.3.

### 3.2 Observed versus assumed cycles

Backfilled history is rarely all real. A user typically remembers the last one or two period
starts and extrapolates the rest at their typical length. Every period therefore carries a
`source` of `observed` or `assumed`.

```
expectedCycleLength    ← computed from observed AND assumed cycles
cycleLengthVariability ← computed from OBSERVED cycles only
```

Seeding the length estimate is the whole point of backfill, so assumed cycles count there. But a
uniform extrapolated sequence has a standard deviation of exactly zero, which would drive
`regularity` to 1.0 and make the app report maximum confidence from data nobody ever observed —
the precise failure this project exists to avoid. Variability is therefore observed-only, and with
fewer than three observed cycles it stays `null` and `regularity` stays 0.5.

This self-cleans: `cycleLengthSampleSize` is 6, so once six real cycles have been logged the
assumed ones fall out of the window entirely and stop influencing anything.

### 3.1 Expected period length

```
expectedPeriodLength =
    median(spanDays of last 6 completed periods)   if >= 2 observed
    user.typicalPeriodLength                       else if set
    defaultPeriodLength                            otherwise
```

A period is "completed" once ≥ `maxIntraPeriodGapDays + 1` non-bleeding days follow it.

---

## 4. Cycle day

**DEFECT 2.** `calculate_cycle_day` (`src/utils/helper_functions.py:96`) contains three compounding
errors in five lines: it never adds 1 for the start day, the modulo **wraps**, and the
`if cycle_day == 0: cycle_day = cycle_length` fallback maps day 1 onto the final day of the cycle.
Measured against a 1 March cycle start with a 28-day setting:

| Date | Correct | Legacy returns | Effect |
|---|---|---|---|
| 1 Mar — period starts | 1 | **28** | Day one of bleeding reports as late luteal |
| 20 Mar — mid-cycle | 20 | 19 | Off by one, every single day |
| 30 Mar — two days late | 30 | **1** | Tells a late user they are on day 1 of a new period |
| 15 Apr — 18 days late | 46 | 17 | Lateness is invisible; reports mid-follicular |

This is the most user-visible error in the system, and it is silent — every number it produces
looks plausible.

Correct:

```
cycleDay = daysBetween(currentCycle.start, targetDate) + 1     // 1-indexed, unbounded
daysLate = max(0, cycleDay - expectedCycleLength)
```

`cycleDay` is allowed to exceed `expectedCycleLength`. Being late is information the app should
surface, not arithmetic to be hidden.

If `targetDate` precedes the current cycle's start, resolve it against whichever cycle contains it.

---

## 5. Phase anchoring

**DEFECT 3.** The current model scales all four phases proportionally to cycle length
(`config/defaults.yaml:9`), which the README presents as its main feature. This adapts in the
wrong direction. The **luteal phase is the conserved one** — roughly 14 days, fairly stable
within a person — and cycle-length variation lives almost entirely in the follicular phase.

Consequence: for a 45-day cycle the proportional model places ovulation around day 21–25;
anchoring from the next period places it at day 29–32. **Eight days out**, and the error grows
with cycle length — so the model is least accurate exactly for the irregular and long cycles
that most need a tracker.

### 5.1 The formula

```
lutealLength  = user.lutealLength ?: defaultLutealLength        // 14
periodLength  = current cycle's observed span, else expectedPeriodLength

ovulationDay  = max(periodLength + 4, expectedCycleLength - lutealLength)
```

Boundaries, all 1-indexed and inclusive:

| Phase | Range |
|---|---|
| Menstruation | `1 .. periodLength` |
| Follicular | `periodLength + 1 .. ovulationDay - 3` |
| Ovulation | `ovulationDay - 2 .. ovulationDay + 1` |
| Luteal | `ovulationDay + 2 .. ∞` |

The `periodLength + 4` floor guarantees at least one follicular day on very short cycles.
Luteal is open-ended so a late cycle stays luteal instead of falling off the end.

### 5.2 Observed beats inferred

> If the target date is logged as bleeding, the phase is **Menstruation** with confidence `1.0`,
> regardless of the computed boundaries.

Menstruation is the one phase that is *observed* rather than estimated. A period running to day 7
means you are menstruating on day 7, not follicular.

### 5.3 Phase confidence

```
boundaryDistance = min(cycleDay - phase.start, phase.end - cycleDay)     // ∞ end → use start distance
positional       = clamp((boundaryDistance + 1) / 2.0, 0.0, 1.0)
regularity       = cycleLengthVariability == null ? 0.5
                                                  : 1.0 - clamp(variability / 7.0, 0.0, 0.6)
phaseConfidence  = positional * regularity
```

Full positional confidence one or more days inside a phase; the boundary day itself scores 0.5.
The `+ 1` matters — without it the first and last day of every phase would report zero confidence,
which is far too harsh for a day that is genuinely in the phase, just adjacent to the edge.

`regularity` floors at 0.4, because even wildly irregular cycles carry some signal. With fewer
than three completed cycles, `regularity` is 0.5 — honest uncertainty rather than a fabricated
number.

If `daysLate > 0`, multiply by `max(0.3, 1.0 - daysLate / 14.0)`. A very late cycle means the
whole anchoring is suspect.

### 5.4 Worked boundaries

| Cycle | Period | Ovul. day | Menstruation | Follicular | Ovulation | Luteal |
|---|---|---|---|---|---|---|
| 21 | 4 | 8 | 1–4 | 5–5 | 6–9 | 10–21 |
| 28 | 5 | 14 | 1–5 | 6–11 | 12–15 | 16–28 |
| 35 | 5 | 21 | 1–5 | 6–18 | 19–22 | 23–35 |
| 45 | 6 | 31 | 1–6 | 7–28 | 29–32 | 33–45 |

The 21-day row shows the degenerate case the `periodLength + 4` floor produces: a single
follicular day. Valid, and preferable to an empty phase.

---

## 6. No data is not day 14

The current implementation defaults a user with no logs to cycle day 14, phase Follicular
(`orchestrator.py:123`). That is fabricated data presented as measurement, and it violates the
project rule that absent is never zero.

```
no periods logged  →  cycleDay = null, phase = null, phaseConfidence = 0.0
```

The UI says "log a period to begin" and shows nothing else. Predictions are withheld entirely,
not estimated from a made-up anchor.

---

## 7. What this spec does not cover

- **Learned luteal length.** Fixed at 14 for now. Refine only with real evidence — basal body
  temperature or wearable skin-temperature shift (Phase 4). Do not infer it from cycle-length
  variance; that is circular.
- **Fertility window.** Falls out of `ovulationDay` once the above is trusted (Phase 5).
- **Recency-weighted length.** The median is deliberately simple. Revisit once there are 12+
  cycles of real history and the prediction-scoring loop (Phase 3) can show whether it helps.
