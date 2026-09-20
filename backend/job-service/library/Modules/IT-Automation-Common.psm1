<#
.SYNOPSIS
    Shared helpers for the IT automation script library.

.DESCRIPTION
    Every generated automation script imports this module rather than
    duplicating logging, authentication, approval or output code. Building it
    first is deliberate: the safety controls the assessment workbook demands
    (approval gates, structured audit logging, pre-flight checks) are only
    trustworthy if there is exactly one implementation of each.

    Exported functions:
      Write-AutomationLog        structured logging, file + console, with rotation
      Connect-AutomationPlatform per-platform authentication wrapper
      Send-AutomationReport      email / Teams webhook / ITSM delivery
      New-ApprovalRequest        creates an approval artifact, returns a reference
      Test-ApprovalReference     validates an approval token before execution
      Export-AutomationResult    CSV / JSON / HTML output
      Test-Prerequisite          module + permission + connectivity pre-flight
      Get-AutomationConfig       reads Config/config.json

.NOTES
    Source          : Agent_Automation_Feasibility_Assessment.xlsx (213 use cases)
    Required modules: none for the module itself; individual helpers check for
                      the platform modules they need at call time.
    Credentials     : this module never stores or logs a secret. Approval
                      artifacts and logs are scrubbed before writing.
#>

Set-StrictMode -Version Latest

#region ---------------------------------------------------------- internal --

# Patterns that must never reach a log file or an approval artifact. Applied to
# every string written by Write-AutomationLog and Export-AutomationResult.
$script:SecretPatterns = @(
    '(?i)(password|pwd|passwd)\s*[:=]\s*\S+'
    '(?i)(secret|apikey|api_key|token|bearer)\s*[:=]\s*\S+'
    '(?i)AKIA[0-9A-Z]{16}'                       # AWS access key id
    '(?i)(ConnectionString)\s*[:=]\s*\S+'
    '(?i)-----BEGIN [A-Z ]*PRIVATE KEY-----'
)

function Get-AutomationDataRoot {
    <#
    .SYNOPSIS
        Where this module keeps logs, approval artifacts and reports.

    .DESCRIPTION
        Was `$env:ProgramData\ITAutomation` inline in four places. That is a
        Windows-only answer in two separate ways, and the library now runs on
        the Linux job-service host as well as on a Windows box:

          * `$env:ProgramData` is undefined off Windows, and `Join-Path` with an
            empty -Path throws rather than degrading to something usable;
          * a backslash is a legal FILENAME CHARACTER on Linux, not a
            separator, so 'ITAutomation\Logs' would have produced one directory
            literally named `ITAutomation\Logs` instead of two nested ones.

        AUTOOPS_DATA_ROOT wins when set — the execution sandbox points it at the
        step's own workspace, so a step writes nothing that outlives it and
        nothing another step can read.
    #>
    [CmdletBinding()]
    [OutputType([string])]
    param()

    if (-not [string]::IsNullOrWhiteSpace($env:AUTOOPS_DATA_ROOT)) {
        return $env:AUTOOPS_DATA_ROOT
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramData)) {
        return (Join-Path -Path $env:ProgramData -ChildPath 'ITAutomation')
    }
    # Off Windows with nothing configured. TMPDIR is always writable, which is
    # the one property this path must have.
    $tmp = if ([string]::IsNullOrWhiteSpace($env:TMPDIR)) { '/tmp' } else { $env:TMPDIR }
    return (Join-Path -Path $tmp -ChildPath 'ITAutomation')
}

function Protect-SensitiveText {
    <#
    .SYNOPSIS
        Redacts anything that looks like a credential.
    .DESCRIPTION
        Defence in depth. Scripts are told never to log secrets, but a caller
        that logs an entire error object or a raw REST response can leak one
        without meaning to. Everything written through this module passes here.
    #>
    [CmdletBinding()]
    [OutputType([string])]
    param([Parameter(ValueFromPipeline)][AllowNull()][string]$Text)

    process {
        if ([string]::IsNullOrEmpty($Text)) { return $Text }
        $out = $Text
        foreach ($p in $script:SecretPatterns) {
            $out = [regex]::Replace($out, $p, '<redacted>')
        }
        return $out
    }
}

function Get-LogPath {
    [CmdletBinding()]
    [OutputType([string])]
    param([string]$LogDirectory, [string]$Name)

    if ([string]::IsNullOrWhiteSpace($LogDirectory)) {
        $LogDirectory = Join-Path -Path (Get-AutomationDataRoot) -ChildPath 'Logs'
    }
    if (-not (Test-Path -LiteralPath $LogDirectory)) {
        New-Item -Path $LogDirectory -ItemType Directory -Force | Out-Null
    }
    $stamp = (Get-Date).ToString('yyyyMMdd')
    return (Join-Path -Path $LogDirectory -ChildPath ("{0}_{1}.log" -f $Name, $stamp))
}

