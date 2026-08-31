[CmdletBinding()]
param(
    [ValidateSet("full-year-v1")]
    [string]$Fixture = "full-year-v1",
    [string]$BaseUrl = "https://staging-ledger.example.com/api/v1",
    [switch]$VerifyOnly
)

$ErrorActionPreference = "Stop"
$parsedBaseUrl = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$parsedBaseUrl) -or $parsedBaseUrl.Scheme -ne "https") {
    throw "-BaseUrl must be an absolute HTTPS URL."
}
$BaseUrl = $BaseUrl.TrimEnd('/')
$ArtifactRoot = Join-Path $PSScriptRoot ".artifacts"
$FixturePath = Join-Path $PSScriptRoot "fixtures\$Fixture.json"
$StatePath = Join-Path $ArtifactRoot "staging-$Fixture-credentials.clixml"

function ConvertTo-PlainText([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function New-RandomPassword {
    $alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#%_-"
    $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
    -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function New-StableUuid([string]$Key) {
    $bytes = [Text.Encoding]::UTF8.GetBytes("bill-record-staging:$Fixture`:$Key")
    $hash = [Security.Cryptography.SHA256]::HashData($bytes)[0..15]
    $hash[6] = ($hash[6] -band 0x0f) -bor 0x50
    $hash[8] = ($hash[8] -band 0x3f) -bor 0x80
    $hex = -join ($hash | ForEach-Object { $_.ToString("x2") })
    "$($hex.Substring(0,8))-$($hex.Substring(8,4))-$($hex.Substring(12,4))-$($hex.Substring(16,4))-$($hex.Substring(20,12))"
}

function Save-State($State) {
    New-Item -ItemType Directory -Path $ArtifactRoot -Force | Out-Null
    $State | Export-Clixml -LiteralPath $StatePath -Force
}

function Get-State {
    if (Test-Path -LiteralPath $StatePath) { return Import-Clixml -LiteralPath $StatePath }
    $ownerPassword = New-RandomPassword
    $editorPassword = New-RandomPassword
    $anchor = [DateTimeOffset]::new((Get-Date).Year, (Get-Date).Month, 15, 12, 0, 0, [TimeSpan]::FromHours(8))
    $state = [pscustomobject]@{
        Fixture = $Fixture
        Anchor = $anchor.ToString("o")
        OwnerCredential = [PSCredential]::new("tester_a", (ConvertTo-SecureString $ownerPassword -AsPlainText -Force))
        EditorCredential = [PSCredential]::new("tester_b", (ConvertTo-SecureString $editorPassword -AsPlainText -Force))
        OwnerDeviceId = New-StableUuid "device:provision-owner"
        EditorDeviceId = New-StableUuid "device:provision-editor"
        OwnerUserId = $null
        EditorUserId = $null
        OwnerRecovery = $null
        EditorRecovery = $null
        BookId = New-StableUuid "book:family-experience"
        Provisioned = $false
        CredentialsPresented = $false
    }
    Save-State $state
    $state
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Token, [object]$Body)
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; NoProxy = $true }
    if ($Token) { $parameters.Headers = @{ Authorization = "Bearer $Token" } }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 30 -Compress
    }
    Invoke-RestMethod @parameters
}

function Login($Credential, [string]$DeviceId, [string]$DeviceName) {
    Invoke-Api -Method Post -Path "/auth/login" -Body @{
        username = $Credential.UserName
        password = ConvertTo-PlainText $Credential.Password
        deviceId = $DeviceId
        deviceName = $DeviceName
    }
}

function New-Operation([string]$BookId, [string]$Type, [string]$EntityId, $Payload) {
    [ordered]@{
        operationId = New-StableUuid "operation:$Type`:$EntityId"
        bookId = $BookId
        entityType = $Type
        entityId = $EntityId
        operation = "UPSERT"
        baseVersion = 0
        changedFields = @($Payload.Keys)
        payload = $Payload
        clientModifiedAt = ([DateTimeOffset]::Parse($script:State.Anchor)).ToUnixTimeMilliseconds()
    }
}

