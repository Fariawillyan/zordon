#!/usr/bin/env bash
#
# Instala o motor de voz do Zordon no WSL (SPEC-011, SPEC-013).
#
# Copyright 2026 Willyan Faria — Apache License 2.0
#
# - venv em ~/.zordon/venv com as dependências travadas por hash;
# - modelos em ~/.zordon/models, de revisão fixa, conferidos por SHA-256;
# - código em ~/.local/share/zordon/voice;
# - unit zordon-voice.service, amarrada ao núcleo.
#
# O que este script NÃO faz: apagar qualquer coisa. Download que não bate com o
# SHA-256 fica como .part e a instalação para (ADR-0015).

# Uso: packaging/wsl/install-voice.sh [--sem-unit]

set -euo pipefail

INSTALL_DIR="${HOME}/.local/share/zordon"
DATA_DIR="${HOME}/.zordon"
VENV="${DATA_DIR}/venv"
MODELS="${DATA_DIR}/models"
UNIT_PATH="/etc/systemd/system/zordon-voice.service"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

log()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m !!\033[0m %s\n' "$*" >&2; exit 1; }

require_python() {
  command -v python3 >/dev/null 2>&1 || die "python3 não encontrado; instale com: sudo apt install python3 python3-venv"
  local version
  version="$(python3 -c 'import sys; print(f"{sys.version_info[0]}.{sys.version_info[1]}")')"
  [ "${version}" = "3.12" ] ||
    warn "o lock foi gerado para Python 3.12; este é o ${version} e o pip pode recusar os hashes"
  python3 -c 'import venv, ensurepip' 2>/dev/null ||
    die "falta o módulo venv; instale com: sudo apt install python3-venv"
}

install_venv() {
  if [ ! -x "${VENV}/bin/python" ]; then
    log "criando o venv em ${VENV}"
    python3 -m venv "${VENV}"
  fi
  log "instalando dependências travadas por hash (~480 MB na primeira vez)"
  "${VENV}/bin/pip" install --quiet --require-hashes --no-deps -r "${REPO_ROOT}/voice/requirements.lock"
}

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

install_models() {
  local dest url sha size target
  while IFS=$'\t' read -r dest url sha size; do
    case "${dest}" in ''|'#'*) continue ;; esac
    target="${MODELS}/${dest}"
    if [ -f "${target}" ] && [ "$(sha256_of "${target}")" = "${sha}" ]; then
      continue
    fi
    mkdir -p "$(dirname "${target}")"
    if [ "${url#repo:}" != "${url}" ]; then
      # Modelo gerado no próprio projeto (SPEC-013): vem do repositório, conferido do mesmo jeito.
      log "copiando ${dest} do repositório"
      cp "${REPO_ROOT}/${url#repo:}" "${target}.part"
    else
      log "baixando ${dest} ($(( size / 1000000 )) MB)"
      curl -fL --retry 3 --silent --show-error -o "${target}.part" "${url}"
    fi
    [ "$(sha256_of "${target}.part")" = "${sha}" ] ||
      die "${dest}: SHA-256 não confere; o arquivo baixado ficou em ${target}.part para inspeção"
    mv "${target}.part" "${target}"
  done < "${REPO_ROOT}/voice/models.lock"
  log "modelos conferidos em ${MODELS}"
}

install_code() {
  log "instalando o motor em ${INSTALL_DIR}/voice"
  mkdir -p "${INSTALL_DIR}/voice"
  cp -r "${REPO_ROOT}/voice/zordon_voice" "${INSTALL_DIR}/voice/"
}

install_unit() {
  log "instalando a unit systemd"
  sed -e "s|@ZORDON_USER@|${USER}|g" \
      -e "s|@ZORDON_DATA_DIR@|${DATA_DIR}|g" \
      -e "s|@ZORDON_INSTALL_DIR@|${INSTALL_DIR}|g" \
      "${REPO_ROOT}/packaging/wsl/zordon-voice.service.template" | sudo tee "${UNIT_PATH}" >/dev/null
  sudo systemctl daemon-reload
  sudo systemctl enable zordon-voice.service
  sudo systemctl restart zordon-voice.service
}

require_python
install_venv
install_models
install_code
if [ "${1:-}" = "--sem-unit" ]; then
  warn "unit não instalada (--sem-unit); instale com o template packaging/wsl/zordon-voice.service.template"
else
  install_unit
fi

cat <<MSG

Pronto. O motor carrega os modelos em alguns segundos:
  systemctl status zordon-voice
  journalctl -u zordon-voice -f
Na tela de Voz, clique na esfera (ou Ctrl+Espaço) e diga "que horas são?".
MSG
