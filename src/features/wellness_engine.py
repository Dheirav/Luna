"""
Wellness Engine - Generates health and lifestyle suggestions

Responsible for:
- Exercise recommendations by phase
- Nutrition suggestions
- Stress management strategies
- Activity recommendations by energy level
"""

from typing import List, Dict, Optional

from src.data.models import Phase, DayPrediction, SymptomPrediction
from src.data.repository import Repository
from src.utils.helper_functions import load_config, RULES, phase_to_display


class WellnessEngine:
    """Generates wellness and lifestyle recommendations"""
    
    def __init__(self, repository: Repository):
        self.repo = repository
    
    # ========================================================================
    # EXERCISE RECOMMENDATIONS
    # ========================================================================
    
    def suggest_exercise(self, user_id: str, phase: Optional[Phase] = None) -> str:
        """
        Get exercise recommendations for phase
        
        Args:
            user_id: User ID
            phase: Phase (current phase used if None)
        
        Returns:
            Exercise recommendation string
        """
        if phase is None:
            from datetime import date
            from src.core.cycle_tracker import CycleTracker
            from src.core.phase_detector import PhaseDetector
            
            repo = self.repo
            cycle_tracker = CycleTracker(repo)
            phase_detector = PhaseDetector(repo, cycle_tracker)
            phase = phase_detector.get_current_phase(user_id, date.today())
        
        if RULES and "wellness_exercise" in RULES:
            exercises = RULES["wellness_exercise"].get(phase.value, [])
            if exercises:
                return " | ".join(exercises)
        
        # Fallback
        return self._get_default_exercise(phase)
    
    def get_exercise_by_energy(self, energy_level: float) -> List[str]:
        """
        Get exercise recommendations by energy level
        
        Args:
            energy_level: Energy 0-5
        
        Returns:
            List of exercise suggestions
        """
        if RULES and "activities_by_energy" in RULES:
            if energy_level >= 4:
                return RULES["activities_by_energy"].get("high_energy", [])
            elif energy_level >= 2:
                return RULES["activities_by_energy"].get("moderate_energy", [])
            else:
                return RULES["activities_by_energy"].get("low_energy", [])
        
        # Fallback
        if energy_level >= 4:
            return [
                "High-intensity interval training (HIIT)",
                "Running or intense cardio",
                "Competitive sports",
                "Strength training",
            ]
        elif energy_level >= 2:
            return [
                "Yoga or Pilates",
                "Hiking",
                "Cycling",
                "Moderate strength training",
            ]
        else:
            return [
                "Gentle yoga or stretching",
                "Walking",
                "Restorative movement",
                "Rest and recovery",
            ]
    
    # ========================================================================
    # NUTRITION RECOMMENDATIONS
    # ========================================================================
    
    def suggest_nutrition(self, user_id: str, phase: Optional[Phase] = None) -> str:
        """
        Get nutrition recommendations for phase
        
        Args:
            user_id: User ID
            phase: Phase (current phase used if None)
        
        Returns:
            Nutrition recommendation string
        """
        if phase is None:
            from datetime import date
            from src.core.cycle_tracker import CycleTracker
            from src.core.phase_detector import PhaseDetector
            
            repo = self.repo
            cycle_tracker = CycleTracker(repo)
            phase_detector = PhaseDetector(repo, cycle_tracker)
            phase = phase_detector.get_current_phase(user_id, date.today())
        
        if RULES and "wellness_nutrition" in RULES:
            nutrition = RULES["wellness_nutrition"].get(phase.value, [])
            if nutrition:
                return " | ".join(nutrition)
        
        # Fallback
        return self._get_default_nutrition(phase)
    
    # ========================================================================
    # STRESS MANAGEMENT
    # ========================================================================
    
    def suggest_stress_management(self, user_id: str, phase: Optional[Phase] = None, stress_level: Optional[int] = None) -> str:
        """
        Get stress management recommendations
        
        Args:
            user_id: User ID
            phase: Phase (current phase used if None)
            stress_level: Current stress level 1-5 (optional)
        
        Returns:
            Stress management recommendation
        """
        if phase is None:
            from datetime import date
            from src.core.cycle_tracker import CycleTracker
            from src.core.phase_detector import PhaseDetector
            
            repo = self.repo
            cycle_tracker = CycleTracker(repo)
            phase_detector = PhaseDetector(repo, cycle_tracker)
            phase = phase_detector.get_current_phase(user_id, date.today())
        
        recommendations = []
        
        # Phase-based recommendations
        if RULES and "stress_management" in RULES:
            phase_recommend = RULES["stress_management"].get(phase.value, [])
            recommendations.extend(phase_recommend)
        else:
            recommendations.extend(self._get_default_stress_management(phase))
        
        # High stress specific (only if stress_level provided and high)
        if stress_level and stress_level >= 4:
            recommendations.insert(0, "Priority: Take breaks and practice immediate stress relief (breathing, short walk)")
        
        return " | ".join(recommendations)
    
    # ========================================================================
    # ACTIVITY RECOMMENDATIONS
    # ========================================================================
    
    def suggest_activities(self, user_id: str, energy_level: Optional[float] = None, phase: Optional[Phase] = None) -> List[str]:
        """
        Suggest activities based on energy and phase
        
        Args:
            user_id: User ID
            energy_level: Current energy 0-5 (optional, defaults to typical for phase)
            phase: Phase (optional, defaults to current)
        
        Returns:
            List of activity suggestions
        """
        if phase is None:
            from datetime import date
            from src.core.cycle_tracker import CycleTracker
            from src.core.phase_detector import PhaseDetector
            
            repo = self.repo
            cycle_tracker = CycleTracker(repo)
            phase_detector = PhaseDetector(repo, cycle_tracker)
            phase = phase_detector.get_current_phase(user_id, date.today())
        
        # Get typical energy for phase if not provided
        if energy_level is None:
            from src.ai.pattern_engine import PatternEngine
            from src.core.cycle_tracker import CycleTracker
            
            repo = self.repo
            cycle_tracker = CycleTracker(repo)
            pattern_engine = PatternEngine(repo, cycle_tracker)
            pattern = pattern_engine.analyze_phase_patterns(user_id, phase)
            energy_level = pattern.metrics.avg_energy or 3
        
        activities = self.get_exercise_by_energy(energy_level)
        
        # Add phase-specific activities
        if phase == Phase.OVULATION:
            activities.extend(["Social events", "Leadership opportunities", "Competitive activities"])
        elif phase == Phase.FOLLICULAR:
            activities.extend(["Trying new things", "Group classes", "Skill-building"])
        elif phase == Phase.MENSTRUATION:
            activities.extend(["Self-care practices", "Comfort activities", "Restorative hobbies"])
        elif phase == Phase.LUTEAL:
            activities.extend(["Organization projects", "Planning", "Reflection activities"])
        
        return list(dict.fromkeys(activities))  # Remove duplicates while preserving order
    
    # ========================================================================
    # COMPREHENSIVE WELLNESS ADVICE
    # ========================================================================
    
    def get_daily_wellness_advice(
        self,
        user_id: str,
        phase: Phase,
        energy_prediction: Optional[SymptomPrediction] = None,
        stress_prediction: Optional[SymptomPrediction] = None,
        pain_prediction: Optional[SymptomPrediction] = None
    ) -> Dict[str, str]:
        """
        Generate comprehensive wellness advice for a day
        
        Args:
            user_id: User ID
            phase: Current phase
            energy_prediction: Optional energy prediction
            stress_prediction: Optional stress prediction
            pain_prediction: Optional pain prediction
        
        Returns:
            Dict with exercise, nutrition, stress_management, activities
        """
        energy_level = energy_prediction.value if energy_prediction else None
        stress_level = int(stress_prediction.value) if stress_prediction else None
        
        return {
            "exercise": self.suggest_exercise(user_id, phase),
            "nutrition": self.suggest_nutrition(user_id, phase),
            "stress_management": self.suggest_stress_management(user_id, phase, stress_level),
            "activities": self.suggest_activities(user_id, energy_level, phase),
        }
    
    # ========================================================================
    # HELPER METHODS
    # ========================================================================
    
    def _get_default_exercise(self, phase: Phase) -> str:
        """Get default exercise recommendations"""
        defaults = {
            Phase.MENSTRUATION: "Gentle yoga, stretching, light walks, rest days are okay",
            Phase.FOLLICULAR: "Gradually increase intensity, try group classes, build endurance",
            Phase.OVULATION: "Peak capability - HIIT, intense cardio, strength training, competitive sports",
            Phase.LUTEAL: "Moderate, consistent exercise, cycling, steady cardio, strength with control",
        }
        return defaults.get(phase, "Exercise that feels good today is best exercise")
    
    def _get_default_nutrition(self, phase: Phase) -> str:
        """Get default nutrition recommendations"""
        defaults = {
            Phase.MENSTRUATION: "Iron-rich foods (red meat, spinach), warm foods, magnesium (dark chocolate, nuts), hydration",
            Phase.FOLLICULAR: "Lighter meals, increase vegetables and fruits, modérate protein, whole grains",
            Phase.OVULATION: "Peak estrogen - lighter meals, continue hydration, antioxidants (berries, greens), watch caffeine",
            Phase.LUTEAL: "Higher calories, complex carbs, healthy fats (avocado, nuts), calcium and magnesium for mood",
        }
        return defaults.get(phase, "Balanced, nutrient-dense meals support cycle health")
    
    def _get_default_stress_management(self, phase: Phase) -> List[str]:
        """Get default stress management recommendations"""
        defaults = {
            Phase.MENSTRUATION: ["Prioritize sleep", "Journaling", "Meditation", "Warm baths"],
            Phase.FOLLICULAR: ["Problem-solving", "Social connection", "Planning", "Active stress release"],
            Phase.OVULATION: ["Mindfulness", "Structured planning", "Boundary-setting", "Physical activity"],
            Phase.LUTEAL: ["Lower expectations", "Breathing exercises", "Processing emotions", "Protected rest time"],
        }
        return defaults.get(phase, ["Self-care practices", "Connections that matter", "Healthy boundaries"])
