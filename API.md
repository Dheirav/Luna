# API Documentation

## Core Orchestrator API

The `Orchestrator` class is the main entry point for the system. It coordinates all 8 modules to provide a unified interface for cycle tracking and intelligence.

### Initialization

```python
from src.data.repository import Repository
from src.main.orchestrator import Orchestrator

# Create database repository (SQLite)
repository = Repository(db_path="menstrual_tracker.db")

# Initialize orchestrator with all modules
orchestrator = Orchestrator(repository)
```

---

## Main API Methods

### 1. `generate_daily_report(user_id: str, target_date: date) -> DailyReport`

**Purpose**: Generate comprehensive daily intelligence report

**Parameters**:
- `user_id: str` - User identifier
- `target_date: date` - Date to generate report for (default: today)

**Returns**: `DailyReport` object containing:

```python
DailyReport(
    date: date,                              # Report date
    cycle_state: CycleState,                 # Cycle position
    current_predictions: Dict[str, SymptomPrediction],  # Mood/energy/pain/sleep/stress
    insights: List[str],                     # Pattern insights
    relationship_advice: List[str],          # Relationship suggestions
    wellness_advice: WellnessAdvice,         # Exercise/nutrition/stress/activities
    upcoming_predictions: List[DayPrediction],  # Next 7 days
    alerts: List[Alert],                     # Warnings & notices
    data_quality: DataQuality                # Confidence metrics
)
```

**Example**:
```python
from datetime import date

report = orchestrator.generate_daily_report(
    user_id="user-123",
    target_date=date(2026, 4, 1)
)

print(f"📅 Phase: {report.cycle_state.phase.value}")
print(f"💪 Predicted Energy: {report.current_predictions['energy'].value:.1f}/5")
print(f"✨ Confidence: {report.data_quality.overall_confidence:.0%}")

for insight in report.insights:
    print(f"💭 {insight}")
```

**Error Handling**:
```python
try:
    report = orchestrator.generate_daily_report(user_id, target_date)
except ValueError as e:
    print(f"User not found: {e}")
except Exception as e:
    print(f"Report generation failed: {e}")
```

---

### 2. `log_symptoms(user_id: str, target_date: date, mood: int, energy: int, pain: int, sleep: int, stress: int, is_period_day: bool = False, flow: str = None, notes: str = "") -> str`

**Purpose**: Log daily symptoms for a user

**Parameters**:
- `user_id: str` - User identifier
- `target_date: date` - Date to log for
- `mood: int` - Mood level (1-5, where 1=very low, 5=very high)
- `energy: int` - Energy level (0-5, where 0=exhausted, 5=peak)
- `pain: int` - Pain level (0-5, where 0=none, 5=severe)
- `sleep: int` - Sleep quality (1-5, where 1=poor, 5=excellent)
- `stress: int` - Stress level (1-5, where 1=calm, 5=very stressed)
- `is_period_day: bool` - Whether this is a period day (default: False)
- `flow: str` - Period flow level ("light", "medium", "heavy"; ignored if not period day)
- `notes: str` - Optional personal notes

**Returns**: `str` - Log ID (UUID)

**Example**:
```python
from datetime import date

log_id = orchestrator.log_symptoms(
    user_id="user-123",
    target_date=date(2026, 4, 1),
    mood=4,
    energy=3,
    pain=1,
    sleep=4,
    stress=2,
    is_period_day=False,
    notes="Great workout today!"
)

print(f"✅ Logged symptoms. Log ID: {log_id}")
```

**Validation**:
- Values are automatically clamped to valid ranges
- mood/energy/pain/sleep/stress will be adjusted if out of range
- Period flow must be "light", "medium", "heavy", or None
- Invalid dates are rejected

**Behavior**:
- Creates DailyLog entry with clamped values
- Triggers pattern cache update
- Returns warning in data_quality if inconsistencies detected

---

### 3. `get_prediction_explanation(user_id: str, symptom: str, target_date: date) -> Dict`

**Purpose**: Get detailed explanation for a symptom prediction

**Parameters**:
- `user_id: str` - User identifier
- `symptom: str` - Symptom name ("mood", "energy", "pain", "sleep", "stress")
- `target_date: date` - Date to get prediction for

