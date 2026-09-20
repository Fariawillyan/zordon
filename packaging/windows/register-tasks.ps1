<#
    Registra as tarefas agendadas do Zordon no Windows.

    Copyright 2026 Willyan Faria - Apache License 2.0

    A tarefa "Zordon WSL Boot" e a resposta ao risco R1: o WSL nao sobe sozinho
    no boot do Windows, e sem ela o nucleo so existe depois que alguem abre um
    terminal. O comando e deliberadamente inofensivo - subir a distro ja dispara
    o systemd, que sobe o zordon.service. Ela repete a cada 5 min: e o
    supervisor do WSL (ADR-0027), que religa a distro depois de um
    "wsl --shutdown" sem que nenhum processo do Zordon execute comandos.

    A tarefa "Zordon Host" (SPEC-007) inicia o host do Windows no logon, com
    javaw.exe (sem janela), e o reinicia se ele cair. So e registrada quando
    -HostDir e -Javaw sao informados; o install-host.sh mostra o comando pronto.
#>

[CmdletBinding()]
param(
    [string] $Distro = 'Ubuntu',
    [string] $HostDir = '',
    [string] $Javaw = ''
)

$ErrorActionPreference = 'Stop'

function Register-ZordonTask {
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [string] $Description,
        [Parameter(Mandatory)] $Action,
        [TimeSpan] $RepeatEvery = [TimeSpan]::Zero,
        [switch] $RestartOnFailure
    )

    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    if ($RepeatEvery -gt [TimeSpan]::Zero) {
        # Repeticao sem prazo, a partir do logon.
        $trigger.Repetition = (New-ScheduledTaskTrigger -Once -At (Get-Date) `
            -RepetitionInterval $RepeatEvery).Repetition
    }

    $options = @{
        AllowStartIfOnBatteries    = $true
        DontStopIfGoingOnBatteries = $true
        StartWhenAvailable         = $true
        ExecutionTimeLimit         = [TimeSpan]::Zero
        MultipleInstances          = 'IgnoreNew'
    }
    if ($RestartOnFailure) {
        $options.RestartCount = 999
        $options.RestartInterval = New-TimeSpan -Minutes 1
    }
    $settings = New-ScheduledTaskSettingsSet @options

    Register-ScheduledTask -TaskName $Name -Description $Description `
        -Trigger $trigger -Action $Action -Settings $settings -Force | Out-Null
    Write-Host "  registrada: $Name" -ForegroundColor Green
}

Write-Host "Zordon - tarefas agendadas" -ForegroundColor Cyan

Register-ZordonTask -Name 'Zordon WSL Boot' `
    -Description 'Sobe a distro do WSL no logon e a religa a cada 5 min se ela cair (ADR-0027).' `
    -Action (New-ScheduledTaskAction -Execute 'wsl.exe' -Argument "-d $Distro --exec /bin/true") `
    -RepeatEvery (New-TimeSpan -Minutes 5)

if ($HostDir -and $Javaw) {
    Register-ZordonTask -Name 'Zordon Host' `
        -Description 'Host do Windows do Zordon: controla o microfone a pedido do nucleo (SPEC-007).' `
        -Action (New-ScheduledTaskAction -Execute $Javaw -Argument '@zordon-host.args' -WorkingDirectory $HostDir) `
        -RestartOnFailure
} else {
    Write-Host "  (Zordon Host nao registrada: rode packaging/windows/install-host.sh no WSL)" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "Verifique com:  Get-ScheduledTask -TaskName 'Zordon*'" -ForegroundColor DarkGray
Write-Host "O teste do marco M0: reinicie o Windows, nao abra nenhum terminal," -ForegroundColor DarkGray
Write-Host "abra o Zordon pelo menu Iniciar e veja Nucleo conectado." -ForegroundColor DarkGray
