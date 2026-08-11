"""
Utility functions and constants for the Menstrual Cycle Tracking System
"""

import math
import statistics
from datetime import datetime, date, timedelta
from typing import List, Dict, Optional, Tuple
from enum import Enum
import yaml
from pathlib import Path

from src.data.models import Phase, Symptoms


# ============================================================================
# CONFIGURATION LOADING
# ============================================================================

def load_config(config_name: str = "defaults.yaml") -> Dict:
    """Load YAML configuration file"""
    config_path = Path(__file__).parent.parent.parent / "config" / config_name
    try:
        with open(config_path, 'r') as f:
            return yaml.safe_load(f)
    except Exception as e:
        print(f"Error loading config {config_name}: {e}")
        # Return defaults if config can't be loaded
        return {
            "cycle": {"default_cycle_length": 28, "default_period_length": 5},
            "learning": {"recency_half_life_days": 90, "min_logs_for_pattern": 3},
            "population_defaults": {"mood": 3.0, "energy": 3.0, "pain": 2.0, "sleep": 4.0, "stress": 3.0, }
        }


# Load configuration at module level
CONFIG = load_config("defaults.yaml")
RULES = load_config("rules.yaml")


# ============================================================================
# CONSTANTS
# ============================================================================

DEFAULT_CYCLE_LENGTH = CONFIG["cycle"]["default_cycle_length"]
DEFAULT_PERIOD_LENGTH = CONFIG["cycle"]["default_period_length"]
MIN_CYCLE_LENGTH = CONFIG["cycle"]["min_cycle_length"]
MAX_CYCLE_LENGTH = CONFIG["cycle"]["max_cycle_length"]

RECENCY_HALF_LIFE_DAYS = CONFIG["learning"]["recency_half_life_days"]
MIN_LOGS_FOR_PATTERN = CONFIG["learning"]["min_logs_for_pattern"]
CONFIDENCE_DATA_THRESHOLD = CONFIG["learning"]["confidence_data_threshold"]
TREND_WINDOW_DAYS = CONFIG["learning"]["trend_window_days"]

PHASE_PROPORTIONS = {
    Phase.MENSTRUATION: (
        CONFIG["phases"]["menstruation"]["start_proportion"],
        CONFIG["phases"]["menstruation"]["end_proportion"]
    ),
    Phase.FOLLICULAR: (
        CONFIG["phases"]["follicular"]["start_proportion"],
        CONFIG["phases"]["follicular"]["end_proportion"]
    ),
    Phase.OVULATION: (
        CONFIG["phases"]["ovulation"]["start_proportion"],
        CONFIG["phases"]["ovulation"]["end_proportion"]
    ),
    Phase.LUTEAL: (
        CONFIG["phases"]["luteal"]["start_proportion"],
        CONFIG["phases"]["luteal"]["end_proportion"]
    ),
}

POPULATION_DEFAULTS = CONFIG["population_defaults"]
CONFIDENCE_WEIGHTS = CONFIG["confidence"]
ANOMALY_THRESHOLD = CONFIG["anomaly"]["std_dev_threshold"]
CYCLE_VARIANCE_THRESHOLD = CONFIG["anomaly"]["cycle_variance_threshold"]


# ============================================================================
# DATE UTILITIES
# ============================================================================

def calculate_cycle_day(last_period_start: date, current_date: date, cycle_length: int) -> int:
    """
    Calculate which day of the cycle we're on (1-indexed)
    
    Args:
        last_period_start: Date when last period started
        current_date: Date to calculate for
        cycle_length: Length of user's cycle
    
    Returns:
        Cycle day (1 to cycle_length)
    """
    days_elapsed = (current_date - last_period_start).days
    cycle_day = days_elapsed % cycle_length
    if cycle_day == 0:
        cycle_day = cycle_length
    return cycle_day


def get_cycle_position(cycle_day: int, cycle_length: int) -> float:
    """
    Get normalized position in cycle (0-1)
    
    Args:
        cycle_day: Which day of cycle (1-indexed)
        cycle_length: Total cycle length
    
    Returns:
        Normalized position (0-1)
    """
    return (cycle_day - 1) / cycle_length


def get_phase_from_position(position: float) -> Phase:
    """
    Determine phase from normalized cycle position
    
    Args:
        position: Normalized position (0-1)
    
    Returns:
        Phase
    """
    for phase, (start, end) in PHASE_PROPORTIONS.items():
        if start <= position < end:
            return phase
    # Default to luteal if exactly at 1.0
    return Phase.LUTEAL


