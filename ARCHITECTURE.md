# Architecture & Design Decisions

## System Overview

The Menstrual Cycle Intelligence System is built around a **modular, privacy-first architecture** designed for:
1. **Offline-first**: All computation happens locally, no cloud dependency
2. **Adaptive learning**: System learns personal patterns and becomes more accurate over time
3. **Confidence-aware**: All predictions include confidence scores; low confidence triggers graceful degradation
4. **Extensibility**: Clean module boundaries enable easy feature addition and platform porting (Android, iOS, web)
5. **Testability**: Dependency injection and repository pattern enable comprehensive testing

---

## Core Design Principles

### 1. **Modular Independence**
Each module has a single responsibility and minimal dependencies:

```
┌─────────────────────────────────────────────────────────────────┐
│ ORCHESTRATOR (Main Coordinator)                                 │
│ - Implements 10-step pipeline                                   │
│ - Dependency injection for all modules                          │
├─────────────────────────────────────────────────────────────────┤
│
├─ CORE (Cycle Mechanics)                      ← Depends on: Data
│  ├─ cycle_tracker.py (cycle day, history)
│  └─ phase_detector.py (adaptive phase detection)
│
├─ AI (Learning & Prediction)                  ← Depends on: Core, Data
│  ├─ pattern_engine.py (learn patterns)
│  ├─ learning_engine.py (trends, confidence)
│  └─ predictor.py (symptom prediction)
│
├─ FEATURES (User Intelligence)                ← Depends on: AI, Data
│  ├─ insights_generator.py (pattern insights)
│  ├─ relationship_suggester.py (relationship advice)
│  └─ wellness_engine.py (exercise, nutrition, stress)
│
├─ DATA (Persistence & Models)                 ← Depends on: None
│  ├─ models.py (Pydantic dataclasses)
│  └─ repository.py (SQLite abstraction)
│
└─ UTILS (Shared Helpers)                      ← Depends on: None
   └─ helper_functions.py (math, validation, dates)
```

**Benefits**:
- Each module testable in isolation
- Replace SQLite with PostgreSQL by changing one Repository class
- Port to Android: Keep core/ai/features, use Android DB layer
- No circular dependencies; clean dependency graph

### 2. **Confidence-First Design**
Every prediction includes a confidence score (0-1) used to decide whether to trust personalized patterns or fall back to population defaults.

```python
# Confidence calculation formula
confidence = (
    0.3  × user_confidence_factor      # Based on # of logs
    + 0.25 × phase_pattern_quality     # Phase consistency
    + 0.25 × data_consistency_score    # Cycle regularity  
    + 0.2  × recency_freshness         # Data freshness
)

# Prediction adjustment based on confidence
if confidence < 0.4:
    use_population_defaults()          # New users
    warn("Low confidence: add more logs")
elif confidence < 0.7:
    blend(personal_pattern=0.5, population=0.5)
    warn("Moderate confidence: some uncertainty")
else:
    use_personal_pattern()             # Trust the learning
    confidence_score = high ✅
```

### 3. **Repository Pattern for Data Access**
All database access is abstracted via repositories, ensuring:
- Easy testing (mock repositories)
- Privacy by design (all data ops in one place)
- Platform independence (swap SQLite for cloud later if needed)

```python
# Instead of scattered SQL queries:
# ❌ BAD: SELECT * FROM daily_logs WHERE user_id = ?
# ✅ GOOD: repo.daily_logs.get_all_for_user(user_id)
```

### 4. **Configuration-Driven Rules**
Relationship suggestions, wellness advice, and rules are **data-driven** (YAML), not hardcoded:

```yaml
# config/rules.yaml
relationship_suggestions:
  follicular:
    support:
      - "Follicular phase: energy is rising"
      - "Great time for planning and setting goals"
    space:
      - "Need personal space to harness focus"
      - "Redirect social energy into projects"
```

**Why**:
- Easy to customize per user/region
- Non-technical users can adjust rules
- No code changes needed for new suggestions
- A/B testing rules without deploying code

---

## Core Algorithms

### 1. Cycle Day Computation
**Problem**: Given last period start date and today's date, what day of the cycle are we?

**Algorithm**:
```
cycle_day = (days_since_last_period % cycle_length) or cycle_length

Examples (28-day cycle):
- Day 1 (period start): (0 % 28) or 28 → 28 (wrap-around logic)
- Day 14 (ovulation): (13 % 28) or 28 → 13
- Day 28 (luteal end): (27 % 28) or 28 → 27
```

