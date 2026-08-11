# Testing Guide

## Quick Start

### Run All Tests
```bash
# Install pytest if not already installed
pip install pytest pytest-cov

# Run all tests
pytest tests/

# Run with coverage report
pytest --cov=src tests/

# Run specific test file
pytest tests/test_cycle_tracker.py -v

# Run specific test
pytest tests/test_cycle_tracker.py::test_cycle_day_computation -v
```

---

## Test Structure

```
tests/
├── __init__.py
├── conftest.py                    # Pytest fixtures (shared setup)
├── test_cycle_tracker.py          # Core: cycle mechanics
├── test_phase_detector.py         # Core: phase detection
├── test_pattern_engine.py         # AI: pattern learning
├── test_learning_engine.py        # AI: trends & confidence
├── test_predictor.py              # AI: predictions
├── test_insights_generator.py     # Features: insights
├── test_relationship_suggester.py # Features: relationship advice
├── test_wellness_engine.py        # Features: wellness advice
├── test_orchestrator.py           # Integration: full pipeline
├── test_edge_cases.py             # Edge cases across modules
└── fixtures/
    ├── sample_users.py            # Test user fixtures
    ├── sample_logs.py             # Test log data
    └── test_database.py           # In-memory test DB
```

---

## Running the System

### 1. Generate a Sample Report

```bash
# Run the test entry point with sample data
python main.py

# Output: Daily report for test user with sample data
```

### 2. Write a Custom Test Script

```python
# test_custom.py
from datetime import date, timedelta
from src.data.repository import Repository
from src.main.orchestrator import Orchestrator
from src.data.models import UserProfile, UserPreferences, CycleSettings, MoodPreference
import uuid

def test_workflow():
    # Initialize
    repo = Repository(":memory:")  # In-memory DB for testing
    orchestrator = Orchestrator(repo)
    
    # Create user
    user = UserProfile(
        user_id=str(uuid.uuid4()),
        name="Test User",
        preferences=UserPreferences(
            low_mood_preference=MoodPreference.SUPPORT
        ),
        cycle_settings=CycleSettings()
    )
    repo.users.create(user)
    
    # Log symptoms over a month
    base_date = date(2026, 3, 1)
    for i in range(30):
        log_date = base_date + timedelta(days=i)
        orchestrator.log_symptoms(
            user_id=user.user_id,
            target_date=log_date,
            mood=4,
            energy=3,
            pain=1,
            sleep=4,
            stress=2
        )
    
    # Generate report
    report = orchestrator.generate_daily_report(
        user_id=user.user_id,
        target_date=base_date + timedelta(days=14)
    )
    
    # Verify
    assert report.cycle_state.phase is not None
    assert len(report.current_predictions) == 5
    assert report.data_quality.overall_confidence > 0.5
    print("✅ Test passed!")

if __name__ == "__main__":
    test_workflow()
```

Run it:
```bash
python test_custom.py
```

---

## Test Categories

### Unit Tests (Individual Modules)

#### CycleTracker Tests
```python
def test_cycle_day_computation():
    """Verify cycle day calculation"""
    # Period starts March 1, 28-day cycle
    # March 15 should be day 15
    
def test_cycle_day_wraparound():
    """Verify wraparound at cycle end"""
    # Day 28 should wrap to day 1
    
def test_cycle_history():
    """Verify period history tracking"""
    # Register multiple periods, verify retrieval
    
def test_no_period_history():
    """Verify graceful handling of new user"""
    # Should default to day 14
```

#### PhaseDetector Tests
```python
def test_phase_detection_28_day():
    """Verify phase detection for 28-day cycle"""
    assert get_phase(day=2, cycle_length=28) == Phase.MENSTRUATION
    assert get_phase(day=10, cycle_length=28) == Phase.FOLLICULAR
    assert get_phase(day=14, cycle_length=28) == Phase.OVULATION
    assert get_phase(day=20, cycle_length=28) == Phase.LUTEAL

def test_phase_detection_35_day():
    """Verify phase detection scales for 35-day cycle"""
    # Boundaries should scale proportionally
    
def test_phase_confidence():
    """Verify confidence at phase center vs. boundary"""
    # Confidence higher at phase center
    
def test_phase_at_boundary():
    """Verify boundary handling"""
    # Exactly at boundary: correct phase assignment
```

