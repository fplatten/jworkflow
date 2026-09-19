-- Fresh PostgreSQL baseline. Never scan together with the legacy SQLite location.
-- Relational times are exact epoch seconds. Lease tokens and sequence allocation
-- storage are reserved for PG-08/09 behavior; their presence does not enable fencing.

create table workflow_definition (
    id varchar(64) collate "C" primary key,
    workflow_key varchar(255) collate "C" not null,
    workflow_version varchar(64) collate "C" not null,
    workflow_revision varchar(128) collate "C" not null,
    definition_text text,
    checksum varchar(128) collate "C",
    created_at numeric(30,9) not null,
    updated_at numeric(30,9) not null,
    semantic_version varchar(64) collate "C",
    definition_source text,
    canonical_json text
);

create table workflow_instance (
    id varchar(64) collate "C" primary key,
    workflow_key varchar(255) collate "C" not null,
    workflow_version varchar(64) collate "C",
    workflow_revision varchar(128) collate "C",
    business_key varchar(255) collate "C" not null,
    correlation_id varchar(255) collate "C",
    current_state varchar(255) collate "C" not null,
    status varchar(64) collate "C" not null,
    variables text,
    lock_version bigint not null default 0,
    created_at numeric(30,9) not null,
    updated_at numeric(30,9) not null,
    pending_wait_json text
);

create table workflow_event (
    id varchar(64) collate "C" primary key,
    event_type varchar(255) collate "C" not null,
    subject varchar(128) collate "C" not null,
    action varchar(128) collate "C" not null,
    source_system varchar(255) collate "C",
    correlation_id varchar(255) collate "C",
    causation_id varchar(255) collate "C",
    trace_id varchar(255) collate "C",
    workflow_instance_id varchar(64) collate "C",
    business_key varchar(255) collate "C",
    tenant_id varchar(255) collate "C",
    taxonomy_version varchar(64) collate "C",
    occurred_at numeric(30,9) not null,
    received_at numeric(30,9) not null,
    headers text,
    message_payload text,
    message_content_type varchar(255) collate "C",
    message_schema_name varchar(255) collate "C",
    message_schema_version varchar(64) collate "C",
    message_redaction_status varchar(64) collate "C",
    sequence_number bigint,
    metadata_json text,
    message_payload_blob bytea
);

create table event_status (
    id varchar(64) collate "C" primary key,
    event_id varchar(64) collate "C" not null,
    attempt_id varchar(64) collate "C" not null,
    attempt_number integer not null,
    idempotency_key varchar(255) collate "C",
    workflow_instance_id varchar(64) collate "C",
    correlation_id varchar(255) collate "C",
    status_scope varchar(64) collate "C" not null,
    handler_id varchar(255) collate "C" not null default '',
    destination varchar(255) collate "C" not null default '',
    status_value varchar(64) collate "C" not null,
    retry_count integer not null default 0,
    next_retry_at numeric(30,9),
    retry_eligible integer not null default 0,
    terminal integer not null default 0,
    last_error_code varchar(255) collate "C",
    last_error_message text,
    created_at numeric(30,9) not null,
    unique (attempt_id),
    unique (event_id, status_scope, handler_id, destination, attempt_number)
);

create table workflow_inbox (
    id varchar(64) collate "C" primary key,
    external_event_id varchar(255) collate "C" not null,
    source_system varchar(255) collate "C" not null,
    event_id varchar(64) collate "C",
    correlation_id varchar(255) collate "C",
    received_at numeric(30,9) not null,
    processed_at numeric(30,9),
    status_value varchar(64) collate "C" not null,
    last_error_message text,
    causation_id varchar(255) collate "C",
    idempotency_key varchar(255) collate "C",
    message_payload text,
    message_payload_blob bytea,
    message_content_type varchar(255) collate "C",
    message_schema_name varchar(255) collate "C",
    message_schema_version varchar(64) collate "C",
    message_metadata_json text,
    attempt_count integer not null default 0,
    next_attempt_at numeric(30,9),
    claimed_by varchar(255) collate "C",
    claim_until numeric(30,9),
    dead_lettered_at numeric(30,9),
    message_redaction_status varchar(32) collate "C" not null default 'VISIBLE',
    claim_token varchar(64) collate "C"
);

create table workflow_outbox (
    id varchar(64) collate "C" primary key,
    event_id varchar(64) collate "C" not null,
    destination varchar(255) collate "C",
    idempotency_key varchar(255) collate "C",
    correlation_id varchar(255) collate "C",
    created_at numeric(30,9) not null,
    published_at numeric(30,9),
    status_value varchar(64) collate "C" not null,
    retry_count integer not null default 0,
    last_error_message text,
    causation_id varchar(255) collate "C",
    message_payload text,
    message_payload_blob bytea,
    message_content_type varchar(255) collate "C",
    message_schema_name varchar(255) collate "C",
    message_schema_version varchar(64) collate "C",
    message_metadata_json text,
    attempt_count integer not null default 0,
    next_attempt_at numeric(30,9),
    claimed_by varchar(255) collate "C",
    claim_until numeric(30,9),
    dead_lettered_at numeric(30,9),
    message_redaction_status varchar(32) collate "C" not null default 'VISIBLE',
    claim_token varchar(64) collate "C"
);