function Send-Operations([string]$Token, [string]$DeviceId, [object[]]$Operations) {
    for ($offset = 0; $offset -lt $Operations.Count; $offset += 100) {
        $last = [Math]::Min($offset + 99, $Operations.Count - 1)
        $batch = @($Operations[$offset..$last])
        $response = Invoke-Api -Method Post -Path "/sync" -Token $Token -Body @{
            deviceId = $DeviceId
            cursorByBook = @{}
            operations = $batch
        }
        if ($response.conflicts.Count -ne 0) { throw "Fixture synchronization returned $($response.conflicts.Count) conflicts." }
        if ($response.acknowledgedOperationIds.Count -ne $batch.Count) { throw "Fixture batch acknowledgement count mismatch." }
    }
}

function Add-Entity([Collections.Generic.List[object]]$Operations, [string]$Type, [string]$Key, $Payload) {
    $id = New-StableUuid "$($Type.ToLowerInvariant()):$Key"
    $body = [ordered]@{ id = $id }
    foreach ($entry in $Payload.GetEnumerator()) { $body[$entry.Key] = $entry.Value }
    $Operations.Add((New-Operation $script:State.BookId $Type $id $body))
    $id
}

function New-Postings([string]$Type, [string]$TransactionId, [string]$AccountId, [string]$CategoryId, [long]$AmountMinor, [long]$BaseAmountMinor, [string]$Currency, [string]$DestinationId, [long]$DestinationAmountMinor, [string]$DestinationCurrency, [object[]]$Splits) {
    if ($Splits.Count -gt 0) {
        $accountSign = if ($Type -eq "EXPENSE") { -1 } else { 1 }
        $categorySign = -$accountSign
        $result = @([ordered]@{ ledgerAccountId = $AccountId; amountMinor = $accountSign * $AmountMinor; currency = $Currency; baseAmountMinor = $accountSign * $BaseAmountMinor })
        foreach ($split in $Splits) {
            $prefix = if ($Type -eq "INCOME") { "system:income:" } else { "system:expense:" }
            $result += [ordered]@{ ledgerAccountId = "$prefix$($split.categoryId)"; amountMinor = $categorySign * [long]$split.amountMinor; currency = $Currency; baseAmountMinor = $categorySign * [long]$split.baseAmountMinor }
        }
        return $result
    }
    switch ($Type) {
        "EXPENSE" { @([ordered]@{ledgerAccountId=$AccountId;amountMinor=-$AmountMinor;currency=$Currency;baseAmountMinor=-$BaseAmountMinor},[ordered]@{ledgerAccountId="system:expense:$CategoryId";amountMinor=$AmountMinor;currency=$Currency;baseAmountMinor=$BaseAmountMinor}) }
        "INCOME" { @([ordered]@{ledgerAccountId=$AccountId;amountMinor=$AmountMinor;currency=$Currency;baseAmountMinor=$BaseAmountMinor},[ordered]@{ledgerAccountId="system:income:$CategoryId";amountMinor=-$AmountMinor;currency=$Currency;baseAmountMinor=-$BaseAmountMinor}) }
        "REFUND" { @([ordered]@{ledgerAccountId=$AccountId;amountMinor=$AmountMinor;currency=$Currency;baseAmountMinor=$BaseAmountMinor},[ordered]@{ledgerAccountId="system:expense:$CategoryId";amountMinor=-$AmountMinor;currency=$Currency;baseAmountMinor=-$BaseAmountMinor}) }
        "TRANSFER" { @([ordered]@{ledgerAccountId=$AccountId;amountMinor=-$AmountMinor;currency=$Currency;baseAmountMinor=-$BaseAmountMinor},[ordered]@{ledgerAccountId=$DestinationId;amountMinor=$DestinationAmountMinor;currency=$DestinationCurrency;baseAmountMinor=$BaseAmountMinor}) }
        "ADJUSTMENT" { @([ordered]@{ledgerAccountId=$AccountId;amountMinor=$AmountMinor;currency=$Currency;baseAmountMinor=$BaseAmountMinor},[ordered]@{ledgerAccountId="system:equity";amountMinor=-$AmountMinor;currency=$Currency;baseAmountMinor=-$BaseAmountMinor}) }
        default { throw "Unsupported transaction type: $Type" }
    }
}