def get_phase_confidence(position: float, cycle_length: int = None) -> float:
    """
    Calculate confidence in phase assignment based on position within phase
    
    Confidence is highest at phase center, lower near boundaries
    
    Args:
        position: Normalized position (0-1)
        cycle_length: Optional cycle length (unused, kept for API stability)
    
    Returns:
        Confidence score (0-1)
    """
    for phase, (start, end) in PHASE_PROPORTIONS.items():
        if start <= position < end:
            phase_width = end - start
            phase_center = start + (phase_width / 2)
            distance_from_center = abs(position - phase_center)
            max_distance = phase_width / 2
            confidence = 1.0 - (distance_from_center / max_distance)
            return max(0.7, confidence)  # Minimum 0.7 confidence
    return 0.7


def days_until_next_period(last_period_start: date, current_date: date, cycle_length: int) -> int:
    """
    Calculate days until next period
    
    Args:
        last_period_start: Date of last period start
        current_date: Current date
        cycle_length: User's cycle length
    
    Returns:
        Number of days until next period
    """
    current_cycle_day = calculate_cycle_day(last_period_start, current_date, cycle_length)
    return cycle_length - current_cycle_day + 1


def get_last_period_date(last_period_start: date, current_date: date, cycle_length: int) -> date:
    """Get the date of the last period start (might be same as or before last_period_start if in new cycle)"""
    current_cycle_day = calculate_cycle_day(last_period_start, current_date, cycle_length)
    days_back = (current_cycle_day - 1)
    return current_date - timedelta(days=days_back)


# ============================================================================
# STATISTICAL UTILITIES
# ============================================================================

def weighted_average(values: List[float], weights: List[float]) -> Optional[float]:
    """
    Calculate weighted average
    
    Args:
        values: List of values
        weights: List of weights (must be same length as values)
    
    Returns:
        Weighted average or None if empty
    """
    if not values or not weights or len(values) != len(weights):
        return None
    
    total_weight = sum(weights)
    if total_weight == 0:
        return None
    
    return sum(v * w for v, w in zip(values, weights)) / total_weight


def weighted_std_dev(values: List[float], weights: List[float], mean: Optional[float] = None) -> Optional[float]:
    """
    Calculate weighted standard deviation
    
    Args:
        values: List of values
        weights: List of weights
        mean: Pre-calculated mean (optional)
    
    Returns:
        Weighted standard deviation or None if not enough data
    """
    if not values or len(values) < 2:
        return None
    
    if mean is None:
        mean = weighted_average(values, weights)
    
    if mean is None:
        return None
    
    total_weight = sum(weights)
    if total_weight == 0:
        return None
    
    variance = sum(w * ((v - mean) ** 2) for v, w in zip(values, weights)) / total_weight
    return math.sqrt(variance)


def calculate_recency_weight(days_old: int, half_life: int = RECENCY_HALF_LIFE_DAYS) -> float:
    """
    Calculate weight for data based on age using exponential decay
    
    Formula: weight = exp(-age_in_days / half_life)
    - At half_life days: weight = 0.5
    - At 0 days: weight = 1.0
    
    Args:
        days_old: How many days old the data is
        half_life: Half-life in days (default 90)
    
    Returns:
        Weight (0-1), higher for recent data
    """
    return math.exp(-days_old / half_life)


def coefficient_of_variation(std_dev: float, mean: float) -> Optional[float]:
    """
    Calculate coefficient of variation (std_dev / mean)
    
    Standardized measure of dispersion
    
    Args:
        std_dev: Standard deviation
        mean: Mean value
    
    Returns:
        Coefficient of variation or None if mean is 0
    """
    if mean == 0:
        return None
    return std_dev / mean


def normalize_value(value: float, min_val: float, max_val: float) -> float:
    """
    Normalize value to 0-1 range
    
    Args:
        value: Value to normalize
        min_val: Minimum possible value
        max_val: Maximum possible value
    
    Returns:
        Normalized value (0-1)
    """
    if max_val == min_val:
        return 0.5
    return (value - min_val) / (max_val - min_val)


# ============================================================================
# VALIDATION
# ============================================================================

