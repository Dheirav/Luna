"""
Insights Generator - Generates pattern insights and anomaly analysis

Responsible for:
- Analyzing user patterns
- Generating phase-specific insights
- Detecting anomalies
- Extracting trends in natural language
"""

import uuid
from typing import List, Dict, Optional
import statistics

from src.data.models import Phase, Insight
from src.data.repository import Repository
from src.utils.helper_functions import (
    phase_to_display, get_phase_by_cycle_day
)
from src.ai.pattern_engine import PatternEngine
from src.ai.learning_engine import LearningEngine
from src.core.cycle_tracker import CycleTracker


class InsightsGenerator:
    """Generates insights from patterns"""
    
    def __init__(
        self,
        repository: Repository,
        pattern_engine: PatternEngine,
        learning_engine: LearningEngine,
        cycle_tracker: CycleTracker
    ):
        self.repo = repository
        self.pattern_engine = pattern_engine
        self.learning_engine = learning_engine
        self.cycle_tracker = cycle_tracker
    
    # ========================================================================
    # PATTERN ANALYSIS
    # ========================================================================
    
    def analyze_user_patterns(self, user_id: str) -> List[str]:
        """
        Analyze user patterns and return natural language insights
        
        Args:
            user_id: User ID
        
        Returns:
            List of insight strings
        """
        insights = []
        patterns = self.pattern_engine.get_all_patterns(user_id)
        
        # Find most and least energetic phases
        phases_by_energy = sorted(
            [(phase, pat.metrics.avg_energy) for phase, pat in patterns.items() if pat.metrics.avg_energy],
            key=lambda x: x[1],
            reverse=True
        )
        
        if phases_by_energy:
            best_phase, best_energy = phases_by_energy[0]
            insights.append(f"Your {phase_to_display(best_phase)} typically has the highest energy ({best_energy:.1f}/5)")
            
            if len(phases_by_energy) > 1:
                worst_phase, worst_energy = phases_by_energy[-1]
                insights.append(f"Your {phase_to_display(worst_phase)} typically has the lowest energy ({worst_energy:.1f}/5)")
        
        # Find phases with highest/lowest pain
        phases_by_pain = sorted(
            [(phase, pat.metrics.avg_pain) for phase, pat in patterns.items() if pat.metrics.avg_pain],
            key=lambda x: x[1]
        )
        
        if phases_by_pain:
            least_pain_phase, _ = phases_by_pain[0]
            most_pain_phase, most_pain = phases_by_pain[-1]
            insights.append(
                f"Pain is typically lowest during {phase_to_display(least_pain_phase)} ({phases_by_pain[0][1]:.1f}/5)"
            )
            insights.append(
                f"Pain typically peaks in {phase_to_display(most_pain_phase)} ({most_pain:.1f}/5)"
            )
        
        # Mood patterns
        phases_by_mood = sorted(
            [(phase, pat.metrics.avg_mood) for phase, pat in patterns.items() if pat.metrics.avg_mood],
            key=lambda x: x[1],
            reverse=True
        )
        
        if phases_by_mood:
            best_mood_phase, best_mood = phases_by_mood[0]
            insights.append(
                f"Your mood peaks during {phase_to_display(best_mood_phase)} ({best_mood:.1f}/5)"
            )
        
        # Trend analysis
        trends = self.learning_engine.get_all_trends(user_id)
        
        if trends.get("mood") and trends["mood"] > 0.3:
            insights.append("Your mood has been improving over recent cycles")
        elif trends.get("mood") and trends["mood"] < -0.3:
            insights.append("Your mood has been declining over recent cycles - consider what might help")
        
        if trends.get("energy") and trends["energy"] > 0.3:
            insights.append("Your energy levels have been trending upward recently")
        elif trends.get("energy") and trends["energy"] < -0.3:
            insights.append("Your energy levels have been declining - prioritize rest and nutrition")
        
        if trends.get("pain") and trends["pain"] > 0.3:
            insights.append("Pain has been increasing - consider new pain management strategies")
        elif trends.get("pain") and trends["pain"] < -0.3:
            insights.append("Your pain levels have been improving - continue what you're doing!")
        
        # Sleep patterns
        phases_by_sleep = sorted(
            [(phase, pat.metrics.avg_sleep) for phase, pat in patterns.items() if pat.metrics.avg_sleep],
            key=lambda x: x[1]
        )
        
        if phases_by_sleep:
            worst_sleep_phase, worst_sleep = phases_by_sleep[0]
            if worst_sleep < 3:
                insights.append(
                    f"Sleep is typically lower during {phase_to_display(worst_sleep_phase)} - prioritize rest then"
                )
        
        # Cycle irregularities
        irregularity = self.pattern_engine.detect_cycle_irregularities(user_id)
        if irregularity:
            insights.append(f"Cycle note: {irregularity}")
        
        return insights
    
    def generate_phase_summary(self, user_id: str, phase: Phase) -> str:
        """
        Generate human-readable phase summary
        
        Args:
            user_id: User ID
            phase: Phase
        
        Returns:
            Phase summary string
        """
        pattern = self.pattern_engine.analyze_phase_patterns(user_id, phase)
        
        if pattern.sample_size == 0:
            return f"No data for {phase_to_display(phase)} yet"
        
        summary_parts = [f"### {phase_to_display(phase)}"]
        summary_parts.append(f"(Based on {pattern.sample_size} cycles)")
        
        if pattern.metrics.avg_mood:
            summary_parts.append(f"- **Mood**: {pattern.metrics.avg_mood:.1f}/5")
        
        if pattern.metrics.avg_energy:
            summary_parts.append(f"- **Energy**: {pattern.metrics.avg_energy:.1f}/5")
        
        if pattern.metrics.avg_pain:
            summary_parts.append(f"- **Pain**: {pattern.metrics.avg_pain:.1f}/5")
        
        if pattern.metrics.avg_sleep:
            summary_parts.append(f"- **Sleep**: {pattern.metrics.avg_sleep:.1f}/5")
        
        if pattern.metrics.avg_stress:
            summary_parts.append(f"- **Stress**: {pattern.metrics.avg_stress:.1f}/5")
        
        # Add typical characteristics
        characteristics = self._get_phase_characteristics(phase)
        summary_parts.append(f"\nUsually: {characteristics}")
        
        return "\n".join(summary_parts)
    
    def detect_anomalies(self, user_id: str, phase: Phase) -> List[str]:
        """
        Detect anomalies in a phase
        
        Args:
            user_id: User ID
            phase: Phase to check
        
        Returns:
            List of anomaly descriptions
        """
        return self.pattern_engine.detect_symptom_anomalies(user_id, phase)
    
    # ========================================================================
    # WELLNESS HIGHLIGHTS
    # ========================================================================
    
    def get_wellness_highlights(self, user_id: str) -> List[str]:
        """
        Get positive wellness insights
        
        Args:
            user_id: User ID
        
        Returns:
            List of positive highlights
        """
        highlights = []
        patterns = self.pattern_engine.get_all_patterns(user_id)
        
        # Check for consistent good mood
        avg_moods = [p.metrics.avg_mood for p in patterns.values() if p.metrics.avg_mood]
        if avg_moods and statistics.mean(avg_moods) > 3.5:
            highlights.append("Your overall mood is consistently good")
        
        # Check for good energy
        avg_energies = [p.metrics.avg_energy for p in patterns.values() if p.metrics.avg_energy]
        if avg_energies and statistics.mean(avg_energies) > 3:
            highlights.append("You maintain good energy throughout your cycle")
        
        # Check for manageable pain
        avg_pains = [p.metrics.avg_pain for p in patterns.values() if p.metrics.avg_pain]
        if avg_pains and statistics.mean(avg_pains) < 2:
            highlights.append("Pain is well-managed throughout your cycle")
        
        # Check for good sleep
        avg_sleeps = [p.metrics.avg_sleep for p in patterns.values() if p.metrics.avg_sleep]
        if avg_sleeps and statistics.mean(avg_sleeps) > 4:
            highlights.append("Your sleep quality is consistently good")
        
        # Check for low stress
        avg_stresses = [p.metrics.avg_stress for p in patterns.values() if p.metrics.avg_stress]
        if avg_stresses and statistics.mean(avg_stresses) < 2:
            highlights.append("Stress levels are healthy throughout your cycle")
        
        # Check if improving
        trends = self.learning_engine.get_all_trends(user_id)
        if trends.get("mood") and trends["mood"] > 0.5:
            highlights.append("Your mood is noticeably improving")
        if trends.get("energy") and trends["energy"] > 0.5:
            highlights.append("Your energy is getting better")
        if trends.get("pain") and trends["pain"] > 0.5:
            highlights.append("Your pain levels are improving")
        
        return highlights if highlights else ["Keep tracking to discover patterns"]
    
    # ========================================================================
    # CYCLE STATISTICS
    # ========================================================================
    
    def get_cycle_statistics(self, user_id: str) -> Dict[str, any]:
        """
        Get overall cycle statistics
        
        Args:
            user_id: User ID
        
        Returns:
            Dict of statistics
        """
        cycles = self.cycle_tracker.get_cycle_history(user_id)
        cycle_lengths = [c.length for c in cycles if c.length is not None and c.length > 0]
        
        stats = {}
        
        if cycle_lengths:
            stats["avg_cycle_length"] = statistics.mean(cycle_lengths)
            stats["min_cycle_length"] = min(cycle_lengths)
            stats["max_cycle_length"] = max(cycle_lengths)
            stats["cycle_variance"] = statistics.variance(cycle_lengths) if len(cycle_lengths) > 1 else 0
            stats["num_cycles_tracked"] = len(cycle_lengths)
        
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        if logs:
            stats["total_logs"] = len(logs)
        
        return stats
    
    # ========================================================================
    # HELPER METHODS
    # ========================================================================
    
    def _get_phase_characteristics(self, phase: Phase) -> str:
        """Get typical characteristics description"""
        characteristics = {
            Phase.MENSTRUATION: "Lower energy, introspection, focus on self-care",
            Phase.FOLLICULAR: "Rising mood and energy, good for new projects",
            Phase.OVULATION: "Peak energy and confidence, socially outgoing",
            Phase.LUTEAL: "Planning phase, detail-oriented, preparation for menstruation",
        }
        return characteristics.get(phase, "")


def create_insights_generator(
    repository: Repository,
    pattern_engine: PatternEngine,
    learning_engine: LearningEngine,
    cycle_tracker: CycleTracker
) -> InsightsGenerator:
    """Factory function for dependency injection"""
    return InsightsGenerator(repository, pattern_engine, learning_engine, cycle_tracker)