#### PatternEngine Tests
```python
def test_phase_pattern_aggregation():
    """Verify weighted aggregation of logs"""
    # Create 10 follicular logs with varying mood
    # Verify pattern.metrics['mood'].average correct
    
def test_recency_weighting():
    """Verify recent logs weighted higher"""
    # Recent log should have more influence than old
    
def test_anomaly_detection():
    """Verify outlier detection"""
    # Log with mood=1 when phase average=4, should flag
    
def test_pattern_caching():
    """Verify patterns cached after log"""
    # Log symptom, verify pattern updates in DB
```

#### LearningEngine Tests
```python
def test_user_confidence_factor():
    """Verify confidence grows with logs"""
    # 1 log: ~0.02, 50 logs: 1.0
    
def test_data_consistency():
    """Verify cycle regularity scoring"""
    # Regular cycles: high score
    # Irregular cycles: low score
    
def test_trend_extraction():
    """Verify trend calculation"""
    # Recent average > baseline: positive trend
    
def test_overall_confidence():
    """Verify 4-factor blend"""
    # Confidence = weighted sum of 4 factors
```

#### Predictor Tests
```python
def test_symptom_prediction():
    """Verify single symptom prediction"""
    # Pattern + trend → predicted value in valid range
    
def test_prediction_confidence():
    """Verify confidence scores"""
    # More data → higher confidence
    
def test_multi_day_forecast():
    """Verify 7-day forecast generation"""
    # Each day should have predictions
    
def test_period_prediction():
    """Verify period date prediction"""
    # Should predict within ±2 days of actual
```

### Integration Tests (Full Pipeline)

```python
def test_full_daily_report():
    """Test complete report generation"""
    # User logs → Pattern learning → Predictions → Insights
    
def test_new_user_workflow():
    """Test new user with limited data"""
    # Should use population defaults, clear warnings
    
def test_irregular_cycle_detection():
    """Test irregular cycle handling"""
    # Log varied cycle lengths, verify detection

def test_sparse_logging():
    """Test handling of missing days"""
    # Log only on bad days, verify confidence adjustments
```

### Edge Case Tests

```python
def test_single_log_per_phase():
    """Minimum viable data"""
    # One log per phase should still generate report
    
def test_all_same_values():
    """Uniform data (std_dev = 0)"""
    # Should handle zero variance
    
def test_invalid_input_clamping():
    """Verify input validation"""
    # Mood=-1 → 1, mood=10 → 5
    
def test_period_overlap():
    """Conflicting period dates"""
    # Should merge or ask for clarification
    
def test_future_predictions():
    """Predictions for dates beyond data"""
    # Should extrapolate from current cycle
```

---

## Manual Testing Checklist

### Core Functionality
- [ ] Create user & verify profile stored
- [ ] Log symptoms & verify in database
- [ ] Register period & verify cycle tracking
- [ ] Generate report for different phases
- [ ] Verify phase detection across cycle lengths

### Adaptive Learning
- [ ] Add 10 logs, verify confidence increases
- [ ] Add logs with improvements (e.g., energy rising), verify trend detected
- [ ] Log anomaly (e.g., very high pain), verify flagged
- [ ] Verify predictions more accurate after 50+ logs

### Edge Cases
- [ ] New user with 0 logs: should show defaults
- [ ] New user with 2 logs: should show warnings
- [ ] Irregular cycles: should flag "inconsistent"
- [ ] Period date in past: should ask for clarification
- [ ] Invalid input (mood=10): should auto-clamp

### Output Quality
- [ ] Insights are meaningful & specific to user
- [ ] Relationship advice matches phase + preference
- [ ] Wellness suggestions vary by energy level
- [ ] Alerts are clear and actionable
- [ ] Confidence scores make sense

---

## Performance Testing

### Benchmark Tests
```python
def benchmark_report_generation(user_id, num_samples):
    """Measure report generation time"""
    # 100 logs: should complete <100ms
    # 1000 logs: should complete <500ms
    # 10000 logs: should complete <2000ms
```

### Database Query Performance
```bash
# Query analysis
sqlite3 menstrual_tracker.db
.timer on
SELECT * FROM daily_logs WHERE user_id = 'X' AND log_date BETWEEN ? AND ?;
```

