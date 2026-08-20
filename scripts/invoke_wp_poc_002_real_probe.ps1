[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://127.0.0.1:8080/api/v1',
    [string] $ExpectedChatId = $env:GREEN_API_TEST_CHAT_ID,
    [ValidateRange(10, 3600)]
    [int] $TimeoutSeconds = 600,
    [ValidateRange(1, 30)]
    [int] $PollSeconds = 2,
    [string] $EvidenceDirectory = (Join-Path $PSScriptRoot '..\.local-evidence')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-RequiredEnvironmentValue {
    param([Parameter(Mandatory = $true)][string] $Name)

    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required environment variable $Name is not set."
    }
    return $value
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string] $Value)

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        return ([BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Invoke-ShiftCatcherApi {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string] $Method,
        [Parameter(Mandatory = $true)][string] $Path,
        [object] $Body,
        [hashtable] $AdditionalHeaders = @{}
    )

    $requestHeaders = @{ Authorization = "Bearer $script:adminToken" }
    foreach ($entry in $AdditionalHeaders.GetEnumerator()) {
        $requestHeaders[$entry.Key] = $entry.Value
    }
    $parameters = @{
        Uri = "$script:normalizedBaseUrl/$Path"
        Method = $Method
        Headers = $requestHeaders
        TimeoutSec = 15
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress
    }

    $response = Invoke-WebRequest @parameters
    return [pscustomobject]@{
        Body = $response.Content | ConvertFrom-Json
        CorrelationId = [string]$response.Headers['X-Correlation-Id']
    }
}

$adminToken = Get-RequiredEnvironmentValue -Name 'ADMIN_API_TOKEN'
$normalizedBaseUrl = $BaseUrl.TrimEnd('/')
$baseUri = $null
if (-not [Uri]::TryCreate($normalizedBaseUrl, [UriKind]::Absolute, [ref]$baseUri) -or
    $baseUri.Scheme -notin @('http', 'https') -or
    [string]::IsNullOrWhiteSpace($baseUri.Host)) {
    throw 'BaseUrl must be an absolute HTTP(S) URL.'
}
if ([string]::IsNullOrWhiteSpace($ExpectedChatId) -or -not $ExpectedChatId.EndsWith('@g.us')) {
    throw 'ExpectedChatId (or GREEN_API_TEST_CHAT_ID) must be the exact target group chatId ending in @g.us.'
}

$probeStartedAt = [DateTimeOffset]::UtcNow
$health = Invoke-ShiftCatcherApi -Method GET -Path 'health'
if ($health.Body.status -ne 'UP' -or $health.Body.database -ne 'UP') {
    throw 'Shift Catcher or its database is not healthy.'
}

$state = Invoke-ShiftCatcherApi -Method GET -Path 'integrations/green-api/state'
if (-not $state.Body.configured) {
    throw 'The running application reports GREEN-API as UNCONFIGURED.'
}
if (-not $state.Body.operational -or $state.Body.state -ne 'AUTHORIZED') {
    throw "The GREEN-API instance is not operational (state=$($state.Body.state))."
}
$webhookCutoff = [DateTimeOffset]::Parse([string]$state.Body.observedAt)

$nonce = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$expectedMessageText = "teste shift catcher $nonce"
Write-Host "Provider state is AUTHORIZED. From another participant, send '$expectedMessageText' to the expected group."

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$verification = $null
do {
    $candidate = Invoke-ShiftCatcherApi -Method POST -Path 'integrations/green-api/verify'
    $latest = $candidate.Body.latestGroupWebhook
    if ($null -ne $latest -and
        $latest.chatId -eq $ExpectedChatId -and
        $latest.messageText -ceq $expectedMessageText -and
        [DateTimeOffset]::Parse([string]$latest.webhookReceivedAt) -ge $webhookCutoff) {
        $verification = $candidate
        break
    }
    Start-Sleep -Seconds $PollSeconds
} while ([DateTimeOffset]::UtcNow -lt $deadline)

if ($null -eq $verification) {
    throw 'No new webhook for the expected group arrived before the timeout.'
}

