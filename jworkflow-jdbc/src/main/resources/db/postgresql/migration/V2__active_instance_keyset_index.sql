-- PG-12: eliminate sorting the entire active set for the first/recovery page.
-- Existing indexes and deployed baseline remain unchanged.
create index idx_workflow_instance_active_keyset
    on workflow_instance (updated_at, id)
    where status in ('RUNNING', 'WAITING', 'FAILED');
