alter table workflow_inbox add column message_redaction_status varchar(32) not null default 'VISIBLE';
alter table workflow_outbox add column message_redaction_status varchar(32) not null default 'VISIBLE';