**Returns**: Dictionary with explanation details:
```python
{
    "predicted_value": 3.5,
    "confidence": 0.78,
    "confidence_reason": "Moderate confidence: some personal data + stable pattern",
    "base_pattern": 3.6,
    "trend": -0.1,
    "phase": "Follicular",
    "recent_vs_average": "Slightly lower than baseline",
    "factors": [
        {"name": "phase_pattern", "weight": 0.5, "contribution": 0.3},
        {"name": "personal_trend", "weight": 0.3, "contribution": -0.03},
        {"name": "population_average", "weight": 0.2, "contribution": 0.6}
    ]
}
```

**Example**:
```python
explanation = orchestrator.get_prediction_explanation(
    user_id="user-123",
    symptom="mood",
    target_date=date(2026, 4, 1)
)

print(f"Predicted Mood: {explanation['predicted_value']:.1f}")
print(f"Confidence: {explanation['confidence']:.0%}")
print(f"Reason: {explanation['confidence_reason']}")
print(f"Phase Pattern: {explanation['base_pattern']:.1f}")
print(f"Recent Trend: {explanation['trend']:+.2f}")
```

---

## Data Structure Reference

### CycleState
```python
@dataclass
class CycleState:
    cycle_day: int          # 1 to cycle_length
    cycle_length: int       # User's cycle length (e.g., 28)
    phase: Phase            # Current phase (enum)
    phase_confidence: float # 0-1 confidence in phase
    days_until_next_period: int
    next_period_date: date
```

**Phase Enum Values**:
```python
class Phase(Enum):
    MENSTRUATION = "Menstruation"
    FOLLICULAR = "Follicular"
    OVULATION = "Ovulation"
    LUTEAL = "Luteal"
```

### SymptomPrediction
```python
@dataclass
class SymptomPrediction:
    value: float           # Predicted value (1-5 for mood/sleep/stress, 0-5 for others)
    confidence: float      # 0-1 confidence score
    min_range: float       # Lower bound of likely range
    max_range: float       # Upper bound of likely range
    explanation: str       # Human-readable explanation
```

### DayPrediction
```python
@dataclass
class DayPrediction:
    date: date
    cycle_day: int
    phase: Phase
    predictions: Dict[str, SymptomPrediction]  # Keys: "mood", "energy", "pain", "sleep", "stress"
    recommended_actions: List[str]
    notes: str
```

### WellnessAdvice
```python
@dataclass
class WellnessAdvice:
    exercise: str          # Exercise recommendation
    nutrition: str         # Nutrition recommendation
    stress_management: str # Stress management strategy
    activities: List[str]  # Suggested activities
```

### DataQuality
```python
@dataclass
class DataQuality:
    overall_confidence: float      # 0-1 weighted confidence
    user_data_points: int          # Number of logs recorded
    cycle_consistency: float       # 0-1 cycle regularity
    data_freshness: float          # 0-1 based on recency
    completeness: float            # 0-1 coverage across phases
    last_log_date: date
    notes: str                     # Human-readable quality notes
```

### Alert
```python
@dataclass
class Alert:
    alert_type: str       # "upcoming_period", "anomaly", "data_quality", "low_confidence"
    message: str          # Alert message
    severity: str         # "info", "warning", "urgent"
    action: str           # Suggested action
```

---

## Configuration API

### Access Configuration

```python
from src.utils.helper_functions import load_config

# Load defaults
defaults = load_config("config/defaults.yaml")
print(f"Typical cycle length: {defaults['cycle_settings']['typical_cycle_length']} days")

# Load rules for suggestions
rules = load_config("config/rules.yaml")
relationships = rules['relationship_suggestions']
print(f"Follicular support suggestions: {relationships['follicular']['support']}")
```

### Configuration Structure

**defaults.yaml**:
```yaml
cycle_settings:
  typical_cycle_length: 28
  typical_period_length: 5
  min_cycle_length: 21
  max_cycle_length: 35

phase_proportions:
  menstruation: [0, 0.15]
  follicular: [0.15, 0.45]
  ovulation: [0.45, 0.55]
  luteal: [0.55, 1.0]

learning_parameters:
  recency_half_life: 90
  min_logs_for_pattern: 3
  confidence_threshold: 0.8

confidence_weights:
  user_data_factor: 0.3
  phase_quality_factor: 0.25
  consistency_factor: 0.25
  freshness_factor: 0.2

population_defaults:
  mood: 3
  energy: 3
  pain: 2
  sleep: 4
  stress: 3
```

