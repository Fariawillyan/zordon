#!/usr/bin/env bash
#
# Instala o host do Windows do Zordon, a partir do WSL (SPEC-007).
#
# Copyright 2026 Willyan Faria — Apache License 2.0
#
# O host controla o microfone a pedido do núcleo. Ele precisa rodar no Windows:
# o WSL não tem áudio (R6). Este script compila, copia, inicia o host agora e
# mostra o comando que o registra para iniciar sozinho no logon.
#
# Uso:  packaging/windows/install-host.sh
#       ZORDON_WINDOWS_JAVA='C:\Program Files\Java\jdk-25' packaging/windows/install-host.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=windows-common.sh
. "${REPO_ROOT}/packaging/windows/windows-common.sh"

require_interop

javaw="$(require_windows_java)"
log "Java do Windows: ${javaw}"

log "montando o host para o Windows"
(cd "${REPO_ROOT}" && ./gradlew --quiet :zordon-host:windowsDist)

local_app_data="$(windows_env LOCALAPPDATA)"
[ -n "${local_app_data}" ] || die "não foi possível ler %LOCALAPPDATA% do Windows"
target_windows="${local_app_data}\\Programs\\Zordon\\host"
target="$(wslpath "${target_windows}")"

log "instalando em ${target_windows}"
mkdir -p "${target}"
cp -r "${REPO_ROOT}/zordon-host/build/windows-dist/." "${target}/"

running="$(powershell.exe -NoProfile -NonInteractive -Command \
  "(Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' or Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*zordon-host.args*' } | Measure-Object).Count" \
  2>/dev/null | tr -d '\r')"
if [ "${running:-0}" -gt 0 ] 2>/dev/null; then
  warn "já há um host rodando; a versão nova vale a partir do próximo início"
  warn "para reiniciar agora (PowerShell): Stop-ScheduledTask 'Zordon Host'; Start-ScheduledTask 'Zordon Host'"
else
  powershell.exe -NoProfile -NonInteractive -Command \
    "Start-Process -FilePath '${javaw}' -ArgumentList '@zordon-host.args' -WorkingDirectory '${target_windows}' -WindowStyle Hidden" \
    >/dev/null 2>&1 && log "host iniciado" || warn "não foi possível iniciar o host agora"
fi

register_script="$(wslpath -w "${REPO_ROOT}/packaging/windows/register-tasks.ps1")"
cat <<MSG

Pronto. Para o host iniciar sozinho no logon, rode UMA VEZ no PowerShell:

    powershell -ExecutionPolicy Bypass -File "${register_script}" -HostDir "${target_windows}" -Javaw "${javaw}"

Isso também faz o "Zordon WSL Boot" religar o WSL a cada 5 min se ele cair.
Na tela de Voz do Zordon, "Host do Windows" passa a "Conectado".
Log do host: %LOCALAPPDATA%\\Zordon\\logs\\host.log
MSG
