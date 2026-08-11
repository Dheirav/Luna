"""
Phase Detector - Determines menstrual cycle phase

Uses proportional cycle positioning (adaptive, not hardcoded) to determine
which phase the user is in based on their cycle position.

Responsible for:
- Mapping cycle day to phase
- Computing phase boundaries
- Providing confidence scores
"""

from typing import Tuple, Dict, Optional
from datetime import date

from src.data.models import Phase, PhasePattern
from src.data.repository import Repository
from src.utils.helper_functions import (
    get_cycle_position, get_phase_from_position, get_phase_confidence,
    get_phase_by_cycle_day, get_phase_range_for_cycle,
    PHASE_PROPORTIONS
)
from src.core.cycle_tracker import CycleTracker


class PhaseDetector:
    """Detects and analyzes menstrual cycle phases"""
    
    def __init__(self, repository: Repository, cycle_tracker: CycleTracker):
        self.repo = repository
        self.cycle_tracker = cycle_tracker
    
    # ========================================================================
    # PHASE DETERMINATION
    # ========================================================================
    
    def get_phase(self, cycle_day: int, cycle_length: int) -> Phase:
        """
        Determine phase for a given cycle day
        
        Uses proportional cycle positioning (adaptive, not hardcoded)
        
        Args:
            cycle_day: Which day of cycle (1-indexed)
            cycle_length: Total cycle length
        
        Returns:
            Phase enum
        """
        position = get_cycle_position(cycle_day, cycle_length)
        return get_phase_from_position(position)
    
    def get_current_phase(self, user_id: str, target_date: date = None) -> Phase:
        """
        Get current phase for user
        
        Args:
            user_id: User ID
            target_date: Date to compute for (defaults to today)
        
        Returns:
            Current phase
        """
        if target_date is None:
            target_date = date.today()
        
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return Phase.FOLLICULAR  # Default
        
        cycle_day = self.cycle_tracker.get_cycle_day(user_id, target_date)
        cycle_length = user.cycle_settings.typical_cycle_length
        
        return self.get_phase(cycle_day, cycle_length)
    
    def get_phase_with_confidence(
        self,
        cycle_day: int,
        cycle_length: int,
        user_history_patterns: Optional[Dict[Phase, PhasePattern]] = None
    ) -> Tuple[Phase, float]:
        """
        Get phase with confidence score
        
        Confidence is higher when cycle position is clearly in a phase,
        lower when near phase boundaries.
        
        Args:
            cycle_day: Which day of cycle (1-indexed)
            cycle_length: Total cycle length
            user_history_patterns: Optional user's phase patterns for refinement
        
        Returns:
            Tuple of (Phase, confidence 0-1)
        """
        position = get_cycle_position(cycle_day, cycle_length)
        phase = get_phase_from_position(position)
        confidence = get_phase_confidence(position, cycle_length)
        
        return phase, confidence
    
    def get_current_phase_with_confidence(
        self,
        user_id: str,
        target_date: date = None
    ) -> Tuple[Phase, float]:
        """
        Get current phase and confidence for user
        
        Args:
            user_id: User ID
            target_date: Date to compute for (defaults to today)
        
        Returns:
            Tuple of (Phase, confidence)
        """
        if target_date is None:
            target_date = date.today()
        
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return Phase.FOLLICULAR, 0.6
        
        cycle_day = self.cycle_tracker.get_cycle_day(user_id, target_date)
        cycle_length = user.cycle_settings.typical_cycle_length
        
        # Optionally get user's patterns for refinement
        patterns = self.repo.phase_patterns.get_for_user(user_id)
        
        return self.get_phase_with_confidence(cycle_day, cycle_length, patterns)
    
    # ========================================================================
    # PHASE BOUNDARIES AND RANGES
    # ========================================================================
    
    def get_phase_start_day(self, phase: Phase, cycle_length: int) -> int:
        """
        Get the typical start day for a phase
        
        Args:
            phase: Phase to get start day for
            cycle_length: User's cycle length
        
        Returns:
            Start day (1-indexed)
        """
        start_prop, _ = PHASE_PROPORTIONS[phase]
        return max(1, int(start_prop * cycle_length) + 1)
    
    def get_phase_end_day(self, phase: Phase, cycle_length: int) -> int:
        """
        Get the typical end day for a phase
        
        Args:
            phase: Phase to get end day for
            cycle_length: User's cycle length
        
        Returns:
            End day (1-indexed, inclusive)
        """
        _, end_prop = PHASE_PROPORTIONS[phase]
        return min(cycle_length, max(1, int(end_prop * cycle_length)))
    
    def get_phase_range(self, phase: Phase, cycle_length: int) -> Tuple[int, int]:
        """
        Get start and end days (1-indexed) for a phase
        
        Args:
            phase: Phase
            cycle_length: Cycle length
        
        Returns:
            Tuple of (start_day, end_day) inclusive
        """
        return get_phase_range_for_cycle(phase, cycle_length)
    
    def get_all_phase_ranges(self, cycle_length: int) -> Dict[Phase, Tuple[int, int]]:
        """
        Get day ranges for all phases
        
        Args:
            cycle_length: Cycle length
        
        Returns:
            Dict mapping Phase to (start_day, end_day)
        """
        return {phase: self.get_phase_range(phase, cycle_length) for phase in Phase}
    
    def get_phase_length(self, phase: Phase, cycle_length: int) -> int:
        """
        Get number of days in a phase for given cycle length
        
        Args:
            phase: Phase
            cycle_length: Cycle length
        
        Returns:
            Number of days in phase
        """
        start, end = self.get_phase_range(phase, cycle_length)
        return end - start + 1
    
    # ========================================================================
    # PHASE INFORMATION
    # ========================================================================
    
    def get_phase_description(self, phase: Phase) -> str:
        """Get human-readable phase description"""
        descriptions = {
            Phase.MENSTRUATION: "Menstruation phase - rest and recuperation",
            Phase.FOLLICULAR: "Follicular phase - rising energy and mood",
            Phase.OVULATION: "Ovulation phase - peak energy and confidence",
            Phase.LUTEAL: "Luteal phase - inward focus, preparation for menstruation",
        }
        return descriptions.get(phase, str(phase))
    
    def get_typical_characteristics(self, phase: Phase) -> Dict[str, str]:
        """Get typical characteristics of a phase"""
        characteristics = {
            Phase.MENSTRUATION: {
                "mood": "Low, introspective",
                "energy": "Low",
                "pain": "High",
                "exercise": "Gentle, restorative",
                "focus": "Rest and recovery",
            },
            Phase.FOLLICULAR: {
                "mood": "Rising, optimistic",
                "energy": "Gradually increasing",
                "pain": "Low",
                "exercise": "Gradually increasing intensity",
                "focus": "New projects, growth",
            },
            Phase.OVULATION: {
                "mood": "Peak, confident",
                "energy": "Peak",
                "pain": "Low",
                "exercise": "High-intensity workouts",
                "focus": "Social, assertive activities",
            },
            Phase.LUTEAL: {
                "mood": "Declining, introspective",
                "energy": "Declining",
                "pain": "Gradually increasing",
                "exercise": "Moderate, consistent",
                "focus": "Detail work, consolidation",
            },
        }
        return characteristics.get(phase, {})
    
    # ========================================================================
    # PHASE TRANSITIONS
    # ========================================================================
    
    def is_near_phase_boundary(self, cycle_day: int, cycle_length: int, threshold: int = 1) -> bool:
        """
        Check if cycle_day is near a phase boundary
        
        Args:
            cycle_day: Cycle day (1-indexed)
            cycle_length: Cycle length
            threshold: Days from boundary to consider "near"
        
        Returns:
            True if near boundary
        """
        position = get_cycle_position(cycle_day, cycle_length)
        
        # Check if position is close to any boundary
        boundaries = [b for phase, (b, _) in PHASE_PROPORTIONS.items()]
        
        for boundary in boundaries:
            distance = abs(position - boundary) * cycle_length
            if distance < threshold:
                return True
        
        return False
    
    def get_phase_transition_info(self, cycle_day: int, cycle_length: int) -> Optional[Dict]:
        """
        Get information about upcoming phase transition if near boundary
        
        Args:
            cycle_day: Cycle day
            cycle_length: Cycle length
        
        Returns:
            Dict with transition info or None if not near boundary
        """
        if not self.is_near_phase_boundary(cycle_day, cycle_length, threshold=2):
            return None
        
        current_phase = self.get_phase(cycle_day, cycle_length)
        
        # Find next phase
        positions = [(phase, start) for phase, (start, _) in PHASE_PROPORTIONS.items()]
        positions.sort(key=lambda x: x[1])
        
        current_pos = get_cycle_position(cycle_day, cycle_length)
        next_phase = None
        
        for phase, start_pos in positions:
            if start_pos > current_pos:
                next_phase = phase
                break
        
        if not next_phase:
            next_phase = positions[0][0]  # Wrap around to first phase
        
        return {
            "current_phase": current_phase,
            "next_phase": next_phase,
            "days_until_transition": self._estimate_days_to_transition(
                cycle_day, current_phase, next_phase, cycle_length
            ),
        }
    
    def _estimate_days_to_transition(
        self,
        cycle_day: int,
        current_phase: Phase,
        next_phase: Phase,
        cycle_length: int
    ) -> int:
        """Estimate days until phase transition"""
        next_phase_start = self.get_phase_start_day(next_phase, cycle_length)
        current_position_in_cycle = cycle_day
        
        if next_phase_start > current_position_in_cycle:
            return next_phase_start - current_position_in_cycle
        else:
            # Wrapping around to next cycle
            return (cycle_length - current_position_in_cycle) + next_phase_start
