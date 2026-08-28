-- The operator axis, at last.
--
-- Eighteen tables have run without one because there was exactly one user: whoever knew
-- ADMIN_API_TOKEN. AUTODEC-0012 replaces that with a real person, introduced by Clara Care, and this
-- is the table that decides whether an introduction is accepted.
--
-- ONE TABLE, NOT EIGHTEEN COLUMNS. AUTODEC-0010 decision 8 named the shape and AUTODEC-0012 keeps
-- it: nothing here is added to shift_claim, opportunity or the rest yet. The axis is this project's
-- own operator_id and never Clara Care's tenant_id, and the trigger to spend that migration is
-- named in AUTODEC-0010 decision 12 rather than guessed at here.
create table operator (
    id uuid primary key,
    -- Clara Care's staff user id, and deliberately not the e-mail. An e-mail is reassignable by a
    -- Workspace administrator; a user id is not. Matching by e-mail is the classic account-takeover
    -- seam, and this column existing as the unique key is what makes that mistake unavailable.
    idp_subject varchar(64) not null unique,
    display_name varchar(160) not null,
    status varchar(16) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    last_seen_at timestamptz,
    constraint operator_status_check check (status in ('ACTIVE', 'INACTIVE'))
);

-- Single use, enforced where the session is created rather than trusted to a clock.
--
-- The assertion carries a jti and lives sixty seconds. A link that grants a session is a credential
-- in a URL — in browser history, in a referrer header, over somebody's shoulder — so it has to stop
-- working almost immediately AND refuse a second presentation. The row is deleted by the same sweep
-- that expires it; nothing here needs to be kept once it cannot be replayed.
create table handoff_redemption (
    jti varchar(64) primary key,
    redeemed_at timestamptz not null default now(),
    expires_at timestamptz not null
);

create index handoff_redemption_expires_idx on handoff_redemption (expires_at);

-- The one seeded row, so the console keeps working the moment the token login is removed.
--
-- Its idp_subject is a placeholder that no Clara Care user id can equal: subjects are UUIDs, and
-- this is not one. The row exists so the table is never empty in a deployment that has not linked
-- anybody yet, and it authenticates nobody until somebody replaces the subject with a real one.
insert into operator (id, idp_subject, display_name, status)
values (
    '00000000-0000-0000-0000-000000000001',
    'unlinked:seed',
    'Operadora',
    'ACTIVE'
);
