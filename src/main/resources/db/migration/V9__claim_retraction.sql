-- Retraction is the compensating action for a claim that should not have been sent: the model
-- misread the offer, or a slower check that ran after the send disagreed with it. It is deliberately
-- an exception rather than a routine step, because WhatsApp leaves a visible "message deleted" mark
-- and taking a shift back has a social cost inside a small group of colleagues.
alter table shift_claim
    drop constraint shift_claim_status_check;

alter table shift_claim
    add constraint shift_claim_status_check check (
        status in (
            'CREATED', 'SENDING', 'RETRY_PENDING', 'PROVIDER_ACCEPTED', 'CLAIMED', 'FAILED',
            'RETRACTING', 'RETRACTED'
        )
    );

alter table shift_claim
    add column retracted_at timestamptz,
    add column retraction_reason varchar(64),
    -- The provider answers a delete with an empty body, so evidence that it happened is what we
    -- record ourselves.
    add column retraction_failure_code varchar(64);

create index idx_shift_claim_retracted on shift_claim (retracted_at desc) where retracted_at is not null;