function Add-Transaction {
    param(
        [Collections.Generic.List[object]]$Operations,
        [string]$Key, [string]$Type, [long]$AmountMinor, [string]$AccountKey, [string]$CategoryKey,
        [DateTimeOffset]$OccurredAt, [string]$Note, [string]$MemberId,
        [long]$BaseAmountMinor = 0, [string]$Currency = "CNY", [string]$ExchangeRate = "1",
        [string]$DestinationAccountKey, [long]$DestinationAmountMinor = 0, [string]$DestinationCurrency,
        [string]$ReimbursementStatus = "NONE", [string]$RefundOfKey, [string[]]$TagKeys = @(),
        [string]$MerchantKey, [string]$ProjectKey, [object[]]$Splits = @()
    )
    if ($BaseAmountMinor -eq 0) { $BaseAmountMinor = $AmountMinor }
    $transactionId = New-StableUuid "transaction:$Key"
    $accountId = $script:AccountIds[$AccountKey]
    $categoryId = if ($CategoryKey) { $script:CategoryIds[$CategoryKey] } else { $null }
    $destinationId = if ($DestinationAccountKey) { $script:AccountIds[$DestinationAccountKey] } else { $null }
    $transactionPayload = [ordered]@{
        id = $transactionId; type = $Type; amountMinor = $AmountMinor; currency = $Currency
        baseAmountMinor = $BaseAmountMinor; exchangeRate = $ExchangeRate; accountId = $accountId
        memberId = $MemberId; reimbursementStatus = $ReimbursementStatus; note = $Note
        occurredAt = $OccurredAt.ToUnixTimeMilliseconds(); createdAt = ([DateTimeOffset]::Parse($script:State.Anchor)).ToUnixTimeMilliseconds()
    }
    if ($categoryId -and $Splits.Count -eq 0) { $transactionPayload.categoryId = $categoryId }
    if ($destinationId) { $transactionPayload.destinationAccountId = $destinationId; $transactionPayload.destinationAmountMinor = $DestinationAmountMinor; $transactionPayload.destinationCurrency = $DestinationCurrency }
    if ($RefundOfKey) { $transactionPayload.refundOfTransactionId = New-StableUuid "transaction:$RefundOfKey" }
    if ($MerchantKey) { $transactionPayload.merchantId = $script:MerchantIds[$MerchantKey] }
    if ($ProjectKey) { $transactionPayload.projectId = $script:ProjectIds[$ProjectKey] }
    $Operations.Add((New-Operation $script:State.BookId "TRANSACTION" $transactionId $transactionPayload))

    $resolvedSplits = @()
    for ($index = 0; $index -lt $Splits.Count; $index++) {
        $split = $Splits[$index]
        $splitId = New-StableUuid "split:$Key`:$index"
        $resolved = [ordered]@{ categoryId = $script:CategoryIds[$split.categoryKey]; amountMinor = [long]$split.amountMinor; baseAmountMinor = [long]$split.amountMinor; note = [string]$split.note }
        $resolvedSplits += $resolved
        $Operations.Add((New-Operation $script:State.BookId "TRANSACTION_SPLIT" $splitId ([ordered]@{id=$splitId;transactionId=$transactionId;categoryId=$resolved.categoryId;amountMinor=$resolved.amountMinor;baseAmountMinor=$resolved.baseAmountMinor;note=$resolved.note})))
    }
    $postings = @(New-Postings $Type $transactionId $accountId $categoryId $AmountMinor $BaseAmountMinor $Currency $destinationId $DestinationAmountMinor $DestinationCurrency $resolvedSplits)
    for ($index = 0; $index -lt $postings.Count; $index++) {
        $postingId = New-StableUuid "posting:$Key`:$index"; $posting = $postings[$index]
        $Operations.Add((New-Operation $script:State.BookId "POSTING" $postingId ([ordered]@{id=$postingId;transactionId=$transactionId;ledgerAccountId=$posting.ledgerAccountId;amountMinor=$posting.amountMinor;currency=$posting.currency;baseAmountMinor=$posting.baseAmountMinor})))
    }
    foreach ($tagKey in $TagKeys) {
        $linkId = New-StableUuid "transaction-tag:$Key`:$tagKey"
        $Operations.Add((New-Operation $script:State.BookId "TRANSACTION_TAG" $linkId ([ordered]@{transactionId=$transactionId;tagId=$script:TagIds[$tagKey]})))
    }
}

