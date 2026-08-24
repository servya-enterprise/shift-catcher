create table shift_claim (
    id uuid primary key default uuidv7(),
    -- `05-Data/Data-Model.md`: one claim per opportunity. This is the concurrency guard that makes
    -- two simultaneous claims resolve into one winner rather than two WhatsApp messages.
    opportunity_id uuid not null unique references shift_opportunity (id),
    status varchar(24) not null check (
        status in ('CREATED', 'SENDING', 'RETRY_PENDING', 'PROVIDER_ACCEPTED', 'CLAIMED', 'FAILED')
    ),
    mode varchar(8) not null check (mode in ('MANUAL', 'AUTO')),
    -- The quote target is frozen at decision time: the worker must never resolve it again.
    chat_id varchar(128) not null,
    quoted_message_id varchar(128) not null,
    message varchar(16) not null check (message = 'PEGO'),
    rule_evaluation_id uuid references rule_evaluation (id),
    provider_message_id varchar(128),
    attempt_count integer not null default 0 check (attempt_count >= 0),
    decided_at timestamptz not null,
    claimed_at timestamptz,
    failed_at timestamptz,
    failure_code varchar(64),
    version integer not null default 0 check (version >= 0),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp
);

create index idx_shift_claim_recent on shift_claim (decided_at desc);

create table claim_attempt (
    id uuid primary key default uuidv7(),
    claim_id uuid not null references shift_claim (id),
    attempt_number integer not null check (attempt_number > 0),
    started_at timestamptz not null,
    completed_at timestamptz,
    provider_response_id varchar(128),
    result varchar(24) not null check (
        result in ('ACCEPTED', 'TRANSIENT_FAILURE', 'PERMANENT_FAILURE')
    ),
    failure_code varchar(64),
    latency_ms integer check (latency_ms is null or latency_ms >= 0),
    created_at timestamptz not null default current_timestamp,
    constraint uq_claim_attempt_number unique (claim_id, attempt_number)
);

create index idx_claim_attempt_claim on claim_attempt (claim_id, attempt_number);

-- `DEC-006`: the claim state and the intent to send are written in one transaction; a worker sends.
create table outbox_event (
    id uuid primary key default uuidv7(),
    aggregate_type varchar(32) not null,
    aggregate_id uuid not null,
    event_type varchar(32) not null check (event_type in ('SEND_CLAIM_MESSAGE')),
    payload jsonb not null,
    status varchar(16) not null check (status in ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    attempts integer not null default 0 check (attempts >= 0),
    available_at timestamptz not null default current_timestamp,
    locked_until timestamptz,
    last_error varchar(256),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    completed_at timestamptz
);

-- Exactly one send intent per claim, whatever happens upstream: "one logical send".
create unique index uq_outbox_send_claim
    on outbox_event (aggregate_id, event_type)
    where event_type = 'SEND_CLAIM_MESSAGE';

create index idx_outbox_event_due
    on outbox_event (available_at)
    where status in ('PENDING', 'PROCESSING');
