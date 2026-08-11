"""
Predictor - Generates symptom predictions

Responsible for:
- Predicting symptoms for specific days
- Generating confidence-aware forecasts
- Handling fallbacks for new users
- Applying trend adjustments
"""

from datetime import date, timedelta
from typing import Optional, List, Dict
import uuid

from src.data.models import (
    Phase, DayPrediction, SymptomPrediction
)
from src.data.repository import Repository
from src.utils.helper_functions import (
    calculate_overall_confidence, POPULATION_DEFAULTS,
    get_phase_by_cycle_day
)
from src.core.cycle_tracker import CycleTracker
from src.core.phase_detector import PhaseDetector
from src.ai.pattern_engine import PatternEngine
from src.ai.learning_engine import LearningEngine


class Predictor:
    """Generates predictions for symptoms"""
    
    def __init__(
        self,
        repository: Repository,
        cycle_tracker: CycleTracker,
        phase_detector: PhaseDetector,
        pattern_engine: PatternEngine,
        learning_engine: LearningEngine
    ):
        self.repo = repository
        self.cycle_tracker = cycle_tracker
        self.phase_detector = phase_detector
        self.pattern_engine = pattern_engine
        self.learning_engine = learning_engine
    
    # ========================================================================
    # SINGLE PREDICTION
    # ========================================================================
    
    def predict_symptom(
        self,
        user_id: str,
        symptom: str,
        target_date: date = None
    ) -> Optional[SymptomPrediction]:
        """
        Predict a single symptom for a target date
        
        Args:
            user_id: User ID
            symptom: "mood", "energy", "pain", "sleep", or "stress"
            target_date: Date to predict for (defaults to today)
        
        Returns:
            SymptomPrediction with value and confidence
        """
        if target_date is None:
            target_date = date.today()
        
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return None
        
        cycle_day = self.cycle_tracker.get_cycle_day(user_id, target_date)
        cycle_length = user.cycle_settings.typical_cycle_length
        phase = self.phase_detector.get_phase(cycle_day, cycle_length)
        
        # Get pattern for this phase
        pattern = self.pattern_engine.analyze_phase_patterns(user_id, phase)
        
        # Get base prediction from pattern
        base_value = getattr(pattern.metrics, f"avg_{symptom}", None)
        
        if base_value is None:
            # Use population default
            base_value = POPULATION_DEFAULTS.get(symptom, 3.0)
            base_confidence = 0.3
        else:
            # Calculate pattern quality/confidence
            base_confidence = pattern.confidence_quality()
        
        # Apply trend adjustment
        trend = self.learning_engine.extract_trend(user_id, symptom, phase=phase)
        if trend is None:
            trend = 0
        
        trend_influence = 0.3  # Trends influence 30% of prediction
        adjusted_value = base_value + (trend * trend_influence)
        
        # Clamp to valid ranges
        if symptom == "mood":
            adjusted_value = max(1.0, min(5.0, adjusted_value))
        elif symptom == "energy":
            adjusted_value = max(0.0, min(5.0, adjusted_value))
        elif symptom == "sleep":
            adjusted_value = max(1.0, min(5.0, adjusted_value))
        elif symptom == "stress":
            adjusted_value = max(1.0, min(5.0, adjusted_value))
        elif symptom == "pain":
            adjusted_value = max(0.0, min(5.0, adjusted_value))
        
        # Calculate overall confidence
        user_confidence = self.learning_engine.get_user_confidence_factor(user_id)
        data_consistency = self.learning_engine.get_data_consistency_score(user_id)
        data_freshness = self.learning_engine.get_data_freshness(user_id)
        
        overall_confidence = calculate_overall_confidence(
            user_confidence,
            pattern.confidence_quality(),
            data_consistency,
            data_freshness
        )
        
        # Determine range
        std_dev = getattr(pattern.metrics, f"std_{symptom}", None)
        if std_dev and std_dev > 0:
            min_val = adjusted_value - std_dev
            max_val = adjusted_value + std_dev
        else:
            if symptom in ["mood", "sleep", "stress"]:
                min_val = max(1.0, adjusted_value - 1.0)
                max_val = min(5.0, adjusted_value + 1.0)
            else:
                min_val = max(0.0, adjusted_value - 1.0)
                max_val = min(5.0, adjusted_value + 1.0)
        
        return SymptomPrediction(
            value=adjusted_value,
            confidence=overall_confidence,
            range=(min_val, max_val)
        )
    
    def predict_day(
        self,
        user_id: str,
        target_date: date = None
    ) -> Optional[DayPrediction]:
        """
        Predict all symptoms for a day
        
        Args:
            user_id: User ID
            target_date: Date to predict for (defaults to today)
        
        Returns:
            DayPrediction with all symptom predictions
        """
        if target_date is None:
            target_date = date.today()
        
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return None
        
        cycle_day = self.cycle_tracker.get_cycle_day(user_id, target_date)
        cycle_length = user.cycle_settings.typical_cycle_length
        phase = self.phase_detector.get_phase(cycle_day, cycle_length)
        
        # Predict all symptoms
        predicted_symptoms = {}
        for symptom in ["mood", "energy", "pain", "sleep", "stress"]:
            pred = self.predict_symptom(user_id, symptom, target_date)
            if pred:
                predicted_symptoms[symptom] = pred
        
        # Generate recommended actions (basic)
        recommended_actions = self._get_recommended_actions(user_id, phase, predicted_symptoms)
        
        return DayPrediction(
            user_id=user_id,
            date=target_date,
            cycle_day=cycle_day,
            phase=phase,
            predicted_symptoms=predicted_symptoms,
            recommended_actions=recommended_actions,
            note=f"Day {cycle_day} of {cycle_length}-day cycle ({phase.value} phase)"
        )
    
    # ========================================================================
    # MULTI-DAY FORECAST
    # ========================================================================
    
    def predict_next_n_days(
        self,
        user_id: str,
        n_days: int = 7,
        start_date: date = None
    ) -> List[DayPrediction]:
        """
        Generate predictions for next N days
        
        Args:
            user_id: User ID
            n_days: Number of days to predict
            start_date: Start date (defaults to today)
        
        Returns:
            List of DayPrediction objects
        """
        if start_date is None:
            start_date = date.today()
        
        predictions = []
        for i in range(n_days):
            target_date = start_date + timedelta(days=i)
            pred = self.predict_day(user_id, target_date)
            if pred:
                predictions.append(pred)
        
        return predictions
    
    # ========================================================================
    # PERIOD PREDICTION
    # ========================================================================
    
    def predict_next_period_date(self, user_id: str) -> Optional[Dict]:
        """
        Predict next period date with confidence range
        
        Args:
            user_id: User ID
        
        Returns:
            Dict with predicted_date, confidence, and range
        """
        cycles = self.cycle_tracker.get_cycle_history(user_id)
        if not cycles:
            return None
        
        # Get average cycle length and variance
        cycle_lengths = [c.length for c in cycles if c.length is not None and c.length > 0]
        
        if not cycle_lengths:
            return None
        
        import statistics
        avg_length = statistics.mean(cycle_lengths)
        variance = statistics.variance(cycle_lengths) if len(cycle_lengths) > 1 else 0
        std_dev = variance ** 0.5
        
        # Calculate next period date
        last_period_start = self.cycle_tracker.get_last_period_start(user_id)
        
        if not last_period_start:
            return None
        
        predicted_date = last_period_start + timedelta(days=int(avg_length))
        
        # Confidence based on consistency
        import math
        consistency_confidence = math.exp(-std_dev / avg_length) if avg_length > 0 else 0.5
        user_confidence = self.learning_engine.get_user_confidence_factor(user_id)
        overall_confidence = (consistency_confidence + user_confidence) / 2
        
        return {
            "predicted_date": predicted_date,
            "confidence": overall_confidence,
            "range": (
                predicted_date - timedelta(days=int(std_dev)),
                predicted_date + timedelta(days=int(std_dev))
            ),
            "avg_cycle_length": avg_length,
            "std_dev": std_dev,
        }
    
    # ========================================================================
    # HELPER METHODS
    # ========================================================================
    
    def _get_recommended_actions(
        self,
        user_id: str,
        phase: Phase,
        predictions: Dict[str, SymptomPrediction]
    ) -> List[str]:
        """Get recommended actions based on phase and predictions"""
        actions = []
        
        # Energy-based recommendations
        if "energy" in predictions:
            energy_pred = predictions["energy"]
            if energy_pred.value < 2:
                actions.append("Prioritize rest and recovery today")
            elif energy_pred.value > 4:
                actions.append("Good day for high-intensity activities")
        
        # Pain-based recommendations
        if "pain" in predictions:
            pain_pred = predictions["pain"]
            if pain_pred.value > 3:
                actions.append("Consider pain management strategies")
        
        # Mood-based recommendations
        if "mood" in predictions:
            mood_pred = predictions["mood"]
            user = self.repo.users.get_by_id(user_id)
            if mood_pred.value < 3 and user:
                pref = user.preferences.low_mood_preference.value
                if pref == "support":
                    actions.append("Reach out for support and connection")
                else:
                    actions.append("Prioritize alone time and self-care")
        
        return actions
    
    def get_prediction_reliability(self, user_id: str) -> str:
        """
        Get a qualitative assessment of prediction reliability
        
        Args:
            user_id: User ID
        
        Returns:
            Reliability assessment string
        """
        state = self.learning_engine.get_learning_state(user_id)
        
        avg_quality = sum(state["phase_qualities"].values()) / 4
        user_conf = state["user_confidence_factor"]
        freshness = state["data_freshness"]
        
        combined_score = (avg_quality + user_conf + freshness) / 3
        
        if combined_score < 0.3:
            return "Low - Limited historical data. Use predictions as guides only."
        elif combined_score < 0.5:
            return "Moderate - Some data available, but patterns still forming. Recommendations exploratory."
        elif combined_score < 0.7:
            return "Good - Solid data foundation. Predictions reasonably reliable."
        elif combined_score < 0.85:
            return "High - Strong data patterns. Predictions quite reliable."
        else:
            return "Very High - Excellent data history. Predictions highly reliable."
