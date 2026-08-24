create table detection_result (
    id uuid primary key default uuidv7(),
    message_id uuid not null unique references incoming_message (id),
    candidate boolean not null,
    score numeric(5, 4) not null check (score >= 0 and score <= 1),
    -- Signal and field names are enum constants without commas, so a joined text column keeps the
    -- JDBC mapping trivial and still round-trips exactly.
    signals varchar(512) not null default '',
    detection_started_at timestamptz not null,
    completed_at timestamptz not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp
);

create table shift_opportunity (
    id uuid primary key default uuidv7(),
    source_message_id uuid not null unique references incoming_message (id),
    group_id uuid references allowed_group (id),
    status varchar(24) not null check (
        status in (
            'DETECTED', 'PARSING', 'REVIEW_REQUIRED', 'EVALUATING', 'REJECTED',
            'ELIGIBLE', 'CLAIM_PENDING', 'CLAIMED', 'CLAIM_FAILED', 'EXPIRED'
        )
    ),
    shift_date date,
    start_time time,
    end_time time,
    ends_next_day boolean not null default false,
    location varchar(256),
    city varchar(128),
    amount numeric(12, 2) check (amount is null or amount >= 0),
    currency varchar(3),
    specialty varchar(128),
    notes text,
    extraction_method varchar(24) not null check (
        extraction_method in ('DETERMINISTIC', 'AI_FALLBACK', 'MANUAL_REVIEW')
    ),
    confidence numeric(5, 4) check (confidence is null or (confidence >= 0 and confidence <= 1)),
    ambiguous_fields varchar(512) not null default '',
    resolution_reason varchar(48),
    review_note text,
    version integer not null default 0 check (version >= 0),
    detected_at timestamptz not null,
    extraction_completed_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp
);

create index idx_shift_opportunity_recent on shift_opportunity (detected_at desc);

create index idx_shift_opportunity_status on shift_opportunity (status, detected_at desc);
