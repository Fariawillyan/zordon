---
document: spec-016
module: security
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,seguranca,ferramentas,skills,processo,windows-bridge,m3]
specId: SPEC-016
---

# SPEC-016 — Execução mediada, ferramentas e ponte com o Windows

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `SecurityAgent` |
| **Revisores** | ArchitectureAgent, JavaAgent |
| **Marco** | M3 (itens 8 a 11 do [roadmap](../../roadmap.md#m3--ação-com-segurança-primeiro)) |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

O Zordon passa a agir. Toda ação passa por um único caminho: validar, classificar,
perguntar se for preciso, auditar, executar e auditar o desfecho. "Zordon, abra o
IntelliJ" funciona. "Zordon, apague os logs" é recusado, e o Zordon oferece a
quarentena.

## 2. Problema

As SPECs 014 e 015 decidem e perguntam, mas nada executa. Sem um caminho único,
cada ferramenta nova tenderia a chamar processo ou disco do seu jeito, e o motor
de permissão viraria decoração ([Arquitetura](../../architecture/security.md)).

## 3. Escopo

**`Gatekeeper` e `ProcessRunner`** (`zordon-security`)

- **`Gatekeeper.authorize`**: classifica, pergunta ao aprovador quando é
  `AskUser` e grava a intenção na auditoria. Devolve uma `Permit`:
  - `Granted`: a execução pode seguir;
  - `Refused`: com o motivo.
- **`Gatekeeper.complete`**: grava o desfecho.
- **`ProcessRunner.run`**: só aceita uma `Permit.Granted` cujo comando seja
  exatamente o que vai rodar. Nunca usa shell. Ambiente mínimo (`PATH`, `HOME`,
  `LANG`), sem nenhuma variável de segredo. Saída limitada a 64 KB e prazo por
  ferramenta; estourou, o processo filho é encerrado.
- É o único lugar do sistema com `ProcessBuilder` (ArchUnit).

**Ferramentas** (`zordon-core`, `zordon.core.tools`)

- **`Tool`** declara nome, descrição, risco-piso e efeitos. Monta o
  `ActionDescriptor` a partir dos argumentos, com o resumo em português gerado
  por código. Executa só com `Granted`.
- **`ToolRegistry`** guarda as ferramentas; **`SkillRuntime`** invoca uma
  ferramenta pelo `Gatekeeper` e devolve o texto do resultado.
- **Ferramentas do M3**

  | Ferramenta | Piso | Efeito | O que faz |
  |---|---|---|---|
  | `app.open` | GREEN | `SPAWN_PROCESS` | Abre um aplicativo do catálogo do Windows (atalhos do Menu Iniciar), pela ponte |
  | `fs.list` | GREEN | `READ_FS` | Lista uma pasta (até 200 entradas) |
  | `fs.read` | GREEN | `READ_FS` | Lê um arquivo de texto (até 256 KB, com aviso de corte) |
  | `system.metrics` | GREEN | — | CPU, memória e disco do WSL |
  | `git.status` | GREEN | `SPAWN_PROCESS` | `git status --short --branch` num repositório |
  | `fs.write` | YELLOW | `WRITE_FS` | Escreve um arquivo de texto numa área de trabalho |
  | `build.run` | YELLOW | `SPAWN_PROCESS` | `gradle build` ou `npm run build` num projeto de área de trabalho |

**Ponte com o Windows** (`windows.*`, host)

- O host declara `windows.apps`, lista os atalhos `.lnk` do Menu Iniciar
  (usuário e todos os usuários) e abre um atalho pelo `java.awt.Desktop`.
- Sem `ProcessBuilder` e sem código nativo no host.
- O núcleo só pede para abrir um item do catálogo que o host mesmo listou.
  Caminho arbitrário não é aceito.

**Rotas rápidas** (`IntentRouter`)

- **"abra / abre / abrir / inicie <app>"** vira `app.open`.
- **"apague / apaga / delete / exclua / remova <algo>"** vira a recusa: o
  Zordon não apaga ([ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md))
  e oferece a quarentena, que é reversível (SPEC-017).
- A origem (`voice` ou `ui`) segue para o motor: por voz, YELLOW e RED só com
  confirmação na tela.

## 4. Não escopo

- Quarentena e restauração (RED): SPEC-017.
- O modelo escolhendo ferramentas (tool use) e a seleção semântica: M4 e M5.
  Aqui as ferramentas são chamadas pelas rotas rápidas e pelo `SkillRuntime`.
- Telas de Skills e Sistema: entram com o Painel técnico, sobre `tools.list`.

## 5. Arquitetura

```text
voz/texto ─► IntentRouter ─► Intent.Tool ─► SkillRuntime ─► Tool.describe ─► ActionDescriptor
                                                  │
                                                  ▼
                                   Gatekeeper.authorize (validar, classificar, perguntar, auditar)
                                        │ Granted                     │ Refused
                                        ▼                             ▼
                              Tool.run (ProcessRunner / ponte / disco)   texto da recusa
                                        │
                                        ▼
                               Gatekeeper.complete (auditar desfecho) ─► texto do resultado
```

## 6. Fluxo

1. "Zordon, abra o IntelliJ" → `app.open {name: "intellij"}`.
2. O catálogo do host resolve "IntelliJ IDEA" → GREEN → auditado → o host abre o
   atalho → "Abrindo o IntelliJ IDEA.".
3. "Apague os logs do projeto" → nada executa → "Eu não apago arquivos. Posso
   mover para a quarentena, que dá para desfazer."
4. `fs.write` em `~/dev/app` pela janela → YELLOW → diálogo → autorizado →
   escreve → auditado.

## 7. Interfaces

```java
public sealed interface Permit {                     // zordon-security
    record Granted(String callId, ActionDescriptor action, Decision decision) implements Permit {}
    record Refused(String callId, Decision decision) implements Permit {}
}
CompletableFuture<Permit> Gatekeeper.authorize(ActionDescriptor, Principal, PolicyContext, String turnId);
void Gatekeeper.complete(Permit.Granted, AuditLog.Status, Duration, String summary, String error);
ProcessRunner.Result ProcessRunner.run(Permit.Granted, Path cwd, Duration timeout);

public interface Tool {                               // zordon-core
    String name(); String description(); RiskLevel baseRisk(); Set<Effect> effects();
    ActionDescriptor describe(Map<String, Object> args) throws ToolException;
    ToolResult run(Permit.Granted permit, Map<String, Object> args) throws Exception;
}
```

| Método ZWP | Sentido | Parâmetros → resultado |
|---|---|---|
| `tools.list` | cliente → núcleo | `{}` → `{tools[{name, description, risk, effects}]}` |
| `windows.apps` | núcleo → host | `{}` → `{apps[{id, name}]}` |
| `windows.openApp` | núcleo → host | `{id}` → `{opened, name}` |

## 8. Eventos

- `TOOL_CALLED` (`tools`): `{callId, tool, risk, decision}`, sem argumentos.
- `TOOL_RESULT` (`tools`): `{callId, tool, status, durationMs}`.
- O intérprete de atividade mostra "executando" durante a ferramenta (SPEC-012).

## 9. Dados

Nenhum dado novo além da auditoria (SPEC-014). O catálogo de aplicativos vive em
memória e é recarregado quando o host conecta.

## 10. Segurança

- A regra ArchUnit continua: `ProcessBuilder` só em `zordon.security`, sem
  exclusão em lugar nenhum.
- `ProcessRunner` confere que o comando executado é o autorizado. Uma ferramenta
  não consegue autorizar um comando e executar outro.
- Toda escrita passa por `PathPolicy.canonical` antes da classificação
  (SPEC-014 CA-4).
- Nenhuma ferramenta desta SPEC apaga, e não existe ferramenta de exclusão.

## 11. Permissões

As da tabela de ferramentas, pelo motor da SPEC-014.

## 12. Observabilidade

- Cada chamada gera duas linhas de auditoria e dois eventos.
- `tools.list` alimenta o Painel técnico.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| App que o catálogo não conhece | "Não encontrei um aplicativo chamado X." |
| Host desconectado | "O host do Windows não está conectado; não consigo abrir aplicativos." |
| Argumento inválido | Recusado antes do motor, com o motivo; nada é auditado como execução |
| Processo estoura o prazo | Encerrado; desfecho `failed` com "tempo esgotado" |
| Lockdown | GREEN executa; o resto é negado com "Zordon em lockdown" |

## 14. Testes

- `Gatekeeper` e `ProcessRunner` com processos reais inofensivos (`true`,
  `echo`, `sleep`): comando divergente recusado, prazo, saída cortada, ambiente
  sem segredos e auditoria das duas linhas.
- Ferramentas com diretório temporário. A ponte com um host falso.
- Rotas: "abra o IntelliJ" vira `app.open` e "apague os logs" vira a recusa.
- Host: catálogo a partir de uma árvore de atalhos falsa.

## 15. Critérios de aceite

- `CA-1` Dada uma ação, então nada executa sem passar por `Gatekeeper.authorize`,
  e a auditoria tem a intenção e o desfecho.
- `CA-2` Dada uma `Permit` para um comando, então o `ProcessRunner` recusa rodar
  outro. Não há shell, o ambiente não tem segredos e o prazo encerra o processo.
- `CA-3` Dado "Zordon, abra o IntelliJ", então o host abre o atalho do catálogo e
  o Zordon diz o que abriu. App desconhecido e host ausente têm resposta própria.
- `CA-4` Dado "Zordon, apague os logs do projeto", então nada é executado e a
  resposta recusa e oferece a quarentena.
- `CA-5` Dado `fs.write` pedido por voz, então a confirmação vai para a tela, e
  sem ela nada é escrito.
- `CA-6` Dadas as ferramentas, então `tools.list` expõe nome, descrição, risco e
  efeitos, e nenhuma tem efeito de exclusão.

## 16. Impacto em outros módulos

- `zordon-security`: `Gatekeeper`, `Permit`, `ProcessRunner`.
- `zordon-core`: `zordon.core.tools`, `Intent.Tool`, `TurnManager` e a ponte
  com o Windows.
- `zordon-host`: `WindowsMethods` e a capacidade `windows.apps`.
- `zordon-api`: eventos `TOOL_CALLED` e `TOOL_RESULT`.

## 17. Dependências

- [SPEC-014](SPEC-014-auditoria-validador-e-motor-de-permissao.md) ·
  [SPEC-015](SPEC-015-pedido-de-permissao-notificacoes-e-kill-switch.md) ·
  [Interfaces §3–§6](../../api/core-interfaces.md#3-zordonskill) ·
  [ADR-0007](../../adr/ADR-0007-permissao-sobre-acao-estruturada.md)
