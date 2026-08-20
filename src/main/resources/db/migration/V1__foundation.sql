create table poc_bootstrap (
    id smallint primary key check (id = 1),
    created_at timestamptz not null default current_timestamp
);

insert into poc_bootstrap (id) values (1);