function Build-FixtureOperations($Definition) {
    $operations = [Collections.Generic.List[object]]::new()
    $script:AccountIds = @{}; $script:CategoryIds = @{}; $script:TagIds = @{}; $script:MerchantIds = @{}; $script:ProjectIds = @{}
    foreach ($account in $Definition.accounts) {
        $payload = [ordered]@{name=$account.name;type=$account.type;currency=if($account.currency){$account.currency}else{"CNY"};openingBalanceMinor=[long]$account.openingBalanceMinor}
        foreach ($field in @("creditLimitMinor","statementDay","repaymentDay")) { if ($null -ne $account.$field) { $payload[$field] = [long]$account.$field } }
        $script:AccountIds[$account.key] = Add-Entity $operations "ACCOUNT" $account.key $payload
    }
    foreach ($category in $Definition.categories) {
        $payload = [ordered]@{name=$category.name;type=$category.type;icon="category"}
        if ($category.parent) { $payload.parentId = New-StableUuid "category:$($category.parent)" }
        $script:CategoryIds[$category.key] = Add-Entity $operations "CATEGORY" $category.key $payload
    }
    $tagKeys = @("essential","work","family","fun","tax")
    for ($index = 0; $index -lt $Definition.tags.Count; $index++) { $script:TagIds[$tagKeys[$index]] = Add-Entity $operations "TAG" $tagKeys[$index] ([ordered]@{name=$Definition.tags[$index];colorArgb=-13865373 + $index * 1315860}) }
    for ($index = 0; $index -lt $Definition.merchants.Count; $index++) { $key = "merchant-$index"; $script:MerchantIds[$key] = Add-Entity $operations "MERCHANT" $key ([ordered]@{name=$Definition.merchants[$index]}) }
    for ($index = 0; $index -lt $Definition.projects.Count; $index++) { $key = "project-$index"; $script:ProjectIds[$key] = Add-Entity $operations "PROJECT" $key ([ordered]@{name=$Definition.projects[$index]}) }

    $anchor = [DateTimeOffset]::Parse($script:State.Anchor)
    for ($month = 0; $month -lt 12; $month++) {
        $period = $anchor.AddMonths(-$month)
        $owner = $script:State.OwnerUserId; $editor = $script:State.EditorUserId
        Add-Transaction $operations "salary-$month" "INCOME" 1800000 "checking" "salary" ($period.AddDays(-10)) "$($period.Month) 月工资" $owner -TagKeys @("work")
        Add-Transaction $operations "rent-$month" "EXPENSE" 420000 "checking" "rent" ($period.AddDays(-13)) "房租与物业费" $owner -TagKeys @("essential","family") -ProjectKey "project-0"
        Add-Transaction $operations "food-$month" "EXPENSE" (68000 + $month * 1350) "wechat" "lunch" ($period.AddDays(-5)) "本月工作日午餐" $(if($month % 2 -eq 0){$owner}else{$editor}) -TagKeys @("essential") -MerchantKey "merchant-4" -ProjectKey "project-0"
        Add-Transaction $operations "transport-$month" "EXPENSE" (24000 + $month * 500) "alipay" "public" ($period.AddDays(-3)) "地铁与公交" $owner -TagKeys @("essential") -ProjectKey "project-0"
        Add-Transaction $operations "shopping-$month" "EXPENSE" (39900 + $month * 2000) "credit" $(if($month % 3 -eq 0){"digital"}else{"grocery"}) ($period.AddDays(3)) $(if($month % 3 -eq 0){"数码配件"}else{"家庭日用品"}) $editor -TagKeys @($(if($month % 3 -eq 0){"fun"}else{"family"})) -MerchantKey "merchant-3" -ProjectKey $(if($month % 3 -eq 0){"project-3"}else{"project-0"})
    }
    Add-Transaction $operations "trip-pending" "EXPENSE" 68000 "credit" "taxi" $anchor.AddDays(-7) "上海出差机场打车，待报销" $script:State.OwnerUserId -ReimbursementStatus "PENDING" -TagKeys @("work","tax") -MerchantKey "merchant-2" -ProjectKey "project-1"
    Add-Transaction $operations "refund-source" "EXPENSE" 32800 "alipay" "shopping" $anchor.AddDays(-9) "网购衣物" $script:State.OwnerUserId -TagKeys @("fun") -MerchantKey "merchant-3"
    Add-Transaction $operations "refund" "REFUND" 8800 "alipay" "shopping" $anchor.AddDays(-4) "部分退货退款" $script:State.OwnerUserId -RefundOfKey "refund-source" -TagKeys @("fun") -MerchantKey "merchant-3"
    Add-Transaction $operations "transfer-saving" "TRANSFER" 300000 "checking" $null $anchor.AddDays(-8) "每月转入应急金" $script:State.OwnerUserId -DestinationAccountKey "savings" -DestinationAmountMinor 300000 -DestinationCurrency "CNY" -TagKeys @("essential")
    $splits = @([pscustomobject]@{categoryKey="dinner";amountMinor=22000;note="晚餐"},[pscustomobject]@{categoryKey="transport";amountMinor=6000;note="交通"},[pscustomobject]@{categoryKey="entertainment";amountMinor=8000;note="娱乐"})
    Add-Transaction $operations "split-family" "EXPENSE" 36000 "wechat" $null $anchor.AddDays(-2) "周末家庭聚会（拆分账单）" $script:State.EditorUserId -TagKeys @("family") -MerchantKey "merchant-4" -Splits $splits

    $monthStart = [DateTimeOffset]::new($anchor.Year,$anchor.Month,1,0,0,0,$anchor.Offset)
    $nextMonth = $monthStart.AddMonths(1); $yearStart = [DateTimeOffset]::new($anchor.Year,1,1,0,0,0,$anchor.Offset)
    $null = Add-Entity $operations "BUDGET" "month" ([ordered]@{name="本月总预算";period="MONTHLY";startAt=$monthStart.ToUnixTimeMilliseconds();endAt=$nextMonth.ToUnixTimeMilliseconds();amountMinor=1200000;currency="CNY";rollover=$false;alertThresholdPercent=80})
    $null = Add-Entity $operations "BUDGET" "food" ([ordered]@{name="餐饮预算";categoryId=$script:CategoryIds.food;period="MONTHLY";startAt=$monthStart.ToUnixTimeMilliseconds();endAt=$nextMonth.ToUnixTimeMilliseconds();amountMinor=220000;currency="CNY";rollover=$true;alertThresholdPercent=75})
    $null = Add-Entity $operations "BUDGET" "travel" ([ordered]@{name="年度旅行预算";categoryId=$script:CategoryIds.travel;period="YEARLY";startAt=$yearStart.ToUnixTimeMilliseconds();amountMinor=2000000;currency="CNY";rollover=$false;alertThresholdPercent=90})
    $null = Add-Entity $operations "SAVING_GOAL" "emergency" ([ordered]@{name="六个月应急金";targetAmountMinor=6000000;currentAmountMinor=3500000;currency="CNY";targetAt=$anchor.AddMonths(8).ToUnixTimeMilliseconds();isWish=$false})
    $null = Add-Entity $operations "SAVING_GOAL" "travel" ([ordered]@{name="北海道旅行";targetAmountMinor=1500000;currentAmountMinor=920000;currency="CNY";targetAt=$anchor.AddMonths(5).ToUnixTimeMilliseconds();isWish=$true})
    $null = Add-Entity $operations "SAVING_GOAL" "phone" ([ordered]@{name="新手机";targetAmountMinor=600000;currentAmountMinor=600000;currency="CNY";targetAt=$anchor.AddDays(-10).ToUnixTimeMilliseconds();isWish=$true})
    $null = Add-Entity $operations "INSTALLMENT_PLAN" "phone" ([ordered]@{accountId=$script:AccountIds.credit;name="手机 12 期免息";totalAmountMinor=720000;installmentCount=12;completedCount=5;firstDueAt=$anchor.AddDays(12).ToUnixTimeMilliseconds();recurrenceRule="MONTHLY"})
    $null = Add-Entity $operations "INSTALLMENT_PLAN" "course" ([ordered]@{accountId=$script:AccountIds.credit;name="课程分期";totalAmountMinor=360000;installmentCount=6;completedCount=6;firstDueAt=$anchor.AddMonths(-1).ToUnixTimeMilliseconds();recurrenceRule="MONTHLY"})
    $rentTemplate = @{type="EXPENSE";amountMinor=420000;accountId=$script:AccountIds.checking;categoryId=$script:CategoryIds.rent;note="自动生成：房租"} | ConvertTo-Json -Compress
    $mobileTemplate = @{type="EXPENSE";amountMinor=8800;accountId=$script:AccountIds.alipay;categoryId=$script:CategoryIds.'other-expense';note="自动生成：手机费"} | ConvertTo-Json -Compress
    $null = Add-Entity $operations "RECURRING_RULE" "rent" ([ordered]@{name="每月房租";transactionTemplateJson=$rentTemplate;recurrenceRule="MONTHLY";nextRunAt=$anchor.AddMonths(1).ToUnixTimeMilliseconds();enabled=$true})
    $null = Add-Entity $operations "RECURRING_RULE" "mobile" ([ordered]@{name="每月手机费";transactionTemplateJson=$mobileTemplate;recurrenceRule="MONTHLY";nextRunAt=$anchor.AddDays(15).ToUnixTimeMilliseconds();enabled=$true})
    $null = Add-Entity $operations "RECURRING_RULE" "paused" ([ordered]@{name="已停用的视频会员";transactionTemplateJson=$mobileTemplate;recurrenceRule="MONTHLY";nextRunAt=$anchor.AddMonths(1).ToUnixTimeMilliseconds();enabled=$false})
    $filterJson = @{bookIds=@($script:State.BookId);types=@("EXPENSE");reimbursementStatuses=@("PENDING");query="差旅"} | ConvertTo-Json -Compress
    $null = Add-Entity $operations "SAVED_FILTER" "reimbursement" ([ordered]@{name="待报销差旅";filterJson=$filterJson})
    @($operations)
}