**rules.yaml**: Contains data-driven suggestions for:
- `relationship_suggestions`: Phase-based with space/support variants
- `wellness_exercise`: Exercise recommendations by phase
- `wellness_nutrition`: Nutrition guidance by phase
- `stress_management`: Stress strategies by phase
- `activities_by_energy`: Activities by energy level

---

## Repository API

For advanced use cases, access data directly:

```python
from src.data.repository import Repository
from src.data.models import UserProfile, UserPreferences, CycleSettings

repo = Repository("menstrual_tracker.db")

# Create user
user = UserProfile(
    user_id="user-123",
    name="Jane",
    preferences=UserPreferences(...),
    cycle_settings=CycleSettings(...)
)
repo.users.create(user)

# Get user
user = repo.users.get_by_id("user-123")

# Get logs for date range
logs = repo.daily_logs.get_for_date_range(
    user_id="user-123",
    start_date=date(2026, 3, 1),
    end_date=date(2026, 4, 1)
)

# Get cycle history
cycles = repo.cycle_records.get_by_user(user_id="user-123")
average_length = sum(c.length for c in cycles) / len(cycles)
```

---

## Module APIs (Advanced)

For testing or custom workflows, access individual modules:

### CycleTracker

```python
from src.core.cycle_tracker import CycleTracker

tracker = CycleTracker(repository)

# Get current cycle day
cycle_day = tracker.get_cycle_day(user_id, date(2026, 4, 1))

# Get cycle information
cycles = tracker.get_cycle_history(user_id)
avg_length = tracker.get_average_cycle_length(user_id)

# Register period start
tracker.register_period_start(user_id, date(2026, 3, 25))
```

### PhaseDetector

```python
from src.core.phase_detector import PhaseDetector

detector = PhaseDetector(cycle_tracker, helpers)

# Get phase
phase = detector.get_phase(cycle_day=10, cycle_length=28)

# Get phase with confidence
phase, confidence = detector.get_phase_with_confidence(cycle_day=10, cycle_length=28)

# Get phase boundaries
start, end = detector.get_phase_range(phase, cycle_length=28)
```

### PatternEngine

```python
from src.ai.pattern_engine import PatternEngine

engine = PatternEngine(repository, cycle_tracker, helpers)

# Analyze patterns for a phase
pattern = engine.analyze_phase_patterns(user_id, phase)
print(f"Mood in {phase}: {pattern.metrics['mood'].average:.1f}±{pattern.metrics['mood'].std_dev:.1f}")

# Get all phase patterns
all_patterns = engine.get_all_patterns(user_id)
for phase, pattern in all_patterns.items():
    print(f"{phase}: {pattern.sample_size} logs")
```

### LearningEngine

```python
from src.ai.learning_engine import LearningEngine

learner = LearningEngine(repository, cycle_tracker, pattern_engine, helpers)

# Get user confidence
conf = learner.get_user_confidence_factor(user_id)

# Extract trends
trend = learner.extract_trend(
    user_id=user_id,
    metric="mood",
    phase=Phase.FOLLICULAR,
    window=30
)

# Assess data quality
quality = learner.assess_data_quality(user_id)
```

### Predictor

```python
from src.ai.predictor import Predictor

predictor = Predictor(repository, cycle_tracker, phase_detector, pattern_engine, learning_engine, helpers)

# Predict single symptom
pred = predictor.predict_symptom(
    user_id=user_id,
    symptom="mood",
    target_date=date(2026, 4, 1)
)
print(f"Mood prediction: {pred.value:.1f} (confidence: {pred.confidence:.0%})")

# Predict full day
day_pred = predictor.predict_day(user_id, date(2026, 4, 1))

# Forecast next N days
forecast = predictor.predict_next_n_days(user_id, n=7)
```

---

## Error Handling

### Common Errors