def is_valid_mood(value: Optional[int]) -> bool:
    """Validate mood value (1-5 or None)"""
    return value is None or (isinstance(value, int) and 1 <= value <= 5)


def is_valid_energy(value: Optional[int]) -> bool:
    """Validate energy value (0-5 or None)"""
    return value is None or (isinstance(value, int) and 0 <= value <= 5)


def is_valid_pain(value: Optional[int]) -> bool:
    """Validate pain value (0-5 or None)"""
    return value is None or (isinstance(value, int) and 0 <= value <= 5)


def is_valid_sleep(value: Optional[int]) -> bool:
    """Validate sleep value (1-5 or None)"""
    return value is None or (isinstance(value, int) and 1 <= value <= 5)


def is_valid_stress(value: Optional[int]) -> bool:
    """Validate stress value (1-5 or None)"""
    return value is None or (isinstance(value, int) and 1 <= value <= 5)


def is_valid_log_entry(symptoms: Symptoms) -> bool:
    """
    Validate all symptoms in a log entry
    
    Args:
        symptoms: Symptoms object to validate
    
    Returns:
        True if all metrics are valid
    """
    return (
        is_valid_mood(symptoms.mood) and
        is_valid_energy(symptoms.energy) and
        is_valid_pain(symptoms.pain) and
        is_valid_sleep(symptoms.sleep) and
        is_valid_stress(symptoms.stress)
    )


def clamp_value(value: int, min_val: int, max_val: int) -> int:
    """Clamp value to range"""
    return max(min_val, min(max_val, value))


def clamp_symptoms(symptoms: Symptoms) -> Symptoms:
    """Clamp all symptom values to valid ranges"""
    return Symptoms(
        mood=clamp_value(symptoms.mood, 1, 5) if symptoms.mood is not None else None,
        energy=clamp_value(symptoms.energy, 0, 5) if symptoms.energy is not None else None,
        pain=clamp_value(symptoms.pain, 0, 5) if symptoms.pain is not None else None,
        sleep=clamp_value(symptoms.sleep, 1, 5) if symptoms.sleep is not None else None,
        stress=clamp_value(symptoms.stress, 1, 5) if symptoms.stress is not None else None,
    )


# ============================================================================
# DATA AGGREGATION HELPERS
# ============================================================================

def get_phase_by_cycle_day(cycle_day: int, cycle_length: int) -> Phase:
    """
    Get phase for a given cycle day
    
    Args:
        cycle_day: Day of cycle (1-indexed)
        cycle_length: Total cycle length
    
    Returns:
        Phase
    """
    position = get_cycle_position(cycle_day, cycle_length)
    return get_phase_from_position(position)


def get_phase_range_for_cycle(phase: Phase, cycle_length: int) -> Tuple[int, int]:
    """
    Get start and end days (1-indexed) for a phase in a specific cycle length
    
    Args:
        phase: Phase to get range for
        cycle_length: Total cycle length
    
    Returns:
        Tuple of (start_day, end_day) inclusive
    """
    start_prop, end_prop = PHASE_PROPORTIONS[phase]
    start_day = max(1, int(start_prop * cycle_length) + 1)
    end_day = min(cycle_length, int(end_prop * cycle_length))
    return (start_day, end_day)


def partition_logs_by_phase(logs: List, cycle_length: int) -> Dict[Phase, List]:
    """
    Partition logs into phases based on cycle_day
    
    Args:
        logs: List of DailyLog objects (must have cycle_day attribute or be sortable by date)
        cycle_length: User's cycle length
    
    Returns:
        Dictionary mapping Phase to list of logs
    """
    partitioned = {phase: [] for phase in Phase}
    
    for log in logs:
        if hasattr(log, 'cycle_day'):
            phase = get_phase_by_cycle_day(log.cycle_day, cycle_length)
        else:
            # If log doesn't have cycle_day, we can't partition it
            continue
        
        partitioned[phase].append(log)
    
    return partitioned


# ============================================================================
# CONFIDENCE SCORING
# ============================================================================

def calculate_user_confidence_factor(num_logs: int, threshold: int = CONFIDENCE_DATA_THRESHOLD) -> float:
    """
    Calculate confidence factor based on number of logs
    
    Args:
        num_logs: Total number of logs for user
        threshold: Number of logs for 0.8 confidence
    
    Returns:
        Confidence factor (0-1)
    """
    return min(num_logs / threshold, 1.0)