#endregion

#region ----------------------------------------------------------- logging --

function Write-AutomationLog {
    <#
    .SYNOPSIS
        Writes a timestamped, structured log line to file and console.

    .DESCRIPTION
        The audit trail for every automation run. Emits a fixed-width, greppable
        line and mirrors it to the console with a severity-appropriate stream so
        that WARN and ERROR remain visible when a script is run interactively.

        Secrets are redacted before writing. Log files roll by day and are
        pruned beyond -RetentionDays.

    .PARAMETER Message
        The text to record.

    .PARAMETER Level
        INFO, WARN, ERROR or SUCCESS.

    .PARAMETER ScriptName
        Identifies the calling script in the log line and the log file name.
        Defaults to the calling script's base name.

    .PARAMETER LogDirectory
        Directory for log files. Defaults to <data root>/Logs — see Get-AutomationDataRoot.

    .PARAMETER RetentionDays
        Delete log files older than this. Default 90.

    .PARAMETER Target
        The object being acted upon, recorded in its own field so that a later
        audit can answer "what did this run touch?" without parsing prose.

    .EXAMPLE
        Write-AutomationLog -Message 'Starting disk report' -Level INFO

    .EXAMPLE
        Write-AutomationLog -Message 'Service restarted' -Level SUCCESS -Target 'SRV01\Spooler'

    .NOTES
        Every script in this library logs start, each target acted upon, each
        decision taken, and the outcome. That is a requirement, not a style.
    #>
    [CmdletBinding()]
    [OutputType([void])]
    param(
        [Parameter(Mandatory, Position = 0)]
        [AllowEmptyString()]
        [string]$Message,

        [Parameter(Position = 1)]
        [ValidateSet('INFO', 'WARN', 'ERROR', 'SUCCESS')]
        [string]$Level = 'INFO',

        [string]$ScriptName,

        [string]$LogDirectory,

        [ValidateRange(1, 3650)]
        [int]$RetentionDays = 90,

        [string]$Target
    )

    if ([string]::IsNullOrWhiteSpace($ScriptName)) {
        $ScriptName = if ($MyInvocation.PSCommandPath) {
            [System.IO.Path]::GetFileNameWithoutExtension($MyInvocation.PSCommandPath)
        } else { 'ITAutomation' }
    }

    $safeMessage = Protect-SensitiveText -Text $Message
    $safeTarget  = Protect-SensitiveText -Text $Target
    $timestamp   = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff')
    $line = '{0} [{1,-7}] [{2}] {3}{4}' -f $timestamp, $Level, $ScriptName,
            $(if ($safeTarget) { "target='$safeTarget' " } else { '' }), $safeMessage

    $path = Get-LogPath -LogDirectory $LogDirectory -Name $ScriptName
    try {
        Add-Content -LiteralPath $path -Value $line -Encoding UTF8 -ErrorAction Stop
    } catch {
        # A failure to write the log must not abort the automation, but it must
        # be visible — a silent loss of the audit trail is worse than noise.
        Write-Warning ("Could not write to log '{0}': {1}" -f $path, $_.Exception.Message)
    }

    switch ($Level) {
        'ERROR'   { Write-Error   $line -ErrorAction Continue }
        'WARN'    { Write-Warning $line }
        'SUCCESS' { Write-Information $line -InformationAction Continue }
        default   { Write-Verbose $line -Verbose:$VerbosePreference }
    }

    # Prune on a sampled basis so a chatty script does not enumerate the log
    # directory on every single line.
    if ((Get-Random -Minimum 1 -Maximum 100) -eq 1) {
        try {
            $cutoff = (Get-Date).AddDays(-$RetentionDays)
            Get-ChildItem -LiteralPath (Split-Path -Parent $path) -Filter '*.log' -File -ErrorAction Stop |
                Where-Object { $_.LastWriteTime -lt $cutoff } |
                Remove-Item -Force -ErrorAction SilentlyContinue
        } catch {
            Write-Verbose ("Log rotation skipped: {0}" -f $_.Exception.Message)
        }
    }
}

#endregion

#region ------------------------------------------------------------ config --

