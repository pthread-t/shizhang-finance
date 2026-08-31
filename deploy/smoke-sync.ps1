param(
    [string]$BaseUrl = "https://localhost:18443",
    [string]$Username = "smoke_owner",
    [Parameter(Mandatory = $true)][string]$Password
)

$ErrorActionPreference = "Stop"
$api = "$($BaseUrl.TrimEnd('/'))/api/v1"

function Invoke-LedgerApi {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Token,
        [object]$Body
    )
    $arguments = @{
        SkipCertificateCheck = $true
        NoProxy = $true
        Method = $Method
        Uri = "$api$Path"
    }
    if ($Token) { $arguments.Headers = @{ Authorization = "Bearer $Token" } }
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json"
        $arguments.Body = $Body | ConvertTo-Json -Depth 15 -Compress
    }
    Invoke-RestMethod @arguments
}

function Assert-Status {
    param([scriptblock]$Action, [int]$Expected)
    $observed = $null
    try { $null = & $Action } catch { $observed = [int]$_.Exception.Response.StatusCode }
    if ($observed -ne $Expected) { throw "Expected HTTP $Expected, observed $observed" }
}

function New-SyncOperation {
    param(
        [string]$BookId,
        [string]$EntityType,
        [string]$EntityId,
        [long]$BaseVersion,
        [string[]]$ChangedFields,
        [hashtable]$Payload,
        [string]$OperationId = ([guid]::NewGuid().ToString()),
        [string]$Operation = "UPSERT"
    )
    @{
        operationId = $OperationId
        bookId = $BookId
        entityType = $EntityType
        entityId = $EntityId
        operation = $Operation
        baseVersion = $BaseVersion
        changedFields = $ChangedFields
        payload = $Payload
        clientModifiedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
}

function Invoke-Sync {
    param([string]$Token, [string]$DeviceId, [object[]]$Operations)
    Invoke-LedgerApi -Method Post -Path "/sync" -Token $Token -Body @{
        deviceId = $DeviceId
        cursorByBook = @{}
        operations = $Operations
    }
}

$deviceA = [guid]::NewGuid().ToString()
$deviceB = [guid]::NewGuid().ToString()
$authA = Invoke-LedgerApi -Method Post -Path "/auth/login" -Body @{ username = $Username; password = $Password; deviceId = $deviceA; deviceName = "sync-smoke-a" }
$authB = Invoke-LedgerApi -Method Post -Path "/auth/login" -Body @{ username = $Username; password = $Password; deviceId = $deviceB; deviceName = "sync-smoke-b" }
$memberships = @(Invoke-LedgerApi -Method Get -Path "/memberships" -Token $authA.accessToken)
$membership = $memberships | Where-Object { $_.role -eq "OWNER" } | Select-Object -First 1
if ($null -eq $membership) { throw "Owner has no accessible book" }
$bookId = $membership.bookId

$transactionId = [guid]::NewGuid().ToString()
$createId = [guid]::NewGuid().ToString()
$createPayload = @{
    id = $transactionId
    type = "EXPENSE"
    amountMinor = 12345
    currency = "CNY"
    baseAmountMinor = 12345
    exchangeRate = "1"
    accountId = [guid]::NewGuid().ToString()
    note = "sync smoke"
    occurredAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    createdAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}
$create = New-SyncOperation -BookId $bookId -EntityType "TRANSACTION" -EntityId $transactionId -BaseVersion 0 -ChangedFields @($createPayload.Keys) -Payload $createPayload -OperationId $createId
$created = Invoke-Sync -Token $authA.accessToken -DeviceId $deviceA -Operations @($create)
$repeated = Invoke-Sync -Token $authA.accessToken -DeviceId $deviceA -Operations @($create)
if ($createId -notin $created.acknowledgedOperationIds -or $createId -notin $repeated.acknowledgedOperationIds) { throw "Idempotent acknowledgement failed" }

$reused = New-SyncOperation -BookId $bookId -EntityType "TRANSACTION" -EntityId ([guid]::NewGuid().ToString()) -BaseVersion 0 -ChangedFields @("note") -Payload @{ note = "invalid reuse" } -OperationId $createId
Assert-Status -Expected 409 -Action { Invoke-Sync -Token $authA.accessToken -DeviceId $deviceA -Operations @($reused) }

$firstEdit = New-SyncOperation -BookId $bookId -EntityType "TRANSACTION" -EntityId $transactionId -BaseVersion 1 -ChangedFields @("note") -Payload @{ note = "device A" }
$secondEdit = New-SyncOperation -BookId $bookId -EntityType "TRANSACTION" -EntityId $transactionId -BaseVersion 1 -ChangedFields @("note") -Payload @{ note = "device B" }
$null = Invoke-Sync -Token $authA.accessToken -DeviceId $deviceA -Operations @($firstEdit)
$conflicted = Invoke-Sync -Token $authB.accessToken -DeviceId $deviceB -Operations @($secondEdit)
if ($conflicted.conflicts.Count -ne 1 -or "note" -notin $conflicted.conflicts[0].conflictingFields) { throw "Same-field conflict was not reported" }

$amountEdit = New-SyncOperation -BookId $bookId -EntityType "TRANSACTION" -EntityId $transactionId -BaseVersion 2 -ChangedFields @("amountMinor") -Payload @{ amountMinor = 23456 }
$merchantEdit = New-SyncOperation -BookId $bookId -EntityType "TRANSACTION" -EntityId $transactionId -BaseVersion 2 -ChangedFields @("merchantId") -Payload @{ merchantId = [guid]::NewGuid().ToString() }
$null = Invoke-Sync -Token $authA.accessToken -DeviceId $deviceA -Operations @($amountEdit)
$merged = Invoke-Sync -Token $authB.accessToken -DeviceId $deviceB -Operations @($merchantEdit)
if ($merged.conflicts.Count -ne 0 -or $merchantEdit.operationId -notin $merged.acknowledgedOperationIds) { throw "Different-field merge failed" }

$splitId = [guid]::NewGuid().ToString()
$split = New-SyncOperation -BookId $bookId -EntityType "TRANSACTION_SPLIT" -EntityId $splitId -BaseVersion 0 -ChangedFields @("transactionId", "amountMinor", "baseAmountMinor", "note") -Payload @{
    transactionId = $transactionId; amountMinor = 23456; baseAmountMinor = 23456; note = "smoke split"
}
$splitResponse = Invoke-Sync -Token $authA.accessToken -DeviceId $deviceA -Operations @($split)
if ($split.operationId -notin $splitResponse.acknowledgedOperationIds) { throw "Split synchronization failed" }

Assert-Status -Expected 403 -Action { Invoke-Sync -Token $authA.accessToken -DeviceId $deviceB -Operations @() }

$invite = Invoke-LedgerApi -Method Post -Path "/invites" -Token $authA.accessToken -Body @{ bookId = $bookId; role = "VIEWER"; expiresInHours = 1 }
$viewerDevice = [guid]::NewGuid().ToString()
$viewerName = "viewer_$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$viewer = Invoke-LedgerApi -Method Post -Path "/auth/register" -Body @{
    username = $viewerName; password = "viewer-password-123"; inviteCode = $invite.code; deviceId = $viewerDevice; deviceName = "sync-smoke-viewer"
}
$viewerMemberships = @(Invoke-LedgerApi -Method Get -Path "/memberships" -Token $viewer.accessToken)
if (($viewerMemberships | Where-Object { $_.bookId -eq $bookId }).role -ne "VIEWER") { throw "Viewer membership refresh failed" }
$viewerWrite = New-SyncOperation -BookId $bookId -EntityType "TRANSACTION" -EntityId ([guid]::NewGuid().ToString()) -BaseVersion 0 -ChangedFields @("note") -Payload @{ note = "forbidden" }
Assert-Status -Expected 403 -Action { Invoke-Sync -Token $viewer.accessToken -DeviceId $viewerDevice -Operations @($viewerWrite) }

$audit = @(Invoke-LedgerApi -Method Get -Path "/books/$bookId/audit-events?limit=200" -Token $viewer.accessToken)
$auditCount = if ($audit.Count -eq 1 -and $audit[0] -is [System.Array]) { $audit[0].Count } else { $audit.Count }
if ($auditCount -lt 1) { throw "Audit endpoint returned no events" }

[ordered]@{
    bookId = $bookId
    idempotentReplay = $true
    operationIdReuseRejected = $true
    sameFieldConflict = $true
    differentFieldMerge = $true
    splitSynchronized = $true
    deviceMismatchRejected = $true
    viewerWriteRejected = $true
    membershipRefresh = $true
    auditEventCount = $auditCount
} | ConvertTo-Json -Compress
