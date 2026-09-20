---
document: adr-0028
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,voz,sidecar,systemd,processo]
specId: null
---

# ADR-0028 — O motor de voz é uma unit systemd, não um processo filho

**Status:** Aceito · 2026-09-18

## Contexto

[Voz §7](../specs/voice/design.md#7-sidecar-zordon-voice) previa o sidecar
`zordon-voice` como processo filho do núcleo: iniciado, supervisionado e
reiniciado por ele, morrendo junto. Para isso o núcleo teria de executar
`python -m zordon_voice` — e a regra 4 de
[Componentes §4](../architecture/components.md#4-regras-de-dependência-verificadas-no-build)
diz que nenhuma classe fora de `zordon-security` executa processo
([ADR-0007](ADR-0007-permissao-sobre-acao-estruturada.md)). O executor mediado só
chega no M3. É o mesmo dilema da [ADR-0027](ADR-0027-supervisor-do-wsl-pelo-agendador.md).

## Alternativas

| Alternativa | Por que não |
|---|---|
| `ProcessBuilder` no núcleo, com exceção nomeada no ArchUnit | Abre a regra 4 no processo que decide as ações, antes do mediador existir |
| Adiar o motor para depois do M3 | O M2 é o MVP de voz; sem motor não há voz |
| **Unit systemd `zordon-voice.service`** | — |

## Decisão

O motor de voz roda como `zordon-voice.service`, do mesmo usuário do núcleo, com
`PartOf=zordon.service`: parar ou reiniciar o núcleo faz o mesmo com o motor, e
`Restart=always` cuida das quedas. O núcleo se conecta ao motor por um socket de
domínio Unix em `/run/zordon-voice/voice.sock`. O diretório é o `RuntimeDirectory`
da unit: o systemd o cria com modo 0700 ao subir e o remove ao parar, então não
sobra socket velho e nenhum código do Zordon precisa apagar arquivo
([ADR-0015](ADR-0015-exclusao-impossivel-por-construcao.md)). A ausência do socket
é "motor de voz não instalado" ou "iniciando".

O que a [Voz §7](../specs/voice/design.md#7-sidecar-zordon-voice) pedia continua
valendo: morre com o núcleo, reinicia sozinho, e o núcleo segue por texto se o
motor não subir.

## Consequências

- O núcleo continua sem executar processo nenhum.
- O motor tem ciclo de vida visível para o sistema: `systemctl status
  zordon-voice`, `journalctl -u zordon-voice`.
- A instalação ganha uma unit e um script (`packaging/wsl/install-voice.sh`).
- A conexão núcleo → motor é de cliente: o núcleo reconecta com backoff enquanto
  o motor carrega os modelos.
