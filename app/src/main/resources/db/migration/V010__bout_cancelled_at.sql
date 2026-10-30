-- When a bout was cancelled, so a market replay can put the cancellation (and its price drop) back on the timeline.
-- Older cancellations have no time; the replay then uses the start of the event.
ALTER TABLE bout ADD COLUMN cancelled_at TIMESTAMPTZ;
