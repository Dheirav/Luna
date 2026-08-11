# 🎯 Menstrual Cycle Intelligence System

A **privacy-first, offline, adaptive** menstrual cycle tracking and intelligence system. Learns personal patterns over time and provides personalized predictions, insights, and wellness suggestions—all running locally with no servers or external APIs.

## ✨ Key Features

- **Adaptive Phase Detection**: Non-hardcoded phase determination that scales across any cycle length
- **Pattern Learning**: Learns personal cycle patterns through recency-weighted historical aggregation
- **Confidence-Aware Predictions**: Predicts mood, energy, pain, sleep, and stress with confidence scores
- **Trend Analysis**: Detects improvements/declines in symptoms over time
- **Personalized Insights**: Generates unique insights based on individual patterns
- **Relationship-Aware**: Phase-based suggestions considering user's "space" vs "support" preference
- **Wellness Intelligence**: Tailored exercise, nutrition, and stress management recommendations
- **Edge Case Handling**: Gracefully handles new users, irregular cycles, sparse data, and inconsistent entries
- **100% Offline**: All data stays on device; no cloud, no tracking, complete privacy

## 🏗️ Architecture

### Module Structure

```
MensturalTracker/
├── src/
│   ├── core/                 # Cycle mechanics
│   │   ├── cycle_tracker.py          # Cycle day computation, history
│   │   └── phase_detector.py         # Adaptive phase detection
│   │
│   ├── ai/                   # Learning & prediction
│   │   ├── pattern_engine.py         # Pattern aggregation & analysis
│   │   ├── learning_engine.py        # Adaptive learning, trends, confidence
│   │   └── predictor.py              # Symptom prediction & forecasting
│   │
│   ├── features/             # User-facing intelligence
│   │   ├── insights_generator.py     # Pattern insights & anomaly detection
│   │   ├── relationship_suggester.py # Relationship-aware suggestions
│   │   └── wellness_engine.py        # Exercise, nutrition, stress advice
│   │
│   ├── data/                 # Data layer
│   │   ├── models.py                 # Data models (Pydantic)
│   │   └── repository.py             # Database abstraction (SQLite)
│   │
│   ├── utils/                # Shared utilities
│   │   └── helper_functions.py       # Math, validation, helpers
│   │
│   └── main/
│       └── orchestrator.py           # Main system coordinator
│
├── config/                   # Configuration files
│   ├── defaults.yaml         # Cycle settings, phase proportions, learning params
│   └── rules.yaml            # Suggestion rules (relationship, wellness)
│
├── database/
│   └── schema.sql            # SQLite database schema
│
└── tests/                    # Unit & integration tests
```

### Key Algorithms

#### 1. Cycle Day Computation
```
cycle_day = (current_date - last_period_start) % cycle_length or cycle_length
```
Maps any date to a 1-indexed position in the user's cycle.

#### 2. Adaptive Phase Detection (No Hardcoding)
- Normalize cycle_day to 0-1 range: `position = (cycle_day - 1) / cycle_length`
- Map proportions (scales across any cycle length):
  - **Menstruation**: 0–15% 
  - **Follicular**: 15–45%
  - **Ovulation**: 45–55% 
  - **Luteal**: 55–100%
- Phase boundaries automatically adapt to cycle length

#### 3. Recency-Weighted Learning
```
weight = exp(-age_in_days / 90)
weighted_avg = Σ(value × weight) / Σ(weight)
```
Recent logs weighted higher (90-day half-life); system adapts as user's cycle changes.

#### 4. Confidence Scoring
```
confidence = 0.3 × user_data_factor 
           + 0.25 × phase_pattern_quality 
           + 0.25 × data_consistency 
           + 0.2 × recency_freshness
```
All predictions include confidence scores; low confidence triggers defaults and warnings.

#### 5. Trend Extraction
```
trend = avg(recent_logs_30d) - avg(all_historical_logs) for that phase
```
Detects if symptoms are improving, declining, or stable.

## 📊 Data Model

### User Profile
```python
{
  user_id: str,
  name: str,
  cycle_settings: {
    typical_cycle_length: 28,
    typical_period_length: 5,
  },
  preferences: {
    low_mood_preference: "space" | "support",
    wellness_focus: ["exercise", "nutrition", "stress_management"]
  }
}
```

### Daily Log
```python
{
  log_id: str,
  date: date,
  period_indicators: {
    is_period_day: bool,
    flow: "light" | "medium" | "heavy" | null
  },
  symptoms: {
    mood: 1-5,
    energy: 0-5,
    pain: 0-5,
    sleep: 1-5,
    stress: 1-5
  },
  notes: str
}
```

