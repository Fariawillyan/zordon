#!/usr/bin/env bash
#
# Configura ou troca a chave de API de um provider de IA.
#
# Copyright 2026 Willyan Faria — Apache License 2.0
#
# A chave é pedida sem eco e nunca passa por argumento de linha de comando nem
# pelo histórico do shell. Quando o provider é conhecido, ela é validada ANTES de
# ser gravada: uma chave recusada não chega ao disco. As chaves dos outros
# providers no mesmo arquivo são preservadas.
#
# Uso:
#   set-api-key.sh [anthropic|openai]      pede, valida, grava e reinicia o serviço
#   set-api-key.sh --env NOME              qualquer outro provider (OpenRouter, Groq…);
#                                          grava sem validar, e diz isso
#   set-api-key.sh [provider] --check      só valida o que já está gravado
#   set-api-key.sh [provider] --no-restart grava sem reiniciar o serviço
#
# A variável precisa bater com a referência do ~/.zordon/config.toml
# (api_key = "env:NOME"). Provisório: o destino das chaves é o Credential Manager
# do Windows (docs/security/model.md §5).

set -euo pipefail

SECRETS="${ZORDON_HOME:-${HOME}/.zordon}/secrets.env"

log()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m !!\033[0m %s\n' "$*" >&2; exit 1; }

# provider → variável, endpoint de validação e forma de autenticar.
# Validar é uma chamada que não gasta tokens: só lista os modelos.
describe_provider() {
  case "$1" in
    anthropic) echo "ANTHROPIC_API_KEY https://api.anthropic.com/v1/models anthropic" ;;
    openai)    echo "OPENAI_API_KEY https://api.openai.com/v1/models bearer" ;;
    *)         return 1 ;;
  esac
}

# Responde o código HTTP; "000" sem rede; "sem-curl" sem curl.
# A chave vai por descritor de arquivo, não por argumento: argumentos aparecem
# para qualquer usuário em `ps`.
http_status() {
  local key="$1" url="$2" auth="$3" header
  command -v curl >/dev/null 2>&1 || { echo "sem-curl"; return; }
  case "${auth}" in
    anthropic) header="$(printf 'x-api-key: %s\nanthropic-version: 2023-06-01\n' "${key}")" ;;
    bearer)    header="$(printf 'Authorization: Bearer %s\n' "${key}")" ;;
  esac
  curl -s -o /dev/null -w '%{http_code}' --max-time 20 "${url}" -H @<(printf '%s\n' "${header}") || true
}

explain() {
  case "$1" in
    200) log "chave aceita pela API" ;;
    401|403) warn "a API recusou a chave ($1): ela está incompleta, errada ou foi revogada" ;;
    sem-curl) warn "curl não encontrado: não deu para validar a chave" ;;
    000) warn "sem acesso à API agora (rede?): não deu para validar a chave" ;;
    *) warn "a API respondeu HTTP $1 ao validar a chave" ;;
  esac
}

stored_value() {
  [ -s "${SECRETS}" ] && sed -n "s/^$1=//p" "${SECRETS}" | tail -1 || true
}

# Troca só a linha desta variável. As outras chaves do arquivo ficam intactas, e
# o arquivo é substituído de uma vez: nunca há um instante com ele pela metade.
store() {
  local variable="$1" value="$2" temporary
  mkdir -p "$(dirname "${SECRETS}")"
  chmod 700 "$(dirname "${SECRETS}")"
  temporary="$(mktemp "${SECRETS}.XXXXXX")"
  {
    [ -f "${SECRETS}" ] && grep -v "^${variable}=" "${SECRETS}" || true
    printf '%s=%s\n' "${variable}" "${value}"
  } > "${temporary}"
  chmod 600 "${temporary}"
  mv "${temporary}" "${SECRETS}"
  log "${variable} gravada em ${SECRETS} (modo 600; as outras chaves foram preservadas)"
}

check_provider() {
  local provider="$1" variable url auth key status
  read -r variable url auth <<<"$(describe_provider "${provider}")"
  key="$(stored_value "${variable}")"
  [ -n "${key}" ] || { warn "nenhuma ${variable} em ${SECRETS}"; return 1; }
  status="$(http_status "${key}" "${url}" "${auth}")"
  unset key
  printf '    %s: ' "${provider}"
  explain "${status}"
  [ "${status}" = "200" ]
}

# Sem provider: confere todas as chaves conhecidas que estiverem gravadas.
check_all() {
  local provider variable found=0 failed=0
  for provider in anthropic openai; do
    read -r variable _ <<<"$(describe_provider "${provider}")"
    if [ -n "$(stored_value "${variable}")" ]; then
      found=1
      check_provider "${provider}" || failed=1
    fi
  done
  [ "${found}" = 1 ] || { warn "nenhuma chave de provider conhecido em ${SECRETS}"; return 1; }
  return "${failed}"
}

# Só a chave vai para a saída padrão, porque quem chama a captura. A quebra de
# linha depois do prompt vai para stderr: na saída, ela grudaria na frente da
# chave, e toda chave seria recusada na validação.
ask() {
  local variable="$1" key
  [ -t 0 ] || die "é preciso um terminal para digitar a chave"
  read -rsp "Cole a ${variable} (não aparece na tela; Enter para pular): " key
  echo >&2
  printf '%s' "${key}"
}

configure() {
  local provider="$1" variable url auth key status
  read -r variable url auth <<<"$(describe_provider "${provider}")"
  key="$(ask "${variable}")"
  [ -n "${key}" ] || { warn "nenhuma chave informada"; return 1; }
  status="$(http_status "${key}" "${url}" "${auth}")"
  explain "${status}"
  if [ "${status}" = "401" ] || [ "${status}" = "403" ]; then
    unset key
    die "nada foi gravado. Gere outra chave no console do provider e tente de novo."
  fi
  store "${variable}" "${key}"
  unset key
}

configure_env() {
  local variable="$1" key
  [[ "${variable}" =~ ^[A-Z_][A-Z0-9_]*$ ]] || die "nome de variável inválido: use letras maiúsculas, números e _"
  key="$(ask "${variable}")"
  [ -n "${key}" ] || { warn "nenhuma chave informada"; return 1; }
  warn "provider sem validação conhecida: a chave foi gravada sem teste; o primeiro turno dirá se ela vale"
  store "${variable}" "${key}"
  unset key
}

restart_if_running() {
  if systemctl is-active --quiet zordon.service 2>/dev/null; then
    log "reiniciando o serviço para ler a chave nova"
    sudo systemctl restart zordon.service
  fi
}

main() {
  local provider="" env_variable="" mode="configure" restart=1
  while [ $# -gt 0 ]; do
    case "$1" in
      --check)      mode="check" ;;
      --no-restart) restart=0 ;;
      --env)        shift; env_variable="${1:-}"; [ -n "${env_variable}" ] || die "--env exige o nome da variável" ;;
      anthropic|openai) provider="$1" ;;
      *) die "opção desconhecida: $1 (providers conhecidos: anthropic, openai; outros: --env NOME)" ;;
    esac
    shift
  done

  if [ "${mode}" = "check" ]; then
    if [ -n "${provider}" ]; then check_provider "${provider}"; else check_all; fi
    return
  fi
  if [ -n "${env_variable}" ]; then
    configure_env "${env_variable}"
  else
    configure "${provider:-anthropic}"
  fi
  [ "${restart}" = 1 ] && restart_if_running
  return 0
}

main "$@"