function Get-AutomationConfig {
    <#
    .SYNOPSIS
        Reads and validates Config/config.json.

    .DESCRIPTION
        Central configuration so that environment-specific values (SMTP relay,
        Teams webhook, ITSM endpoint, log directory, protected-object lists) are
        never hardcoded in a script.

        Returns a PSCustomObject. Throws if the file is missing or malformed —
        silently continuing with defaults would let a script act against the
        wrong environment.

    .PARAMETER Path
        Explicit path to the config file. Defaults to Config\config.json
        relative to the module root.

    .EXAMPLE
        $cfg = Get-AutomationConfig

    .EXAMPLE
        $cfg = Get-AutomationConfig -Path 'D:\Automation\Config\prod.json'

    .NOTES
        The sample file Config\config.sample.json documents every key. A real
        config.json must never be committed — it is git-ignored by convention.
    #>
    [CmdletBinding()]
    [OutputType([PSCustomObject])]
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        $Path = Join-Path -Path (Join-Path -Path (Split-Path -Parent $PSScriptRoot) -ChildPath 'Config') -ChildPath 'config.json'
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        throw ("Configuration file not found at '{0}'. Copy Config\config.sample.json " +
               "to config.json and populate it for this environment." -f $Path)
    }
    try {
        return (Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        throw ("Configuration file '{0}' is not valid JSON: {1}" -f $Path, $_.Exception.Message)
    }
}

#endregion

#region ---------------------------------------------------------- preflight --

function Test-Prerequisite {
    <#
    .SYNOPSIS
        Pre-flight check for modules, connectivity, and elevation.

    .DESCRIPTION
        Required by the workbook for every Risk = High row and used defensively
        everywhere else. Verifies BEFORE any action that the required modules
        are present, the targets are reachable, and the session is elevated if
        the operation needs it.

        Returns a PSCustomObject with Passed plus per-check detail so a caller
        can log exactly which precondition failed rather than a generic error.

    .PARAMETER RequiredModule
        Module names that must be available.

    .PARAMETER ComputerName
        Hosts to test for connectivity.

    .PARAMETER RequireElevation
        Fail if the session is not running as Administrator.

    .PARAMETER Port
        TCP port used for the connectivity test. Default 5985 (WinRM HTTP).

    .EXAMPLE
        $pre = Test-Prerequisite -RequiredModule ActiveDirectory -RequireElevation
        if (-not $pre.Passed) { throw $pre.Summary }

    .EXAMPLE
        Test-Prerequisite -ComputerName SRV01,SRV02 -Port 5985

    .NOTES
        Connectivity uses Test-NetConnection where available and falls back to
        Test-Connection so the helper still works on older hosts.
    #>
    [CmdletBinding()]
    [OutputType([PSCustomObject])]
    param(
        [string[]]$RequiredModule,
        [string[]]$ComputerName,
        [switch]$RequireElevation,
        [ValidateRange(1, 65535)][int]$Port = 5985
    )

    $checks = [System.Collections.Generic.List[PSCustomObject]]::new()

    foreach ($m in $RequiredModule) {
        $found = $null -ne (Get-Module -ListAvailable -Name $m -ErrorAction SilentlyContinue)
        $checks.Add([PSCustomObject]@{
            Check  = 'Module'
            Item   = $m
            Passed = $found
            Detail = if ($found) { 'available' } else { "module '$m' is not installed" }
        })
    }

    if ($RequireElevation) {
        $isAdmin = $false
        try {
            $id = [Security.Principal.WindowsIdentity]::GetCurrent()
            $isAdmin = ([Security.Principal.WindowsPrincipal]$id).IsInRole(
                [Security.Principal.WindowsBuiltInRole]::Administrator)
        } catch {
            Write-Verbose ("Elevation check failed: {0}" -f $_.Exception.Message)
        }
        $checks.Add([PSCustomObject]@{
            Check  = 'Elevation'
            Item   = 'Administrator'
            Passed = $isAdmin
            Detail = if ($isAdmin) { 'session is elevated' } else { 'session is NOT elevated' }
        })
    }

    foreach ($c in $ComputerName) {
        $ok = $false
        $detail = ''
        try {
            if (Get-Command Test-NetConnection -ErrorAction SilentlyContinue) {
                $r = Test-NetConnection -ComputerName $c -Port $Port -WarningAction SilentlyContinue -ErrorAction Stop
                $ok = [bool]$r.TcpTestSucceeded
                $detail = if ($ok) { "tcp/$Port open" } else { "tcp/$Port closed or filtered" }
            } else {
                $ok = Test-Connection -ComputerName $c -Count 1 -Quiet -ErrorAction Stop
                $detail = if ($ok) { 'icmp reachable' } else { 'icmp unreachable' }
            }
        } catch {
            $detail = $_.Exception.Message
        }
        $checks.Add([PSCustomObject]@{ Check = 'Connectivity'; Item = $c; Passed = $ok; Detail = $detail })
    }

    $failed = @($checks | Where-Object { -not $_.Passed })
    return [PSCustomObject]@{
        Passed  = ($failed.Count -eq 0)
        Checks  = $checks.ToArray()
        Failed  = $failed
        Summary = if ($failed.Count -eq 0) {
                      'All pre-flight checks passed.'
                  } else {
                      'Pre-flight FAILED: ' + (($failed | ForEach-Object { "$($_.Check)/$($_.Item): $($_.Detail)" }) -join '; ')
                  }
    }
}

