"""
Cycle Tracker - Computes cycle position and cycle history management

Responsible for:
- Computing current cycle day
- Tracking period boundaries
- Maintaining cycle history
- Calculating metrics like days until next period
"""

from datetime import date, timedelta
from typing import Optional, List
import uuid

from src.data.models import DailyLog, CycleRecord
from src.data.repository import Repository
from src.utils.helper_functions import (
    calculate_cycle_day, days_until_next_period, get_last_period_date,
    DEFAULT_CYCLE_LENGTH
)


class CycleTracker:
    """Manages cycle computation and cycle history"""
    
    def __init__(self, repository: Repository):
        self.repo = repository
    
    # ========================================================================
    # CYCLE DAY COMPUTATION
    # ========================================================================
    
    def get_cycle_day(self, user_id: str, target_date: date) -> int:
        """
        Compute which day of the cycle the user is on
        
        Args:
            user_id: User ID
            target_date: Date to compute for
        
        Returns:
            Cycle day (1 to cycle_length)
        """
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return 1  # Default
        
        cycle_length = user.cycle_settings.typical_cycle_length
        last_period_start = self._get_last_period_start(user_id)
        
        if not last_period_start:
            # New user with no period logged
            # Default to cycle_day 14 (mid-cycle)
            return 14
        
        return calculate_cycle_day(last_period_start, target_date, cycle_length)
    
    def _get_last_period_start(self, user_id: str) -> Optional[date]:
        """
        Get the most recent period start date
        
        Args:
            user_id: User ID
        
        Returns:
            Date of last period start or None
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        
        # Find the most recent period log
        period_logs = [log for log in logs if log.period_indicators.is_period_day]
        
        if not period_logs:
            return None
        
        # Sort by date descending and take the most recent
        period_logs.sort(key=lambda log: log.date, reverse=True)
        most_recent = period_logs[0]
        
        # Find the start of this period (consecutive days marked as period)
        period_start = most_recent.date
        current_date = most_recent.date - timedelta(days=1)
        
        while current_date >= period_logs[-1].date - timedelta(days=10):  # Look back max 10 days
            log = self.repo.daily_logs.get_by_user_date(user_id, current_date)
            if log and log.period_indicators.is_period_day:
                period_start = current_date
                current_date -= timedelta(days=1)
            else:
                break
        
        return period_start
    
    # ========================================================================
    # CYCLE INFORMATION
    # ========================================================================
    
    def get_last_period_start(self, user_id: str) -> Optional[date]:
        """
        Get the start date of the last period
        
        Args:
            user_id: User ID
        
        Returns:
            Date of last period start or None
        """
        return self._get_last_period_start(user_id)
    
    def get_days_until_next_period(self, user_id: str, target_date: date = None) -> int:
        """
        Calculate days until next period is expected
        
        Args:
            user_id: User ID
            target_date: Reference date (defaults to today)
        
        Returns:
            Number of days until next period
        """
        if target_date is None:
            target_date = date.today()
        
        user = self.repo.users.get_by_id(user_id)
        if not user:
            return 0
        
        cycle_length = user.cycle_settings.typical_cycle_length
        last_period_start = self._get_last_period_start(user_id)
        
        if not last_period_start:
            # New user, assume next period in 14 days (mid-cycle default)
            return 14
        
        return days_until_next_period(last_period_start, target_date, cycle_length)
    
    def get_next_period_date(self, user_id: str) -> Optional[date]:
        """
        Estimate date of next period
        
        Args:
            user_id: User ID
        
        Returns:
            Estimated next period date or None
        """
        days_until = self.get_days_until_next_period(user_id)
        if days_until <= 0:
            return None
        return date.today() + timedelta(days=days_until)
    
    # ========================================================================
    # CYCLE HISTORY
    # ========================================================================
    
    def get_cycle_history(self, user_id: str) -> List[CycleRecord]:
        """
        Get all cycle records for user
        
        Args:
            user_id: User ID
        
        Returns:
            List of CycleRecord objects
        """
        return self.repo.cycle_records.get_all_for_user(user_id)
    
    def get_average_cycle_length(self, user_id: str) -> int:
        """
        Calculate average cycle length from history
        
        Args:
            user_id: User ID
        
        Returns:
            Average cycle length or default if insufficient data
        """
        cycles = self.get_cycle_history(user_id)
        
        completed_cycles = [c for c in cycles if c.length is not None and c.length > 0]
        
        if not completed_cycles:
            user = self.repo.users.get_by_id(user_id)
            return user.cycle_settings.typical_cycle_length if user else DEFAULT_CYCLE_LENGTH
        
        return int(sum(c.length for c in completed_cycles) / len(completed_cycles))
    
    def get_period_dates(self, user_id: str) -> List[date]:
        """
        Get all recorded period start dates
        
        Args:
            user_id: User ID
        
        Returns:
            List of period start dates
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        period_dates = []
        
        for log in logs:
            if log.period_indicators.is_period_day:
                # Check if this is the start of a period
                prev_date = log.date - timedelta(days=1)
                prev_log = self.repo.daily_logs.get_by_user_date(user_id, prev_date)
                
                if not prev_log or not prev_log.period_indicators.is_period_day:
                    period_dates.append(log.date)
        
        return sorted(period_dates)
    
    def register_period_start(self, user_id: str, period_start_date: date) -> bool:
        """
        Register a new period start (creates cycle record if needed)
        
        Args:
            user_id: User ID
            period_start_date: Date period started
        
        Returns:
            True if successful
        """
        try:
            # Get current/last cycle
            cycles = self.get_cycle_history(user_id)
            
            # Close the previous cycle if it's still open
            if cycles and not cycles[0].end_date:
                cycles[0].end_date = period_start_date - timedelta(days=1)
                cycles[0].length = (cycles[0].end_date - cycles[0].start_date).days + 1
                self.repo.cycle_records.update(cycles[0])
            
            # Create new cycle record
            new_cycle = CycleRecord(
                cycle_id=str(uuid.uuid4()),
                user_id=user_id,
                start_date=period_start_date,
                end_date=None,
                length=None,
                period_length=None,
            )
            
            return self.repo.cycle_records.create(new_cycle)
        except Exception as e:
            print(f"Error registering period start: {e}")
            return False
    
    # ========================================================================
    # CYCLE STATISTICS
    # ========================================================================
    
    def get_cycle_regularity_score(self, user_id: str) -> float:
        """
        Calculate how regular user's cycles are (0-1)
        
        More regular = higher score
        
        Args:
            user_id: User ID
        
        Returns:
            Regularity score (0-1)
        """
        cycles = self.get_cycle_history(user_id)
        
        cycle_lengths = [c.length for c in cycles if c.length is not None and c.length > 0]
        
        if len(cycle_lengths) < 2:
            return 0.0
        
        # Calculate std deviation
        mean = sum(cycle_lengths) / len(cycle_lengths)
        variance = sum((x - mean) ** 2 for x in cycle_lengths) / len(cycle_lengths)
        std_dev = variance ** 0.5
        
        # Convert to regularity score (less variation = higher score)
        # Score is 1.0 at 0 variation, asymptotically approaches 0 as variation increases
        import math
        regularity = math.exp(-std_dev / mean) if mean > 0 else 0.0
        
        return min(1.0, max(0.0, regularity))
    
    def get_period_flow_distribution(self, user_id: str) -> dict:
        """
        Get distribution of reported flow levels
        
        Args:
            user_id: User ID
        
        Returns:
            Dict of flow level counts
        """
        logs = self.repo.daily_logs.get_all_for_user(user_id)
        period_logs = [log for log in logs if log.period_indicators.is_period_day]
        
        flow_dist = {"light": 0, "medium": 0, "heavy": 0, "unknown": 0}
        
        for log in period_logs:
            if log.period_indicators.flow:
                flow_dist[log.period_indicators.flow.value] += 1
            else:
                flow_dist["unknown"] += 1
        
        return flow_dist
