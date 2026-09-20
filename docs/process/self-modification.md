---
document: self-modification
module: process
section: self-modification
version: 1
updatedAt: 2026-09-17
securityLevel: restricted
tags: [auto-modificacao,nucleo-de-confianca,preflight,terceiros,git]
specId: null
---

# Auto-modificação e alteração de projetos de terceiros

## 1. Objetivo

Definir como o Zordon altera o **próprio projeto** e **projetos de terceiros**,
sem que essa capacidade destrua as garantias do sistema.

## 2. O paradoxo que este documento resolve

O Zordon é um sistema cuja segurança é garantida por **código**: o
`PermissionEngine` classifica, o `AuditLog` registra, o `NotificationCenter`
comunica, as regras ArchUnit reprovam o build.

Se o Zordon pode editar esse código, a segurança dele passa a depender da boa
vontade do modelo naquele momento — que é exatamente o que
[ADR-0016](../adr/ADR-0016-defesa-deterministica.md) diz que não fazemos.

```text
   Sem controle:

   Zordon quer fazer X  →  X é bloqueado pelo PermissionEngine
                        →  Zordon edita o PermissionEngine
                        →  X deixa de ser bloqueado

   Toda invariante vira uma sugestão.
```

Isso não é hipotético nem exige má-fé: um agente otimizando para "completar a
tarefa" encontra a remoção da verificação como o caminho mais curto. É o mesmo
mecanismo que faz um agente de teste ajustar a asserção até passar
([Catálogo §5](../agents/catalog.md#testingagent)).

A resposta tem duas partes, e **as duas são necessárias**:

1. **Núcleo de confiança** — um conjunto de arquivos que o Zordon nunca altera
   sem revisão humana ([§4](#4-núcleo-de-confiança)).
2. **Separação entre escrever e executar** — o Zordon escreve código-fonte; ele
   não se instala ([§5](#5-o-zordon-escreve-código-ele-não-se-instala)).

Ver [ADR-0024](../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md).

## 3. A regra principal

> **Documentação não é subproduto do código. Ela faz parte da implementação.**

Uma tarefa está concluída quando, e apenas quando:

```text
SPEC + implementação + testes + segurança + documentação + RAG + auditoria
```

Faltando qualquer item, a tarefa permanece **incompleta** — não "quase pronta",
não "pendente de ajuste". Incompleta. Ver
[Definition of Done](definition-of-done.md).

E, para toda alteração do próprio projeto ou de projeto de terceiro:

> **Nunca modificar um projeto silenciosamente.**

É a [invariante 3](../README.md#as-cinco-invariantes) aplicada ao caso mais
sensível: o usuário precisa saber o que o Zordon pretende alterar **antes** de a
alteração acontecer, e o que foi alterado depois.

## 4. Núcleo de confiança

Conjunto de arquivos cuja integridade sustenta todas as invariantes. O Zordon
pode **propor** mudanças neles; não pode aplicá-las.

| Caminho | Por que é protegido |
|---|---|
| `zordon-security/**` | Classificação, validação, auditoria, segredos |
| `zordon-defense/**` | Detecção e contenção |
| `zordon-api/**` | Contratos que os dois lados honram |
| `**/ArchitectureTest.java` | As regras ArchUnit que verificam as invariantes |
| `**/golden/**` | Tabelas de classificação de risco e de detectores |
| `~/.zordon/security-policy.toml` | Política — já imutável em execução |
| `.github/workflows/**` | Alterar o CI é alterar quem verifica |
| `CODEOWNERS` | Define quem precisa revisar |
| `gradle/libs.versions.toml` | Ponto de entrada de dependência |
| `docs/security/**`, `docs/adr/**` | A norma e as decisões |
| `LICENSE`, `SECURITY.md` | Governança |

### Regras

1. **Proposta sim, aplicação não.** O Zordon pode escrever a alteração em um
   branch e abrir PR. O merge exige revisão humana via
   [CODEOWNERS](../../CODEOWNERS).
2. **Sempre RED.** Qualquer alteração que toque o núcleo é classificada RED,
   independentemente do tamanho. Uma mudança de uma linha em
   `PermissionEngine.java` é RED.
3. **Nunca por automação.** Automação não executa RED
   ([Automação §7](../specs/automation/design.md#7-segurança-de-automações)),
   logo o núcleo nunca é alterado sem um humano no turno.
4. **Nunca em execução.** Nem o arquivo instalado, nem o processo rodando.
5. **Detectada como ameaça.** Tentativa de escrita direta no núcleo instalado
   dispara `ai.policy-tamper` → CRITICAL → Defense Lockdown
   ([Defesa §4](../security/defense.md#4-detection-engine)).

O item 5 é o que torna o controle real: não basta que a regra exista; violá-la
precisa ser um evento observável e contido.

## 5. O Zordon escreve código; ele não se instala

Esta é a separação que quebra o laço.

```text
   ┌──────────────────────────────────────────────────────┐
   │ CÓDIGO-FONTE        <repo>/zordon-core/src/...       │
   │                                                      │
   │ O Zordon escreve aqui: branch, commit, PR.           │
   │ Nada disso afeta o Zordon que está rodando.          │
   └──────────────────────────┬───────────────────────────┘
                              │
                     build + instalação
                     ── ação humana ──
                              │
   ┌──────────────────────────▼───────────────────────────┐
   │ INSTALADO           ~/.local/share/zordon/bin/...    │
   │                     %LOCALAPPDATA%\...\Zordon\       │
   │                                                      │
   │ O Zordon NUNCA escreve aqui.                         │
   │ O Zordon NUNCA reinicia a si mesmo com código novo.  │
   └──────────────────────────────────────────────────────┘
```

| Operação | Permitido |
|---|---|
| Escrever em `<repo>/**` (fora do núcleo), em branch | ✅ YELLOW |
| Escrever em `<repo>/**` dentro do núcleo, em branch | ⚠️ RED, com PR |
| Commit em branch de trabalho | ✅ YELLOW |
| Commit direto em `main` | ❌ Nunca |
| `push` | ❌ Nunca sem o usuário |
| Escrever em `~/.local/share/zordon/**` | ❌ **Nunca**, em hipótese alguma |
| Escrever em `%LOCALAPPDATA%\Programs\Zordon\**` | ❌ **Nunca** |
| `systemctl restart zordon` após alterar código | ❌ Nunca |
| Alterar `~/.zordon/security-policy.toml` | ❌ Nunca |
| Alterar `~/.zordon/config.toml` | ⚠️ RED, com confirmação |

Consequência: um Zordon comprometido consegue escrever código malicioso no
repositório, e **não consegue fazer esse código rodar**. Entre a escrita e a
execução há um build e uma instalação que uma pessoa executa, depois de um PR que
uma pessoa revisou.

Os caminhos de instalação estão em `forbidden`
([Operação §6](../operations/install.md#6-configuração)) e o `CommandValidator`
os rejeita antes de qualquer decisão do modelo.

## 6. Preflight obrigatório

Antes de alterar o próprio projeto **ou** um projeto de terceiros, o Zordon
executa nove passos. Não é recomendação: é verificação, e a ausência de qualquer
passo impede a execução.

```text
   IDEIA
     │
     ▼
 ┌─ 1. Identificar a tarefa ──────────────────────────────────┐
 │    escopo, repositório, arquivos prováveis                  │
 ├─ 2. Consultar RAG e documentação existente ────────────────┤
 │    ContextRouter com intent=IMPLEMENT                       │
 ├─ 3. Localizar SPECs e ADRs relacionados ───────────────────┤
 │    TraceabilityIndex: o que já decidimos sobre isto?        │
 ├─ 4. Criar ou atualizar a SPEC quando necessário ───────────┤
 │    SpecAgent; tabela de SDD §4 decide se é obrigatória      │
 ├─ 5. Analisar impacto arquitetural e de segurança ──────────┤
 │    toca o núcleo de confiança? viola invariante?            │
 ├─ 6. Selecionar Agents especializados ──────────────────────┤
 │    o mínimo necessário                                      │
 ├─ 7. Criar plano de execução ───────────────────────────────┤
 │    passos ordenados, arquivos, testes, documentação         │
 ├─ 8. Registrar token budget ────────────────────────────────┤
 │    orçamento da tarefa, antes de gastar                     │
 ├─ 9. COMUNICAR ao usuário o que pretende alterar ───────────┤
 │    e aguardar, quando o risco exige                         │
 └────────────────────────────┬───────────────────────────────┘
                              ▼
                        IMPLEMENTAÇÃO
```

Fluxo resumido:

```text
IDEIA → RAG → SPEC → ADR (se necessário) → PLANO → AGENTS → IMPLEMENTAÇÃO
```

### O plano

O passo 7 produz um objeto, não uma narrativa:

```java
public record ChangePlan(
        TaskId taskId,
        ChangeScope scope,            // SELF | THIRD_PARTY
        String repository,
        GitState gitState,            // branch, limpo?, commit base
        List<SpecRef> specs,          // existentes ou criadas no passo 4
        List<AdrRef> adrs,
        ImpactAnalysis impact,        // módulos, invariantes tocadas
        boolean touchesTrustKernel,   // §4 — força RED
        List<AgentId> agents,
        List<PlannedStep> steps,      // arquivo, ação, motivo
        Budget tokenBudget,
        RiskLevel risk,
        String humanSummary) {}       // gerado por CÓDIGO, não pelo modelo
```

`humanSummary` é derivado dos passos resolvidos, nunca escrito pelo modelo —
mesma regra do diálogo de permissão
([UI §6](../specs/ui/design.md#6-diálogo-de-permissão)). Um modelo comprometido
não pode descrever "ajustar formatação" enquanto o plano remove uma verificação.

### Quando comunicar e quando aguardar

| Situação | Comportamento |
|---|---|
| Toca o núcleo de confiança | **RED** — comunica, aguarda, exige PR com revisão humana |
| Altera comportamento observável | Comunica o plano, aguarda confirmação |
| Altera projeto de terceiro | Comunica o plano, aguarda confirmação |
| Refatoração local, sem mudança de comportamento | Comunica o plano, executa; resultado comunicado |
| Correção de typo em documentação | Comunica no resultado |

Em nenhuma linha o valor é "não comunica".

## 7. Git como rede de segurança

Auto-modificação sem controle de versão seria irreversível — e o Zordon não faz
nada irreversível ([ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md)).

| Regra | Razão |
|---|---|
| **Árvore de trabalho limpa é pré-requisito** | Alterar por cima de trabalho não commitado do usuário destruiria trabalho dele. Se `git status` não está limpo, o Zordon para e pergunta |
| Sempre em branch dedicado | `zordon/<taskId>-<slug>` |
| Commit por passo do plano, com `Spec:` | [Rastreabilidade §4](traceability.md#4-convenções) |
| **Nunca** `main` direto | Nem com confirmação |
| **Nunca** `push` sem o usuário | Publicar é ação externa |
| **Nunca** `reset --hard`, `clean -fdx`, `push --force`, `filter-branch` | Exclusão com outro nome |
| Reversão é `git revert`, nunca reescrita de histórico | Preserva o rastro |

O branch dedicado é o que torna toda alteração descartável: se ficou ruim, o
usuário troca de branch e nada aconteceu.

## 8. Projetos de terceiros

"Terceiros" cobre dois casos distintos:

### 8.1 Outros projetos do usuário

Ex.: um projeto seu em `D:/projetos/aurora`. Além do preflight de §6:

| Controle | Regra |
|---|---|
| Autorização | O projeto precisa estar em `paths.workspaces` ([Operação §6](../operations/install.md#6-configuração)). Fora disso, negado |
| Estado | `git status` limpo; sem isso, para e pergunta |
| Convenções | Lê `CONTRIBUTING`, configuração de lint e formatação **do projeto** e as respeita — não impõe as do Zordon |
| Testes | Roda a suíte do projeto, não presume a nossa |
| Escopo | Só os arquivos do plano. Arquivo fora do plano é violação e abre disjuntor |
| Publicação | Nunca `push`, nunca abre PR, nunca publica pacote |
| Agente | Agente de projeto quando existir (`projeto-<nome>`), com `allowedPaths` restrito àquele repositório |

### 8.2 Código de terceiro dentro do Zordon

Dependências, servidores MCP, modelos, código vendorizado. Regra diferente e mais
dura: **não se corrige código de terceiro no lugar.**

| Situação | Ação correta |
|---|---|
| Bug em dependência | Reportar upstream; contornar no nosso código, isolado e comentado com `WHY` |
| Vulnerabilidade em dependência | Atualizar a versão, ou substituir a dependência |
| Servidor MCP se comportando mal | Isolar ([MCP §6](../specs/mcp/design.md#6-isolamento-de-falhas)); não editar o servidor |
| Patch vendorizado | Só com ADR justificando, e o patch fica visível e versionado |

Editar código de terceiro no lugar cria uma bifurcação silenciosa que ninguém
lembra na próxima atualização — e, em uma dependência, é um vetor de cadeia de
suprimentos com a nossa própria assinatura
([Cadeia de suprimentos](../security/supply-chain.md)).

### 8.3 Versionamento do ecossistema

Skills, agentes, servidores MCP, providers, automações e workflows têm manifesto
versionado ([ADR-0034](../adr/ADR-0034-manifesto-de-extensao-versionado.md),
[Extensões](../architecture/extensions.md)). Quando o Zordon altera um deles:

- produz uma **versão nova** — nunca edita a aprovada no lugar;
- a versão nova fica suspensa até aprovação, e a anterior continua ativa;
- automações continuam presas à versão que aprovaram;
- reverter é apontar para a versão anterior, que nunca é apagada.

## 9. Auditoria

Toda alteração de projeto produz entrada de auditoria, na mesma cadeia de hash do
resto ([Segurança §7](../security/model.md#7-auditoria)):

```java
public record ChangeAudit(
        TaskId taskId,
        ChangeScope scope,
        String repository, String branch, String baseCommit,
        List<String> filesChanged,
        List<SpecRef> specs,
        boolean touchedTrustKernel,
        RiskLevel risk,
        Decision decision, String decidedBy,
        List<String> commits,
        String userMessageId,          // liga à comunicação do passo 9
        TokenUsage usage,
        Outcome outcome) {}
```

`userMessageId` nulo com `outcome=APPLIED` é **bug de severidade máxima** — é a
mesma verificação que garante que nenhuma ação de defesa é silenciosa
([ADR-0014](../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md)), aplicada à
auto-modificação.

## 10. Comunicação

Modelo da mensagem do passo 9, seguindo o contrato de
[Comunicação §4](../security/communication.md#4-contrato-de-explicação):

```text
🟠 Zordon — alteração de projeto                               aguardando você

Pretendo alterar o projeto Zordon.

Tarefa        Adicionar cache ao ToolRegistry
SPEC          SPEC-018 (criada agora, status APPROVED)
Repositório   /home/<user>/zordon   branch zordon/t-4471-tool-cache
Base          a3f19c (árvore limpa)

Vou alterar    zordon-mcp/src/main/java/.../ToolRegistry.java
               zordon-mcp/src/test/java/.../ToolRegistryTest.java
               docs/specs/mcp/design.md
Não vou tocar  nenhum arquivo do núcleo de confiança

Agentes       McpAgent → TestingAgent → CodeReviewAgent → DocumentationAgent
Orçamento     18.000 tokens
Risco         YELLOW — reversível, em branch dedicado

[Ver plano completo]  [Aprovar]  [Ajustar escopo]  [Cancelar]
```

Quando o plano toca o núcleo de confiança, a mensagem muda de forma visível:

```text
🔴 Zordon — alteração do núcleo de confiança                         CRITICAL

Esta alteração toca o núcleo de confiança do Zordon.

Arquivo       zordon-security/src/main/java/.../PermissionEngine.java
Por quê       A SPEC-019 pede um novo Effect para operações de rede

O que NÃO acontece automaticamente:
  · nada é aplicado ao Zordon em execução
  · nada vai para main sem revisão humana
  · será aberto um PR, que você revisa

[Ver diff proposto]  [Autorizar o PR]  [Recusar]
```

## 11. Interfaces

```java
public interface ChangePlanner {
    ChangePlan plan(ChangeRequest request);      // executa os passos 1–8
    PlanValidation validate(ChangePlan plan);    // núcleo? invariante? git limpo?
}

public interface ChangeExecutor {
    /** Só aceita plano validado, aprovado e comunicado. */
    ChangeResult execute(ApprovedPlan plan);
}

/** Só o ChangePlanner constrói. Carrega a prova de que o preflight rodou. */
public record ApprovedPlan(
        ChangePlan plan,
        String userMessageId,   // obrigatório
        Decision decision,
        Instant approvedAt) {}
```

`ChangeExecutor.execute` **só aceita `ApprovedPlan`**, e `ApprovedPlan` só é
construível pelo planejador com `userMessageId` não nulo. O preflight não é uma
convenção que alguém pode esquecer: é o tipo do parâmetro.

## 12. Eventos

| Evento | Payload |
|---|---|
| `CHANGE_PLANNED` | `{taskId, scope, repository, files, specs, risk, touchesTrustKernel}` |
| `CHANGE_APPROVED` | `{taskId, by, decision}` |
| `CHANGE_APPLIED` | `{taskId, branch, commits, filesChanged}` |
| `CHANGE_REVERTED` | `{taskId, by, revertCommit}` |
| `TRUST_KERNEL_PROPOSAL` | `{taskId, files, prUrl}` — proposta, nunca aplicação |

## 13. Casos de erro

| Situação | Comportamento |
|---|---|
| Árvore de trabalho suja | Para antes de tudo e pergunta. Nunca altera por cima |
| Projeto fora de `workspaces` | Negado com `ERR_PERMISSION_DENIED` |
| Plano toca o núcleo e não há UI conectada | Negado. RED sem usuário é sempre negado |
| Agente altera arquivo fora do plano | **Violação**: disjuntor aberto, alteração revertida, notificação CRITICAL |
| Testes do projeto falham após a alteração | Não commita; reporta com o diff e a falha |
| Estouro de orçamento no meio | Commita o que está consistente, reporta o parcial, não deixa a árvore quebrada |
| SPEC exigida e ausente | Para no passo 4; não implementa |

A quarta linha é a proteção contra o desvio mais provável: o agente decide, no
meio do trabalho, que precisa "só ajustar mais um arquivinho". O plano é o
contrato, e sair dele é um sinal de comportamento anômalo, não uma conveniência.

## 14. Riscos

| Risco | Mitigação |
|---|---|
| Zordon enfraquece as próprias proteções | Núcleo de confiança + separação fonte/instalado + CODEOWNERS |
| Alteração aplicada ao Zordon em execução | Caminhos de instalação em `forbidden`; `ai.policy-tamper` detecta |
| Trabalho não commitado do usuário destruído | Árvore limpa é pré-requisito |
| Alteração silenciosa | `ApprovedPlan` exige `userMessageId`; auditoria verifica |
| Escopo crescendo durante a execução | Arquivo fora do plano abre disjuntor |
| Bifurcação silenciosa em dependência | Não se corrige terceiro no lugar (§8.2) |
| Preflight vira carimbo | Passos 2, 3, 5 produzem artefatos verificáveis, não declarações |
| Projeto de terceiro quebrado | Branch dedicado, suíte do projeto, sem push |

## 15. Testes

- `ChangeExecutor` não aceita plano sem `userMessageId`.
- Escrita em caminho de instalação é negada e gera `ai.policy-tamper`.
- Alteração no núcleo de confiança é classificada RED, sempre.
- Árvore suja bloqueia o início.
- Arquivo fora do plano abre disjuntor e reverte.
- Nenhum caminho produz commit em `main` ou `push`.
- `CHANGE_APPLIED` sem `CHANGE_PLANNED` e `CHANGE_APPROVED` anteriores é
  impossível de construir.

## 16. Critérios de aceite

- `CA-1` Nenhuma alteração de projeto executa sem `ApprovedPlan` válido.
- `CA-2` Nenhuma escrita atinge os diretórios instalados do Zordon.
- `CA-3` Toda alteração no núcleo de confiança é RED e termina em PR, nunca em
  merge automático.
- `CA-4` Árvore de trabalho suja interrompe a tarefa antes de qualquer escrita.
- `CA-5` Todo `CHANGE_APPLIED` tem `userMessageId` e entrada de auditoria.
- `CA-6` Alterar arquivo fora do plano abre disjuntor e reverte a alteração.
- `CA-7` O Zordon nunca reinicia a si mesmo com código que ele escreveu.