create table workflow_timer (
    id varchar(64) collate "C" primary key,
    workflow_instance_id varchar(64) collate "C" not null,
    timer_type varchar(64) collate "C" not null,
    due_at numeric(30,9) not null,
    status_value varchar(64) collate "C" not null,
    created_at numeric(30,9) not null,
    step_name varchar(255) collate "C",
    target_node varchar(255) collate "C",
    emitted_event varchar(255) collate "C",
    attempt_count integer not null default 0,
    next_attempt_at numeric(30,9),
    claimed_by varchar(255) collate "C",
    claim_until numeric(30,9),
    last_error_message text,
    updated_at numeric(30,9),
    claim_token varchar(64) collate "C"
);

create table workflow_lock (
    lock_key varchar(255) collate "C" primary key,
    owner_id varchar(255) collate "C" not null,
    expires_at numeric(30,9) not null
);

create table workflow_inbox_attempt (
    id varchar(64) collate "C" primary key, inbox_id varchar(64) collate "C" not null, attempt_number integer not null,
    status_value varchar(64) collate "C" not null, error_code varchar(255) collate "C", error_message text, created_at numeric(30,9) not null,
    foreign key (inbox_id) references workflow_inbox(id), unique (inbox_id, attempt_number)
);

create table workflow_outbox_attempt (
    id varchar(64) collate "C" primary key, outbox_id varchar(64) collate "C" not null, attempt_number integer not null,
    status_value varchar(64) collate "C" not null, error_code varchar(255) collate "C", error_message text, created_at numeric(30,9) not null,
    foreign key (outbox_id) references workflow_outbox(id), unique (outbox_id, attempt_number)
);

create table workflow_command_result (
    idempotency_key varchar(255) collate "C" primary key, command_type varchar(128) collate "C" not null, request_hash varchar(128) collate "C" not null,
    workflow_instance_id varchar(64) collate "C", result_json text not null, created_at numeric(30,9) not null,
    foreign key (workflow_instance_id) references workflow_instance(id)
);

create table workflow_timer_attempt (
    id varchar(64) collate "C" primary key,
    timer_id varchar(64) collate "C" not null,
    attempt_number integer not null,
    status_value varchar(64) collate "C" not null,
    owner_id varchar(255) collate "C" not null,
    error_message text,
    created_at numeric(30,9) not null,
    foreign key (timer_id) references workflow_timer(id),
    unique (timer_id, attempt_number)
);

create table workflow_event_sequence (
    workflow_instance_id varchar(64) collate "C" primary key,
    last_sequence bigint not null default 0
);

create index idx_workflow_definition_key_version on workflow_definition (workflow_key, workflow_version);

create index idx_workflow_instance_business_key on workflow_instance (business_key);

create index idx_workflow_instance_correlation_id on workflow_instance (correlation_id);

create index idx_workflow_instance_status on workflow_instance (status);

create index idx_workflow_event_instance on workflow_event (workflow_instance_id);

create index idx_workflow_event_correlation on workflow_event (correlation_id);

create index idx_workflow_event_type on workflow_event (event_type);

create index idx_event_status_event on event_status (event_id);

create index idx_event_status_latest on event_status (event_id, status_scope, handler_id, destination, attempt_number);

create index idx_event_status_value on event_status (status_value);

create index idx_event_status_retry on event_status (retry_eligible);

create unique index idx_workflow_inbox_dedupe on workflow_inbox (external_event_id, source_system);

create index idx_workflow_outbox_status on workflow_outbox (status_value);

create index idx_workflow_timer_due on workflow_timer (due_at, status_value);

create unique index idx_workflow_definition_revision on workflow_definition (workflow_key, workflow_version, workflow_revision);

create index idx_workflow_instance_active on workflow_instance (status, updated_at, id);

create unique index idx_workflow_event_instance_sequence on workflow_event (workflow_instance_id, sequence_number);

create index idx_workflow_timer_claimable on workflow_timer (status_value, next_attempt_at, due_at, claim_until, id);

create index idx_workflow_inbox_claimable on workflow_inbox (status_value, next_attempt_at, claim_until, received_at, id);

create unique index idx_workflow_outbox_idempotency on workflow_outbox (destination, idempotency_key);

create index idx_workflow_outbox_claimable on workflow_outbox (status_value, next_attempt_at, claim_until, created_at, id);

create index idx_workflow_command_instance on workflow_command_result (workflow_instance_id);

create index idx_workflow_timer_attempt_timer on workflow_timer_attempt (timer_id, attempt_number);

create index idx_workflow_instance_key_correlation_status
    on workflow_instance(workflow_key, correlation_id, status);

create index idx_workflow_instance_key_business_status
    on workflow_instance(workflow_key, business_key, status);

create index idx_workflow_instance_status_updated_id
    on workflow_instance(status, updated_at, id);