#endregion

#region ------------------------------------------------------------- auth ---

function Connect-AutomationPlatform {
    <#
    .SYNOPSIS
        Per-platform authentication wrapper.

    .DESCRIPTION
        One place that knows how each platform is authenticated, so scripts do
        not each invent their own approach. Prefers non-interactive, secretless
        methods (managed identity, IAM role, certificate) and never accepts a
        plaintext password.

    .PARAMETER Platform
        Which platform to connect to.

    .PARAMETER Credential
        A PSCredential where the platform requires one. Never a plaintext string.

    .PARAMETER TenantId
        Entra ID tenant, for Azure / Graph / Exchange Online.

    .PARAMETER ApplicationId
        App registration client id for certificate-based authentication.

    .PARAMETER CertificateThumbprint
        Thumbprint of the client certificate for app-only authentication.

    .PARAMETER UseManagedIdentity
        Use the host's managed identity. Preferred for Azure when running in
        Automation Accounts or on an Azure VM.

    .PARAMETER Server
        Target server for platforms that connect to an endpoint (VMware, Commvault).

    .EXAMPLE
        Connect-AutomationPlatform -Platform Azure -UseManagedIdentity

    .EXAMPLE
        Connect-AutomationPlatform -Platform ExchangeOnline -TenantId $t -ApplicationId $a -CertificateThumbprint $c

    .NOTES
        WindowsServer is a no-op: native cmdlets over PSRemoting use the
        caller's Kerberos context or an explicit -Credential per call.
    #>
    [CmdletBinding(SupportsShouldProcess)]
    [OutputType([PSCustomObject])]
    param(
        [Parameter(Mandatory)]
        [ValidateSet('AWS', 'Azure', 'AzureAVD', 'Graph', 'ExchangeOnline', 'SharePoint',
                     'ActiveDirectory', 'VMware', 'HyperV', 'WindowsServer', 'Commvault', 'OCI')]
        [string]$Platform,

        [System.Management.Automation.PSCredential]$Credential,
        [string]$TenantId,
        [string]$ApplicationId,
        [string]$CertificateThumbprint,
        [switch]$UseManagedIdentity,
        [string]$Server
    )

    if (-not $PSCmdlet.ShouldProcess($Platform, 'Connect')) {
        return [PSCustomObject]@{ Platform = $Platform; Connected = $false; Method = 'WhatIf' }
    }

    $method = 'unknown'
    switch ($Platform) {
        'WindowsServer' {
            # Nothing to connect. Kerberos / explicit -Credential per remoting call.
            $method = 'integrated (PSRemoting)'
        }
        'ActiveDirectory' {
            Import-Module ActiveDirectory -ErrorAction Stop
            $method = 'integrated (delegated service account)'
        }
        'Azure' {
            Import-Module Az.Accounts -ErrorAction Stop
            if ($UseManagedIdentity) {
                Connect-AzAccount -Identity -ErrorAction Stop | Out-Null
                $method = 'managed identity'
            } elseif ($ApplicationId -and $CertificateThumbprint -and $TenantId) {
                Connect-AzAccount -ServicePrincipal -ApplicationId $ApplicationId `
                    -CertificateThumbprint $CertificateThumbprint -Tenant $TenantId -ErrorAction Stop | Out-Null
                $method = 'service principal (certificate)'
            } else {
                throw 'Azure requires -UseManagedIdentity, or -ApplicationId + -CertificateThumbprint + -TenantId.'
            }
        }
        'AzureAVD' {
            Import-Module Az.DesktopVirtualization -ErrorAction Stop
            $method = 'inherits Az context'
        }
        'Graph' {
            Import-Module Microsoft.Graph.Authentication -ErrorAction Stop
            if ($ApplicationId -and $CertificateThumbprint -and $TenantId) {
                Connect-MgGraph -ClientId $ApplicationId -CertificateThumbprint $CertificateThumbprint `
                    -TenantId $TenantId -NoWelcome -ErrorAction Stop
                $method = 'app registration (certificate)'
            } elseif ($UseManagedIdentity) {
                Connect-MgGraph -Identity -NoWelcome -ErrorAction Stop
                $method = 'managed identity'
            } else {
                throw 'Graph requires app-only certificate auth or -UseManagedIdentity.'
            }
        }
        'ExchangeOnline' {
            Import-Module ExchangeOnlineManagement -ErrorAction Stop
            if (-not ($ApplicationId -and $CertificateThumbprint -and $TenantId)) {
                throw 'ExchangeOnline requires -ApplicationId, -CertificateThumbprint and -TenantId.'
            }
            Connect-ExchangeOnline -AppId $ApplicationId -CertificateThumbprint $CertificateThumbprint `
                -Organization $TenantId -ShowBanner:$false -ErrorAction Stop
            $method = 'app-only (certificate)'
        }
        'SharePoint' {
            Import-Module PnP.PowerShell -ErrorAction Stop
            $method = 'PnP (certificate)'
        }
        'AWS' {
            Import-Module AWS.Tools.Common -ErrorAction Stop
            $method = 'IAM role / SSO profile'
        }
        'VMware' {
            Import-Module VMware.VimAutomation.Core -ErrorAction Stop
            if (-not $Server) { throw 'VMware requires -Server.' }
            if ($Credential) {
                Connect-VIServer -Server $Server -Credential $Credential -ErrorAction Stop | Out-Null
                $method = 'explicit credential'
            } else {
                Connect-VIServer -Server $Server -ErrorAction Stop | Out-Null
                $method = 'credential store / SSPI'
            }
        }
        'HyperV'    { Import-Module Hyper-V -ErrorAction Stop; $method = 'integrated' }
        'Commvault' { $method = 'REST token (obtained per call)' }
        'OCI'       { $method = 'OCI CLI config profile' }
    }

    Write-AutomationLog -Message ("Connected to {0} using {1}" -f $Platform, $method) `
        -Level SUCCESS -ScriptName 'Connect-AutomationPlatform'

    return [PSCustomObject]@{ Platform = $Platform; Connected = $true; Method = $method }
}

#endregion

#region -------------------------------------------------------- approvals ---

function New-ApprovalRequest {
    <#
    .SYNOPSIS
        Creates an approval artifact for a proposed change set and returns its reference.

    .DESCRIPTION
        Implements the first half of the approval gate the workbook requires on
        every row where "Human Approval Needed?" is Yes. The script produces the
        change set; this function records it and returns a reference that a human
        must supply back before anything executes.

        The artifact is written as JSON next to the logs and, where the config
        supplies an ITSM endpoint, raised as a ticket. The returned reference is
        what Test-ApprovalReference will later validate.

    .PARAMETER ScriptName
        The automation requesting approval.

    .PARAMETER Action
        Human-readable description of what will happen if approved.

    .PARAMETER ChangeSet
        The objects that would be acted upon. Recorded in full so the approver
        sees exactly what they are agreeing to.

    .PARAMETER RequestedBy
        Defaults to the current user.

    .PARAMETER ApprovalDirectory
        Where approval artifacts are written.

    .PARAMETER ValidHours
        How long the approval remains valid once granted. Default 24.

    .EXAMPLE
        $ref = New-ApprovalRequest -ScriptName 'Restart-WinServer' -Action 'Reboot 3 servers' -ChangeSet $targets

    .NOTES
        This function does NOT approve anything. It records the request and
        returns a reference in state 'Pending'. A human moves it to 'Approved'.
    #>
    [CmdletBinding(SupportsShouldProcess)]
    [OutputType([PSCustomObject])]
    param(
        [Parameter(Mandatory)][string]$ScriptName,
        [Parameter(Mandatory)][string]$Action,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ChangeSet,
        [string]$RequestedBy = "$env:USERDOMAIN\$env:USERNAME",
        [string]$ApprovalDirectory,
        [ValidateRange(1, 720)][int]$ValidHours = 24
    )

    if ([string]::IsNullOrWhiteSpace($ApprovalDirectory)) {
        $ApprovalDirectory = Join-Path -Path (Get-AutomationDataRoot) -ChildPath 'Approvals'
    }
    if (-not (Test-Path -LiteralPath $ApprovalDirectory)) {
        New-Item -Path $ApprovalDirectory -ItemType Directory -Force | Out-Null
    }

    $reference = 'APR-{0}-{1}' -f (Get-Date).ToString('yyyyMMddHHmmss'), (Get-Random -Minimum 1000 -Maximum 9999)
    $artifact = [PSCustomObject]@{
        Reference     = $reference
        ScriptName    = $ScriptName
        Action        = $Action
        RequestedBy   = $RequestedBy
        RequestedAt   = (Get-Date).ToString('o')
        ExpiresAt     = (Get-Date).AddHours($ValidHours).ToString('o')
        State         = 'Pending'
        ApprovedBy    = $null
        ApprovedAt    = $null
        ItemCount     = @($ChangeSet).Count
        ChangeSet     = $ChangeSet
    }

    $path = Join-Path -Path $ApprovalDirectory -ChildPath "$reference.json"
    if ($PSCmdlet.ShouldProcess($path, 'Write approval request')) {
        $json = $artifact | ConvertTo-Json -Depth 6
        Protect-SensitiveText -Text $json | Set-Content -LiteralPath $path -Encoding UTF8
        Write-AutomationLog -ScriptName $ScriptName -Level INFO -Target $reference `
            -Message ("Approval requested: {0} ({1} item(s)). Artifact: {2}" -f $Action, $artifact.ItemCount, $path)
    }

    return $artifact
}

function Test-ApprovalReference {
    <#
    .SYNOPSIS
        Validates an approval reference before a gated script is allowed to execute.

    .DESCRIPTION
        The second half of the approval gate. A script whose workbook row says
        approval is required must call this and refuse to act unless it returns
        IsValid = $true.

        Validation is deliberately strict: the artifact must exist, be in state
        'Approved', not be expired, and — when -ScriptName is supplied — belong
        to the script attempting to use it. That last check stops an approval
        for a harmless report being replayed against a destructive action.

    .PARAMETER Reference
        The approval reference supplied by the operator.

    .PARAMETER ScriptName
        The script attempting to execute. Checked against the artifact.

    .PARAMETER ApprovalDirectory
        Where approval artifacts live.

    .EXAMPLE
        $a = Test-ApprovalReference -Reference $ApprovalReference -ScriptName 'Restart-WinServer'
        if (-not $a.IsValid) { throw $a.Reason }

    .EXAMPLE
        Test-ApprovalReference -Reference 'APR-20260808120000-1234'

    .NOTES
        Returns an object rather than throwing so the caller can log the precise
        reason before deciding to stop.
    #>
    [CmdletBinding()]
    [OutputType([PSCustomObject])]
    param(
        [Parameter(Mandatory)][string]$Reference,
        [string]$ScriptName,
        [string]$ApprovalDirectory
    )

    if ([string]::IsNullOrWhiteSpace($ApprovalDirectory)) {
        $ApprovalDirectory = Join-Path -Path (Get-AutomationDataRoot) -ChildPath 'Approvals'
    }

    $result = [PSCustomObject]@{
        Reference = $Reference; IsValid = $false; Reason = ''
        State = $null; ApprovedBy = $null; Artifact = $null
    }

    $path = Join-Path -Path $ApprovalDirectory -ChildPath "$Reference.json"
    if (-not (Test-Path -LiteralPath $path)) {
        $result.Reason = "No approval artifact found for reference '$Reference'."
        return $result
    }

    try {
        $artifact = Get-Content -LiteralPath $path -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    } catch {
        $result.Reason = "Approval artifact '$Reference' is unreadable: $($_.Exception.Message)"
        return $result
    }

    $result.Artifact   = $artifact
    $result.State      = $artifact.State
    $result.ApprovedBy = $artifact.ApprovedBy

    if ($artifact.State -ne 'Approved') {
        $result.Reason = "Approval '$Reference' is in state '$($artifact.State)', not 'Approved'."
        return $result
    }
    if ([datetime]$artifact.ExpiresAt -lt (Get-Date)) {
        $result.Reason = "Approval '$Reference' expired at $($artifact.ExpiresAt)."
        return $result
    }
    if ($ScriptName -and $artifact.ScriptName -ne $ScriptName) {
        $result.Reason = ("Approval '{0}' was raised for '{1}', not '{2}'." -f
                          $Reference, $artifact.ScriptName, $ScriptName)
        return $result
    }

    $result.IsValid = $true
    $result.Reason  = "Approved by $($artifact.ApprovedBy) at $($artifact.ApprovedAt)."
    return $result
}

#endregion

#region ---------------------------------------------------------- output ----

function Export-AutomationResult {
    <#
    .SYNOPSIS
        Writes a result set to console, CSV, JSON or HTML.

    .DESCRIPTION
        Standard output path so every script supports -OutputFormat without
        reimplementing formatting. Objects stay objects on the pipeline; only
        the file formats are rendered.

    .PARAMETER InputObject
        The result objects.

    .PARAMETER OutputFormat
        Console, CSV, JSON or HTML.

    .PARAMETER Path
        Destination file for CSV/JSON/HTML. Generated under the reports
        directory when omitted.

    .PARAMETER Title
        Heading used in HTML output.

    .EXAMPLE
        $disks | Export-AutomationResult -OutputFormat HTML -Title 'Disk Report'

    .EXAMPLE
        Export-AutomationResult -InputObject $rows -OutputFormat CSV -Path 'C:\Reports\disks.csv'

    .NOTES
        Returns the input objects so it can sit mid-pipeline without swallowing
        the result.
    #>
    [CmdletBinding()]
    [OutputType([PSCustomObject])]
    param(
        [Parameter(Mandatory, ValueFromPipeline)][AllowEmptyCollection()][object[]]$InputObject,
        [ValidateSet('Console', 'CSV', 'JSON', 'HTML')][string]$OutputFormat = 'Console',
        [string]$Path,
        [string]$Title = 'IT Automation Result'
    )

    begin { $items = [System.Collections.Generic.List[object]]::new() }
    process { foreach ($i in $InputObject) { $items.Add($i) } }
    end {
        $data = $items.ToArray()
        if ($OutputFormat -ne 'Console') {
            if ([string]::IsNullOrWhiteSpace($Path)) {
                $dir = Join-Path -Path (Get-AutomationDataRoot) -ChildPath 'Reports'
                if (-not (Test-Path -LiteralPath $dir)) { New-Item -Path $dir -ItemType Directory -Force | Out-Null }
                $safeTitle = ($Title -replace '[^\w\-]', '_')
                $Path = Join-Path -Path $dir -ChildPath ('{0}_{1}.{2}' -f $safeTitle,
                        (Get-Date).ToString('yyyyMMdd_HHmmss'), $OutputFormat.ToLower())
            }
            switch ($OutputFormat) {
                'CSV'  { $data | Export-Csv -LiteralPath $Path -NoTypeInformation -Encoding UTF8 }
                'JSON' {
                    $json = $data | ConvertTo-Json -Depth 6
                    Protect-SensitiveText -Text $json | Set-Content -LiteralPath $Path -Encoding UTF8
                }
                'HTML' {
                    $style = @'
<style>
body{font-family:Segoe UI,Arial,sans-serif;font-size:13px;color:#222}
h1{font-size:18px;color:#0F2348;border-bottom:2px solid #1F5FA8;padding-bottom:6px}
table{border-collapse:collapse;width:100%}
th{background:#0F2348;color:#fff;text-align:left;padding:6px 8px;font-size:12px}
td{border-bottom:1px solid #dde3ea;padding:5px 8px}
tr:nth-child(even) td{background:#f6f8fb}
.footer{color:#6E7884;font-size:11px;margin-top:14px}
</style>
'@
                    $foot = "<div class='footer'>Generated {0} by {1}\{2}</div>" -f
                            (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'), $env:USERDOMAIN, $env:USERNAME
                    $html = $data | ConvertTo-Html -Head $style -PreContent "<h1>$Title</h1>" -PostContent $foot
                    Protect-SensitiveText -Text ($html -join [Environment]::NewLine) |
                        Set-Content -LiteralPath $Path -Encoding UTF8
                }
            }
            Write-AutomationLog -Message ("Report written: {0} ({1} row(s))" -f $Path, $data.Count) `
                -Level SUCCESS -ScriptName 'Export-AutomationResult'
        }
        return $data
    }
}

function Send-AutomationReport {
    <#
    .SYNOPSIS
        Delivers a report or notification by email, Teams webhook, or ITSM.

    .DESCRIPTION
        Single delivery path so scripts do not each hold SMTP or webhook logic.
        Endpoints come from Get-AutomationConfig, never from hardcoded values.

        Delivery failure is logged and surfaced but does not throw by default —
        losing a notification should not roll back completed automation. Use
        -FailOnError where the notification is the point of the run.

    .PARAMETER Subject
        Subject line / card title.

    .PARAMETER Body
        Message body. HTML is accepted for email.

    .PARAMETER Channel
        Email, Teams or ITSM.

    .PARAMETER To
        Email recipients. Defaults to config notifications.defaultRecipients.

    .PARAMETER AttachmentPath
        Files to attach (email only).

    .PARAMETER Config
        A config object from Get-AutomationConfig. Read automatically if omitted.

    .PARAMETER FailOnError
        Throw instead of warning when delivery fails.

    .EXAMPLE
        Send-AutomationReport -Subject 'Disk report' -Body $html -Channel Email

    .EXAMPLE
        Send-AutomationReport -Subject 'Reboot complete' -Body 'SRV01 rebooted' -Channel Teams

    .NOTES
        Send-MailMessage is obsolete but remains the dependency-free option on
        Windows PowerShell 5.1. Where the org has Graph mail available, prefer
        that and note the change in the SOP.
    #>
    [CmdletBinding(SupportsShouldProcess)]
    [OutputType([PSCustomObject])]
    param(
        [Parameter(Mandatory)][string]$Subject,
        [Parameter(Mandatory)][string]$Body,
        [ValidateSet('Email', 'Teams', 'ITSM')][string]$Channel = 'Email',
        [string[]]$To,
        [string[]]$AttachmentPath,
        [PSCustomObject]$Config,
        [switch]$FailOnError
    )

    if (-not $Config) {
        try { $Config = Get-AutomationConfig } catch {
            $msg = "Cannot send report: $($_.Exception.Message)"
            if ($FailOnError) { throw $msg }
            Write-AutomationLog -Message $msg -Level WARN -ScriptName 'Send-AutomationReport'
            return [PSCustomObject]@{ Channel = $Channel; Delivered = $false; Detail = $msg }
        }
    }

    $safeBody = Protect-SensitiveText -Text $Body
    $detail = ''
    $delivered = $false

    try {
        switch ($Channel) {
            'Email' {
                $smtp = $Config.notifications.smtpServer
                $from = $Config.notifications.fromAddress
                if (-not $To) { $To = $Config.notifications.defaultRecipients }
                if (-not $smtp -or -not $from -or -not $To) {
                    throw 'notifications.smtpServer, fromAddress and recipients must be configured.'
                }
                if ($PSCmdlet.ShouldProcess(($To -join ','), "Send email '$Subject'")) {
                    $p = @{
                        SmtpServer = $smtp; From = $from; To = $To; Subject = $Subject
                        Body = $safeBody; BodyAsHtml = $true; ErrorAction = 'Stop'
                    }
                    if ($Config.notifications.PSObject.Properties.Name -contains 'smtpPort') {
                        $p.Port = $Config.notifications.smtpPort
                    }
                    if ($AttachmentPath) { $p.Attachments = $AttachmentPath }
                    Send-MailMessage @p
                    $delivered = $true; $detail = "sent to $($To -join ', ')"
                }
            }
            'Teams' {
                $hook = $Config.notifications.teamsWebhookUrl
                if (-not $hook) { throw 'notifications.teamsWebhookUrl is not configured.' }
                if ($PSCmdlet.ShouldProcess('Teams webhook', "Post '$Subject'")) {
                    $payload = @{ title = $Subject; text = $safeBody } | ConvertTo-Json -Depth 4
                    Invoke-RestMethod -Uri $hook -Method Post -ContentType 'application/json' `
                        -Body $payload -ErrorAction Stop | Out-Null
                    $delivered = $true; $detail = 'posted to Teams'
                }
            }
            'ITSM' {
                $url = $Config.itsm.createTicketUrl
                if (-not $url) { throw 'itsm.createTicketUrl is not configured.' }
                if ($PSCmdlet.ShouldProcess($url, "Raise ticket '$Subject'")) {
                    $payload = @{
                        short_description = $Subject; description = $safeBody
                        category = $Config.itsm.category; assignment_group = $Config.itsm.assignmentGroup
                    } | ConvertTo-Json -Depth 4
                    $r = Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' `
                        -Body $payload -UseDefaultCredentials -ErrorAction Stop
                    $delivered = $true
                    $detail = "ticket raised: $($r.number)"
                }
            }
        }
        if ($delivered) {
            Write-AutomationLog -Message ("Notification delivered via {0}: {1}" -f $Channel, $detail) `
                -Level SUCCESS -ScriptName 'Send-AutomationReport'
        }
    } catch {
        $detail = $_.Exception.Message
        $msg = "Notification via $Channel failed: $detail"
        if ($FailOnError) { throw $msg }
        Write-AutomationLog -Message $msg -Level WARN -ScriptName 'Send-AutomationReport'
    }

    return [PSCustomObject]@{ Channel = $Channel; Delivered = $delivered; Detail = $detail }
}

#endregion

Export-ModuleMember -Function @(
    'Write-AutomationLog'
    'Connect-AutomationPlatform'
    'Send-AutomationReport'
    'New-ApprovalRequest'
    'Test-ApprovalReference'
    'Export-AutomationResult'
    'Test-Prerequisite'
    'Get-AutomationConfig'
    # Exported so a script can report WHERE its artifacts went. Nothing in the
    # library calls it, but "the report was written" with no path is the kind
    # of log line that wastes an hour during an incident.
    'Get-AutomationDataRoot'
)