function Get-AllChanges([string]$Token, [string]$DeviceId, [string]$BookId) {
    $cursor = 0; $all = @()
    do {
        $response = Invoke-Api -Method Post -Path "/sync" -Token $Token -Body @{deviceId=$DeviceId;cursorByBook=@{$BookId=$cursor};operations=@()}
        $all += @($response.changes)
        $next = [long]$response.cursorByBook.$BookId
        if ($next -eq $cursor) { break }
        $cursor = $next
    } while ($response.changes.Count -ge 500)
    $all
}

function Verify-Provisioning {
    $owner = Login $script:State.OwnerCredential $script:State.OwnerDeviceId "fixture-verify-owner"
    $editor = Login $script:State.EditorCredential $script:State.EditorDeviceId "fixture-verify-editor"
    $ownerMemberships = @(Invoke-Api Get "/memberships" $owner.accessToken $null)
    $editorMemberships = @(Invoke-Api Get "/memberships" $editor.accessToken $null)
    if (-not ($ownerMemberships | Where-Object { $_.bookId -eq $script:State.BookId -and $_.role -eq "OWNER" })) { throw "tester_a OWNER membership is missing." }
    if (-not ($editorMemberships | Where-Object { $_.bookId -eq $script:State.BookId -and $_.role -eq "EDITOR" })) { throw "tester_b EDITOR membership is missing." }
    $changes = @(Get-AllChanges $owner.accessToken $script:State.OwnerDeviceId $script:State.BookId)
    $counts = $changes | Group-Object entityType | ForEach-Object { [ordered]@{ type=$_.Name; count=$_.Count } }
    $countMap = @{}; $counts | ForEach-Object { $countMap[$_.type] = $_.count }
    foreach ($minimum in @{ACCOUNT=10;CATEGORY=20;TRANSACTION=65;POSTING=130;BUDGET=3;SAVING_GOAL=3}.GetEnumerator()) {
        if ([int]$countMap[$minimum.Key] -lt $minimum.Value) { throw "$($minimum.Key) count is below $($minimum.Value)." }
    }
    [ordered]@{fixture=$Fixture;bookId=$script:State.BookId;totalChanges=$changes.Count;entityCounts=$counts;ownerRole="OWNER";editorRole="EDITOR"} | ConvertTo-Json -Depth 8
}

