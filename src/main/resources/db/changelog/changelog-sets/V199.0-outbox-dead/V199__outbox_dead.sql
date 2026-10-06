-- Event audit E2 (2026-10-05): an outbox row that can never be sent -- too large for the broker, an impossible topic, or
-- failing a tenth time while the broker answers -- is parked here instead of holding up every later event of every
-- workspace. The relay skips it; clearing dead_at (and attempts) sends it again. Expand-only.
ALTER TABLE platform_outbox ADD COLUMN dead_at TIMESTAMPTZ;
COMMENT ON COLUMN platform_outbox.dead_at IS 'Event audit E2: when the relay parked this event as never sendable (last_error says why); NULL to send it again.';
