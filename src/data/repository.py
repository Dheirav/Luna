"""
Repository Layer - Database abstraction for Menstrual Cycle Tracker

Handles all SQLite interactions with proper error handling and transactions.
"""

import sqlite3
import uuid
from datetime import datetime, date
from typing import Optional, List, Dict, Tuple
from pathlib import Path

from .models import (
    UserProfile, UserPreferences, CycleSettings, DailyLog, DailyLog,
    PeriodIndicators, Symptoms, CycleRecord, PhasePattern, PhaseMetrics,
    Phase, MoodPreference, FlowLevel, Trend
)


class Database:
    """SQLite database connection and initialization"""
    
    def __init__(self, db_path: str = "menstrual_tracker.db"):
        """Initialize database connection"""
        self.db_path = db_path
        self.connection = None
        self._init_db()
    
    def _init_db(self):
        """Initialize database if it doesn't exist"""
        schema_path = Path(__file__).parent.parent.parent / "database" / "schema.sql"
        
        if not Path(self.db_path).exists():
            self.connection = sqlite3.connect(self.db_path)
            cursor = self.connection.cursor()
            
            with open(schema_path, 'r') as f:
                schema = f.read()
            
            cursor.executescript(schema)
            self.connection.commit()
        else:
            self.connection = sqlite3.connect(self.db_path)
    
    def get_connection(self):
        """Get database connection"""
        if self.connection is None:
            self.connection = sqlite3.connect(self.db_path)
        return self.connection
    
    def close(self):
        """Close database connection"""
        if self.connection:
            self.connection.close()
    
    def execute(self, query: str, params: tuple = ()) -> sqlite3.Cursor:
        """Execute a query and return cursor"""
        cursor = self.get_connection().cursor()
        cursor.execute(query, params)
        return cursor
    
    def commit(self):
        """Commit transaction"""
        self.connection.commit()
    
    def rollback(self):
        """Rollback transaction"""
        self.connection.rollback()


# ============================================================================
# REPOSITORY LAYER
# ============================================================================