$source = $verification.Body.latestGroupWebhook
$idempotencyKey = "wp-poc-002-real-$([Guid]::NewGuid().ToString('N'))"
$sendBody = @{
    chatId = [string]$source.chatId
    quotedMessageId = [string]$source.providerMessageId
}
$send = Invoke-ShiftCatcherApi -Method POST -Path 'poc/send-test-reply' -Body $sendBody -AdditionalHeaders @{
    'Idempotency-Key' = $idempotencyKey
}
if ($send.Body.status -ne 'ACCEPTED' -or
    $send.Body.message -ne 'PEGO' -or
    $send.Body.chatId -ne $source.chatId -or
    $send.Body.quotedMessageId -ne $source.providerMessageId -or
    $send.Body.idempotentReplay) {
    throw 'The first quoted PEGO response did not satisfy the transport contract.'
}

$replay = Invoke-ShiftCatcherApi -Method POST -Path 'poc/send-test-reply' -Body $sendBody -AdditionalHeaders @{
    'Idempotency-Key' = $idempotencyKey
}
if (-not $replay.Body.idempotentReplay -or
    $replay.Body.replyId -ne $send.Body.replyId -or
    $replay.Body.providerMessageId -ne $send.Body.providerMessageId) {
    throw 'The HTTP replay did not resolve to the original provider effect.'
}

$providerTimestamp = [DateTimeOffset]::Parse([string]$source.providerTimestamp)
$webhookReceivedAt = [DateTimeOffset]::Parse([string]$source.webhookReceivedAt)
$persistedAt = [DateTimeOffset]::Parse([string]$source.persistedAt)
$sendStartedAt = [DateTimeOffset]::Parse([string]$send.Body.sendStartedAt)
$providerAcceptedAt = [DateTimeOffset]::Parse([string]$send.Body.providerAcceptedAt)

$evidence = [ordered]@{
    schemaVersion = 1
    workPackage = 'WP-POC-002'
    result = 'API_ACCEPTED_VISUAL_CONFIRMATION_PENDING'
    probeStartedAt = $probeStartedAt.ToString('o')
    completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    markerNonce = $nonce
    health = [ordered]@{
        application = [string]$health.Body.status
        database = [string]$health.Body.database
        correlationId = $health.CorrelationId
    }
    provider = [ordered]@{
        state = [string]$state.Body.state
        operational = [bool]$state.Body.operational
        correlationId = $state.CorrelationId
    }
    source = [ordered]@{
        chatIdSha256 = Get-Sha256 -Value ([string]$source.chatId)
        senderIdSha256 = Get-Sha256 -Value ([string]$source.senderId)
        providerMessageIdSha256 = Get-Sha256 -Value ([string]$source.providerMessageId)
        contentMatchesMarker = $source.messageText -ceq $expectedMessageText
        providerTimestamp = $providerTimestamp.ToString('o')
        webhookReceivedAt = $webhookReceivedAt.ToString('o')
        persistedAt = $persistedAt.ToString('o')
    }
    reply = [ordered]@{
        replyId = [string]$send.Body.replyId
        providerMessageIdSha256 = Get-Sha256 -Value ([string]$send.Body.providerMessageId)
        message = [string]$send.Body.message
        quoteInputMatchesWebhook = $send.Body.quotedMessageId -eq $source.providerMessageId
        providerStatus = [string]$send.Body.status
        sendStartedAt = $sendStartedAt.ToString('o')
        providerAcceptedAt = $providerAcceptedAt.ToString('o')
        correlationId = $send.CorrelationId
    }
    idempotency = [ordered]@{
        replayResolvedToOriginal = [bool]$replay.Body.idempotentReplay
        replyIdMatches = $replay.Body.replyId -eq $send.Body.replyId
        providerMessageIdMatches = $replay.Body.providerMessageId -eq $send.Body.providerMessageId
        correlationId = $replay.CorrelationId
    }
    latencyMilliseconds = [ordered]@{
        providerToWebhook = [Math]::Round(($webhookReceivedAt - $providerTimestamp).TotalMilliseconds, 3)
        webhookToPersist = [Math]::Round(($persistedAt - $webhookReceivedAt).TotalMilliseconds, 3)
        sendToProviderAcceptance = [Math]::Round(($providerAcceptedAt - $sendStartedAt).TotalMilliseconds, 3)
    }
    visualConfirmation = 'PENDING'
    visualConfirmationRequired = $true
}

$evidencePath = [IO.Path]::GetFullPath((Join-Path $EvidenceDirectory "WP-POC-002-$([DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')).json"))
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($evidencePath)) -Force | Out-Null
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding utf8

Write-Host "Transport probe accepted and replay-safe. Evidence: $evidencePath"
Write-Host 'This is not GO: a participant must still confirm visually that PEGO quotes the exact source message.'
