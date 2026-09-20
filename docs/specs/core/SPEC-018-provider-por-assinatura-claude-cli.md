---
document: spec-018
module: core
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: internal
tags: [spec,ia,provider,assinatura,claude,cli,m3]
specId: SPEC-018
---

# SPEC-018 — Provider por assinatura: `claude-cli`

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaAgent` |
| **Revisores** | SecurityAgent, ArchitectureAgent |
| **Marco** | M3 |
| **Decisão** | [ADR-0039](../../adr/ADR-0039-provider-por-assinatura-pelo-cli.md) |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

O Zordon responder com o modelo da assinatura do owner, pelo `claude` CLI, sem
chave paga e sem dar ao CLI nenhum poder sobre a máquina.

## 2. Problema

Sem API paga, não há provider configurado: todo turno que vai ao modelo termina
em "nenhum modelo configurado" (SPEC-004).

## 3. Escopo

- **Tipo de provider `claude-cli`** no `config.toml`.
- **`ClaudeCliProvider`** (`zordon-ai`) monta a chamada e interpreta a saída
  JSON (`result`, `usage`, `is_error`). Não inicia processo: recebe um
  `CliRunner`.
- **`GatekeptCliRunner`** (`zordon-core`) implementa o `CliRunner` com o
  `Gatekeeper` e o `ProcessRunner`:
  - ação `ai.cli`, GREEN, efeitos `SPAWN_PROCESS` e `NETWORK`;
  - auditada, com o tamanho do prompt e não o texto;
  - programa `claude` do catálogo;
  - pasta de trabalho vazia `~/.zordon/cli-work`;
  - prompt pela entrada padrão.
- **Flags obrigatórias:** `-p --output-format json --disallowed-tools "*"
  --max-turns 1 --no-session-persistence --model <m> --system-prompt <s>`. O
  provider recusa rodar se alguma faltar.
- **Streaming:** o texto chega inteiro e é entregue como um único fragmento;
  depois vêm o uso e o fim.
- **`ProcessRunner`** passa a aceitar entrada padrão.

```toml
[ai.providers.claude]
type = "claude-cli"          # usa o login do `claude` do usuário; nenhuma chave aqui

[ai.roles.conversation]
provider = "claude"
model    = "sonnet"
```

**Ordem de preferência: a assinatura primeiro, a chave de API por último**

Decisão do owner (2026-09-19): *"eu quero que essa opcao de api seja a ultima,
devemos sempre priorizar por assinatura"*. Ela vale em três lugares, e não só na
documentação:

| Ordem | Provider | Por quê |
|---|---|---|
| 0 | `claude-cli` (assinatura) | Já está pago; não cobra por token |
| 1 | `openai-compatible` sem `api_key` (Ollama e afins) | Local e gratuito; os dados não saem |
| 2 | `anthropic` e qualquer `openai-compatible` com chave | Cobra por uso: o último recurso |

- `ProviderConfig.precedence()` é o único lugar que ordena; o registro monta os
  providers nessa ordem, e não na do arquivo.
- **Sem `config.toml`**: a conversa é da assinatura e a API por chave fica como
  papel `fallback` — o contrário do que valia no M1.
- **Papel não declarado**: em vez de "nenhum modelo configurado", o Zordon segue
  a ordem de preferência. `embeddings` fica de fora: modelo de conversa não serve
  de embedding, e adivinhar daria vetor errado em silêncio.
- **Papel declarado manda**: quem escreve `conversation = { provider = "anthropic" }`
  está dizendo "eu quero este mesmo", e a ordem não se aplica a ele.
- **Reserva**: sem papel `fallback`, a reserva de um turno que falhou é o próximo
  da ordem, pulando quem acabou de falhar — a chave paga continua sendo a última.

## 4. Não escopo

- `codex` (ChatGPT): depende da sandbox ([ADR-0031](../../adr/ADR-0031-sandbox-para-codigo-de-agente.md)).
- Instalar ou autenticar o CLI: é do owner (`npm i -g @anthropic-ai/claude-code`
  e `claude` para entrar).
- Ferramentas pelo modelo (tool use): M4 e M5.

## 5. Arquitetura

```text
TurnManager ─► ClaudeCliProvider ─► CliRunner (interface do zordon-ai)
                                          │ implementado no núcleo
                                          ▼
                        GatekeptCliRunner ─► Gatekeeper (ai.cli, auditado) ─► ProcessRunner
                                                                                   │
                                           claude -p … --disallowed-tools "*" ◄── stdin: a conversa
