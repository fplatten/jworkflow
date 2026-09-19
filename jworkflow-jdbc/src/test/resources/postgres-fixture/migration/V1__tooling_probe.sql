-- Harness-only DDL, not a jworkflow application migration.
create table tooling_probe (
    id bigint primary key,
    payload bytea,
    instant_value numeric(30,9) not null,
    json_value text not null
);
