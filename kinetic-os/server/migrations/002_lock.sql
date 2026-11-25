-- Anti-theft lock: remember whether a tamper alert is active (fires once per tamper event)
ALTER TABLE vehicle_settings ADD COLUMN IF NOT EXISTS tamper_active boolean NOT NULL DEFAULT false;
