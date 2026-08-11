"""
main.py - Entry point for the Menstrual Cycle Intelligence System

This is a simple CLI interface for testing and using the system.
"""

from datetime import date
from pathlib import Path
import uuid
import json

from src.data.repository import Repository
from src.main.orchestrator import Orchestrator
from src.data.models import UserProfile, UserPreferences, CycleSettings, MoodPreference


def create_test_user(repo: Repository) -> str:
    """Create a test user for demonstration"""
    user = UserProfile(
        user_id=str(uuid.uuid4()),
        name="Test User",
        preferences=UserPreferences(
            low_mood_preference=MoodPreference.SUPPORT,
            notification_timing="3_days_before",
            wellness_focus=["exercise", "nutrition", "stress_management"]
        ),
        cycle_settings=CycleSettings(
            typical_cycle_length=28,
            typical_period_length=5
        )
    )
    
    repo.users.create(user)
    return user.user_id


def main():
    """Main CLI interface"""
    # Initialize repository
    db_path = Path(__file__).parent / "menstrual_tracker.db"
    repo = Repository(str(db_path))
    
    # Initialize orchestrator
    orchestrator = Orchestrator(repo)
    
    print("=" * 80)
    print("Menstrual Cycle Intelligence System")
    print("=" * 80)
    print()
    
    # Create a test user if no users exist
    users = repo.users
    user_id = create_test_user(repo)
    print(f"✓ Created test user: {user_id}")
    print()
    
    # Example: Add some test data
    print("Adding test data...")
    
    test_data = [
        {"date": "2026-03-01", "is_period_day": True, "flow": "medium", "mood": 2, "energy": 1, "pain": 4, "sleep": 3, "stress": 4},
        {"date": "2026-03-02", "is_period_day": True, "flow": "heavy", "mood": 2, "energy": 1, "pain": 5, "sleep": 3, "stress": 4},
        {"date": "2026-03-03", "is_period_day": False, "mood": 3, "energy": 2, "pain": 3, "sleep": 4, "stress": 3},
        {"date": "2026-03-04", "is_period_day": False, "mood": 3, "energy": 2, "pain": 2, "sleep": 4, "stress": 2},
        {"date": "2026-03-05", "is_period_day": False, "mood": 3, "energy": 3, "pain": 1, "sleep": 4, "stress": 2},
        {"date": "2026-03-10", "is_period_day": False, "mood": 4, "energy": 4, "pain": 0, "sleep": 5, "stress": 1},
        {"date": "2026-03-15", "is_period_day": False, "mood": 5, "energy": 5, "pain": 0, "sleep": 5, "stress": 1},
        {"date": "2026-03-16", "is_period_day": False, "mood": 4, "energy": 4, "pain": 1, "sleep": 4, "stress": 2},
        {"date": "2026-03-20", "is_period_day": False, "mood": 3, "energy": 3, "pain": 2, "sleep": 4, "stress": 3},
        {"date": "2026-03-25", "is_period_day": False, "mood": 2, "energy": 2, "pain": 3, "sleep": 4, "stress": 4},
        {"date": "2026-03-28", "is_period_day": False, "mood": 2, "energy": 2, "pain": 3, "sleep": 3, "stress": 4},
        {"date": "2026-03-29", "is_period_day": True, "flow": "light", "mood": 2, "energy": 1, "pain": 3, "sleep": 3, "stress": 4},
    ]
    
    for data in test_data:
        test_date = date.fromisoformat(data.pop("date"))
        orchestrator.log_symptoms(user_id, test_date, **data)
    
    print(f"✓ Added {len(test_data)} test data points")
    print()
    
    # Generate daily report for today
    print("Generating daily report for April 1, 2026...")
    print("-" * 80)
    
    report = orchestrator.generate_daily_report(user_id, date(2026, 4, 1))
    
    if report:
        print(f"📅 Date: {report.date}")
        print(f"🔄 Cycle: Day {report.cycle_state.cycle_day}/{report.cycle_state.cycle_length}")
        print(f"🌙 Phase: {report.cycle_state.phase.value} (confidence: {report.cycle_state.phase_confidence:.0%})")
        print(f"⏳ Next period: {report.cycle_state.days_until_next_period} days")
        print()
        
        print("📊 TODAY'S PREDICTIONS:")
        print("-" * 40)
        for metric, pred in report.current_predictions.items():
            print(f"  {metric.capitalize()}: {pred.value:.1f} (confidence: {pred.confidence:.0%})")
        print()
        
        print("💡 INSIGHTS:")
        print("-" * 40)
        for insight in report.insights[:5]:  # First 5 insights
            print(f"  • {insight}")
        if len(report.insights) > 5:
            print(f"  ... and {len(report.insights) - 5} more insights")
        print()
        
        print("💑 RELATIONSHIP ADVICE:")
        print("-" * 40)
        for advice in report.relationship_advice[:3]:
            print(f"  • {advice}")
        print()
        
        print("🏃 WELLNESS ADVICE:")
        print("-" * 40)
        print(f"  Exercise: {report.wellness_advice.exercise[:80]}...")
        print(f"  Nutrition: {report.wellness_advice.nutrition[:80]}...")
        print(f"  Stress Mgmt: {report.wellness_advice.stress_management[:60]}...")
        print()
        
        print("📈 UPCOMING WEEK (Next 3 Days):")
        print("-" * 40)
        for pred in report.upcoming_predictions[:3]:
            print(f"  {pred.date}: Day {pred.cycle_day} ({pred.phase.value}) - "
                  f"Mood: {pred.predicted_symptoms.get('mood').value:.1f}, "
                  f"Energy: {pred.predicted_symptoms.get('energy').value:.1f}")
        print()
        
        print("📊 DATA QUALITY:")
        print("-" * 40)
        print(f"  Overall Confidence: {report.data_quality.overall_confidence:.0%}")
        print(f"  Total Logs: {report.data_quality.user_data_points}")
        print(f"  Cycle Consistency: {report.data_quality.cycle_consistency}")
        print(f"  Days Since Last Log: {report.data_quality.data_freshness_days}")
        print(f"  Note: {report.data_quality.notes}")
        print()
        
        if report.alerts:
            print("⚠️  ALERTS:")
            print("-" * 40)
            for alert in report.alerts[:3]:
                print(f"  [{alert.severity.upper()}] {alert.message}")
        print()
    else:
        print("❌ Error generating report")
    
    print("=" * 80)
    print("✓ System test complete!")
    print("=" * 80)
    print()
    print("Database location:", db_path)
    print()
    
    # Close database
    repo.close()


if __name__ == "__main__":
    main()