**Why modulo arithmetic?**
- Simple, efficient O(1)
- Handles cycle wraparounds naturally
- Works with any cycle length

**Edge cases handled**:
- No period history: Default to day 14 (mid-cycle)
- Irregular cycles: Use average cycle length
- Recent cycle change: Detect and adapt

### 2. Adaptive Phase Detection (NO Hardcoding)
**Problem**: Determine menstrual phase without hardcoding day ranges

**Algorithm**:
```python
# Step 1: Normalize cycle position to 0-1 range
position = (cycle_day - 1) / cycle_length

# Step 2: Map proportions (these DON'T change for different cycle lengths)
PHASE_BOUNDARIES = {
    Phase.MENSTRUATION: (0.00, 0.15),   # 0-15% of cycle
    Phase.FOLLICULAR:   (0.15, 0.45),   # 15-45%
    Phase.OVULATION:    (0.45, 0.55),   # 45-55%
    Phase.LUTEAL:       (0.55, 1.00),   # 55-100%
}

# Step 3: Look up phase
for phase, (start, end) in PHASE_BOUNDARIES.items():
    if start <= position < end:
        return phase
```

**Why proportions instead of day ranges?**

Scales across cycle lengths automatically:

| Cycle Length | Menstruation | Follicular | Ovulation | Luteal |
|---|---|---|---|---|
| 21 days | 1-3 | 4-9 | 10-11 | 12-21 |
| 28 days | 1-4 | 5-13 | 13-15 | 16-28 |
| 35 days | 1-5 | 6-16 | 16-19 | 20-35 |

**No code changes needed!** Proportions handle all cycle lengths.

**Confidence scoring for phase**:
```python
# Phase confidence higher at phase center, lower at boundaries
distance_from_center = min(
    abs(position - phase_center),
    min(abs(position - phase_start), abs(position - phase_end))
)
phase_confidence = 1.0 - (distance_from_center / phase_width)

# Example: Ovulation (45-55% of cycle, center at 50%)
# At 50%: confidence = 1.0 (certain)
# At 48%: confidence = 0.8 (near boundary, less certain)
# At 46%: confidence = 0.6 (might be follicular or ovulation)
```

### 3. Recency-Weighted Aggregation
**Problem**: Learn patterns from historical logs, but recent data is more relevant as the cycle may change

**Algorithm**:
```
weight(log) = exp(-age_in_days / half_life)

# With 90-day half-life:
- Log from 0 days ago: weight = 1.0 (full)
- Log from 30 days ago: weight = 0.78 (78%)
- Log from 90 days ago: weight = 0.5 (50%)
- Log from 180 days ago: weight = 0.25 (25%)

weighted_avg = Σ(value × weight) / Σ(weight)
```

**Example**: Mood in follicular phase

```
Logs (oldest to recent):
| Age | Mood | Weight | Contribution |
|-----|------|--------|--------------|
| 30d | 3.0  | 0.78   | 2.34         |
| 20d | 4.0  | 0.85   | 3.40         |
| 10d | 4.2  | 0.93   | 3.91         |
| 2d  | 3.8  | 0.98   | 3.72         |
|     |      | 3.54   | 13.37        |

Weighted average = 13.37 / 3.54 = 3.78
```

**Why exponential decay?**
- Mathematical elegance: smooth degradation
- No hard cutoff (unlike "ignore logs >90 days old")
- Adapts naturally when cycle changes or user's mood improves
- Gives more weight to recent patterns

### 4. Confidence Scoring (4-Factor Blend)
**Problem**: Some users have rich data, others are new; predictions have different reliability

**Algorithm**:
```
confidence = 0.3 × U + 0.25 × P + 0.25 × C + 0.2 × F

Where:
U = User confidence factor = min(num_logs / 50, 1.0)
    (100% confidence at 50 logs, scales 0-100%)

P = Phase pattern quality = std_dev of phase logs
    (if logs in a phase are consistent: high; noisy: low)

C = Cycle consistency = 1 - (cycle_variance / ideal_variance)
    (regular cycles: high; irregular: low)

F = Recency freshness = exp(-days_since_last_log / 90)
    (recent logs: high; stale: low)
```

**Examples**:

