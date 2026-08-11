"""
Data Models for Menstrual Cycle Tracking System

Uses Pydantic for validation and serialization.
"""

from dataclasses import dataclass, field
from datetime import datetime, date
from typing import Optional, Dict, List, Literal
from enum import Enum


class Phase(str, Enum):
    """Menstrual cycle phases"""
    MENSTRUATION = "menstruation"
    FOLLICULAR = "follicular"
    OVULATION = "ovulation"
    LUTEAL = "luteal"


class FlowLevel(str, Enum):
    """Menstrual flow intensity"""
    LIGHT = "light"
    MEDIUM = "medium"
    HEAVY = "heavy"


class MoodPreference(str, Enum):
    """During low mood phases, does user prefer space or support?"""
    SPACE = "space"
    SUPPORT = "support"


# ============================================================================
# USER PROFILE
# ============================================================================

@dataclass
class UserPreferences:
    """User preferences for tracking and suggestions"""
    low_mood_preference: MoodPreference = MoodPreference.SUPPORT
    notification_timing: str = "3_days_before"
    wellness_focus: List[str] = field(default_factory=lambda: ["exercise", "nutrition", "stress_management"])


@dataclass
class CycleSettings:
    """User's typical cycle characteristics"""
    typical_cycle_length: int = 28
    typical_period_length: int = 5


@dataclass
class UserProfile:
    """User profile with settings and metadata"""
    user_id: str
    name: str
    preferences: UserPreferences = field(default_factory=UserPreferences)
    cycle_settings: CycleSettings = field(default_factory=CycleSettings)
    created_date: datetime = field(default_factory=datetime.utcnow)
    last_active: datetime = field(default_factory=datetime.utcnow)
    data_consistency_score: float = 0.0
    
    def update_activity(self):
        """Update last active timestamp"""
        self.last_active = datetime.utcnow()


# ============================================================================
# DAILY LOG
# ============================================================================

@dataclass
class PeriodIndicators:
    """Period-related information"""
    is_period_day: bool = False
    flow: Optional[FlowLevel] = None


@dataclass
class Symptoms:
    """Daily symptom tracking (1-5 scale, except pain 0-5)"""
    mood: Optional[int] = None  # 1-5
    energy: Optional[int] = None  # 0-5
    pain: Optional[int] = None  # 0-5
    sleep: Optional[int] = None  # 1-5
    stress: Optional[int] = None  # 1-5
    
    def as_dict(self) -> Dict[str, Optional[int]]:
        """Convert to dictionary"""
        return {
            "mood": self.mood,
            "energy": self.energy,
            "pain": self.pain,
            "sleep": self.sleep,
            "stress": self.stress,
        }
    
    def get_metrics(self) -> Dict[str, int]:
        """Get only non-None metrics"""
        return {k: v for k, v in self.as_dict().items() if v is not None}


@dataclass
class DailyLog:
    """Single day's log entry"""
    log_id: str
    user_id: str
    date: date
    period_indicators: PeriodIndicators = field(default_factory=PeriodIndicators)
    symptoms: Symptoms = field(default_factory=Symptoms)
    notes: str = ""
    created_date: datetime = field(default_factory=datetime.utcnow)


# ============================================================================
# CYCLE HISTORY
# ============================================================================

@dataclass
class CycleRecord:
    """Record of a completed or ongoing cycle"""
    cycle_id: str
    user_id: str
    start_date: date
    end_date: Optional[date] = None
    length: Optional[int] = None
    period_length: Optional[int] = None
    regularity_score: float = 0.0
    created_date: datetime = field(default_factory=datetime.utcnow)
    
    def is_current(self) -> bool:
        """Check if this is the current ongoing cycle"""
        return self.end_date is None


# ============================================================================
# PATTERN AGGREGATION
# ============================================================================

@dataclass
class PhaseMetrics:
    """Aggregated statistics for a phase"""
    avg_mood: Optional[float] = None
    avg_energy: Optional[float] = None
    avg_pain: Optional[float] = None
    avg_sleep: Optional[float] = None
    avg_stress: Optional[float] = None
    
    std_mood: Optional[float] = None
    std_energy: Optional[float] = None
    std_pain: Optional[float] = None
    std_sleep: Optional[float] = None
    std_stress: Optional[float] = None
    
    def as_dict(self) -> Dict:
        """Convert to dictionary"""
        return {
            "avg_mood": self.avg_mood,
            "avg_energy": self.avg_energy,
            "avg_pain": self.avg_pain,
            "avg_sleep": self.avg_sleep,
            "avg_stress": self.avg_stress,
            "std_mood": self.std_mood,
            "std_energy": self.std_energy,
            "std_pain": self.std_pain,
            "std_sleep": self.std_sleep,
            "std_stress": self.std_stress,
        }


