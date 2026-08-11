"""
Learning Engine - Adaptive learning and trend extraction

Responsible for:
- Applying recency weighting to data
- Extracting trends over time
- Computing user confidence factors
- Detecting improvements/declines
"""

from datetime import date, timedelta
from typing import Dict, List, Optional
import statistics

from src.data.models import Phase, DailyLog, Trend
from src.data.repository import Repository
from src.utils.helper_functions import (
    calculate_recency_weight, calculate_user_confidence_factor,
    calculate_data_consistency, calculate_data_freshness,
    get_phase_by_cycle_day, TREND_WINDOW_DAYS, RECENCY_HALF_LIFE_DAYS,
    CONFIDENCE_DATA_THRESHOLD
)
from src.core.cycle_tracker import CycleTracker
from src.ai.pattern_engine import PatternEngine


class LearningEngine:
    """Manages adaptive learning and trend extraction"""
    
    def __init__(self, repository: Repository, cycle_tracker: CycleTracker, pattern_engine: PatternEngine):
        self.repo = repository
        self.cycle_tracker = cycle_tracker
        self.pattern_engine = pattern_engine
    
    # ========================================================================
    # LOG WEIGHTING
    # ========================================================================
    
    def weight_logs(
        self,
        logs: List[DailyLog],
        recency_factor: int = RECENCY_HALF_LIFE_DAYS
    ) -> List[tuple]:
        """
        Assign recency weights to logs
        
        Weights recent logs higher using exponential decay
        
        Args:
            logs: List of DailyLog objects
            recency_factor: Half-life in days
        
        Returns:
            List of (log, weight) tuples
        """
        reference_date = date.today()
        weighted_logs = []
        
        for log in logs:
            days_old = (reference_date - log.date).days
            weight = calculate_recency_weight(days_old, recency_factor)
            weighted_logs.append((log, weight))
        
        return weighted_logs
    
    def get_weighted_average(
        self,
        logs: List[DailyLog],
        metric: str,
        recency_factor: int = RECENCY_HALF_LIFE_DAYS
    ) -> Optional[float]:
        """
        Calculate weighted average for a metric across logs
        
        Args:
            logs: List of DailyLog objects
            metric: "mood", "energy", "pain", "sleep", or "stress"
            recency_factor: Half-life in days
        
        Returns:
            Weighted average or None
        """
        weighted_logs = self.weight_logs(logs, recency_factor)
        
        values = []
        weights = []
        
        for log, weight in weighted_logs:
            value = getattr(log.symptoms, metric, None)
            if value is not None:
                values.append(value)
                weights.append(weight)
        
        if not values:
            return None
        
        total_weight = sum(weights)
        if total_weight == 0:
            return None
        
        return sum(v * w for v, w in zip(values, weights)) / total_weight
    
    # ========================================================================
    # TREND EXTRACTION
    # ========================================================================
    
    def extract_trend(
        self,
        user_id: str,
        metric: str,
        phase: Optional[Phase] = None,
        window_days: int = TREND_WINDOW_DAYS
    ) -> Optional[float]:
        """
        Extract trend for a metric (positive = improving, negative = declining)
        
        Args:
            user_id: User ID
            metric: "mood", "energy", "pain", "sleep", or "stress"
            phase: Optional phase filter
            window_days: Window size for trend calculation
        
        Returns:
            Trend value (positive/negative) or None
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        if len(logs) < 3:
            return None
        
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return None
        
        cycle_length = user.cycle_settings.typical_cycle_length
        
        # Add cycle_day
        for log in logs:
            log.cycle_day = self.cycle_tracker.get_cycle_day(user_id, log.date)
        
        # Filter by phase if specified
        if phase:
            logs = [log for log in logs if get_phase_by_cycle_day(log.cycle_day, cycle_length) == phase]
        
        if len(logs) < 3:
            return None
        
        # Split into recent and older
        cutoff_date = date.today() - timedelta(days=window_days)
        recent_logs = [log for log in logs if log.date >= cutoff_date]
        older_logs = [log for log in logs if log.date < cutoff_date]
        
        if not recent_logs or not older_logs:
            return None
        
        # Calculate averages
        recent_avg = self.get_weighted_average(recent_logs, metric)
        older_avg = self.get_weighted_average(older_logs, metric)
        
        if recent_avg is None or older_avg is None:
            return None
        
        # For pain, negative trend = improving
        if metric == "pain":
            return older_avg - recent_avg
        else:
            return recent_avg - older_avg
    
    def get_all_trends(
        self,
        user_id: str,
        window_days: int = TREND_WINDOW_DAYS
    ) -> Dict[str, Optional[float]]:
        """
        Get trends for all metrics
        
        Args:
            user_id: User ID
            window_days: Trend window
        
        Returns:
            Dict of metric -> trend value
        """
        metrics = ["mood", "energy", "pain", "sleep", "stress"]
        trends = {}
        
        for metric in metrics:
            trends[metric] = self.extract_trend(user_id, metric, window_days=window_days)
        
        return trends
    
    # ========================================================================
    # CONFIDENCE CALCULATION
    # ========================================================================
    
    def get_user_confidence_factor(self, user_id: str) -> float:
        """
        Calculate how much we should trust predictions for this user
        
        Based on amount of historical data
        
        Args:
            user_id: User ID
        
        Returns:
            Confidence factor (0-1)
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        num_logs = len(logs)
        
        return calculate_user_confidence_factor(num_logs, CONFIDENCE_DATA_THRESHOLD)
    
    def get_data_consistency_score(self, user_id: str) -> float:
        """
        Calculate consistency of cycle and data quality
        
        Args:
            user_id: User ID
        
        Returns:
            Consistency score (0-1)
        """
        cycles = self.cycle_tracker.get_cycle_history(user_id)
        
        cycle_lengths = [c.length for c in cycles if c.length is not None and c.length > 0]
        
        if len(cycle_lengths) < 2:
            return 0.0
        
        return calculate_data_consistency(cycle_lengths)
    
    def get_data_freshness(self, user_id: str, window: int = 30) -> float:
        """
        Calculate how fresh user's data is (based on recency of logs)
        
        Args:
            user_id: User ID
            window: Time window in days
        
        Returns:
            Freshness score (0-1)
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        
        if not logs:
            return 0.0
        
        most_recent = max(logs, key=lambda l: l.date)
        days_since = (date.today() - most_recent.date).days
        
        return calculate_data_freshness(days_since, window)
    
    # ========================================================================
    # PHASE-SPECIFIC LEARNING
    # ========================================================================
    
    def get_phase_pattern_quality(self, user_id: str, phase: Phase) -> float:
        """
        Calculate quality/confidence of phase pattern
        
        Higher consistency = higher quality
        
        Args:
            user_id: User ID
            phase: Phase
        
        Returns:
            Quality score (0-1)
        """
        pattern = self.pattern_engine.analyze_phase_patterns(user_id, phase)
        
        if pattern.sample_size < 3:
            return 0.0
        
        # Use confidence_quality from PhasePattern
        return pattern.confidence_quality()
    
    def get_all_phase_qualities(self, user_id: str) -> Dict[Phase, float]:
        """
        Get pattern quality for all phases
        
        Args:
            user_id: User ID
        
        Returns:
            Dict of Phase -> quality score
        """
        qualities = {}
        for phase in Phase:
            qualities[phase] = self.get_phase_pattern_quality(user_id, phase)
        
        return qualities
    
    # ========================================================================
    # PATTERN REFINEMENT
    # ========================================================================
    
    def detect_pattern_shift(self, user_id: str, phase: Phase, threshold: float = 0.5) -> bool:
        """
        Detect if user's patterns for a phase have shifted significantly
        
        Args:
            user_id: User ID
            phase: Phase to check
            threshold: How much change indicates a shift
        
        Returns:
            True if pattern has shifted
        """
        deviations = self.pattern_engine.get_recent_vs_average(user_id, phase, window_days=30)
        
        if not deviations:
            return False
        
        # Check if any metric has shifted by threshold
        for metric, delta in deviations.items():
            if abs(delta) >= threshold:
                return True
        
        return False
    
    def get_all_pattern_shifts(self, user_id: str) -> Dict[Phase, bool]:
        """
        Detect pattern shifts for all phases
        
        Args:
            user_id: User ID
        
        Returns:
            Dict of Phase -> has_shifted
        """
        shifts = {}
        for phase in Phase:
            shifts[phase] = self.detect_pattern_shift(user_id, phase)
        
        return shifts
    
    # ========================================================================
    # ADAPTIVE LEARNING STATE
    # ========================================================================
    
    def get_learning_state(self, user_id: str) -> Dict:
        """
        Get complete learning state for a user
        
        Args:
            user_id: User ID
        
        Returns:
            Dict with all learning metrics
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        
        return {
            "num_logs": len(logs),
            "user_confidence_factor": self.get_user_confidence_factor(user_id),
            "data_consistency": self.get_data_consistency_score(user_id),
            "data_freshness": self.get_data_freshness(user_id),
            "phase_qualities": self.get_all_phase_qualities(user_id),
            "trends": self.get_all_trends(user_id),
            "pattern_shifts": self.get_all_pattern_shifts(user_id),
        }
    
    # ========================================================================
    # CONFIDENCE INSIGHTS
    # ========================================================================
    
    def get_prediction_confidence_explanation(self, user_id: str) -> str:
        """
        Get human-readable explanation of prediction confidence
        
        Args:
            user_id: User ID
        
        Returns:
            Explanation string
        """
        state = self.get_learning_state(user_id)
        
        num_logs = state["num_logs"]
        user_conf = state["user_confidence_factor"]
        consistency = state["data_consistency"]
        
        if num_logs == 0:
            return "No historical data; using population defaults. Start logging for personalized predictions."
        elif num_logs < 10:
            return f"Limited data ({num_logs} logs); predictions are exploratory. Predictions improve with more logs."
        elif num_logs < 30:
            return f"Moderate data ({num_logs} logs); gaining confidence. Continue logging for better accuracy."
        elif num_logs < 100:
            return f"Good data ({num_logs} logs, {consistency:.0%} consistency); predictions are reliable."
        else:
            return f"Excellent data ({num_logs} logs, {consistency:.0%} consistency); predictions are highly reliable."
    
    def is_low_confidence_user(self, user_id: str, threshold: float = 0.4) -> bool:
        """
        Check if user has low prediction confidence
        
        Args:
            user_id: User ID
            threshold: Confidence threshold
        
        Returns:
            True if below threshold
        """
        conf = self.get_user_confidence_factor(user_id)
        return conf < threshold
    
    # ========================================================================
    # DATA QUALITY ASSESSMENT
    # ========================================================================
    
    def assess_data_quality(self, user_id: str) -> Dict:
        """
        Comprehensive data quality assessment
        
        Args:
            user_id: User ID
        
        Returns:
            Dict with quality metrics
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        cycles = self.cycle_tracker.get_cycle_history(user_id)
        
        # Calculate coverage
        if not logs:
            coverage = 0.0
        else:
            date_range = (logs[-1].date - logs[0].date).days + 1
            coverage = len(logs) / max(1, date_range)
        
        # Calculate completeness of entries
        complete_entries = sum(
            1 for log in logs if all([
                log.symptoms.mood is not None,
                log.symptoms.energy is not None,
                log.symptoms.pain is not None
            ])
        )
        
        completeness = complete_entries / len(logs) if logs else 0.0
        
        # Get cycle patterns
        cycle_lengths = [c.length for c in cycles if c.length is not None and c.length > 0]
        cycle_regularity = 0.0
        if len(cycle_lengths) >= 2:
            mean_length = statistics.mean(cycle_lengths)
            variance = statistics.variance(cycle_lengths)
            import math
            cycle_regularity = min(1.0, math.exp(-math.sqrt(variance) / mean_length)) if mean_length > 0 else 0.0
        
        return {
            "total_logs": len(logs),
            "coverage_percent": coverage,
            "entry_completeness": completeness,
            "cycle_regularity": cycle_regularity,
            "num_complete_cycles": len([c for c in cycles if c.length is not None and c.length > 0]),
            "overall_quality": (coverage + completeness + cycle_regularity) / 3,
        }