*New user (2 logs in follicular phase, logged 1 day ago)*:
```
U = min(2/50, 1.0) = 0.04
P = 0.1 (too few logs for consistency)
C = 0.0 (can't assess cycle regularity)
F = exp(-1/90) = 0.99

confidence = 0.3×0.04 + 0.25×0.1 + 0.25×0 + 0.2×0.99
           = 0.012 + 0.025 + 0 + 0.198
           = 0.235 → 24% confidence ⚠️
           
→ Use population defaults (mood=3), warn "Add more logs"
```

*Regular user (150 logs, consistent 28-day cycles, logged today)*:
```
U = min(150/50, 1.0) = 1.0
P = 0.9 (consistent patterns)
C = 0.95 (cycles vary ±1 day)
F = exp(-0/90) = 1.0

confidence = 0.3×1.0 + 0.25×0.9 + 0.25×0.95 + 0.2×1.0
           = 0.3 + 0.225 + 0.2375 + 0.2
           = 0.9625 → 96% confidence ✅
           
→ Trust personal predictions
```

### 5. Trend Extraction
**Problem**: Is mood improving? Worsening? Stable? Over what timeframe?

**Algorithm**:
```
trend(metric, phase) = avg_recent(window=30d) - avg_all_historical(phase)

Examples (mood in follicular):
- Recent 30d average: 4.1
- All-time average: 3.8
- Trend = +0.3 (mood improving in follicular!)

- Recent 30d average: 3.6
- All-time average: 3.8
- Trend = -0.2 (mood declining slightly in follicular)
```

**Used in predictions**:
```
predicted_value = base_pattern + (trend × adjustment_factor)

# If there's an improving trend, boost prediction
# If trend is negative, lower prediction
# Adjustment factor = 0.2-0.5 (don't go overboard)
```

### 6. Pattern Learning (Per-Phase Aggregation)
**Problem**: Collect and summarize logs by phase for pattern recognition

**Algorithm**:
```python
for phase in all_phases:
    phase_logs = [log for log in all_logs if phase_of(log.date) == phase]
    
    for metric in ["mood", "energy", "pain", "sleep", "stress"]:
        values = [log.get(metric) for log in phase_logs]
        weights = [recency_weight(log.date) for log in phase_logs]
        
        average = weighted_mean(values, weights)
        std_dev = weighted_std_dev(values, weights)
        
        pattern.metrics[metric] = {
            "average": average,
            "std_dev": std_dev,
            "frequency_map": build_distribution(values)
        }

store_pattern_cache(phase, pattern)
```

**Frequency map**: For each metric, count how often each value occurs

```
Mood in follicular phase (from 50 logs):
1: ██ (4 times, 8%)
2: ███ (6 times, 12%)
3: ████████ (16 times, 32%)  ← Most common
4: ███████████ (22 times, 44%) ← Peak
5: ██ (2 times, 4%)

Average = 3.6, Std Dev = 0.8
Distribution = "bimodal around 3-4"
```

---

## Data Flow Architecture

### Daily Report Generation (10-Step Pipeline)

```
[User calls generate_daily_report(user_id, date)]
                    ↓
    [Step 1: Get cycle state]
         cycle_tracker.get_cycle_day(user_id, date)
         phase_detector.get_phase(cycle_day, cycle_length)
                    ↓
    [Step 2: Retrieve historical data]
         repository.daily_logs.get_all_for_user(user_id)
         repository.cycle_records.get_by_user(user_id)
                    ↓
    [Step 3: Compute patterns]
         pattern_engine.get_all_patterns(user_id)
         (uses recency-weighted aggregation per phase)
                    ↓
    [Step 4: Calculate trends]
         learning_engine.extract_trend(user_id, metric, phase)
         (for each symptom)
                    ↓
    [Step 5: Generate predictions]
         predictor.predict_day(user_id, date)
         (base_pattern + trend + confidence for each symptom)
                    ↓
    [Step 6: Mine insights]
         insights_generator.analyze_user_patterns(user_id)
         (pattern mining: highest/lowest phases, anomalies)
                    ↓
    [Step 7: Generate relationship advice]
         relationship_suggester.suggest_for_phase(user_id, phase)
         (phase + preference → suggestions from RULES.yaml)
                    ↓
    [Step 8: Generate wellness advice]
         wellness_engine.get_daily_wellness_advice(user_id, phase)
         (exercise, nutrition, stress management)
                    ↓
    [Step 9: Forecast next 7 days]
         predictor.predict_next_n_days(user_id, n=7)
         (for each future date → predictions)
                    ↓
    [Step 10: Generate alerts & quality metrics]
         learning_engine.assess_data_quality(user_id)
         orchestrator._generate_alerts()
         (upcoming period, anomalies, low confidence)
                    ↓
    [Assemble & return DailyReport]
```