```python
from src.data.models import UserNotFoundError, InvalidInputError

try:
    report = orchestrator.generate_daily_report("invalid-user", date.today())
except UserNotFoundError:
    print("User does not exist")
except ValueError as e:
    print(f"Invalid input: {e}")
except Exception as e:
    print(f"Unexpected error: {e}")

try:
    orchestrator.log_symptoms(
        user_id="user-123",
        target_date=date(2026, 4, 1),
        mood=10,  # Invalid: must be 1-5
        energy=-1  # Invalid: must be 0-5
    )
    # Values will be auto-clamped to valid ranges
except Exception as e:
    print(f"Logging failed: {e}")
```

### Confidence Warnings

All reports include data quality metrics:

```python
report = orchestrator.generate_daily_report(user_id, target_date)

if report.data_quality.overall_confidence < 0.5:
    print("⚠️  Low confidence: insufficient data")
    print(f"   Add more logs. Current: {report.data_quality.user_data_points}")

if report.data_quality.cycle_consistency < 0.7:
    print("⚠️  Irregular cycles detected")
    print("   Predictions less reliable")

for alert in report.alerts:
    if alert.severity == "warning":
        print(f"⚠️  {alert.message}")
```

---

## Performance Considerations

### Database Queries
- Repository caches user objects in memory
- Phase patterns cached after update
- Trend calculations use indexed queries on date ranges

### Computational Complexity
- Cycle day: O(1)
- Phase detection: O(1)
- Pattern analysis: O(n) where n = number of logs (weighted aggregation)
- Report generation: O(n) → dominated by log retrieval
- Full forecast (7 days): 7 × O(1) prediction calls

### Optimization Tips
- Call `generate_daily_report()` once, reuse for multiple insights
- Use `get_prediction_explanation()` sparingly (involves detailed factor calculation)
- Cache reports for users checked multiple times in same session
- Batch log operations when possible

---

## Migration & Data Export

### Export User Data

```python
from datetime import date
import json

def export_user_data(repository, user_id):
    user = repository.users.get_by_id(user_id)
    logs = repository.daily_logs.get_all_for_user(user_id)
    cycles = repository.cycle_records.get_by_user(user_id)
    
    export = {
        "user": user,
        "logs": logs,
        "cycles": cycles
    }
    
    with open(f"export_{user_id}.json", "w") as f:
        # Note: Need to serialize dataclasses to JSON
        # Simplified example:
        json.dump({
            "user_id": user.user_id,
            "name": user.name,
            "log_count": len(logs),
            "cycle_count": len(cycles)
        }, f)

export_user_data(repo, "user-123")
```

---

## Examples

### Full Workflow Example

```python
from datetime import date
from src.data.repository import Repository
from src.main.orchestrator import Orchestrator
from src.data.models import UserProfile, UserPreferences, CycleSettings, MoodPreference
import uuid

# 1. Initialize
repository = Repository("app.db")
orchestrator = Orchestrator(repository)

# 2. Create user
user = UserProfile(
    user_id=str(uuid.uuid4()),
    name="Alice",
    preferences=UserPreferences(low_mood_preference=MoodPreference.SUPPORT),
    cycle_settings=CycleSettings()
)
repository.users.create(user)

# 3. Log symptoms over time
today = date(2026, 4, 1)
for i in range(30):
    log_date = date(2026, 3, 1) + timedelta(days=i)
    orchestrator.log_symptoms(
        user_id=user.user_id,
        target_date=log_date,
        mood=4, energy=3, pain=1, sleep=4, stress=2
    )

# 4. Generate report
report = orchestrator.generate_daily_report(user.user_id, today)

# 5. Display insights
print(f"📅 {today}: {report.cycle_state.phase.value} (Day {report.cycle_state.cycle_day})")
print(f"💪 Energy: {report.current_predictions['energy'].value:.1f}/5 ({report.current_predictions['energy'].confidence:.0%})")
print(f"\n💭 Insights:")
for insight in report.insights:
    print(f"  • {insight}")
print(f"\n💑 Relationship:")
for advice in report.relationship_advice:
    print(f"  • {advice}")
print(f"\n🧘 Wellness: {report.wellness_advice.exercise}")
print(f"\n📊 Confidence: {report.data_quality.overall_confidence:.0%}")
```

---

For more examples, see `main.py` and test files.
