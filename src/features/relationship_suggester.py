"""
Relationship Suggester - Generates relationship-aware advice

Responsible for:
- Phase-aware relationship suggestions
- Preference-based (space vs support) recommendations
- Intimacy timing advice
- Communication strategies
"""

from typing import List, Dict, Optional

from src.data.models import Phase, MoodPreference
from src.data.repository import Repository
from src.utils.helper_functions import load_config, RULES, phase_to_display


class RelationshipSuggester:
    """Generates relationship-aware suggestions"""
    
    def __init__(self, repository: Repository):
        self.repo = repository
    
    # ========================================================================
    # PHASE-BASED SUGGESTIONS
    # ========================================================================
    
    def suggest_for_phase(
        self,
        user_id: str,
        phase: Phase,
        preference: Optional[MoodPreference] = None
    ) -> List[str]:
        """
        Generate relationship suggestions based on phase and preference
        
        Args:
            user_id: User ID
            phase: Current phase
            preference: User's preference (space or support), retrieved if None
        
        Returns:
            List of suggestion strings
        """
        if preference is None:
            user = self.repo.users.get_by_id(user_id)
            if user:
                preference = user.preferences.low_mood_preference
            else:
                preference = MoodPreference.SUPPORT
        
        pref_key = preference.value
        
        # Get suggestions from config
        if RULES and "relationship_suggestions" in RULES:
            suggestions_config = RULES["relationship_suggestions"]
            
            # Get suggestions for this phase and preference
            if pref_key in suggestions_config:
                phase_key = phase.value
                if phase_key in suggestions_config[pref_key]:
                    return suggestions_config[pref_key][phase_key]
        
        # Fallback
        return self._get_default_suggestions(phase, pref_key)
    
    def get_current_phase_suggestions(self, user_id: str) -> List[str]:
        """
        Get suggestions for user's current phase
        
        Args:
            user_id: User ID
        
        Returns:
            List of suggestions
        """
        from src.core.cycle_tracker import CycleTracker
        from src.core.phase_detector import PhaseDetector
        
        # These should be injected, but for simple usage we'll create them
        repo = self.repo
        cycle_tracker = CycleTracker(repo)
        phase_detector = PhaseDetector(repo, cycle_tracker)
        
        user = repo.users.get_by_id(user_id)
        if not user:
            return []
        
        from datetime import date
        current_phase = phase_detector.get_current_phase(user_id, date.today())
        
        return self.suggest_for_phase(user_id, current_phase, user.preferences.low_mood_preference)
    
    # ========================================================================
    # INTIMACY TIMING
    # ========================================================================
    
    def suggest_intimacy_timing(self, user_id: str) -> str:
        """
        Suggest timing for intimacy based on cycle
        
        Args:
            user_id: User ID
        
        Returns:
            Suggestion string
        """
        from src.core.cycle_tracker import CycleTracker
        from src.core.phase_detector import PhaseDetector
        from src.ai.pattern_engine import PatternEngine
        
        repo = self.repo
        cycle_tracker = CycleTracker(repo)
        phase_detector = PhaseDetector(repo, cycle_tracker)
        pattern_engine = PatternEngine(repo, cycle_tracker)
        
        from datetime import date
        current_phase = phase_detector.get_current_phase(user_id, date.today())
        
        # Get pain and energy for this phase
        pattern = pattern_engine.analyze_phase_patterns(user_id, current_phase)
        
        if current_phase == Phase.OVULATION:
            return "Ovulation phase often features highest libido and comfort - a great time for intimacy if desired"
        elif current_phase == Phase.FOLLICULAR:
            return "Rising energy in follicular phase - good time to explore connection and intimacy"
        elif current_phase == Phase.MENSTRUATION:
            if pattern.metrics.avg_pain and pattern.metrics.avg_pain > 2:
                return "Menstruation can be more sensitive - comfort and communication are key; many people enjoy intimacy to manage pain"
            else:
                return "Menstruation - intimacy preferences vary; check in with each other about comfort and desires"
        else:  # Luteal
            if pattern.metrics.avg_energy and pattern.metrics.avg_energy < 3:
                return "Energy is lower in luteal phase - intimacy might focus on quality connection rather than intensity"
            else:
                return "Luteal phase can still be intimate - communication about energy and preferences helps"
    
    # ========================================================================
    # COMMUNICATION STRATEGIES
    # ========================================================================
    
    def suggest_communication(self, user_id: str, phase: Phase) -> str:
        """
        Suggest communication strategies for the phase
        
        Args:
            user_id: User ID
            phase: Current phase
        
        Returns:
            Communication suggestion
        """
        strategies = {
            Phase.MENSTRUATION: "Focus on reassurance and patience. Share what kind of support feels best (alone time vs. check-ins). Express gratitude for understanding.",
            Phase.FOLLICULAR: "This is a great time to discuss plans and bond. Energy for communication is high. Share your excitement and listen actively.",
            Phase.OVULATION: "Peak communication confidence - good time to have important conversations. Your clarity and assertiveness can help resolve issues.",
            Phase.LUTEAL: "Take time to process emotions before big conversations. Ask for space if needed, but also share what's on your mind. Patience and empathy matter.",
        }
        
        return strategies.get(phase, "Open communication about cycle needs and emotions strengthens relationships.")
    
    # ========================================================================
    # PARTNER INSIGHTS
    # ========================================================================
    
    def get_partner_insights(self, user_id: str, phase: Phase) -> Dict[str, str]:
        """
        Get insights to share with partners for empathy
        
        Args:
            user_id: User ID
            phase: Current phase
        
        Returns:
            Dict with insights
        """
        from src.ai.pattern_engine import PatternEngine
        from src.core.cycle_tracker import CycleTracker
        
        repo = self.repo
        cycle_tracker = CycleTracker(repo)
        pattern_engine = PatternEngine(repo, cycle_tracker)
        
        pattern = pattern_engine.analyze_phase_patterns(user_id, phase)
        
        insights = {
            "phase": phase_to_display(phase),
            "typical_mood": f"{pattern.metrics.avg_mood or 3:.1f}/5" if pattern.metrics.avg_mood else "Variable",
            "typical_energy": f"{pattern.metrics.avg_energy or 3:.1f}/5" if pattern.metrics.avg_energy else "Variable",
            "typical_pain": f"{pattern.metrics.avg_pain or 2:.1f}/5" if pattern.metrics.avg_pain else "Variable",
        }
        
        # Add phase-specific insight
        if phase == Phase.MENSTRUATION:
            insights["what_helps"] = "Rest, comfort, understanding, minimal demands, hot drinks/heating pads"
        elif phase == Phase.FOLLICULAR:
            insights["what_helps"] = "New activities, planning together, social time, positivity"
        elif phase == Phase.OVULATION:
            insights["what_helps"] = "Engagement, connection, adventure, recognition of achievements"
        elif phase == Phase.LUTEAL:
            insights["what_helps"] = "Space for reflection, detail work, protected time, low-pressure plans"
        
        return insights
    
    # ========================================================================
    # HELPER METHODS
    # ========================================================================
    
    def _get_default_suggestions(self, phase: Phase, preference: str) -> List[str]:
        """Get default suggestions if config not found"""
        
        defaults = {
            f"{Phase.MENSTRUATION.value}_{preference}": [
                "Comfort and self-care are priorities right now",
                "Check in about what kind of support feels best",
            ],
            f"{Phase.FOLLICULAR.value}_{preference}": [
                "Energy and mood are naturally rising - great time for connection",
                "Partner can enjoy your growing enthusiasm and optimism",
            ],
            f"{Phase.OVULATION.value}_{preference}": [
                "Peak confidence and social energy - natural time for engagement",
                "Celebrate your strength and share your achievements",
            ],
            f"{Phase.LUTEAL.value}_{preference}": [
                "Honor your need for reflection and processing",
                "Patience and flexibility help during this introspective phase",
            ],
        }
        
        key = f"{phase.value}_{preference}"
        return defaults.get(key, ["Open communication about your cycle needs builds stronger relationships"])


def _get_phase_characteristics_for_partner(phase: Phase) -> str:
    """Get characteristics explanation for partners"""
    characteristics = {
        Phase.MENSTRUATION: "Often lower energy, higher sensitivity, more inward focus. Comfort and rest are restorative.",
        Phase.FOLLICULAR: "Rising mood and energy, optimism, increased social enjoyment, readiness for new things.",
        Phase.OVULATION: "Peak confidence, highest energy, socially engaged, capable of handling stress well.",
        Phase.LUTEAL: "Details matter more, more reflection, may need more alone time, preparation for rest phase.",
    }
    return characteristics.get(phase, "")