---

## Edge Case Handling Architecture

### New User (No Historical Data)
```
User creates account, logs first symptom

→ Confidence factors:
  - user_confidence = 0.02 (1 log ÷ 50 threshold)
  - pattern_quality = 0 (insufficient data)
  - cycle_consistency = 0 (can't assess)
  - freshness = 1.0 (just logged)
  → Overall: 20% confidence

→ Prediction strategy:
  - Use population defaults for all symptoms
  - Flag all predictions: "Low confidence: add more logs"
  - Suggest logging daily for 1 month to build pattern
  - No personalized insights yet

→ After 30 logs:
  - user_confidence = 0.6 (30 ÷ 50)
  - Can assess consistency across cycles
  - Confidence ~40% → blend personal/population
  
→ After 50+ logs (1 month+):
  - user_confidence = 1.0
  - Clear personal patterns emerge
  - Can detect trends
  - Confidence 70%+ → trust personal patterns
```

### Irregular Cycles
```
User's cycles: 25, 28, 32, 27, 29 days
Average: 28.2, Variance: 2.8

→ Detection:
  if max(cycles) - min(cycles) > 3:
      confidence_penalty = 0.1
      alert = "Irregular cycles detected; predictions less reliable"

→ Adjustment:
  - Use average cycle length (28.2) for phase detection
  - Increase confidence thresholds (need more data to trust)
  - Highlight in alerts: "Monitor cycle length"
  
→ Recovery:
  - If cycles stabilize, penalty decreases
  - After 5 regular cycles, flag resolved
```

### Sparse Logging (Missing Days)
```
User logs only on challenging days (high pain, bad mood)

→ Detection:
  if logs_per_day < 0.5:
      completeness_score = days_logged / total_days
      → 0.3 (only 30% coverage)

→ Adjustment:
  - Reduce confidence by sparsity factor
  - Alert: "Sparse logging detected; add entries for 3+ consecutive days"
  - Use available data but acknowledge gaps

→ Metrics
  - Pattern quality: lower (fewer samples per phase)
  - Freshness: penalize old logs more harshly
```

### Invalid Input
```
User logs: mood=10 (invalid, should be 1-5)

→ Clamping:
  value = clamp(value, min=1, max=5)
  if original != clamped:
      warning = f"Mood value (10) adjusted to 5"
      user_notified = true

→ Learning impact:
  - Log adjusted value
  - Pattern learns from clamped value (not invalid one)
  - User informed: "Check your input"
```

### Period Date Conflicts
```
User logs period on March 25, then March 22

→ Detection:
  - Period in past detected
  - Check for overlap: March 22-26 overlaps with March 25-28

→ Resolution:
  - Merge periods: March 22-28 treated as single cycle
  - Ask user: "Did you mean March 22 instead of March 25?"
  - Recalculate cycle_day from merged period
```

---

## Testing Architecture

```
tests/
├── test_cycle_tracker.py
│   ├── Test cycle day calculation
│   ├── Test period registration
│   ├── Test cycle history
│   └── Edge cases: no history, sparse dates
│
├── test_phase_detector.py
│   ├── Test phase detection at boundaries
│   ├── Test proportional scaling (20, 28, 35 day cycles)
│   ├── Test confidence scoring
│   └── Edge case: exactly at boundary
│
├── test_pattern_engine.py
│   ├── Test weighted aggregation
│   ├── Test anomaly detection
│   ├── Test phase pattern caching
│   └── Edge case: single-log phase
│
├── test_learning_engine.py
│   ├── Test recency weighting
│   ├── Test trend calculation
│   ├── Test confidence factors
│   └── Edge case: all zeros, all same value
│
├── test_predictor.py
│   ├── Test symptom prediction
│   ├── Test multiday forecast
│   ├── Test period prediction
│   └── Edge case: new user, sparse data
│
├── test_integration.py
│   ├── Full pipeline end-to-end
│   ├── Realistic data simulation
│   └── Verify edge cases handled
│
└── fixtures/
    ├── sample_users.py
    ├── sample_logs.py
    └── test_data_generator.py
```

---

## Performance Optimization

