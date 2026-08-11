-- SQLite Database Schema for Menstrual Cycle Tracker

-- Users Table
CREATE TABLE IF NOT EXISTS users (
    user_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    
    -- Cycle Settings
    typical_cycle_length INTEGER DEFAULT 28,
    typical_period_length INTEGER DEFAULT 5,
    
    -- Preferences
    low_mood_preference TEXT CHECK(low_mood_preference IN ('space', 'support')) DEFAULT 'support',
    notification_timing TEXT DEFAULT '3_days_before',
    
    -- Wellness Focus (comma-separated list)
    wellness_focus TEXT DEFAULT 'exercise,nutrition,stress_management',
    
    -- Metadata
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_active TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    data_consistency_score REAL DEFAULT 0.0,
    
    -- Versioning
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Daily Logs Table (user's daily entries)
CREATE TABLE IF NOT EXISTS daily_logs (
    log_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    date DATE NOT NULL,
    
    -- Period Indicators
    is_period_day BOOLEAN DEFAULT 0,
    flow TEXT CHECK(flow IN ('light', 'medium', 'heavy')) DEFAULT NULL,
    
    -- Symptoms (1-5 scale for mood/energy/sleep/stress, 0-5 for pain)
    mood INTEGER CHECK(mood >= 1 AND mood <= 5) DEFAULT NULL,
    energy INTEGER CHECK(energy >= 0 AND energy <= 5) DEFAULT NULL,
    pain INTEGER CHECK(pain >= 0 AND pain <= 5) DEFAULT NULL,
    sleep INTEGER CHECK(sleep >= 1 AND sleep <= 5) DEFAULT NULL,
    stress INTEGER CHECK(stress >= 1 AND stress <= 5) DEFAULT NULL,
    
    -- Metadata
    notes TEXT DEFAULT '',
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    UNIQUE(user_id, date)
);

-- Cycle Records Table (derived from period logs)
CREATE TABLE IF NOT EXISTS cycle_records (
    cycle_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    length INTEGER,
    period_length INTEGER,
    regularity_score REAL DEFAULT 0.0,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Phase Patterns Cache (aggregated patterns per user per phase)
CREATE TABLE IF NOT EXISTS phase_patterns (
    pattern_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    phase TEXT NOT NULL CHECK(phase IN ('menstruation', 'follicular', 'ovulation', 'luteal')),
    
    -- Sample Information
    sample_size INTEGER DEFAULT 0,
    
    -- Aggregated Metrics (averages)
    avg_mood REAL,
    avg_energy REAL,
    avg_pain REAL,
    avg_sleep REAL,
    avg_stress REAL,
    
    -- Standard Deviations (for confidence)
    std_mood REAL,
    std_energy REAL,
    std_pain REAL,
    std_sleep REAL,
    std_stress REAL,
    
    -- Metadata
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    UNIQUE(user_id, phase)
);

-- Frequency Maps (how often each value occurs in a phase)
CREATE TABLE IF NOT EXISTS frequency_maps (
    freq_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    phase TEXT NOT NULL,
    metric TEXT NOT NULL CHECK(metric IN ('mood', 'energy', 'pain', 'sleep', 'stress')),
    value INTEGER NOT NULL,
    frequency INTEGER DEFAULT 0,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    UNIQUE(user_id, phase, metric, value)
);

-- Trends Table (tracks trend vectors over time)
CREATE TABLE IF NOT EXISTS trends (
    trend_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    phase TEXT NOT NULL,
    metric TEXT NOT NULL CHECK(metric IN ('mood', 'energy', 'pain', 'sleep', 'stress')),
    trend_value REAL,  -- positive = improving, negative = declining
    window_start_date DATE,
    window_end_date DATE,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_daily_logs_user_date ON daily_logs(user_id, date);
CREATE INDEX IF NOT EXISTS idx_daily_logs_user_period ON daily_logs(user_id, is_period_day);
CREATE INDEX IF NOT EXISTS idx_cycle_records_user ON cycle_records(user_id);
CREATE INDEX IF NOT EXISTS idx_phase_patterns_user_phase ON phase_patterns(user_id, phase);
CREATE INDEX IF NOT EXISTS idx_frequency_maps_user_phase ON frequency_maps(user_id, phase);
CREATE INDEX IF NOT EXISTS idx_trends_user_phase ON trends(user_id, phase);
