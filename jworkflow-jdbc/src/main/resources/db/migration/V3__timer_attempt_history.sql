create table workflow_timer_attempt (
    id varchar(64) primary key,
    timer_id varchar(64) not null,
    attempt_number integer not null,
    status_value varchar(64) not null,
    owner_id varchar(255) not null,
    error_message text,
    created_at varchar(64) not null,
    foreign key (timer_id) references workflow_timer(id),
    unique (timer_id, attempt_number)
);
create index idx_workflow_timer_attempt_timer on workflow_timer_attempt (timer_id, attempt_number);
