# AUTODEC-0002 — Generic Problem Code

## Context
The frozen error model requires Problem Details with a stable `code` and forbids leaking secrets, while its catalog does not provide a code for an unexpected internal failure unrelated to GREEN-API.

## Gap
Using a domain/provider code for an internal 500 response would be misleading, and returning an unstructured container error would violate the foundation convention.

## Alternatives
- reuse `INVALID_REQUEST` for server failures;
- leak exception types/messages;
- define a generic non-domain code.

## Decision
Use `INTERNAL_ERROR` for unexpected HTTP 500 responses. Return a fixed public detail, correlation ID, and request path; log the exception through structured logging without request payloads or secrets.

## Rationale
The code is explicit, safe, provider-independent, and preserves the frozen Problem Details shape.

## Reversibility
HIGH

## Impact
Clients may distinguish malformed requests from server failures without depending on implementation exception types.

## Evidence
Covered by the global exception handler introduced in WP-POC-001.

## Status
ACTIVE

