#!/usr/bin/env bash
#
# Funções comuns aos instaladores que, rodando no WSL, instalam algo no Windows.
#
# Copyright 2026 Willyan Faria — Apache License 2.0
#
# Nada aqui apaga arquivo: uma instalação anterior é sobrescrita arquivo a
# arquivo, e os argfiles listam cada jar pelo nome, então um jar antigo que
# tenha ficado na pasta nunca entra no classpath (ADR-0015).

log()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m !!\033[0m %s\n' "$*" >&2; exit 1; }

require_interop() {
  command -v powershell.exe >/dev/null 2>&1 ||
    die "interop com o Windows indisponível: rode este script dentro do WSL, com o Windows acessível"
}

# Pergunta ao Windows, em vez de derivar do usuário do WSL (R21).
windows_env() {
  powershell.exe -NoProfile -NonInteractive -Command "\$env:$1" 2>/dev/null | tr -d '\r'
}

windows_folder() {
  powershell.exe -NoProfile -NonInteractive -Command "[Environment]::GetFolderPath('$1')" 2>/dev/null | tr -d '\r'
}

# Versão de um java.exe do Windows, pelo caminho WSL dele.
java_version_of() {
  "$1" -XshowSettings:properties -version 2>&1 | tr -d '\r' \
    | sed -n 's/^ *java.specification.version = //p' | head -1
}

# Imprime o caminho WINDOWS do javaw.exe de um Java 25+ instalado no Windows.
# Ordem: ZORDON_WINDOWS_JAVA (pasta do JDK/JRE), pastas de instalação, PATH. O
# PATH vem por último porque costuma apontar para um atalho (o "javapath" da
# Oracle) que abre um segundo processo só para repassar a chamada.
find_windows_java() {
  local candidates=() windows_path wsl_dir version
  if [ -n "${ZORDON_WINDOWS_JAVA:-}" ]; then
    candidates+=("${ZORDON_WINDOWS_JAVA}\\bin\\javaw.exe")
  fi
  for wsl_dir in /mnt/c/Program\ Files/Java/* /mnt/c/Program\ Files/Eclipse\ Adoptium/* \
                 /mnt/c/Program\ Files/Microsoft/jdk-* /mnt/c/Program\ Files/Zulu/*; do
    [ -x "${wsl_dir}/bin/javaw.exe" ] && candidates+=("$(wslpath -w "${wsl_dir}/bin/javaw.exe")")
  done
  while IFS= read -r windows_path; do
    [ -n "${windows_path}" ] && candidates+=("${windows_path}")
  done < <(cmd.exe /c "where javaw" 2>/dev/null | tr -d '\r')
  for windows_path in "${candidates[@]}"; do
    local java_exe
    java_exe="$(wslpath "${windows_path%javaw.exe}java.exe" 2>/dev/null)" || continue
    [ -x "${java_exe}" ] || continue
    version="$(java_version_of "${java_exe}")"
    if [ "${version%%.*}" -ge 25 ] 2>/dev/null; then
      echo "${windows_path}"
      return 0
    fi
  done
  return 1
}

require_windows_java() {
  find_windows_java ||
    die "nenhum Java 25 no Windows. Instale com: winget install EclipseAdoptium.Temurin.25.JRE
    (ou aponte a pasta com ZORDON_WINDOWS_JAVA='C:\\caminho\\do\\jdk')"
}

# Cria ou atualiza um atalho do menu Iniciar do usuário (sem elevação).
create_shortcut() {
  local name="$1" target="$2" arguments="$3" workdir="$4" description="$5" programs
  programs="$(windows_folder Programs)"
  [ -n "${programs}" ] || { warn "pasta do menu Iniciar não encontrada; atalho não criado"; return 1; }
  powershell.exe -NoProfile -NonInteractive -Command "
    \$s = (New-Object -ComObject WScript.Shell).CreateShortcut('${programs}\\${name}.lnk');
    \$s.TargetPath = '${target}';
    \$s.Arguments = '${arguments}';
    \$s.WorkingDirectory = '${workdir}';
    \$s.Description = '${description}';
    \$s.Save()" >/dev/null 2>&1 ||
    { warn "não foi possível criar o atalho ${name}"; return 1; }
  log "atalho no menu Iniciar: ${name}"
}