```

## 6. Fluxo

1. O turno vai ao papel `conversation`, que aponta para `claude`.
2. O provider monta o argv e a conversa em texto, com os papéis marcados.
3. `ai.cli` é autorizado (GREEN), auditado e roda.
4. A saída JSON vira resposta, com o uso de tokens informado pelo CLI.

## 7. Interfaces

```java
public interface CliRunner {                                  // zordon-ai
    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {}
    Result run(List<String> argv, String stdin, Duration timeout) throws Exception;
}
```

## 8. Eventos

Os mesmos de um turno (`AI_THINKING`, `AI_RESPONSE`), com `provider: "claude"`.

## 9. Dados

Nenhum dado novo. O CLI não persiste sessão (`--no-session-persistence`).

## 10. Segurança

- **Sem ferramentas:** `--disallowed-tools "*"` tira todas, inclusive as de MCP.
  O argv é conferido antes de cada chamada, e sem essa flag não roda.
- **Ambiente mínimo** (SPEC-016): nenhuma chave do núcleo chega ao CLI; o login
  do CLI é o do próprio usuário, em `~/.claude`.
- **Pasta vazia**: nenhum arquivo de projeto entra no contexto.
- **Prompt pela entrada padrão**: não aparece em `ps` nem na auditoria.

## 11. Permissões

`ai.cli` é GREEN, com o programa do catálogo. Em lockdown, GREEN continua: a voz
segue respondendo perguntas (SPEC-015).

## 12. Observabilidade

Duas linhas de auditoria por chamada e o tempo da chamada no log.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| `claude` fora do `PATH` | Provider indisponível: "claude não está no catálogo de programas" |
| Não autenticado ou limite da assinatura | `is_error` → erro do turno com a mensagem do CLI, sem repetir |
| Tempo esgotado (120 s) | Erro do turno, retentável |
| Saída que não é JSON | Erro do turno com as primeiras linhas de `stderr` |

## 14. Testes

- `ClaudeCliProvider` com `CliRunner` falso: argv com todas as flags, conversa
  pela entrada padrão, JSON de sucesso, `is_error` e saída inválida.
- `GatekeptCliRunner` com um `claude` falso (script no catálogo de teste): a
  auditoria tem a chamada sem o texto do prompt.
- Ordem de preferência: com a assinatura disponível, é ela que atende a conversa;
  sem ela, a chave de API entra como último recurso; servidor local fica entre as
  duas; e o papel não declarado cai na ordem, menos `embeddings`.

## 15. Critérios de aceite

- `CA-1` Dada uma requisição, então o argv tem `-p`, `--output-format json`,
  `--disallowed-tools "*"`, `--max-turns 1`, `--no-session-persistence`, o modelo
  e o system prompt, e a conversa vai só pela entrada padrão.
- `CA-2` Dado um JSON de sucesso, então o texto e o uso viram a resposta; dado
  `is_error` ou saída inválida, então o turno falha com a mensagem.
- `CA-3` Dada uma chamada real, então ela passa pelo `Gatekeeper`, é auditada sem
  o texto do prompt e roda numa pasta vazia com ambiente mínimo.
- `CA-4` Dado `type = "claude-cli"` sem o programa no catálogo, então o provider
  fica indisponível com o motivo.
- `CA-5` Dados a assinatura e uma chave de API configuradas, então a conversa vai
  pela assinatura, a chave de API é o último da ordem de preferência, e um papel
  não declarado segue essa mesma ordem.

### Evidências (2026-09-19)

- `ProviderRegistryTest` (4 testes de CA-5): `preference()` devolve
  `[claude, anthropic]`; sem o CLI instalado a conversa fica indisponível
  nomeando `claude` e a reserva cai em `anthropic`; a precedência põe a
  assinatura antes do servidor local e o local antes da chave paga; e o papel não
  declarado resolve para a assinatura enquanto `embeddings` continua sem resolver.
- `SystemMethodsTest`: o diagnóstico mostra `conversation` em `claude` e
  `fallback` em `anthropic`.
- Sem provider nenhum, o turno falha dizendo o motivo de cada um — o do CLI e o
  da chave — e não só o do primeiro.

## 16. Impacto em outros módulos

- `zordon-ai`: `ProviderType.CLAUDE_CLI`, `CliRunner`, `ClaudeCliProvider` e o
  registro.
- `zordon-security`: entrada padrão no `ProcessRunner`; `claude` nos programas
  padrão.
- `zordon-core`: `GatekeptCliRunner`.
- `packaging/wsl/config.toml.example`: o bloco comentado.

## 17. Dependências

- [ADR-0039](../../adr/ADR-0039-provider-por-assinatura-pelo-cli.md) ·
  [SPEC-004](SPEC-004-providers-configuraveis.md) ·
  [SPEC-016](../security/SPEC-016-execucao-mediada-ferramentas-e-ponte-windows.md)