class UserRepository:
    """User profile data access"""
    
    def __init__(self, db: Database):
        self.db = db
    
    def create(self, user: UserProfile) -> bool:
        """Create new user"""
        try:
            query = """
                INSERT INTO users (
                    user_id, name, typical_cycle_length, typical_period_length,
                    low_mood_preference, notification_timing, wellness_focus,
                    created_date, last_active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
            self.db.execute(
                query,
                (
                    user.user_id,
                    user.name,
                    user.cycle_settings.typical_cycle_length,
                    user.cycle_settings.typical_period_length,
                    user.preferences.low_mood_preference.value,
                    user.preferences.notification_timing,
                    ",".join(user.preferences.wellness_focus),
                    user.created_date.isoformat(),
                    user.last_active.isoformat(),
                )
            )
            self.db.commit()
            return True
        except Exception as e:
            self.db.rollback()
            print(f"Error creating user: {e}")
            return False
    
    def get_by_id(self, user_id: str) -> Optional[UserProfile]:
        """Retrieve user by ID"""
        try:
            cursor = self.db.execute(
                "SELECT * FROM users WHERE user_id = ?",
                (user_id,)
            )
            row = cursor.fetchone()
            
            if not row:
                return None
            
            # Map row to UserProfile
            columns = [desc[0] for desc in cursor.description]
            data = dict(zip(columns, row))
            
            prefs = UserPreferences(
                low_mood_preference=MoodPreference(data["low_mood_preference"]),
                notification_timing=data["notification_timing"],
                wellness_focus=data["wellness_focus"].split(",") if data["wellness_focus"] else []
            )
            
            cycle_settings = CycleSettings(
                typical_cycle_length=data["typical_cycle_length"],
                typical_period_length=data["typical_period_length"]
            )
            
            return UserProfile(
                user_id=data["user_id"],
                name=data["name"],
                preferences=prefs,
                cycle_settings=cycle_settings,
                created_date=datetime.fromisoformat(data["created_date"]),
                last_active=datetime.fromisoformat(data["last_active"]),
                data_consistency_score=data["data_consistency_score"]
            )
        except Exception as e:
            print(f"Error retrieving user: {e}")
            return None
    
    def update(self, user: UserProfile) -> bool:
        """Update user profile"""
        try:
            user.update_activity()
            query = """
                UPDATE users SET
                    name = ?, typical_cycle_length = ?, typical_period_length = ?,
                    low_mood_preference = ?, notification_timing = ?, wellness_focus = ?,
                    last_active = ?, data_consistency_score = ?, updated_date = ?
                WHERE user_id = ?
            """
            self.db.execute(
                query,
                (
                    user.name,
                    user.cycle_settings.typical_cycle_length,
                    user.cycle_settings.typical_period_length,
                    user.preferences.low_mood_preference.value,
                    user.preferences.notification_timing,
                    ",".join(user.preferences.wellness_focus),
                    user.last_active.isoformat(),
                    user.data_consistency_score,
                    datetime.utcnow().isoformat(),
                    user.user_id,
                )
            )
            self.db.commit()
            return True
        except Exception as e:
            self.db.rollback()
            print(f"Error updating user: {e}")
            return False


class DailyLogRepository:
    """Daily log data access"""
    
    def __init__(self, db: Database):
        self.db = db
    
    def create(self, log: DailyLog) -> bool:
        """Create new daily log entry"""
        try:
            query = """
                INSERT INTO daily_logs (
                    log_id, user_id, date, is_period_day, flow,
                    mood, energy, pain, sleep, stress, notes, created_date
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
            self.db.execute(
                query,
                (
                    log.log_id,
                    log.user_id,
                    log.date.isoformat(),
                    log.period_indicators.is_period_day,
                    log.period_indicators.flow.value if log.period_indicators.flow else None,
                    log.symptoms.mood,
                    log.symptoms.energy,
                    log.symptoms.pain,
                    log.symptoms.sleep,
                    log.symptoms.stress,
                    log.notes,
                    log.created_date.isoformat(),
                )
            )
            self.db.commit()
            return True
        except Exception as e:
            self.db.rollback()
            print(f"Error creating daily log: {e}")
            return False
    
    def get(self, log_id: str) -> Optional[DailyLog]:
        """Retrieve single log by ID"""
        try:
            cursor = self.db.execute(
                "SELECT * FROM daily_logs WHERE log_id = ?",
                (log_id,)
            )
            row = cursor.fetchone()
            
            if not row:
                return None
            
            columns = [desc[0] for desc in cursor.description]
            data = dict(zip(columns, row))
            
            period_indicators = PeriodIndicators(
                is_period_day=bool(data["is_period_day"]),
                flow=FlowLevel(data["flow"]) if data["flow"] else None
            )
            
            symptoms = Symptoms(
                mood=data["mood"],
                energy=data["energy"],
                pain=data["pain"],
                sleep=data["sleep"],
                stress=data["stress"]
            )
            
            return DailyLog(
                log_id=data["log_id"],
                user_id=data["user_id"],
                date=date.fromisoformat(data["date"]),
                period_indicators=period_indicators,
                symptoms=symptoms,
                notes=data["notes"],
                created_date=datetime.fromisoformat(data["created_date"])
            )
        except Exception as e:
            print(f"Error retrieving daily log: {e}")
            return None
    
    def get_by_user_date(self, user_id: str, target_date: date) -> Optional[DailyLog]:
        """Get log for specific user on specific date"""
        try:
            cursor = self.db.execute(
                "SELECT * FROM daily_logs WHERE user_id = ? AND date = ?",
                (user_id, target_date.isoformat())
            )
            row = cursor.fetchone()
            
            if not row:
                return None
            
            columns = [desc[0] for desc in cursor.description]
            data = dict(zip(columns, row))
            
            period_indicators = PeriodIndicators(
                is_period_day=bool(data["is_period_day"]),
                flow=FlowLevel(data["flow"]) if data["flow"] else None
            )
            
            symptoms = Symptoms(
                mood=data["mood"],
                energy=data["energy"],
                pain=data["pain"],
                sleep=data["sleep"],
                stress=data["stress"]
            )
            
            return DailyLog(
                log_id=data["log_id"],
                user_id=data["user_id"],
                date=date.fromisoformat(data["date"]),
                period_indicators=period_indicators,
                symptoms=symptoms,
                notes=data["notes"],
                created_date=datetime.fromisoformat(data["created_date"])
            )
        except Exception as e:
            print(f"Error retrieving daily log: {e}")
            return None
    
    def get_all_for_user(self, user_id: str) -> List[DailyLog]:
        """Get all logs for a user, ordered by date"""
        try:
            cursor = self.db.execute(
                "SELECT * FROM daily_logs WHERE user_id = ? ORDER BY date ASC",
                (user_id,)
            )
            rows = cursor.fetchall()
            
            if not rows:
                return []
            
            columns = [desc[0] for desc in cursor.description]
            logs = []
            
            for row in rows:
                data = dict(zip(columns, row))
                
                period_indicators = PeriodIndicators(
                    is_period_day=bool(data["is_period_day"]),
                    flow=FlowLevel(data["flow"]) if data["flow"] else None
                )
                
                symptoms = Symptoms(
                    mood=data["mood"],
                    energy=data["energy"],
                    pain=data["pain"],
                    sleep=data["sleep"],
                    stress=data["stress"]
                )
                
                logs.append(DailyLog(
                    log_id=data["log_id"],
                    user_id=data["user_id"],
                    date=date.fromisoformat(data["date"]),
                    period_indicators=period_indicators,
                    symptoms=symptoms,
                    notes=data["notes"],
                    created_date=datetime.fromisoformat(data["created_date"])
                ))
            
            return logs
        except Exception as e:
            print(f"Error retrieving daily logs: {e}")
            return []
    
    def get_for_date_range(self, user_id: str, start_date: date, end_date: date) -> List[DailyLog]:
        """Get logs for user within date range"""
        try:
            cursor = self.db.execute(
                "SELECT * FROM daily_logs WHERE user_id = ? AND date BETWEEN ? AND ? ORDER BY date ASC",
                (user_id, start_date.isoformat(), end_date.isoformat())
            )
            rows = cursor.fetchall()
            
            if not rows:
                return []
            
            columns = [desc[0] for desc in cursor.description]
            logs = []
            
            for row in rows:
                data = dict(zip(columns, row))
                
                period_indicators = PeriodIndicators(
                    is_period_day=bool(data["is_period_day"]),
                    flow=FlowLevel(data["flow"]) if data["flow"] else None
                )
                
                symptoms = Symptoms(
                    mood=data["mood"],
                    energy=data["energy"],
                    pain=data["pain"],
                    sleep=data["sleep"],
                    stress=data["stress"]
                )
                
                logs.append(DailyLog(
                    log_id=data["log_id"],
                    user_id=data["user_id"],
                    date=date.fromisoformat(data["date"]),
                    period_indicators=period_indicators,
                    symptoms=symptoms,
                    notes=data["notes"],
                    created_date=datetime.fromisoformat(data["created_date"])
                ))
            
            return logs
        except Exception as e:
            print(f"Error retrieving daily logs in range: {e}")
            return []


class CycleRecordRepository:
    """Cycle record data access"""
    
    def __init__(self, db: Database):
        self.db = db
    
    def create(self, cycle: CycleRecord) -> bool:
        """Create new cycle record"""
        try:
            query = """
                INSERT INTO cycle_records (
                    cycle_id, user_id, start_date, end_date, length,
                    period_length, regularity_score, created_date
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """
            self.db.execute(
                query,
                (
                    cycle.cycle_id,
                    cycle.user_id,
                    cycle.start_date.isoformat(),
                    cycle.end_date.isoformat() if cycle.end_date else None,
                    cycle.length,
                    cycle.period_length,
                    cycle.regularity_score,
                    cycle.created_date.isoformat(),
                )
            )
            self.db.commit()
            return True
        except Exception as e:
            self.db.rollback()
            print(f"Error creating cycle record: {e}")
            return False

    def update(self, cycle: CycleRecord) -> bool:
        """Update an existing cycle record"""
        try:
            query = """
                UPDATE cycle_records SET
                    start_date = ?, end_date = ?, length = ?,
                    period_length = ?, regularity_score = ?
                WHERE cycle_id = ?
            """
            self.db.execute(
                query,
                (
                    cycle.start_date.isoformat(),
                    cycle.end_date.isoformat() if cycle.end_date else None,
                    cycle.length,
                    cycle.period_length,
                    cycle.regularity_score,
                    cycle.cycle_id,
                )
            )
            self.db.commit()
            return True
        except Exception as e:
            self.db.rollback()
            print(f"Error updating cycle record: {e}")
            return False
    
    def get_all_for_user(self, user_id: str) -> List[CycleRecord]:
        """Get all cycle records for user"""
        try:
            cursor = self.db.execute(
                "SELECT * FROM cycle_records WHERE user_id = ? ORDER BY start_date DESC",
                (user_id,)
            )
            rows = cursor.fetchall()
            
            if not rows:
                return []
            
            columns = [desc[0] for desc in cursor.description]
            cycles = []
            
            for row in rows:
                data = dict(zip(columns, row))
                cycles.append(CycleRecord(
                    cycle_id=data["cycle_id"],
                    user_id=data["user_id"],
                    start_date=date.fromisoformat(data["start_date"]),
                    end_date=date.fromisoformat(data["end_date"]) if data["end_date"] else None,
                    length=data["length"],
                    period_length=data["period_length"],
                    regularity_score=data["regularity_score"],
                    created_date=datetime.fromisoformat(data["created_date"])
                ))
            
            return cycles
        except Exception as e:
            print(f"Error retrieving cycle records: {e}")
            return []


class PhasePatternRepository:
    """Phase pattern data access (cached aggregations)"""
    
    def __init__(self, db: Database):
        self.db = db
    
    def upsert(self, pattern: PhasePattern) -> bool:
        """Insert or update pattern"""
        try:
            # Check if exists
            cursor = self.db.execute(
                "SELECT pattern_id FROM phase_patterns WHERE user_id = ? AND phase = ?",
                (pattern.user_id, pattern.phase.value)
            )
            exists = cursor.fetchone() is not None
            
            if exists:
                query = """
                    UPDATE phase_patterns SET
                        sample_size = ?, avg_mood = ?, avg_energy = ?, avg_pain = ?,
                        avg_sleep = ?, avg_stress = ?, std_mood = ?, std_energy = ?,
                        std_pain = ?, std_sleep = ?, std_stress = ?, last_updated = ?
                    WHERE user_id = ? AND phase = ?
                """
                self.db.execute(
                    query,
                    (
                        pattern.sample_size,
                        pattern.metrics.avg_mood,
                        pattern.metrics.avg_energy,
                        pattern.metrics.avg_pain,
                        pattern.metrics.avg_sleep,
                        pattern.metrics.avg_stress,
                        pattern.metrics.std_mood,
                        pattern.metrics.std_energy,
                        pattern.metrics.std_pain,
                        pattern.metrics.std_sleep,
                        pattern.metrics.std_stress,
                        datetime.utcnow().isoformat(),
                        pattern.user_id,
                        pattern.phase.value,
                    )
                )
            else:
                query = """
                    INSERT INTO phase_patterns (
                        pattern_id, user_id, phase, sample_size,
                        avg_mood, avg_energy, avg_pain, avg_sleep, avg_stress,
                        std_mood, std_energy, std_pain, std_sleep, std_stress,
                        last_updated
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                self.db.execute(
                    query,
                    (
                        pattern.pattern_id,
                        pattern.user_id,
                        pattern.phase.value,
                        pattern.sample_size,
                        pattern.metrics.avg_mood,
                        pattern.metrics.avg_energy,
                        pattern.metrics.avg_pain,
                        pattern.metrics.avg_sleep,
                        pattern.metrics.avg_stress,
                        pattern.metrics.std_mood,
                        pattern.metrics.std_energy,
                        pattern.metrics.std_pain,
                        pattern.metrics.std_sleep,
                        pattern.metrics.std_stress,
                        datetime.utcnow().isoformat(),
                    )
                )
            
            self.db.commit()
            return True
        except Exception as e:
            self.db.rollback()
            print(f"Error upserting pattern: {e}")
            return False
    
    def get_for_user(self, user_id: str) -> Dict[Phase, PhasePattern]:
        """Get all patterns for user"""
        try:
            cursor = self.db.execute(
                "SELECT * FROM phase_patterns WHERE user_id = ?",
                (user_id,)
            )
            rows = cursor.fetchall()
            
            patterns = {}
            columns = [desc[0] for desc in cursor.description]
            
            for row in rows:
                data = dict(zip(columns, row))
                phase = Phase(data["phase"])
                
                metrics = PhaseMetrics(
                    avg_mood=data["avg_mood"],
                    avg_energy=data["avg_energy"],
                    avg_pain=data["avg_pain"],
                    avg_sleep=data["avg_sleep"],
                    avg_stress=data["avg_stress"],
                    std_mood=data["std_mood"],
                    std_energy=data["std_energy"],
                    std_pain=data["std_pain"],
                    std_sleep=data["std_sleep"],
                    std_stress=data["std_stress"],
                )
                
                patterns[phase] = PhasePattern(
                    pattern_id=data["pattern_id"],
                    user_id=data["user_id"],
                    phase=phase,
                    sample_size=data["sample_size"],
                    metrics=metrics,
                    last_updated=datetime.fromisoformat(data["last_updated"])
                )
            
            return patterns
        except Exception as e:
            print(f"Error retrieving patterns: {e}")
            return {}


# ============================================================================
# MAIN REPOSITORY CLASS
# ============================================================================

class Repository:
    """Main repository class aggregating all data access"""
    
    def __init__(self, db_path: str = "menstrual_tracker.db"):
        self.db = Database(db_path)
        self.users = UserRepository(self.db)
        self.daily_logs = DailyLogRepository(self.db)
        self.cycle_records = CycleRecordRepository(self.db)
        self.phase_patterns = PhasePatternRepository(self.db)
    
    def close(self):
        """Close database connection"""
        self.db.close()
