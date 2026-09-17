-- V2 migrates V1 in place so representative legacy rows remain available.
alter table workflow_definition add column semantic_version varchar(64);
alter table workflow_definition add column definition_source text;
alter table workflow_definition add column canonical_json text;
update workflow_definition set semantic_version = workflow_version where semantic_version is null;
update workflow_definition set definition_source = definition_text where definition_source is null;
alter table workflow_instance add column pending_wait_json text;
alter table workflow_event add column sequence_number integer;
alter table workflow_event add column metadata_json text;
alter table workflow_event add column message_payload_blob blob;
alter table workflow_timer add column step_name varchar(255);
alter table workflow_timer add column target_node varchar(255);
alter table workflow_timer add column emitted_event varchar(255);
alter table workflow_timer add column attempt_count integer not null default 0;
alter table workflow_timer add column next_attempt_at varchar(64);
alter table workflow_timer add column claimed_by varchar(255);
alter table workflow_timer add column claim_until varchar(64);
alter table workflow_timer add column last_error_message text;
alter table workflow_timer add column updated_at varchar(64);
update workflow_timer set step_name = timer_type where step_name is null;
update workflow_timer set next_attempt_at = due_at where next_attempt_at is null;
update workflow_timer set updated_at = created_at where updated_at is null;
alter table workflow_inbox add column causation_id varchar(255);
alter table workflow_inbox add column idempotency_key varchar(255);
alter table workflow_inbox add column message_payload text;
alter table workflow_inbox add column message_payload_blob blob;
alter table workflow_inbox add column message_content_type varchar(255);
alter table workflow_inbox add column message_schema_name varchar(255);
alter table workflow_inbox add column message_schema_version varchar(64);
alter table workflow_inbox add column message_metadata_json text;
alter table workflow_inbox add column attempt_count integer not null default 0;
alter table workflow_inbox add column next_attempt_at varchar(64);
alter table workflow_inbox add column claimed_by varchar(255);
alter table workflow_inbox add column claim_until varchar(64);
alter table workflow_inbox add column dead_lettered_at varchar(64);
alter table workflow_outbox add column causation_id varchar(255);
alter table workflow_outbox add column message_payload text;
alter table workflow_outbox add column message_payload_blob blob;
alter table workflow_outbox add column message_content_type varchar(255);
alter table workflow_outbox add column message_schema_name varchar(255);
alter table workflow_outbox add column message_schema_version varchar(64);
alter table workflow_outbox add column message_metadata_json text;
alter table workflow_outbox add column attempt_count integer not null default 0;
alter table workflow_outbox add column next_attempt_at varchar(64);
alter table workflow_outbox add column claimed_by varchar(255);
alter table workflow_outbox add column claim_until varchar(64);
alter table workflow_outbox add column dead_lettered_at varchar(64);

create table workflow_inbox_attempt (
    id varchar(64) primary key, inbox_id varchar(64) not null, attempt_number integer not null,
    status_value varchar(64) not null, error_code varchar(255), error_message text, created_at varchar(64) not null,
    foreign key (inbox_id) references workflow_inbox(id), unique (inbox_id, attempt_number)
);
create table workflow_outbox_attempt (
    id varchar(64) primary key, outbox_id varchar(64) not null, attempt_number integer not null,
    status_value varchar(64) not null, error_code varchar(255), error_message text, created_at varchar(64) not null,
    foreign key (outbox_id) references workflow_outbox(id), unique (outbox_id, attempt_number)
);
create table workflow_command_result (
    idempotency_key varchar(255) primary key, command_type varchar(128) not null, request_hash varchar(128) not null,
    workflow_instance_id varchar(64), result_json text not null, created_at varchar(64) not null,
    foreign key (workflow_instance_id) references workflow_instance(id)
);

create unique index idx_workflow_definition_revision on workflow_definition (workflow_key, workflow_version, workflow_revision);
create index idx_workflow_instance_active on workflow_instance (status, updated_at, id);
create unique index idx_workflow_event_instance_sequence on workflow_event (workflow_instance_id, sequence_number);
create index idx_workflow_timer_claimable on workflow_timer (status_value, next_attempt_at, due_at, claim_until, id);
create index idx_workflow_inbox_claimable on workflow_inbox (status_value, next_attempt_at, claim_until, received_at, id);
create unique index idx_workflow_outbox_idempotency on workflow_outbox (destination, idempotency_key);
create index idx_workflow_outbox_claimable on workflow_outbox (status_value, next_attempt_at, claim_until, created_at, id);
create index idx_workflow_command_instance on workflow_command_result (workflow_instance_id);
