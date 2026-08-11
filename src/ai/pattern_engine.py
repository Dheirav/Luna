"""
Pattern Engine - Learns and analyzes user patterns

Responsible for:
- Aggregating historical logs by phase
- Computing phase baselines (averages, std devs)
- Building frequency distributions
- Detecting irregularities and anomalies
"""

from datetime import date, timedelta
from typing import Dict, List, Optional
import statistics
import uuid

from src.data.models import (
    Phase, DailyLog, PhasePattern, PhaseMetrics
)
from src.data.repository import Repository
from src.utils.helper_functions import (
    get_phase_by_cycle_day, weighted_average, weighted_std_dev,
    calculate_recency_weight, partition_logs_by_phase,
    detect_outlier, detect_cycle_irregularity,
    POPULATION_DEFAULTS, MIN_LOGS_FOR_PATTERN, RECENCY_HALF_LIFE_DAYS
)
from src.core.cycle_tracker import CycleTracker


class PatternEngine:
    """Learns and analyzes patterns from user logs"""
    
    def __init__(self, repository: Repository, cycle_tracker: CycleTracker):
        self.repo = repository
        self.cycle_tracker = cycle_tracker
    
    # ========================================================================
    # PATTERN ANALYSIS
    # ========================================================================
    
    def analyze_phase_patterns(self, user_id: str, phase: Phase) -> PhasePattern:
        """
        Analyze all logs for a specific phase and create pattern
        
        Args:
            user_id: User ID
            phase: Phase to analyze
        
        Returns:
            PhasePattern with aggregated statistics
        """
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return self._create_empty_pattern(user_id, phase)
        
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        if not logs:
            return self._create_empty_pattern(user_id, phase)
        
        cycle_length = user.cycle_settings.typical_cycle_length
        
        # Add cycle_day to each log based on date
        for log in logs:
            log.cycle_day = self.cycle_tracker.get_cycle_day(user_id, log.date)
        
        # Filter logs for this phase
        phase_logs = [log for log in logs if get_phase_by_cycle_day(log.cycle_day, cycle_length) == phase]
        
        if len(phase_logs) < MIN_LOGS_FOR_PATTERN:
            return self._create_empty_pattern(user_id, phase)
        
        # Calculate weights (recency-based)
        reference_date = date.today()
        weights = []
        for log in phase_logs:
            days_old = (reference_date - log.date).days
            weight = calculate_recency_weight(days_old, RECENCY_HALF_LIFE_DAYS)
            weights.append(weight)
        
        # Extract metric values
        moods = [log.symptoms.mood for log in phase_logs if log.symptoms.mood is not None]
        energies = [log.symptoms.energy for log in phase_logs if log.symptoms.energy is not None]
        pains = [log.symptoms.pain for log in phase_logs if log.symptoms.pain is not None]
        sleeps = [log.symptoms.sleep for log in phase_logs if log.symptoms.sleep is not None]
        stresses = [log.symptoms.stress for log in phase_logs if log.symptoms.stress is not None]
        
        # Use only weights for logs that have data
        mood_weights = [w for w, log in zip(weights, phase_logs) if log.symptoms.mood is not None]
        energy_weights = [w for w, log in zip(weights, phase_logs) if log.symptoms.energy is not None]
        pain_weights = [w for w, log in zip(weights, phase_logs) if log.symptoms.pain is not None]
        sleep_weights = [w for w, log in zip(weights, phase_logs) if log.symptoms.sleep is not None]
        stress_weights = [w for w, log in zip(weights, phase_logs) if log.symptoms.stress is not None]
        
        # Calculate weighted averages and std devs
        metrics = PhaseMetrics(
            avg_mood=weighted_average(moods, mood_weights) if moods else None,
            avg_energy=weighted_average(energies, energy_weights) if energies else None,
            avg_pain=weighted_average(pains, pain_weights) if pains else None,
            avg_sleep=weighted_average(sleeps, sleep_weights) if sleeps else None,
            avg_stress=weighted_average(stresses, stress_weights) if stresses else None,
            std_mood=weighted_std_dev(moods, mood_weights) if moods and len(moods) > 1 else None,
            std_energy=weighted_std_dev(energies, energy_weights) if energies and len(energies) > 1 else None,
            std_pain=weighted_std_dev(pains, pain_weights) if pains and len(pains) > 1 else None,
            std_sleep=weighted_std_dev(sleeps, sleep_weights) if sleeps and len(sleeps) > 1 else None,
            std_stress=weighted_std_dev(stresses, stress_weights) if stresses and len(stresses) > 1 else None,
        )
        
        # Build frequency map
        frequency_map = self._build_frequency_map(phase_logs)
        
        # Create pattern
        pattern = PhasePattern(
            pattern_id=str(uuid.uuid4()),
            user_id=user_id,
            phase=phase,
            sample_size=len(phase_logs),
            metrics=metrics,
            frequency_map=frequency_map
        )
        
        return pattern
    
    def get_all_patterns(self, user_id: str) -> Dict[Phase, PhasePattern]:
        """
        Analyze and get patterns for all phases
        
        Args:
            user_id: User ID
        
        Returns:
            Dict mapping Phase to PhasePattern
        """
        patterns = {}
        for phase in Phase:
            pattern = self.analyze_phase_patterns(user_id, phase)
            patterns[phase] = pattern
        
        return patterns
    
    def _create_empty_pattern(self, user_id: str, phase: Phase) -> PhasePattern:
        """Create empty pattern with population defaults"""
        return PhasePattern(
            pattern_id=str(uuid.uuid4()),
            user_id=user_id,
            phase=phase,
            sample_size=0,
            metrics=PhaseMetrics(
                avg_mood=POPULATION_DEFAULTS["mood"],
                avg_energy=POPULATION_DEFAULTS["energy"],
                avg_pain=POPULATION_DEFAULTS["pain"],
                avg_sleep=POPULATION_DEFAULTS["sleep"],
                avg_stress=POPULATION_DEFAULTS["stress"],
            ),
            frequency_map={}
        )
    
    def _build_frequency_map(self, logs: List[DailyLog]) -> Dict[str, int]:
        """Build frequency distribution for metrics"""
        freq_map = {}
        
        for log in logs:
            if log.symptoms.mood is not None:
                key = f"mood_{log.symptoms.mood}"
                freq_map[key] = freq_map.get(key, 0) + 1
            
            if log.symptoms.energy is not None:
                key = f"energy_{log.symptoms.energy}"
                freq_map[key] = freq_map.get(key, 0) + 1
            
            if log.symptoms.pain is not None:
                key = f"pain_{int(log.symptoms.pain)}"
                freq_map[key] = freq_map.get(key, 0) + 1
            
            if log.symptoms.sleep is not None:
                key = f"sleep_{log.symptoms.sleep}"
                freq_map[key] = freq_map.get(key, 0) + 1
            
            if log.symptoms.stress is not None:
                key = f"stress_{log.symptoms.stress}"
                freq_map[key] = freq_map.get(key, 0) + 1
        
        return freq_map
    
    # ========================================================================
    # PATTERN CACHING
    # ========================================================================
    
    def update_cached_patterns(self, user_id: str) -> bool:
        """
        Recompute and cache patterns for a user
        
        Args:
            user_id: User ID
        
        Returns:
            True if successful
        """
        try:
            patterns = self.get_all_patterns(user_id)
            for phase, pattern in patterns.items():
                self.repo.phase_patterns.upsert(pattern)
            return True
        except Exception as e:
            print(f"Error updating cached patterns: {e}")
            return False
    
    def get_cached_patterns(self, user_id: str) -> Dict[Phase, PhasePattern]:
        """
        Get cached patterns if they exist, otherwise compute and cache them
        
        Args:
            user_id: User ID
        
        Returns:
            Dict of cached patterns
        """
        patterns = self.repo.phase_patterns.get_for_user(user_id)
        
        # If missing any phase, recompute all
        if len(patterns) < 4:
            self.update_cached_patterns(user_id)
            patterns = self.repo.phase_patterns.get_for_user(user_id)
        
        return patterns
    
    # ========================================================================
    # IRREGULARITY DETECTION
    # ========================================================================
    
    def detect_cycle_irregularities(self, user_id: str) -> Optional[str]:
        """
        Detect cycle irregularities
        
        Args:
            user_id: User ID
        
        Returns:
            Description of irregularity or None
        """
        cycles = self.cycle_tracker.get_cycle_history(user_id)
        
        cycle_lengths = [c.length for c in cycles if c.length is not None and c.length > 0]
        
        if len(cycle_lengths) < 2:
            return None
        
        # Check most recent cycle
        recent_irregular = detect_cycle_irregularity(cycle_lengths)
        
        return recent_irregular
    
    def get_cycle_consistency_issues(self, user_id: str) -> List[str]:
        """
        Get all consistency issues detected
        
        Args:
            user_id: User ID
        
        Returns:
            List of issue descriptions
        """
        issues = []
        
        cycles = self.cycle_tracker.get_cycle_history(user_id)
        cycle_lengths = [c.length for c in cycles if c.length is not None and c.length > 0]
        
        if len(cycle_lengths) < 2:
            return issues
        
        # Check for high variance
        avg = statistics.mean(cycle_lengths)
        variance = statistics.variance(cycle_lengths) if len(cycle_lengths) > 1 else 0
        std_dev = variance ** 0.5
        
        if std_dev > 3:
            issues.append(f"High cycle length variance ({std_dev:.1f} days)")
        
        # Check for recent irregularities
        if len(cycle_lengths) >= 3:
            recent = cycle_lengths[-3:]
            recent_avg = statistics.mean(recent)
            if abs(recent_avg - avg) > 2:
                issues.append(f"Recent cycles different from average ({recent_avg:.1f} vs {avg:.1f})")
        
        return issues
    
    def detect_symptom_anomalies(self, user_id: str, phase: Phase) -> List[str]:
        """
        Detect anomalies in symptom patterns for a phase
        
        Args:
            user_id: User ID
            phase: Phase to check
        
        Returns:
            List of anomaly descriptions
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        if not logs:
            return []
        
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return []
        
        cycle_length = user.cycle_settings.typical_cycle_length
        
        # Filter for this phase
        for log in logs:
            log.cycle_day = self.cycle_tracker.get_cycle_day(user_id, log.date)
        
        phase_logs = [log for log in logs if get_phase_by_cycle_day(log.cycle_day, cycle_length) == phase]
        
        if len(phase_logs) < 5:
            return []
        
        # Get pattern
        pattern = self.analyze_phase_patterns(user_id, phase)
        
        anomalies = []
        
        # Check each log for anomalies
        for log in phase_logs[-3:]:  # Check recent 3 logs
            if log.symptoms.mood and pattern.metrics.avg_mood and pattern.metrics.std_mood:
                if detect_outlier(log.symptoms.mood, pattern.metrics.avg_mood, pattern.metrics.std_mood or 1):
                    anomalies.append(f"Unusual mood on {log.date}: {log.symptoms.mood}")
            
            if log.symptoms.energy and pattern.metrics.avg_energy and pattern.metrics.std_energy:
                if detect_outlier(log.symptoms.energy, pattern.metrics.avg_energy, pattern.metrics.std_energy or 1):
                    anomalies.append(f"Unusual energy on {log.date}: {log.symptoms.energy}")
            
            if log.symptoms.pain and pattern.metrics.avg_pain and pattern.metrics.std_pain:
                if detect_outlier(log.symptoms.pain, pattern.metrics.avg_pain, pattern.metrics.std_pain or 1):
                    anomalies.append(f"Unusual pain on {log.date}: {log.symptoms.pain}")
        
        return anomalies
    
    # ========================================================================
    # COMPARISON & DEVIATIONS
    # ========================================================================
    
    def get_recent_vs_average(self, user_id: str, phase: Phase, window_days: int = 30) -> Dict[str, float]:
        """
        Compare recent metrics to phase average
        
        Args:
            user_id: User ID
            phase: Phase to compare
            window_days: Days to consider "recent"
        
        Returns:
            Dict of metric deltas (positive = above average)
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        if not logs:
            return {}
        
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return {}
        
        cycle_length = user.cycle_settings.typical_cycle_length
        
        # Add cycle_day and filter
        for log in logs:
            log.cycle_day = self.cycle_tracker.get_cycle_day(user_id, log.date)
        
        phase_logs = [log for log in logs if get_phase_by_cycle_day(log.cycle_day, cycle_length) == phase]
        
        # Get recent logs
        cutoff_date = date.today() - timedelta(days=window_days)
        recent_logs = [log for log in phase_logs if log.date >= cutoff_date]
        
        if not recent_logs:
            return {}
        
        # Get pattern (all-time average)
        pattern = self.analyze_phase_patterns(user_id, phase)
        
        # Calculate recent averages
        recent_moods = [log.symptoms.mood for log in recent_logs if log.symptoms.mood is not None]
        recent_energies = [log.symptoms.energy for log in recent_logs if log.symptoms.energy is not None]
        recent_pains = [log.symptoms.pain for log in recent_logs if log.symptoms.pain is not None]
        recent_sleeps = [log.symptoms.sleep for log in recent_logs if log.symptoms.sleep is not None]
        recent_stresses = [log.symptoms.stress for log in recent_logs if log.symptoms.stress is not None]
        
        deltas = {}
        
        if recent_moods and pattern.metrics.avg_mood:
            deltas["mood"] = statistics.mean(recent_moods) - pattern.metrics.avg_mood
        
        if recent_energies and pattern.metrics.avg_energy:
            deltas["energy"] = statistics.mean(recent_energies) - pattern.metrics.avg_energy
        
        if recent_pains and pattern.metrics.avg_pain:
            deltas["pain"] = statistics.mean(recent_pains) - pattern.metrics.avg_pain
        
        if recent_sleeps and pattern.metrics.avg_sleep:
            deltas["sleep"] = statistics.mean(recent_sleeps) - pattern.metrics.avg_sleep
        
        if recent_stresses and pattern.metrics.avg_stress:
            deltas["stress"] = statistics.mean(recent_stresses) - pattern.metrics.avg_stress
        
        return deltas
