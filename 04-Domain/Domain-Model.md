# Domain Model

## AllowedGroup
`id, providerChatId, displayName, enabled, autoClaimEnabled, version`

## IncomingProviderEvent
`id, providerEventType, providerInstanceId, providerMessageId, receivedAt, providerTimestamp, payloadHash, processingStatus, correlationId`

## IncomingMessage
`id, providerEventId, providerMessageId, chatId, chatName, senderId, senderName, text, providerTimestamp, receivedAt`

## DetectionResult
`messageId, candidate, score, signals, completedAt`

## ShiftOpportunity
`id, sourceMessageId, groupId, status, shiftDate, startTime, endTime, endsNextDay, location, city, amount, currency, specialty, notes, extractionMethod, confidence, version`

## RuleEvaluation
`id, opportunityId, ruleSetVersion, result, reasons, evaluatedAt`

## ShiftClaim
`id, opportunityId, status, mode, decidedAt, providerMessageId, claimedAt, failedAt, failureCode, version`

## ClaimAttempt
`id, claimId, attemptNumber, startedAt, completedAt, providerResponseId, result, latencyMs`

## OutboxEvent
Reliability primitive.

## AuditEvent
Append-only.
