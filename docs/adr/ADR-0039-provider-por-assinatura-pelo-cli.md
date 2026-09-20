---
document: adr-0039
module: adr
section: decision
version: 1
updatedAt: 2026-09-19
securityLevel: public
tags: [adr,decisao,ia,provider,assinatura,cli,claude,codex]
specId: SPEC-018
---

# ADR-0039 — Provider por assinatura pelo CLI oficial, pelo caminho mediado e sem ferramentas

**Status:** Aceito · 2026-09-19 (aprovação delegada pelo owner durante a ausência dele)

## Contexto

O owner não quer pagar crédito de API: ele já tem assinatura (Claude via `claude`
CLI; ChatGPT via `codex`). A decisão foi adiada para o alinhamento do M3,
inclusive porque iniciar um processo fora de `zordon-security` é proibido pelo
ArchUnit ([ADR-0007](ADR-0007-permissao-sobre-acao-estruturada.md)).

Os dois CLIs não são "um modelo": são **agentes**, com ferramentas próprias
(shell, leitura e edição de arquivos). Rodados como estão, fariam o que o
motor de permissão do Zordon existe para impedir, e sem auditoria.

## Alternativas

| Alternativa | Por que não |
|---|---|
| API paga | O owner não quer |
| Exceção ao ArchUnit para o `zordon-ai` iniciar processo | Um segundo caminho de execução, fora da auditoria |
| `codex exec --sandbox read-only` | "Só leitura" ainda lê tudo, `~/.ssh` inclusive; com prompt injection, vira vazamento |
| **`claude -p` sem nenhuma ferramenta, pelo `ProcessRunner` da SPEC-016** | — |

## Decisão

- **Tipo de provider `claude-cli`**, opcional e nunca padrão.
- **Chamado pelo `Gatekeeper` + `ProcessRunner`:** o programa `claude` precisa
  estar no catálogo, cada chamada é auditada e o ambiente vai limpo.
- **Sem ferramentas e sem memória do agente:** `--disallowed-tools "*"`,
  `--max-turns 1`, `--no-session-persistence` e `--system-prompt` do Zordon. O
  processo roda numa pasta vazia (`~/.zordon/cli-work`), para nenhum
  `CLAUDE.md` de projeto entrar no contexto.
- **Prompt pela entrada padrão, nunca na linha de comando** (`ps` mostra
  argumentos). A auditoria guarda o tamanho, não o texto.
- **Codex fica para depois da sandbox** ([ADR-0031](ADR-0031-sandbox-para-codigo-de-agente.md)):
  sem um "sem ferramentas" equivalente, só roda isolado.

## Consequências

- O Zordon passa a responder com o modelo da assinatura do owner, sem chave paga.
- Não há streaming de tokens: a resposta chega inteira. A voz já fala por
  sentença depois que o texto chega; o custo é latência no primeiro fonema.
- O uso conta nos limites da assinatura do owner, e a auditoria mostra cada chamada.
- Se o CLI mudar as flags, o provider falha fechado: sem a garantia de "sem
  ferramentas", não roda.