### Memory Usage
```python
import tracemalloc

tracemalloc.start()
report = orchestrator.generate_daily_report(user_id, date.today())
current, peak = tracemalloc.get_traced_memory()
print(f"Memory used: {peak / 1024 / 1024:.2f} MB")
```

---

## Continuous Integration Setup

### GitHub Actions Example (.github/workflows/tests.yml)
```yaml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        python-version: ['3.8', '3.9', '3.10', '3.11']
    
    steps:
      - uses: actions/checkout@v2
      - name: Set up Python
        uses: actions/setup-python@v2
        with:
          python-version: ${{ matrix.python-version }}
      - name: Install dependencies
        run: |
          pip install -r requirements.txt
      - name: Run tests
        run: |
          pytest tests/ --cov=src
      - name: Upload coverage
        uses: codecov/codecov-action@v2
```

---

## Debugging Tests

### Print Debug Info
```python
import pytest

def test_with_debug():
    report = orchestrator.generate_daily_report(user_id, date.today())
    
    # Print full report structure
    print("\n" + "="*60)
    print(f"Cycle Day: {report.cycle_state.cycle_day}")
    print(f"Phase: {report.cycle_state.phase.value}")
    print(f"Predictions: {report.current_predictions}")
    print(f"Confidence: {report.data_quality.overall_confidence:.0%}")
    print("="*60)
```

Run with output:
```bash
pytest test_file.py::test_with_debug -s  # -s = show print statements
```

### Use Debugger
```python
import pdb

def test_with_breakpoint():
    report = orchestrator.generate_daily_report(user_id, date.today())
    
    # Break here
    pdb.set_trace()
    
    # Then in debugger:
    # p report.cycle_state.cycle_day
    # p report.current_predictions['mood']
    # c (continue)
```

Run with debugger:
```bash
pytest test_file.py -pdb  # Drop to debugger on failure
```

### Verbose Output
```bash
pytest -vv                 # Very verbose
pytest -vv -s              # Verbose + show prints
pytest --tb=long           # Long traceback
pytest --tb=native         # Native Python traceback
```

---

## Expected Test Results

### Successful Test Run
```
tests/test_cycle_tracker.py ✓ 8 passed
tests/test_phase_detector.py ✓ 12 passed
tests/test_pattern_engine.py ✓ 10 passed
tests/test_learning_engine.py ✓ 9 passed
tests/test_predictor.py ✓ 11 passed
tests/test_insights_generator.py ✓ 6 passed
tests/test_relationship_suggester.py ✓ 5 passed
tests/test_wellness_engine.py ✓ 7 passed
tests/test_orchestrator.py ✓ 4 passed
tests/test_edge_cases.py ✓ 15 passed

======================== 87 passed in 2.34s ========================
Coverage: 94% (src module covered)
```

---

## Troubleshooting

### Test Import Errors
```
ImportError: No module named 'src'
```
**Solution**: Run pytest from project root
```bash
cd /path/to/MensturalTracker
pytest tests/
```

### SQLite Lock / Database Errors
```
sqlite3.OperationalError: database is locked
```
**Solution**: Use in-memory DB for tests
```python
repo = Repository(":memory:")  # ← Use this for testing
```

### Flaky Tests (Pass sometimes, fail sometimes)
**Usually caused by**: Date-dependent tests, randomness, timing
**Solution**:
```python
@freeze_time("2026-04-01")  # Pin time
def test_date_dependent():
    report = orchestrator.generate_daily_report(...)
```

### Assertion Failures
```
AssertionError: predicted_value not in range
```
**Debug**:
```python
def test_something():
    pred = predictor.predict_symptom(...)
    print(f"Value: {pred.value}, Range: {pred.min_range}-{pred.max_range}")
    assert pred.min_range <= pred.value <= pred.max_range
```

---

## Next Steps

1. **Run existing tests**: `pytest tests/`
2. **Add your own**: Create test cases for specific scenarios
3. **Measure coverage**: `pytest --cov=src tests/`
4. **Fix any failures**: Debug and refactor
5. **Set up CI/CD**: Automate testing on git push

---

For detailed testing patterns and best practices, see the test files themselves—they contain extensive inline documentation and examples.
