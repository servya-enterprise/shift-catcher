create table allowed_group (
    id uuid primary key default uuidv7(),
    provider_chat_id varchar(128) not null unique,
    display_name varchar(256),
    enabled boolean not null default true,
    auto_claim_enabled boolean not null default false,
    version integer not null default 0 check (version >= 0),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    constraint ck_allowed_group_is_group_chat check (provider_chat_id like '%@g.us')
);

alter table incoming_provider_event
    add column payload_hash varchar(64),
    add column correlation_id varchar(128),
    add column processing_status varchar(16) not null default 'RECEIVED'
        check (processing_status in ('RECEIVED', 'PENDING', 'PROCESSED', 'IGNORED', 'FAILED')),
    add column ignored_reason varchar(48)
        check (ignored_reason in ('GROUP_NOT_ALLOWLISTED', 'GROUP_DISABLED')),
    add column processing_updated_at timestamptz;

create table incoming_message (
    id uuid primary key default uuidv7(),
    provider_event_id uuid not null unique references incoming_provider_event (id),
    group_id uuid references allowed_group (id),
    provider_message_id varchar(128) not null,
    chat_id varchar(128) not null,
    chat_name varchar(256),
    sender_id varchar(128) not null,
    sender_name varchar(256),
    text text not null,
    provider_timestamp timestamptz not null,
    received_at timestamptz not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp
);

create index idx_incoming_message_recent on incoming_message (received_at desc);

create index idx_incoming_message_chat on incoming_message (chat_id, received_at desc);

-- Events captured before the allowlist gate existed (WP-POC-002 transport proof) keep their
-- raw record and gain a normalized projection. They are marked GROUP_NOT_ALLOWLISTED because
-- the allowlist starts empty: registering the group and calling EP-017 promotes them to PENDING.
insert into incoming_message (
    provider_event_id, provider_message_id, chat_id, chat_name, sender_id, sender_name,
    text, provider_timestamp, received_at
)
select id,
       provider_message_id,
       chat_id,
       chat_name,
       sender_id,
       sender_name,
       regexp_replace(btrim(message_text), '\s+', ' ', 'g'),
       provider_timestamp,
       webhook_received_at
  from incoming_provider_event
 where chat_id like '%@g.us'
   and message_type = 'textMessage';

update incoming_provider_event
   set processing_status = 'IGNORED',
       ignored_reason = 'GROUP_NOT_ALLOWLISTED',
       processing_updated_at = current_timestamp
 where chat_id like '%@g.us'
   and message_type = 'textMessage';
