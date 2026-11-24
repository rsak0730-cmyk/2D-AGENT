-- Durable PostgreSQL notification fan-out; clients still obtain truth from saved events/snapshots.
CREATE OR REPLACE FUNCTION nova_notify_activity_event() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('nova_events', json_build_object('id', NEW.id)::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_nova_activity_event_notify ON activity_events;
CREATE TRIGGER trg_nova_activity_event_notify
AFTER INSERT ON activity_events
FOR EACH ROW EXECUTE FUNCTION nova_notify_activity_event();
