create index if not exists idx_workflow_instance_key_correlation_status
    on workflow_instance(workflow_key, correlation_id, status);

create index if not exists idx_workflow_instance_key_business_status
    on workflow_instance(workflow_key, business_key, status);

create index if not exists idx_workflow_instance_status_updated_id
    on workflow_instance(status, updated_at, id);
