-- Commands, routing and timer firing load all timers of one workflow; without this index each lookup scans
-- the whole timer table, which keeps fired and canceled rows.
create index if not exists idx_workflow_timer_instance on workflow_timer (workflow_instance_id);