### Daily Report (Main Output)
```python
{
  cycle_state: {
    cycle_day, phase, phase_confidence, days_until_next_period
  },
  current_predictions: {
    mood: {value, confidence, range},
    energy: {value, confidence, range},
    ...
  },
  insights: ["insight1", "insight2", ...],
  relationship_advice: ["suggestion1", ...],
  wellness_advice: {
    exercise: str,
    nutrition: str,
    stress_management: str,
    activities: [str]
  },
  upcoming_predictions: [{date, phase, predictions, ...}, ...],
  alerts: [{type, message, severity}, ...],
  data_quality: {
    overall_confidence,
    user_data_points,
    cycle_consistency,
    ...
  }
}
```

## 🚀 Quick Start

### Installation

```bash
# Clone or download the project
cd MensturalTracker

# Install dependencies
pip install -r requirements.txt

# Run the system
python main.py
```

### Usage

#### 1. Initialize Repository & Orchestrator

```python
from pathlib import Path
from src.data.repository import Repository
from src.main.orchestrator import Orchestrator
from datetime import date

# Create repository (SQLite)
repo = Repository("menstrual_tracker.db")

# Create orchestrator (coordinates all modules)
orchestrator = Orchestrator(repo)
```

#### 2. Create a User

```python
from src.data.models import UserProfile, UserPreferences, CycleSettings, MoodPreference
import uuid

user = UserProfile(
    user_id=str(uuid.uuid4()),
    name="Jane",
    preferences=UserPreferences(
        low_mood_preference=MoodPreference.SUPPORT,
    ),
    cycle_settings=CycleSettings(
        typical_cycle_length=28,
        typical_period_length=5
    )
)

repo.users.create(user)
user_id = user.user_id
```

#### 3. Log Daily Symptoms

```python
from datetime import date

# Log symptoms for a day
orchestrator.log_symptoms(
    user_id=user_id,
    target_date=date(2026, 4, 1),
    mood=4,              # 1-5
    energy=4,            # 0-5
    pain=1,              # 0-5
    sleep=4,             # 1-5
    stress=2,            # 1-5
    is_period_day=False,
    notes="Great day!"
)
```

#### 4. Generate Daily Report

```python
# Generate comprehensive report
report = orchestrator.generate_daily_report(
    user_id=user_id,
    target_date=date(2026, 4, 1)
)

# Access report components
print(f"Phase: {report.cycle_state.phase.value}")
print(f"Day: {report.cycle_state.cycle_day}/{report.cycle_state.cycle_length}")
print(f"Predicted mood: {report.current_predictions['mood'].value:.1f}")
print(f"Confidence: {report.data_quality.overall_confidence:.0%}")

# View insights, relationship advice, wellness suggestions
for insight in report.insights:
    print(f"Insight: {insight}")

for advice in report.relationship_advice:
    print(f"Relationship tip: {advice}")

print(f"Exercise: {report.wellness_advice.exercise}")
```

## 🧠 Algorithm Details

### Phase Detection Example

For a 28-day cycle:
```
Position in cycle    Phase           Day Range
0.00-0.15 (0-4 days):     Menstruation  (1-4 days)
0.15-0.45 (4-12 days):    Follicular    (4-13 days)
0.45-0.55 (13-15 days):   Ovulation     (13-15 days)
0.55-1.00 (16-28 days):   Luteal        (16-28 days)
```

For a 32-day cycle:
```
0.00-0.15 (0-4.8 days):   Menstruation  (1-5 days)
0.15-0.45 (4.8-14.4 days): Follicular   (5-14 days)
0.45-0.55 (14.4-17.6 days): Ovulation   (15-18 days)
0.55-1.00 (17.6-32 days): Luteal        (18-32 days)
```

**Key**: Proportions scale automatically; no hardcoding needed!

### Pattern Learning Example

```
Logs for Follicular phase (sorted by date, most recent first):
- 5 days ago: mood=3.5
- 10 days ago: mood=4  
- 20 days ago: mood=4.2
- 30 days ago: mood=3.8

Recency weights (90-day half-life):
- 5 days: 0.97
- 10 days: 0.93
- 20 days: 0.85
- 30 days: 0.76

Weighted average:
= (3.5×0.97 + 4×0.93 + 4.2×0.85 + 3.8×0.76) / (0.97+0.93+0.85+0.76)
= 1.93 + 3.65 + 3.92 + 2.88 / 3.51
≈ 3.89/5
```

