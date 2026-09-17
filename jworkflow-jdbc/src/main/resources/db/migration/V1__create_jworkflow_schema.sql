create table if not exists workflow_definition (
    id varchar(64) primary key,
    workflow_key varchar(255) not null,
    workflow_version varchar(64) not null,
    workflow_revision varchar(128) not null,
    definition_text text,
    checksum varchar(128),
    created_at varchar(64) not null,
    updated_at varchar(64) not null
);

create table if not exists workflow_instance (
    id varchar(64) primary key,
    workflow_key varchar(255) not null,
    workflow_version varchar(64),
    workflow_revision varchar(128),
    business_key varchar(255) not null,
    correlation_id varchar(255),
    current_state varchar(255) not null,
    status varchar(64) not null,
    variables text,
    lock_version integer not null default 0,
    created_at varchar(64) not null,
    updated_at varchar(64) not null
);

create table if not exists workflow_event (
    id varchar(64) primary key,
    event_type varchar(255) not null,
    subject varchar(128) not null,
    action varchar(128) not null,
    source_system varchar(255),
    correlation_id varchar(255),
    causation_id varchar(255),
    trace_id varchar(255),
    workflow_instance_id varchar(64),
    business_key varchar(255),
    tenant_id varchar(255),
    taxonomy_version varchar(64),
    occurred_at varchar(64) not null,
    received_at varchar(64) not null,
    headers text,
    message_payload text,
    message_content_type varchar(255),
    message_schema_name varchar(255),
    message_schema_version varchar(64),
    message_redaction_status varchar(64)
);

create table if not exists event_status (
    id varchar(64) primary key,
    event_id varchar(64) not null,
    attempt_id varchar(64) not null,
    attempt_number integer not null,
    idempotency_key varchar(255),
    workflow_instance_id varchar(64),
    correlation_id varchar(255),
    status_scope varchar(64) not null,
    handler_id varchar(255) not null default '',
    destination varchar(255) not null default '',
    status_value varchar(64) not null,
    retry_count integer not null default 0,
    next_retry_at varchar(64),
    retry_eligible integer not null default 0,
    terminal integer not null default 0,
    last_error_code varchar(255),
    last_error_message text,
    created_at varchar(64) not null,
    unique (attempt_id),
    unique (event_id, status_scope, handler_id, destination, attempt_number)
);

create table if not exists workflow_inbox (
    id varchar(64) primary key,
    external_event_id varchar(255) not null,
    source_system varchar(255) not null,
    event_id varchar(64),
    correlation_id varchar(255),
    received_at varchar(64) not null,
    processed_at varchar(64),
    status_value varchar(64) not null,
    last_error_message text
);

create table if not exists workflow_outbox (
    id varchar(64) primary key,
    event_id varchar(64) not null,
    destination varchar(255),
    idempotency_key varchar(255),
    correlation_id varchar(255),
    created_at varchar(64) not null,
    published_at varchar(64),
    status_value varchar(64) not null,
    retry_count integer not null default 0,
    last_error_message text
);

create table if not exists workflow_timer (
    id varchar(64) primary key,
    workflow_instance_id varchar(64) not null,
    timer_type varchar(64) not null,
    due_at varchar(64) not null,
    status_value varchar(64) not null,
    created_at varchar(64) not null
);

create table if not exists workflow_lock (
    lock_key varchar(255) primary key,
    owner_id varchar(255) not null,
    expires_at varchar(64) not null
);

create index if not exists idx_workflow_definition_key_version on workflow_definition (workflow_key, workflow_version);
create index if not exists idx_workflow_instance_business_key on workflow_instance (business_key);
create index if not exists idx_workflow_instance_correlation_id on workflow_instance (correlation_id);
create index if not exists idx_workflow_instance_status on workflow_instance (status);
create index if not exists idx_workflow_event_instance on workflow_event (workflow_instance_id);
create index if not exists idx_workflow_event_correlation on workflow_event (correlation_id);
create index if not exists idx_workflow_event_type on workflow_event (event_type);
create index if not exists idx_event_status_event on event_status (event_id);
create index if not exists idx_event_status_latest on event_status (event_id, status_scope, handler_id, destination, attempt_number);
create index if not exists idx_event_status_value on event_status (status_value);
create index if not exists idx_event_status_retry on event_status (retry_eligible);
create unique index if not exists idx_workflow_inbox_dedupe on workflow_inbox (external_event_id, source_system);
create index if not exists idx_workflow_outbox_status on workflow_outbox (status_value);
create index if not exists idx_workflow_timer_due on workflow_timer (due_at, status_value);