@dataclass
class PhasePattern:
    """Learned pattern for a specific phase"""
    pattern_id: str
    user_id: str
    phase: Phase
    sample_size: int = 0
    metrics: PhaseMetrics = field(default_factory=PhaseMetrics)
    frequency_map: Dict[str, int] = field(default_factory=dict)  # {"mood_3": 5, "mood_4": 8, ...}
    last_updated: datetime = field(default_factory=datetime.utcnow)
    
    def confidence_quality(self) -> float:
        """Calculate pattern quality based on consistency"""
        if not self.metrics.std_mood or not self.metrics.avg_mood:
            return 0.0
        coefficient_of_variation = self.metrics.std_mood / self.metrics.avg_mood if self.metrics.avg_mood > 0 else 1.0
        confidence = max(0.0, 1.0 - coefficient_of_variation)
        return min(1.0, confidence)


# ============================================================================
# TRENDS
# ============================================================================

@dataclass
class Trend:
    """Trend for a specific metric in a specific phase"""
    trend_id: str
    user_id: str
    phase: Phase
    metric: str  # "mood", "energy", etc.
    trend_value: float  # positive = improving, negative = declining
    window_start_date: date
    window_end_date: date
    created_date: datetime = field(default_factory=datetime.utcnow)


# ============================================================================
# PREDICTIONS
# ============================================================================

@dataclass
class SymptomPrediction:
    """Prediction for a single symptom"""
    value: float  # predicted value (e.g., 3.5 for mood)
    confidence: float  # 0-1 confidence score
    range: tuple = field(default_factory=lambda: (0.0, 5.0))  # (min, max) possible values


@dataclass
class DayPrediction:
    """Prediction for a specific day"""
    user_id: str
    date: date
    cycle_day: int
    phase: Phase
    predicted_symptoms: Dict[str, SymptomPrediction] = field(default_factory=dict)  # {"mood": SymptomPrediction(...), ...}
    recommended_actions: List[str] = field(default_factory=list)
    note: str = ""


# ============================================================================
# INSIGHTS
# ============================================================================

@dataclass
class Insight:
    """A single insight/pattern observation"""
    insight_id: str
    user_id: str
    category: str  # "behavioral", "trend", "anomaly", "correlation"
    phase: Optional[Phase] = None
    text: str = ""
    severity: str = "info"  # "info", "warning", "alert"
    confidence: float = 0.8
    created_date: datetime = field(default_factory=datetime.utcnow)


# ============================================================================
# ALERTS
# ============================================================================

@dataclass
class Alert:
    """System alerts and notifications"""
    alert_id: str
    user_id: str
    alert_type: str  # "upcoming_period", "anomaly_detected", "data_suggestion", "cycle_milestone", "pattern_insight"
    message: str
    severity: str = "info"  # "info", "warning", "critical"
    action_suggested: Optional[str] = None
    created_date: datetime = field(default_factory=datetime.utcnow)


# ============================================================================
# FINAL REPORT
# ============================================================================

@dataclass
class CycleState:
    """Current position in cycle"""
    cycle_day: int
    cycle_length: int
    phase: Phase
    phase_confidence: float
    days_until_next_period: int


@dataclass
class WellnessAdvice:
    """Wellness recommendations"""
    exercise: str = ""
    nutrition: str = ""
    stress_management: str = ""
    activities: List[str] = field(default_factory=list)


@dataclass
class DataQuality:
    """Data quality metrics"""
    overall_confidence: float
    user_data_points: int
    logs_this_cycle: int
    logs_per_phase_avg: float
    cycle_consistency: str
    data_freshness_days: int
    notes: str


@dataclass
class DailyReport:
    """Complete daily analysis and predictions"""
    user_id: str
    date: date
    cycle_state: CycleState
    
    # Predictions
    current_predictions: Dict[str, SymptomPrediction] = field(default_factory=dict)
    
    # Insights and Advice
    insights: List[str] = field(default_factory=list)
    relationship_advice: List[str] = field(default_factory=list)
    wellness_advice: WellnessAdvice = field(default_factory=WellnessAdvice)
    
    # Future Predictions
    upcoming_predictions: List[DayPrediction] = field(default_factory=list)
    
    # Alerts
    alerts: List[Alert] = field(default_factory=list)
    
    # Quality Metrics
    data_quality: DataQuality = field(default_factory=lambda: DataQuality(overall_confidence=0.0, user_data_points=0, logs_this_cycle=0, logs_per_phase_avg=0.0, cycle_consistency="", data_freshness_days=0, notes=""))
    
    generated_at: datetime = field(default_factory=datetime.utcnow)
