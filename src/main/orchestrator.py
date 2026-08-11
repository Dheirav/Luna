"""
Orchestrator - Main system coordinator

Orchestrates all modules to generate complete daily reports and insights.
"""

from datetime import date, datetime
from typing import Optional
import uuid

from src.data.models import (
    DailyReport, CycleState, DailyLog, PeriodIndicators, Symptoms,
    Alert, DataQuality, WellnessAdvice
)
from src.data.repository import Repository
from src.core.cycle_tracker import CycleTracker
from src.core.phase_detector import PhaseDetector
from src.ai.pattern_engine import PatternEngine
from src.ai.learning_engine import LearningEngine
from src.ai.predictor import Predictor
from src.features.insights_generator import InsightsGenerator
from src.features.relationship_suggester import RelationshipSuggester
from src.features.wellness_engine import WellnessEngine


class Orchestrator:
    """Main orchestrator - coordinates all modules"""
    
    def __init__(self, repository: Repository):
        self.repo = repository
        
        # Initialize all modules
        self.cycle_tracker = CycleTracker(repository)
        self.phase_detector = PhaseDetector(repository, self.cycle_tracker)
        self.pattern_engine = PatternEngine(repository, self.cycle_tracker)
        self.learning_engine = LearningEngine(repository, self.cycle_tracker, self.pattern_engine)
        self.predictor = Predictor(
            repository, self.cycle_tracker, self.phase_detector,
            self.pattern_engine, self.learning_engine
        )
        self.insights_gen = InsightsGenerator(
            repository, self.pattern_engine, self.learning_engine, self.cycle_tracker
        )
        self.relationship_suggester = RelationshipSuggester(repository)
        self.wellness_engine = WellnessEngine(repository)
    
    # ========================================================================
    # MAIN API
    # ========================================================================
    
    def generate_daily_report(self, user_id: str, target_date: date = None) -> Optional[DailyReport]:
        """
        Generate comprehensive daily report with all insights and predictions
        
        This is the main API endpoint for the system.
        
        Args:
            user_id: User ID
            target_date: Date to generate report for (defaults to today)
        
        Returns:
            Complete DailyReport or None if user not found
        """
        if target_date is None:
            target_date = date.today()
        
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return None
        
        # Step 1: Compute cycle state
        cycle_state = self._compute_cycle_state(user_id, target_date)
        
        # Step 2: Get current predictions
        current_predictions = self._get_current_predictions(user_id, target_date)
        
        # Step 3: Generate insights
        insights = self.insights_gen.analyze_user_patterns(user_id)
        
        # Step 4: Generate relationship advice
        user_pref = user.preferences.low_mood_preference if hasattr(user.preferences, 'low_mood_preference') else None
        relationship_advice = self.relationship_suggester.suggest_for_phase(
            user_id, cycle_state.phase, user_pref
        )
        
        # Step 5: Generate wellness advice
        wellness_advice = self._get_wellness_advice(user_id, cycle_state.phase, current_predictions)
        
        # Step 6: Generate upcoming predictions
        upcoming_predictions = self.predictor.predict_next_n_days(user_id, 7, target_date)
        
        # Step 7: Generate alerts
        alerts = self._generate_alerts(user_id, target_date, cycle_state)
        
        # Step 8: Calculate data quality
        data_quality = self._calculate_data_quality(user_id)
        
        # Step 9: Assemble and return report
        report = DailyReport(
            user_id=user_id,
            date=target_date,
            cycle_state=cycle_state,
            current_predictions=current_predictions,
            insights=insights,
            relationship_advice=relationship_advice,
            wellness_advice=wellness_advice,
            upcoming_predictions=upcoming_predictions,
            alerts=alerts,
            data_quality=data_quality,
            generated_at=datetime.utcnow()
        )
        
        return report
    
    # ========================================================================
    # HELPER METHODS FOR REPORT GENERATION
    # ========================================================================
    
    def _compute_cycle_state(self, user_id: str, target_date: date) -> CycleState:
        """Compute current cycle position and phase"""
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return CycleState(
                cycle_day=14,
                cycle_length=28,
                phase=None,  # Will fail gracefully
                phase_confidence=0.0,
                days_until_next_period=14
            )
        
        cycle_day = self.cycle_tracker.get_cycle_day(user_id, target_date)
        cycle_length = user.cycle_settings.typical_cycle_length
        phase, phase_confidence = self.phase_detector.get_phase_with_confidence(
            cycle_day, cycle_length
        )
        days_until = self.cycle_tracker.get_days_until_next_period(user_id, target_date)
        
        return CycleState(
            cycle_day=cycle_day,
            cycle_length=cycle_length,
            phase=phase,
            phase_confidence=phase_confidence,
            days_until_next_period=days_until
        )
    
    def _get_current_predictions(self, user_id: str, target_date: date) -> dict:
        """Get current symptom predictions"""
        from src.data.models import SymptomPrediction
        
        predictions = {}
        for symptom in ["mood", "energy", "pain", "sleep", "stress"]:
            pred = self.predictor.predict_symptom(user_id, symptom, target_date)
            if pred:
                predictions[symptom] = pred
        
        return predictions
    
    def _get_wellness_advice(self, user_id: str, phase, predictions: dict) -> WellnessAdvice:
        """Generate wellness advice for the day"""
        energy_pred = predictions.get("energy")
        stress_pred = predictions.get("stress")
        
        wellness_dict = self.wellness_engine.get_daily_wellness_advice(
            user_id, phase, energy_pred, stress_pred
        )
        
        return WellnessAdvice(
            exercise=wellness_dict.get("exercise", ""),
            nutrition=wellness_dict.get("nutrition", ""),
            stress_management=wellness_dict.get("stress_management", ""),
            activities=wellness_dict.get("activities", [])
        )
    
    def _generate_alerts(self, user_id: str, target_date: date, cycle_state) -> list:
        """Generate relevant alerts"""
        alerts = []
        
        # Upcoming period alert
        if cycle_state.days_until_next_period <= 3 and cycle_state.days_until_next_period > 0:
            alerts.append(Alert(
                alert_id=str(uuid.uuid4()),
                user_id=user_id,
                alert_type="upcoming_period",
                message=f"Period expected in about {cycle_state.days_until_next_period} days",
                severity="info"
            ))
        
        # Check for logging consistency
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        if logs:
            most_recent = max(logs, key=lambda l: l.date)
            days_since = (target_date - most_recent.date).days
            if days_since > 2:
                alerts.append(Alert(
                    alert_id=str(uuid.uuid4()),
                    user_id=user_id,
                    alert_type="data_suggestion",
                    message=f"Haven't logged in {days_since} days. Logging helps improve predictions!",
                    severity="info"
                ))
        
        # Check for anomalies
        anomalies = self.pattern_engine.detect_symptom_anomalies(user_id, cycle_state.phase)
        for anomaly in anomalies[:2]:  # Limit to 2 anomaly alerts
            alerts.append(Alert(
                alert_id=str(uuid.uuid4()),
                user_id=user_id,
                alert_type="anomaly_detected",
                message=anomaly,
                severity="warning"
            ))
        
        return alerts
    
    def _calculate_data_quality(self, user_id: str) -> DataQuality:
        """Calculate data quality metrics"""
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        cycles = self.cycle_tracker.get_cycle_history(user_id)
        
        # Logs for current cycle
        if cycles and cycles[0].end_date is None:
            current_cycle_logs = [
                log for log in logs if log.date >= cycles[0].start_date
            ]
            logs_this_cycle = len(current_cycle_logs)
        else:
            logs_this_cycle = 0
        
        # Average logs per phase
        cycle_lengths = [c.length for c in cycles if c.length]
        cycle_count = len(cycle_lengths)
        logs_per_phase_avg = len(logs) / (4 * cycle_count) if cycle_count > 0 else 0
        
        # Cycle consistency
        consistency = self.learning_engine.get_data_consistency_score(user_id)
        if consistency > 0.8:
            consistency_str = "Very regular"
        elif consistency > 0.6:
            consistency_str = "Regular (slight variation)"
        elif consistency > 0.4:
            consistency_str = "Somewhat regular (moderate variation)"
        else:
            consistency_str = "Irregular (high variation)"
        
        # Data freshness
        days_since = 0
        if logs:
            most_recent = max(logs, key=lambda l: l.date)
            days_since = (date.today() - most_recent.date).days
        
        # Overall confidence
        overall_confidence = self.learning_engine.get_user_confidence_factor(user_id)
        
        # Notes
        if len(logs) == 0:
            notes = "No historical data; using population defaults. Start logging for personalized predictions!"
        elif len(logs) < 10:
            notes = f"Limited data ({len(logs)} logs); predictions are exploratory."
        elif len(logs) < 30:
            notes = f"Moderate data ({len(logs)} logs); predictions are becoming reliable."
        else:
            notes = f"Excellent data ({len(logs)} logs); predictions are highly reliable."
        
        return DataQuality(
            overall_confidence=overall_confidence,
            user_data_points=len(logs),
            logs_this_cycle=logs_this_cycle,
            logs_per_phase_avg=logs_per_phase_avg,
            cycle_consistency=consistency_str,
            data_freshness_days=days_since,
            notes=notes
        )
    
    # ========================================================================
    # ADDITIONAL API METHODS
    # ========================================================================
    
    def log_symptoms(
        self,
        user_id: str,
        target_date: date,
        mood: Optional[int] = None,
        energy: Optional[int] = None,
        pain: Optional[int] = None,
        sleep: Optional[int] = None,
        stress: Optional[int] = None,
        is_period_day: bool = False,
        flow: Optional[str] = None,
        notes: str = ""
    ) -> bool:
        """
        Log daily symptoms for a user
        
        Args:
            user_id: User ID
            target_date: Date of log
            mood: Mood 1-5
            energy: Energy 0-5
            pain: Pain 0-5
            sleep: Sleep 1-5
            stress: Stress 1-5
            is_period_day: Is this a period day?
            flow: Period flow level (light/medium/heavy)
            notes: Optional notes
        
        Returns:
            True if logged successfully
        """
        try:
            from src.data.models import FlowLevel
            
            # Validate and clamp values
            if mood is not None:
                mood = max(1, min(5, mood))
            if energy is not None:
                energy = max(0, min(5, energy))
            if pain is not None:
                pain = max(0, min(5, pain))
            if sleep is not None:
                sleep = max(1, min(5, sleep))
            if stress is not None:
                stress = max(1, min(5, stress))
            
            # Create log entry
            log = DailyLog(
                log_id=str(uuid.uuid4()),
                user_id=user_id,
                date=target_date,
                period_indicators=PeriodIndicators(
                    is_period_day=is_period_day,
                    flow=FlowLevel(flow) if flow else None
                ),
                symptoms=Symptoms(
                    mood=mood,
                    energy=energy,
                    pain=pain,
                    sleep=sleep,
                    stress=stress
                ),
                notes=notes
            )
            
            # Save to repository
            success = self.repo.daily_logs.create(log)
            
            # If period logged, register it
            if is_period_day:
                self.cycle_tracker.register_period_start(user_id, target_date)
            
            # Update cached patterns
            self.pattern_engine.update_cached_patterns(user_id)
            
            return success
        except Exception as e:
            print(f"Error logging symptoms: {e}")
            return False
    
    def get_prediction_explanation(self, user_id: str) -> str:
        """Get explanation of prediction confidence"""
        return self.learning_engine.get_prediction_confidence_explanation(user_id)
