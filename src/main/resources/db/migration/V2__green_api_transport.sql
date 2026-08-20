create table incoming_provider_event (
    id uuid primary key default uuidv7(),
    provider varchar(32) not null,
    instance_id varchar(64) not null,
    webhook_type varchar(64) not null,
    provider_message_id varchar(128) not null,
    provider_timestamp timestamptz not null,
    webhook_received_at timestamptz not null,
    parsing_completed_at timestamptz not null,
    persisted_at timestamptz not null default current_timestamp,
    chat_id varchar(128) not null,
    chat_name varchar(256),
    sender_id varchar(128) not null,
    sender_name varchar(256),
    sender_contact_name varchar(256),
    message_type varchar(64) not null,
    message_text text not null,
    duplicate_count integer not null default 0 check (duplicate_count >= 0),
    constraint uq_incoming_provider_event_dedupe
        unique (provider, instance_id, webhook_type, provider_message_id)
);

create index idx_incoming_provider_event_latest
    on incoming_provider_event (persisted_at desc);

create table transport_test_reply (
    id uuid primary key default uuidv7(),
    idempotency_key varchar(128) not null unique,
    logical_key varchar(320) not null unique,
    chat_id varchar(128) not null,
    quoted_message_id varchar(128) not null,
    message varchar(16) not null check (message = 'PEGO'),
    status varchar(32) not null check (status in ('PENDING', 'ACCEPTED', 'FAILED')),
    provider_message_id varchar(128),
    created_at timestamptz not null default current_timestamp,
    send_started_at timestamptz,
    provider_accepted_at timestamptz,
    failed_at timestamptz,
    failure_code varchar(64)
);
