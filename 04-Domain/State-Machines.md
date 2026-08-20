# State Machines

## Provider event
`RECEIVED -> PENDING -> PROCESSED | IGNORED | FAILED`

## Opportunity

```text
DETECTED
 -> PARSING
 -> REVIEW_REQUIRED
 -> EVALUATING
    -> REJECTED
    -> ELIGIBLE
       -> CLAIM_PENDING
          -> CLAIMED
          -> CLAIM_FAILED
 -> EXPIRED
```

`CLAIMED` terminal na POC.

## Claim
`CREATED -> SENDING -> PROVIDER_ACCEPTED -> CLAIMED`
ou `SENDING -> RETRY_PENDING -> SENDING`
ou `SENDING -> FAILED`.

## Instance
- authorized -> OPERATIONAL
- starting/sleepMode -> DEGRADED
- notAuthorized/blocked/suspended -> NON_OPERATIONAL
