---
document: spec-014
module: security
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,seguranca,auditoria,permissao,validador,m3]
specId: SPEC-014
---

# SPEC-014 — Auditoria, validador de comandos e motor de permissão

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `SecurityAgent` |
| **Revisores** | ArchitectureAgent |
| **Marco** | M3 (itens 1 a 3 da [ordem obrigatória](../../security/model.md#10-ordem-de-implementação)) |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

Antes de qualquer ferramenta com efeito existir, o Zordon precisa de três peças,
nesta ordem: um registro de auditoria que não se deixa adulterar em silêncio,
um validador que recusa o que nunca deve rodar e um motor que classifica cada
ação pelo risco e decide quem autoriza.

## 2. Problema

Até o M2 o Zordon só conversa e fala. O M3 traz ações: abrir programas, escrever
arquivos, rodar builds. Sem estas três peças, a primeira ação seria executada sem
trilha, sem limite de caminho e sem pergunta ao usuário. O
[modelo de segurança](../../security/model.md) proíbe que exista esse período.

## 3. Escopo

**Tipos em `zordon-api`**: `RiskLevel`, `Effect` (sem `DELETE_FS`), `ZPath`,
`Principal` (ator + origem da ordem, [ADR-0030](../../adr/ADR-0030-origem-da-ordem.md)),
`ActionDescriptor` e `Decision` (`Allow`, `Deny`, `AskUser`).

**Módulo novo `zordon-security`**:

- **`AuditLog`**
  - SQLite `append-only`: triggers recusam `UPDATE` e `DELETE`.
  - Cada linha carrega `prev_hash` e `hash = sha256(prev_hash ‖ campos canônicos)`.
  - `begin` grava a intenção antes de executar; `complete` grava o desfecho numa
    linha nova, ligada pelo `callId`. Não existe atualização.
  - `verify(n)` confere as últimas `n` linhas; roda na inicialização com 1 000.
  - Os argumentos entram já redigidos.
- **`Redactor`**: mascara segredos conhecidos (prefixo e 3 últimos caracteres,
  [Segurança §5](../../security/model.md#mascaramento)) antes de qualquer registro.
- **`CommandValidator`**
  - Caminho vira absoluto e canônico, com symlinks resolvidos, antes de qualquer
    regra.
  - `forbidden` vence tudo; `workspaces` (escrita) e `readable` (leitura)
    definem o resto.
  - Programas só do catálogo, por nome.
  - Tabela de subcomandos RED por programa.
  - Nenhum argumento de shell: não há `sh -c`, `cmd /c` nem `powershell -Command`.
- **`PermissionEngine`**
  - `evaluate` é puro e determinístico: risco base da ferramenta, escalonamento
    por argumento ([Segurança §2](../../security/model.md#escalonamento-por-argumento)),
    teto por origem, lockdown e disjuntor.
  - Decide `Allow`, `Deny` ou `AskUser`.
  - RED pergunta sempre, por ação. YELLOW pergunta na primeira vez por
    (ferramenta, área), e "permitir nesta sessão" vale até o núcleo reiniciar.
    GREEN executa.
  - `requestApproval` usa um `Approver`. Sem aprovador, nega. Em 60 s, nega.
- **Tabela golden** de classificação, versionada: qualquer mudança de risco ou
  de decisão reprova o build até a tabela ser revista junto.

## 4. Não escopo

- O diálogo de permissão no desktop (`ui.requestPermission`): SPEC-015.
- `NotificationCenter` e kill switch: SPEC-015.
- Skills, `WindowsBridge` e `ProcessRunner` que executam: SPEC-016.
- Cofre de segredos e `SecretBroker` ([ADR-0032](../../adr/ADR-0032-segredo-se-usa-nao-se-entrega.md)):
  o `Redactor` desta SPEC só mascara.
- Poda da auditoria por retenção: fica documentada, entra com a tela de
  Diagnóstico completa (M6).

## 5. Arquitetura

```text
zordon-core ──► zordon-security ──► zordon-api
                  ├─ AuditLog ──► SQLite (~/.zordon/state/audit.db)
                  ├─ Redactor
                  ├─ CommandValidator ◄── PathPolicy (config.toml [paths])
                  └─ PermissionEngine ──► Approver (desktop, SPEC-015)
```

- `zordon-security` não depende de `zordon-core`, e ninguém fora dele monta um
  `ProcessBuilder` (ArchUnit).
- A política é lida na inicialização e não muda em execução. Mudar a lista
  `forbidden` é editar o `config.toml`, fora da conversa.

## 6. Fluxo

1. A ferramenta propõe uma ação com argumentos já validados pelo schema.
2. O `CommandValidator` normaliza caminhos e programa; o que for proibido é
   negado ali, com o motivo.
3. O `PermissionEngine.evaluate` calcula o risco efetivo e a decisão.
4. `AskUser` vai ao `Approver`. Sem resposta em 60 s, ou sem UI, é negado.
5. O `AuditLog.begin` grava a intenção, e sempre grava, inclusive o que foi negado.
6. A execução acontece (SPEC-016) e o `AuditLog.complete` grava o desfecho.

## 7. Interfaces

```java
public interface AuditLog {
    long begin(AuditEntry entry);                 // devolve o id da linha
    void complete(String callId, Status status, Duration took, String summary, String error);
    Verification verify(int lastN);
}

public interface PermissionEngine {
    Decision evaluate(ActionDescriptor action, Principal actor, PolicyContext ctx);
    CompletableFuture<Decision> requestApproval(ActionDescriptor action, Duration ttl);
}

public interface Approver {                        // implementado pelo desktop (SPEC-015)
    CompletableFuture<Boolean> ask(ActionDescriptor action, Duration ttl);
}
```

## 8. Eventos

- `SECURITY_DECISION` (tópico `system`): `{tool, risk, decision, decidedBy,
  origin}`, sem argumentos. A auditoria tem o detalhe redigido.
- A cadeia quebrada na inicialização publica `SYSTEM_ALERT` com severidade
  `CRITICAL`.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Auditoria | `~/.zordon/state/audit.db` (SQLite, WAL) | 180 dias (poda explícita, M6); nunca apagada pelo Zordon fora disso |
| Política de caminhos e programas | `config.toml` `[paths]`, `[programs]` | Do usuário |
| Permissões de sessão | Memória do núcleo | Até reiniciar |
| Tabela golden | `zordon-security/src/test/resources/golden/permissions.tsv` | Versionada |

## 10. Segurança

- As cinco invariantes ([Arquitetura §2](../../architecture/security.md#2-as-cinco-invariantes))
  valem. Em particular: não existe `Effect` de exclusão, e nenhum caminho do
  Zordon chega a `Files.delete`.
- Escrita em caminho de instalação do Zordon é negada sempre.
- `MODIFY_TRUST_KERNEL` é RED e nunca `Allow`.
- O texto que o usuário lê (`humanSummary`) é montado pelo núcleo a partir dos
  argumentos resolvidos, nunca pelo modelo.

## 11. Permissões

Esta SPEC é o próprio mecanismo. Nenhuma permissão nova é concedida por ela.

## 12. Observabilidade

- Log INFO de cada decisão: ferramenta, risco, decisão e quem decidiu.
- `system.diagnostics` ganha `audit: {entries, chain: ok|broken, verifiedAt}`.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Banco da auditoria indisponível | Nenhuma ação acima de GREEN executa: sem trilha, sem ação |
| Cadeia quebrada na inicialização | `SYSTEM_ALERT` CRITICAL; ações acima de GREEN ficam negadas até verificação manual |
| Caminho não canonicalizável | Negado, com o caminho na mensagem |
| Programa fora do catálogo | RED, com o caminho completo no resumo |
| Aprovador ausente ou 60 s sem resposta | Negado; auditado como `decided_by=timeout` |

## 14. Testes

- Auditoria em SQLite temporário: `UPDATE` e `DELETE` recusados pelo banco;
  uma linha alterada por fora quebra a verificação dali em diante.
- Validador: `../../Windows/System32`, symlink para `~/.ssh`, `**/.env`,
  `git reset --hard`, `sh -c`.
- Motor: tabela golden com 40 casos ou mais, cobrindo cada regra de escalonamento.
- ArchUnit: `ProcessBuilder` só em `zordon.security`; `Files.delete` em lugar
  nenhum.

## 15. Critérios de aceite

- `CA-1` Dada a auditoria, então `UPDATE` e `DELETE` são recusados pelo próprio
  banco, e alterar uma linha por fora faz `verify` apontar a primeira linha
  inválida.
- `CA-2` Dada uma ação, então a intenção é gravada antes do desfecho, e as duas
  linhas ficam ligadas pelo `callId`, inclusive quando a ação é negada.
- `CA-3` Dados argumentos com segredo, então a auditoria só guarda a forma
  mascarada.
- `CA-4` Dado um caminho relativo, com `..` ou por symlink, então ele é
  classificado pelo destino canônico, e `forbidden` vence qualquer confirmação.
- `CA-5` Dado um comando, então não há shell, o programa vem do catálogo e os
  subcomandos da tabela são RED.
- `CA-6` Dada a tabela golden, então cada caso produz exatamente o risco e a
  decisão registrados.
- `CA-7` Dado RED, então a pergunta é por ação; sem aprovador, ou em 60 s sem
  resposta, é negado.
- `CA-8` Dado lockdown, ou disjuntor aberto para o ator, então tudo acima de
  GREEN é negado; dada uma origem, então o teto dela é respeitado.
- `CA-9` Dado o código, então `ProcessBuilder` só existe em `zordon.security`, e
  não existe `Files.delete` nem efeito de exclusão.

## 16. Impacto em outros módulos

- `zordon-api`: tipos de risco, efeito, caminho, principal e decisão.
- `zordon-core`: compõe o motor e a auditoria; diagnóstico da auditoria.
- `settings.gradle.kts` e `gradle/libs.versions.toml`: módulo e `sqlite-jdbc`.
- [Cadeia de suprimentos](../../security/supply-chain.md): `sqlite-jdbc` (Apache 2.0).

## 17. Dependências

- [Segurança](../../security/model.md) · [Identidade](../../security/identity.md) ·
  [Interfaces §5](../../api/core-interfaces.md#5-permissionengine) ·
  [ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md) ·
  [ADR-0030](../../adr/ADR-0030-origem-da-ordem.md)
