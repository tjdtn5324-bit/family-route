param([Parameter(Mandatory=$true)][string]$Origin)
$ErrorActionPreference = 'Stop'
if ($Origin -notmatch '^https://[a-z0-9-]+\.trycloudflare\.com$') { throw 'A verified HTTPS preview origin is required.' }
$path = if ($env:FAMILY_ACCOUNT_CONFIG) { $env:FAMILY_ACCOUNT_CONFIG } else { Join-Path (Split-Path -Parent $PSScriptRoot) 'account-config.local.json' }
if (!(Test-Path -LiteralPath $path)) { return }
$config = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
if ($config.region -ne 'ap-southeast-2') { throw 'Unexpected account region.' }
$common = @('--profile', $config.profile, '--region', $config.region, '--no-cli-pager')
$description = & aws cognito-idp describe-user-pool-client --user-pool-id $config.userPoolId --client-id $config.clientId @common --output json 2>&1
if ($LASTEXITCODE) { throw 'AWS account session needs attention. Run aws login --profile family-route and retry account setup.' }
$client = ($description -join "`n" | ConvertFrom-Json).UserPoolClient
$callback = $Origin + '/account'
if ($client.CallbackURLs -contains $callback -and $client.LogoutURLs -contains $callback) { Write-Output 'Account callback already registered.'; return }
if ($client.ClientSecret) { throw 'The web app must use its existing public client without a secret.' }
if (@($client.CallbackURLs).Count -ge 100 -or @($client.LogoutURLs).Count -ge 100) { throw 'Account callback limit reached. Review old preview addresses before registering another.' }
# Filter out response-only fields, preserving every existing value accepted by the current update API.
$skeleton = & aws cognito-idp update-user-pool-client --generate-cli-skeleton input @common 2>&1
if ($LASTEXITCODE) { throw 'Could not inspect the account client update schema.' }
$allowed = ($skeleton -join "`n" | ConvertFrom-Json).PSObject.Properties.Name
$update = [ordered]@{}
foreach ($property in $client.PSObject.Properties) {
    if ($allowed -contains $property.Name -and $null -ne $property.Value) { $update[$property.Name] = $property.Value }
}
$update['CallbackURLs'] = @(@($client.CallbackURLs) + $callback | Select-Object -Unique)
$update['LogoutURLs'] = @(@($client.LogoutURLs) + $callback | Select-Object -Unique)
$tempFile = Join-Path ([IO.Path]::GetTempPath()) ('family-client-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    [IO.File]::WriteAllText($tempFile, ($update | ConvertTo-Json -Depth 30), (New-Object Text.UTF8Encoding($false)))
    $result = & aws cognito-idp update-user-pool-client --cli-input-json ('file://' + $tempFile) @common --query UserPoolClient.ClientId --output text 2>&1
    if ($LASTEXITCODE) { throw 'Could not register the account callback. Check the AWS login session.' }
    $verified = & aws cognito-idp describe-user-pool-client --user-pool-id $config.userPoolId --client-id $config.clientId @common --output json 2>&1
    if ($LASTEXITCODE) { throw 'Could not verify the registered account callback.' }
    $after = ($verified -join "`n" | ConvertFrom-Json).UserPoolClient
    if ($after.CallbackURLs -notcontains $callback -or $after.LogoutURLs -notcontains $callback) { throw 'Account callback registration was not retained.' }
    Write-Output 'Registered the current account callback and preserved existing client settings.'
} finally { Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue }
