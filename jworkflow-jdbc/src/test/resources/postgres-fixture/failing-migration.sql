create table must_rollback (id integer primary key);
insert into must_rollback values (1);
alter table table_that_does_not_exist add column failure integer;