def calculate_data_consistency(cycle_lengths: List[int]) -> float:
    """
    Calculate consistency score for cycle lengths
    
    More consistent cycles = higher score (0-1)
    
    Args:
        cycle_lengths: List of historical cycle lengths
    
    Returns:
        Consistency score (0-1)
    """
    if len(cycle_lengths) < 2:
        return 0.0
    
    avg_length = statistics.mean(cycle_lengths)
    variance = sum((length - avg_length) ** 2 for length in cycle_lengths) / len(cycle_lengths)
    std_dev = math.sqrt(variance)
    
    # If std_dev is 0, consistency is 1.0
    if std_dev == 0:
        return 1.0
    
    # Use an exponential decay: more variance = lower score
    consistency = math.exp(-std_dev / avg_length)
    return min(1.0, max(0.0, consistency))


def calculate_data_freshness(days_since_last_log: int, window: int = 30) -> float:
    """
    Calculate freshness score based on how recent the last log is
    
    Args:
        days_since_last_log: Days since most recent log
        window: Time window in days (data older than this window gets lower score)
    
    Returns:
        Freshness score (0-1)
    """
    if days_since_last_log <= 0:
        return 1.0
    
    freshness = math.exp(-days_since_last_log / window)
    return min(1.0, max(0.0, freshness))


def calculate_overall_confidence(
    user_confidence_factor: float,
    phase_pattern_quality: float,
    data_consistency: float,
    data_freshness: float
) -> float:
    """
    Calculate overall confidence score as weighted blend
    
    Args:
        user_confidence_factor: Based on number of logs
        phase_pattern_quality: Based on pattern consistency
        data_consistency: Based on cycle regularity
        data_freshness: Based on recency of logs
    
    Returns:
        Overall confidence (0-1)
    """
    weights = {
        "user_data": CONFIDENCE_WEIGHTS["weight_user_data_factor"],
        "pattern": CONFIDENCE_WEIGHTS["weight_phase_pattern_quality"],
        "consistency": CONFIDENCE_WEIGHTS["weight_data_consistency"],
        "freshness": CONFIDENCE_WEIGHTS["weight_recency"],
    }
    
    overall = (
        weights["user_data"] * user_confidence_factor +
        weights["pattern"] * phase_pattern_quality +
        weights["consistency"] * data_consistency +
        weights["freshness"] * data_freshness
    )
    
    return min(1.0, max(CONFIDENCE_WEIGHTS["min_confidence_threshold"], overall))


# ============================================================================
# ANOMALY DETECTION
# ============================================================================

def detect_outlier(value: float, mean: float, std_dev: float, threshold: float = ANOMALY_THRESHOLD) -> bool:
    """
    Detect if value is an outlier using z-score
    
    Args:
        value: Value to check
        mean: Mean of distribution
        std_dev: Standard deviation
        threshold: Z-score threshold (default 2.0)
    
    Returns:
        True if value is outlier
    """
    if std_dev == 0:
        return False
    
    z_score = abs((value - mean) / std_dev)
    return z_score > threshold


def detect_cycle_irregularity(cycle_lengths: List[int], threshold: float = CYCLE_VARIANCE_THRESHOLD) -> Optional[str]:
    """
    Detect cycle irregularities
    
    Args:
        cycle_lengths: List of cycle lengths
        threshold: Variance threshold in days
    
    Returns:
        Description of irregularity or None if regular
    """
    if len(cycle_lengths) < 2:
        return None
    
    avg = statistics.mean(cycle_lengths)
    recent = cycle_lengths[-1]
    variance = abs(recent - avg)
    
    if variance > threshold:
        if recent < avg - threshold:
            return f"Cycle shorter than usual ({recent} days vs {avg:.1f} avg)"
        else:
            return f"Cycle longer than usual ({recent} days vs {avg:.1f} avg)"
    
    return None


# ============================================================================
# STRING FORMATTING
# ============================================================================

def format_symptom_value(metric: str, value: float) -> str:
    """Format symptom value for display"""
    if metric in ["mood", "energy", "sleep", "stress"]:
        return f"{value:.1f}/{5 if metric != 'energy' else 5}"
    elif metric == "pain":
        return f"{value:.1f}/5"
    return f"{value:.1f}"


def phase_to_display(phase: Phase) -> str:
    """Convert phase enum to display string"""
    return phase.value.replace("_", " ").title()
