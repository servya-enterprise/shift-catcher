-- Single-row cache of the provider state. The provider rate-limits `getStateInstance`, so the
-- automatic path reads an observation taken on a schedule instead of asking on every decision.
create table provider_health (
    id smallint primary key check (id = 1),
    state varchar(24) not null,
    operational boolean not null,
    observed_at timestamptz not null,
    consecutive_failures integer not null default 0 check (consecutive_failures >= 0),
    last_error varchar(256),
    updated_at timestamptz not null default current_timestamp
);

-- `05-Data/Audit-and-Observability.md`: append-only. Nothing in the application updates or deletes
-- a row here; the repository only inserts.
create table audit_event (
    id uuid primary key default uuidv7(),
    aggregate_type varchar(32) not null,
    aggregate_id uuid,
    event_type varchar(48) not null,
    detail varchar(512),
    correlation_id varchar(128),
    occurred_at timestamptz not null default current_timestamp
);

create index idx_audit_event_recent on audit_event (occurred_at desc);

create index idx_audit_event_aggregate on audit_event (aggregate_type, aggregate_id, occurred_at desc);