$definition = Get-Content -LiteralPath $FixturePath -Raw | ConvertFrom-Json
if ($definition.id -ne $Fixture) { throw "Fixture id mismatch." }
$script:State = Get-State
if ($script:State.Fixture -ne $Fixture) { throw "Credential state belongs to a different fixture." }
if ($VerifyOnly) { Verify-Provisioning; exit 0 }

$ownerPassword = ConvertTo-PlainText $script:State.OwnerCredential.Password
try {
    $ownerAuth = Invoke-Api Post "/auth/bootstrap" $null @{username=$script:State.OwnerCredential.UserName;password=$ownerPassword;deviceId=$script:State.OwnerDeviceId;deviceName="fixture-provision-owner"}
    $script:State.OwnerUserId = $ownerAuth.userId
    $script:State.OwnerRecovery = ConvertTo-SecureString $ownerAuth.recoveryCode -AsPlainText -Force
    Save-State $script:State
} catch {
    if ([int]$_.Exception.Response.StatusCode -ne 409) { throw }
    $ownerAuth = Login $script:State.OwnerCredential $script:State.OwnerDeviceId "fixture-provision-owner"
    $script:State.OwnerUserId = $ownerAuth.userId
}

$bookPayload = [ordered]@{id=$script:State.BookId;name=$definition.bookName;baseCurrency="CNY";timezone="Asia/Shanghai";monthStartDay=1}
Send-Operations $ownerAuth.accessToken $script:State.OwnerDeviceId @((New-Operation $script:State.BookId "BOOK" $script:State.BookId $bookPayload))

