#!/usr/bin/env bash
#
# Instala o desktop do Zordon no Windows, a partir do WSL (SPEC-008 v2).
#
# Copyright 2026 Willyan Faria — Apache License 2.0
#
# Por que no Windows: é lá que há saída de áudio (R6). No WSLg a janela abre,
# mas os efeitos sonoros não têm por onde sair.
#
# Uso:  packaging/windows/install-desktop.sh
#       ZORDON_WINDOWS_JAVA='C:\Program Files\Java\jdk-25' packaging/windows/install-desktop.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=windows-common.sh
. "${REPO_ROOT}/packaging/windows/windows-common.sh"

require_interop

javaw="$(require_windows_java)"
log "Java do Windows: ${javaw}"

log "montando o desktop para o Windows"
(cd "${REPO_ROOT}" && ./gradlew --quiet :zordon-desktop:windowsDist)

local_app_data="$(windows_env LOCALAPPDATA)"
[ -n "${local_app_data}" ] || die "não foi possível ler %LOCALAPPDATA% do Windows"
target_windows="${local_app_data}\\Programs\\Zordon\\desktop"
target="$(wslpath "${target_windows}")"

log "instalando em ${target_windows}"
mkdir -p "${target}"
cp -r "${REPO_ROOT}/zordon-desktop/build/windows-dist/." "${target}/"

create_shortcut "Zordon" "${javaw}" "@zordon-desktop.args" "${target_windows}" \
  "Zordon - seu assistente residente" "${target_windows}\\zordon.ico" || true

# Prova de que os efeitos saem pela placa: os seis sinais, em silêncio.
log "verificando a saída de áudio do Windows (seis sinais em silêncio, ~12 s)"
java_exe="$(wslpath "${javaw%javaw.exe}java.exe")"
if (cd "${target}" && "${java_exe}" @zordon-audio-check.args 2>/dev/null | tr -d '\r' | grep -v ' INFO '); then
  log "efeitos sonoros comprovados na saída padrão do Windows"
else
  warn "a verificação de áudio falhou; veja a saída acima. O desktop foi instalado mesmo assim."
fi

cat <<MSG

Pronto. Abra pelo menu Iniciar: "Zordon".
Na tela de Voz, "Testar som" toca na saída padrão do Windows, que o painel nomeia.
Se não ouvir nada, confira a saída padrão em Configurações > Sistema > Som.
MSG