Recent data has more influence; older patterns fade. Perfect for detecting cycle changes!

### Confidence Scoring

For a new user with 2 logs:
```
user_confidence_factor = min(2/50, 1.0) = 0.04 (very low)
pattern_quality = 0.1 (inconsistent)
data_consistency = 0.0 (too few cycles)
data_freshness = 0.9 (recent)

overall = 0.3×0.04 + 0.25×0.1 + 0.25×0.0 + 0.2×0.9
        = 0.012 + 0.025 + 0 + 0.18
        = 0.217 → **22% confidence** ⚠️
        
→ Use population defaults + heavy warnings
```

For a user with 150 logs over 6 months:
```
user_confidence_factor = min(150/50, 1.0) = 1.0
pattern_quality = 0.85 (consistent patterns)
data_consistency = 0.92 (regular cycles)
data_freshness = 0.98 (logged today)

overall = 0.3×1.0 + 0.25×0.85 + 0.25×0.92 + 0.2×0.98
        = 0.3 + 0.2125 + 0.23 + 0.196
        = 0.9385 → **94% confidence** ✅
        
→ Trust personalized patterns
```

## ⚠️ Edge Case Handling

### New User (No Data)
- Cycle day defaults to 14 (mid-cycle assumption)
- Phase defaults to Follicular
- Predictions use population defaults (mood 3, energy 3, pain 2, etc.)
- Confidence ≤ 30%
- Report clearly states: "Low confidence: insufficient personal data"

### Irregular Cycles
- System detects cycle_length from period history
- Uses dynamic cycle_length instead of fixed 28
- Flags if cycles vary ±1 day from average
- Adjusts confidence accordingly ("Cycle length varies; predictions less reliable")

### Sparse Logging
- Reduces confidence by sparsity ratio
- Acknowledges gaps in insights
- Provides gentle reminder to log more frequently
- Still generates predictions from available data

### Inconsistent Input
- Values automatically clamped to valid ranges (mood → 1-5, pain → 0-5)
- User warned: "Energy value (-1) adjusted to 0"
- Optional: Quarantine entries > 2 std_dev as potential errors
- Robust statistics (weighted averages) handle outliers

### Period Logging Errors
- Detects overlapping period entries
- Merges consecutive period logs into single cycle
- Asks for confirmation if early period detected
- Timestamps prevent duplicate entries

## 🔄 Workflow Pipeline

```
1. Log symptoms for a day
   ↓
2. Compute cycle day (modulo arithmetic)
   ↓
3. Determine phase (proportional boundaries)
   ↓
4. Retrieve & group historical logs by phase
   ↓
5. Calculate weighted phase patterns (recency weighting)
   ↓
6. Extract trends & confidence factors
   ↓
7. Predict current symptoms (base + trend adjustment)
   ↓
8. Generate insights (pattern mining, anomaly detection)
   ↓
9. Generate relationship suggestions (phase + preference)
   ↓
10. Generate wellness advice (exercise, nutrition, stress)
   ↓
11. Forecast next 7-14 days
   ↓
12. Generate alerts (period, anomalies, data quality)
   ↓
13. Calculate overall confidence
   ↓
14. Output: Daily Report
```

## 🧪 Testing

```bash
# Run all tests
pytest tests/

# Run specific test category
pytest tests/test_cycle_tracker.py
pytest tests/test_phase_detector.py
pytest tests/test_pattern_engine.py

# With coverage
pytest --cov=src tests/
```

## 📈 Future Enhancements

- [ ] Android/iOS app version (reuse core modules)
- [ ] Web dashboard (read-only for privacy)
- [ ] Advanced trend analysis (polynomial fitting, seasonality)
- [ ] Machine learning refinement (optional, still offline)
- [ ] Export/backup functionality
- [ ] Medication interaction predictions
- [ ] fertility window predictions
- [ ] Custom wellness goal tracking

## 🔒 Privacy & Security

- **100% Offline**: No internet required, no servers
- **Local Storage**: SQLite database on device only
- **No Tracking**: No cookies, analytics, or user tracking
- **No Sharing**: Data never leaves device unless user explicitly exports
- **Encrypted DB (Future)**: SQLite encryption support available

## 📄 License

MIT License - See LICENSE file

## 🤝 Contributing

Contributions welcome! Areas for improvement:
- More sophisticated ML models (stays offline)
- Better UI/UX for the web version
- Additional wellness features
- international language support
- Extended research on phase proportions

---

**Built with ❤️ for cycle health & privacy**