try {
    $editorAuth = Login $script:State.EditorCredential $script:State.EditorDeviceId "fixture-provision-editor"
} catch {
    if ([int]$_.Exception.Response.StatusCode -ne 401) { throw }
    $invite = Invoke-Api Post "/invites" $ownerAuth.accessToken @{bookId=$script:State.BookId;role="EDITOR";expiresInHours=24}
    $editorAuth = Invoke-Api Post "/auth/register" $null @{username=$script:State.EditorCredential.UserName;password=(ConvertTo-PlainText $script:State.EditorCredential.Password);inviteCode=$invite.code;deviceId=$script:State.EditorDeviceId;deviceName="fixture-provision-editor"}
    $script:State.EditorRecovery = ConvertTo-SecureString $editorAuth.recoveryCode -AsPlainText -Force
}
$script:State.EditorUserId = $editorAuth.userId
Save-State $script:State

$operations = @(Build-FixtureOperations $definition)
Send-Operations $ownerAuth.accessToken $script:State.OwnerDeviceId $operations
$script:State.Provisioned = $true
Save-State $script:State
$verification = Verify-Provisioning

if (-not $script:State.CredentialsPresented) {
    Write-Host "`nStaging credentials (shown once):" -ForegroundColor Yellow
    Write-Host "tester_a / $ownerPassword"
    Write-Host "tester_b / $(ConvertTo-PlainText $script:State.EditorCredential.Password)"
    if ($script:State.OwnerRecovery) { Write-Host "tester_a recovery / $(ConvertTo-PlainText $script:State.OwnerRecovery)" }
    if ($script:State.EditorRecovery) { Write-Host "tester_b recovery / $(ConvertTo-PlainText $script:State.EditorRecovery)" }
    $script:State.CredentialsPresented = $true
    Save-State $script:State
}
$verification
