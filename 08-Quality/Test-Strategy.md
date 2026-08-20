# Test Strategy

## Unit
detector, date/time, amount, rules, state transitions, retry classifier.

## PostgreSQL integration
dedupe, concurrent claim, outbox atomicity, lease recovery, audit append-only.

## HTTP
webhook auth, invalid payload, Problem Details, idempotency.

## Fake GREEN-API
authorized/notAuthorized, success, 4xx, 5xx, timeout, invalid JSON.

## Real POC
group message, sender/chat/id, quoted PEGO, restart, duplicates, state, quota.

CI nunca depende de número real.
