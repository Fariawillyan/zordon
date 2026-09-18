#!/usr/bin/env bash
#
# Instala o núcleo do Zordon no WSL.
#
# Copyright 2026 Willyan Faria — Apache License 2.0
#
# O que este script NÃO faz, de propósito: apagar qualquer coisa. Uma instalação
# anterior é sobrescrita arquivo a arquivo (ADR-0015).

set -euo pipefail

INSTALL_DIR="${HOME}/.local/share/zordon"
DATA_DIR="${HOME}/.zordon"
UNIT_PATH="/etc/systemd/system/zordon.service"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

log()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m !!\033[0m %s\n' "$*" >&2; exit 1; }

require_systemd() {
  [ "$(ps -p 1 -o comm=)" = "systemd" ] ||
    die "esta distro não está com systemd como PID 1; ajuste /etc/wsl.conf ([boot] systemd=true) e rode 'wsl --shutdown'"
}

# O perfil do Windows é PERGUNTADO ao Windows. Derivar de \$USER é o bug que a
# R21 documenta: nesta máquina os dois usuários têm nomes diferentes.
resolve_windows_home() {
  local profile
  if ! command -v powershell.exe >/dev/null 2>&1; then
    warn "interop com o Windows indisponível: o endpoint não será publicado no perfil do Windows"
    return 1
  fi
  profile="$(powershell.exe -NoProfile -NonInteractive -Command '$env:USERPROFILE' 2>/dev/null | tr -d '\r')"
  [ -n "${profile}" ] || return 1
  wslpath "${profile}" 2>/dev/null
}

# O JDK que compilou é o que vai rodar. O systemd tem PATH próprio e, sem isto,
# acharia o java do sistema — nesta máquina, o 21, que não roda classes do 25.
resolve_java_home() {
  local java_bin home version
  java_bin="$(command -v java)" || die "java não encontrado no PATH; instale o JDK 25 (ex.: sdk install java 25-tem)"
  home="$(dirname "$(dirname "$(readlink -f "${java_bin}")")")"
  version="$("${home}/bin/java" -XshowSettings:properties -version 2>&1 \
    | sed -n 's/^ *java.specification.version = //p')"
  [ "${version%%.*}" -ge 25 ] 2>/dev/null ||
    die "o Zordon precisa do Java 25 ou mais novo; o java do PATH é o ${version:-desconhecido} (${home})"
  echo "${home}"
}

# O exemplo só entra se não houver configuração: o arquivo do usuário é dele, e
# reinstalar nunca pode desfazer uma escolha de modelo.
install_config_example() {
  local target="${DATA_DIR}/config.toml"
  if [ -e "${target}" ]; then
    log "configuração existente preservada: ${target}"
  else
    cp "${REPO_ROOT}/packaging/wsl/config.toml.example" "${target}"
    chmod 600 "${target}"
    log "exemplo de configuração em ${target} (tudo comentado: o padrão é a Anthropic)"
  fi
}

configure_api_key() {
  local helper="${REPO_ROOT}/packaging/wsl/set-api-key.sh" provider
  if [ -s "${DATA_DIR}/secrets.env" ]; then
    log "chaves de API já configuradas; validando as conhecidas"
    "${helper}" --check ||
      warn "troque uma chave depois com: packaging/wsl/set-api-key.sh <provider>"
  elif [ -t 0 ]; then
    log "chave de API (opcional agora; um modelo local não precisa de chave nenhuma)"
    read -rp "   De qual provider? [anthropic / openai / Enter para pular]: " provider
    if [ -n "${provider}" ]; then
      "${helper}" "${provider}" --no-restart ||
        warn "sem chave: o núcleo sobe, e o chat avisa o que falta"
    fi
  else
    warn "sem terminal interativo: configure a chave depois com packaging/wsl/set-api-key.sh"
  fi
}

# Type=notify: o systemd só considera o serviço ativo quando o núcleo avisa que o
# ZWP está escutando e o endpoint.json foi publicado. Esperar por isso é o que
# permite dizer "pronto" com verdade.
wait_until_ready() {
  local attempt
  for attempt in $(seq 1 30); do
    if systemctl is-active --quiet zordon.service && [ -s "${DATA_DIR}/endpoint.json" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# Medido: sem instanceIdleTimeout=-1 o WSL desliga a distro 15 s depois do último
# terminal, e o núcleo cai junto. Não dá para corrigir daqui — o arquivo é do
# Windows e só vale depois de um wsl --shutdown —, então o instalador avisa.
check_idle_timeouts() {
  local config="$1/.wslconfig" missing=()
  grep -qiE '^\s*instanceIdleTimeout\s*=\s*-1' "${config}" 2>/dev/null || missing+=("[general] instanceIdleTimeout=-1")
  grep -qiE '^\s*vmIdleTimeout\s*=\s*-1' "${config}" 2>/dev/null || missing+=("[wsl2] vmIdleTimeout=-1")
  if [ "${#missing[@]}" -gt 0 ]; then
    warn "o WSL vai desligar o núcleo por ociosidade: faltam em %USERPROFILE%\\.wslconfig: ${missing[*]}"
    warn "depois de acrescentar, rode 'wsl --shutdown' no PowerShell (docs/operations/quickstart.md, passo 6)"
  fi
}

detect_networking_mode() {
  local config="$1/.wslconfig"
  if [ -f "${config}" ] && grep -qiE '^\s*networkingMode\s*=\s*mirrored' "${config}"; then
    echo "mirrored"
  else
    echo "nat"
  fi
}

main() {
  require_systemd

  local java_home
  java_home="$(resolve_java_home)"
  log "JDK: ${java_home}"

  log "compilando o núcleo"
  (cd "${REPO_ROOT}" && ./gradlew --quiet :zordon-core:installDist)

  log "instalando em ${INSTALL_DIR}"
  mkdir -p "${INSTALL_DIR}" "${DATA_DIR}"
  cp -r "${REPO_ROOT}/zordon-core/build/install/zordon-core/." "${INSTALL_DIR}/"

  local windows_home networking_mode
  if windows_home="$(resolve_windows_home)"; then
    log "perfil do Windows: ${windows_home}"
    mkdir -p "${windows_home}/.zordon"
    networking_mode="$(detect_networking_mode "${windows_home}")"
    check_idle_timeouts "${windows_home}"
    case "${windows_home}" in
      */OneDrive/*) warn "o perfil está sob OneDrive: sincronizar o endpoint.json pode vazar o token" ;;
    esac
  else
    warn "perfil do Windows não resolvido: os clientes não vão achar o núcleo até isto ser corrigido"
    windows_home=""
    networking_mode="nat"
  fi
  log "modo de rede do WSL: ${networking_mode}"

  install_config_example
  configure_api_key

  log "instalando a unit systemd"
  sed -e "s|@ZORDON_USER@|${USER}|g" \
      -e "s|@ZORDON_GROUP@|$(id -gn)|g" \
      -e "s|@ZORDON_INSTALL_DIR@|${INSTALL_DIR}|g" \
      -e "s|@ZORDON_DATA_DIR@|${DATA_DIR}|g" \
      -e "s|@ZORDON_WINDOWS_HOME@|${windows_home}|g" \
      -e "s|@ZORDON_NETWORKING_MODE@|${networking_mode}|g" \
      -e "s|@JAVA_HOME@|${java_home}|g" \
      "${REPO_ROOT}/packaging/wsl/zordon.service.template" | sudo tee "${UNIT_PATH}" >/dev/null

  sudo systemctl daemon-reload
  sudo systemctl enable zordon.service
  # Uma instalação anterior que falhou em sequência aciona o limitador de partidas
  # do systemd ("start request repeated too quickly"), e ele recusaria até a
  # instalação corrigida. Limpar o estado de falha é o que torna reinstalar seguro.
  sudo systemctl reset-failed zordon.service 2>/dev/null || true
  # restart, e não start: numa reinstalação o serviço antigo precisa largar os
  # binários velhos.
  sudo systemctl restart zordon.service

  if wait_until_ready; then
    log "núcleo pronto: $(grep -o 'ws://[^"]*' "${DATA_DIR}/endpoint.json" | paste -sd ' ')"
  else
    warn "o núcleo não ficou pronto em 30 s. Veja o motivo com: journalctl -u zordon -n 50"
    systemctl --no-pager --lines=15 status zordon.service || true
    exit 1
  fi

  local windows_script
  windows_script="$(wslpath -w "${REPO_ROOT}/packaging/windows/register-tasks.ps1" 2>/dev/null \
    || echo 'packaging\windows\register-tasks.ps1')"

  cat <<MSG

Pronto. Dois passos opcionais:

  • Abrir a janela do Zordon (Windows 11 mostra apps do WSL direto na área de trabalho):
        ./gradlew :zordon-desktop:run

  • Subir sozinho quando o Windows iniciar — uma vez só, num PowerShell do Windows:
        powershell -ExecutionPolicy Bypass -File "${windows_script}"
    Sem isso, o núcleo só existe depois que alguém abrir o WSL (R1).

Escolher o modelo (Anthropic, OpenAI, Ollama local, outros):
        edite ${DATA_DIR}/config.toml e rode: sudo systemctl restart zordon

Trocar ou conferir chaves de API:
        packaging/wsl/set-api-key.sh anthropic      (ou openai, ou --env NOME)
        packaging/wsl/set-api-key.sh --check
MSG
}

main "$@"
