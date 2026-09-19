alter table workflow_timer add column claim_token varchar(64);
alter table workflow_inbox add column claim_token varchar(64);
alter table workflow_outbox add column claim_token varchar(64);