### Caching Strategy
```python
# Pattern caching (persistent in DB)
↓ User logs symptom
↓ Repository.daily_logs.create(log)
↓ Trigger: pattern_cache_update(user_id, phase)
↓ Recompute pattern for that phase
↓ Store in database.phase_patterns
→ Retrieve instantly for next report

# Eliminates: O(n) aggregation on every report request
# Cost: O(n) once per new log, cached thereafter
```

### Database Indexes
```sql
CREATE INDEX idx_daily_logs_user_date 
  ON daily_logs(user_id, log_date);  -- Fast range queries

CREATE INDEX idx_cycle_records_user_date
  ON cycle_records(user_id, start_date);  -- Fast history lookup

CREATE INDEX idx_phase_patterns_user_phase
  ON phase_patterns(user_id, phase);  -- Fast pattern lookup
```

### Computation Order (Early Exit)
```python
def generate_daily_report():
    # Cheap operations first
    cycle_state = get_cycle_day()      # O(1)
    phase = get_phase(cycle_day)       # O(1)
    
    # Then more expensive
    logs = repository.get_all_logs()   # O(n), but cached in DB
    patterns = pattern_engine.get_all() # O(1) if cached
    
    # Expensive but necessary
    predictions = predictor.predict_day() # O(5 symptoms)
    
    # Only if needed
    if confidence < 0.5:
        detailed_explanation = get_explanation() # Optional
```

---

## Android Port Readiness

The architecture is designed for easy Android/iOS porting:

```
Core (stays identical):
├─ cycle_tracker.py         → CycleTracker.kt
├─ phase_detector.py        → PhaseDetector.kt
├─ pattern_engine.py        → PatternEngine.kt
├─ learning_engine.py       → LearningEngine.kt
├─ predictor.py             → Predictor.kt
├─ insights_generator.py    → InsightsGenerator.kt
├─ relationship_suggester.py → RelationshipSuggester.kt
└─ wellness_engine.py       → WellnessEngine.kt

Data Layer (needs Android adaptation):
├─ models.py                → Kotlin dataclasses + Parcelable
└─ repository.py            → Room (SQLite abstraction)

Utils (reusable):
└─ helper_functions.py      → Kotlin extension functions

Platform-specific:
└─ UI Layer                 → Android/Compose UI
```

**Why this structure works for Android**:
- Pure business logic (no I/O) in core modules
- All I/O abstracted in Repository pattern
- No platform-specific imports in core
- Easy to mock for testing
- Kotlin translation straightforward (similar OOP)

---

## Future Extensibility

### Adding a New Wellness Feature
```python
# 1. Add to data model (if needed)
# src/data/models.py → add MedicationLog, VitaminLog

# 2. Create feature module
# src/features/medication_advisor.py
class MedicationAdvisor:
    def __init__(self, repo, phase_detector):
        self.repo = repo
        self.phase_detector = phase_detector
    
    def suggest_supplement(self, user_id, date):
        phase = self.phase_detector.get_phase(...)
        # Phase-specific supplement advice
        return suggestions

# 3. Inject into Orchestrator
# src/main/orchestrator.py
def __init__(self, repo, ..., medication_advisor):
    self.medication_advisor = medication_advisor

# 4. Add to daily report
# In generate_daily_report():
report.medication_advice = self.medication_advisor.suggest_supplement(...)

# 5. No changes needed elsewhere!
```

### Adding a New Data Type
```python
# 1. Extend DailyLog model
@dataclass
class DailyLog:
    # Existing fields...
    medication: List[str] = None
    supplements: List[str] = None

# 2. Update repository
class MedicationLogRepository:
    def create(self, log): ...
    def get_for_user_phase(self, user_id, phase): ...

# 3. Update pattern engine
pattern_engine.analyze_medication_patterns(user_id, phase)

# 4. Everything else inherits automatically!
```

---

## Summary: Design Principles in Action

| Principle | Implementation | Benefit |
|-----------|---|---|
| Modular | 8 independent modules | Testable, extensible, portable |
| Offline-first | SQLite repository | Privacy, works without internet |
| Confidence-aware | 4-factor blend scoring | Graceful degradation, transparency |
| Adaptive | Recency-weighted learning | Learns as user's cycle changes |
| No hardcoding | YAML rules, proportional phases | Flexible, customizable, scales |
| DI & repos | Dependency injection everywhere | Testable, swappable components |
| Clear data flow | 10-step pipeline | Observable, debuggable, verifiable |
| Edge case handling | Explicit per-case logic | Robust, reliable, user-friendly |

This architecture enables a system that is simultaneously **robust, adaptive, private, and future-proof**.
