<#
    Registra as tarefas agendadas do Zordon no Windows.

    Copyright 2026 Willyan Faria - Apache License 2.0

    A tarefa "Zordon WSL Boot" e a resposta ao risco R1: o WSL nao sobe sozinho
    no boot do Windows, e sem ela o nucleo so existe depois que alguem abre um
    terminal. O comando e deliberadamente inofensivo - subir a distro ja dispara
    o systemd, que sobe o zordon.service.
#>

[CmdletBinding()]
param(
    [string] $Distro = 'Ubuntu'
)

$ErrorActionPreference = 'Stop'

function Register-ZordonTask {
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [string] $Description,
        [Parameter(Mandatory)] $Action
    )

    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $settings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -StartWhenAvailable `
        -ExecutionTimeLimit ([TimeSpan]::Zero)

    Register-ScheduledTask -TaskName $Name -Description $Description `
        -Trigger $trigger -Action $Action -Settings $settings -Force | Out-Null

    Write-Host "  registrada: $Name" -ForegroundColor Green
}

Write-Host "Zordon - tarefas agendadas" -ForegroundColor Cyan

Register-ZordonTask -Name 'Zordon WSL Boot' `
    -Description 'Sobe a distro do WSL no logon para que o nucleo do Zordon inicie sozinho.' `
    -Action (New-ScheduledTaskAction -Execute 'wsl.exe' -Argument "-d $Distro --exec /bin/true")

Write-Host ""
Write-Host "Verifique com:  Get-ScheduledTask -TaskName 'Zordon*'" -ForegroundColor DarkGray
Write-Host "O teste do marco M0: reinicie o Windows, nao abra nenhum terminal," -ForegroundColor DarkGray
Write-Host "abra o zordon-desktop e veja CORE ONLINE." -ForegroundColor DarkGray
