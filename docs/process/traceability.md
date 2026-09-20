---
document: traceability
module: process
section: traceability
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [rastreabilidade,spec,commit,teste,documentacao]
specId: null
---

# Rastreabilidade

## 1. Objetivo

Permitir responder, mecanicamente e a qualquer momento:

```text
"Quais arquivos implementam a SPEC-014?"
"Quais testes validam a SPEC-014?"
"Por que este arquivo existe?"
"Que documentação descreve este comportamento?"
"Qual decisão levou a isto?"
```

## 2. Problema

Em um repositório mantido principalmente por agentes, a memória institucional não
existe na cabeça de ninguém. Seis meses depois, a única forma de saber por que um
componente é como é, é o registro. Se o registro não liga SPEC a código, cada
mudança vira arqueologia — e agente que faz arqueologia gasta contexto e erra.

## 3. A cadeia

```text
   ADR ────────────► decisão arquitetural, permanente
    │
    ▼
   SPEC-XXX ───────► o que será construído, com critérios de aceite
    │
    ├──► commit ──► mensagem carrega "SPEC-XXX"
    │
    ├──► código ──► @Spec("SPEC-XXX") nas classes principais
    │
    ├──► teste ───► @AcceptanceCriteria("SPEC-XXX/CA-n")
    │
    └──► doc ─────► front-matter specId: SPEC-XXX
```

Cada elo é uma referência textual verificável. Não há banco de dados nem
ferramenta externa: o repositório é a fonte.

## 4. Convenções

### Commit

```text
feat(mcp): descoberta de ferramentas na conexão

Implementa SPEC-014 §7. Registra ferramentas no ToolRegistry com
prefixo mcp: e riskFloor vindo da configuração local.

Spec: SPEC-014
Refs: ADR-0010
```

A linha `Spec:` é obrigatória em commit que implementa SPEC. O hook de commit
valida o formato e a existência da SPEC.

### Código

```java
@Spec("SPEC-014")
public final class McpToolDiscovery { ... }
```

Anotação apenas nas classes **principais** da SPEC, não em toda classe tocada.
Anotação em tudo vira ruído e deixa de ser sinal.

### Teste

```java
@AcceptanceCriteria("SPEC-014/CA-3")
@Test
void ferramentaAcimaDoTetoDoAgenteNaoEhOferecidaAoModelo() { ... }
```

Um critério pode ter vários testes; um teste referencia um critério.

No motor Python, a mesma marca fica num comentário imediatamente acima do
teste: `# @AcceptanceCriteria("SPEC-011/CA-8")`. Os arquivos `voice/**/*.py`
entram no índice e seus testes rodam em `voiceUnitTest`.

### Documentação

O front-matter de todo documento carrega `specId` quando ele descreve o resultado
de uma SPEC:

```yaml
specId: SPEC-014
```

Isso liga documentação a SPEC e alimenta o filtro do
[Context Router](../rag/context-router.md).

## 5. Consultas

O `TraceabilityIndex` é construído no build a partir do repositório e responde:

| Pergunta | Fonte |
|---|---|
| Arquivos que implementam SPEC-014 | `@Spec` + commits com `Spec: SPEC-014` |
| Testes que validam SPEC-014 | `@AcceptanceCriteria("SPEC-014/*")` |
| Critérios sem teste | Diferença entre `CA-n` da SPEC e anotações encontradas |
| Documentação afetada | Front-matter com `specId: SPEC-014` |
| ADRs relacionados | `Refs:` nos commits + links na SPEC |
| SPECs que tocam um arquivo | Índice invertido |

O mesmo índice é exposto ao `DocumentationAgent`, que o usa para descobrir o que
precisa ser atualizado após uma mudança.

## 6. Verificações no build

| Verificação | Reprova quando |
|---|---|
| `@Spec` referencia SPEC existente | Referência órfã |
| `@AcceptanceCriteria` referencia SPEC e critério existentes | Referência órfã |
| SPEC `DONE` tem todos os `CA-n` cobertos | Critério sem teste |
| Commit com `Spec:` referencia SPEC existente | Referência órfã |
| `specId` em front-matter existe | Referência órfã |

Referência órfã reprova o build. É barato de corrigir na hora e caro de descobrir
depois.

## 7. Segurança

A rastreabilidade é **leitura**, nunca autoridade. Um `@Spec` no código não
concede nada: permissão vem da política, e classificação de risco vem do
[Permission Engine](../security/model.md). Uma SPEC que se declarasse isenta de
revisão não seria obedecida por nenhum componente.

O índice não contém segredo: ele guarda identificadores, caminhos e números de
linha, e passa por `SecretManager.redact` antes de ir para qualquer log.

## 8. Riscos

| Risco | Mitigação |
|---|---|
| Anotação em excesso vira ruído | `@Spec` só nas classes principais |
| Rastro desatualizado após refatoração | Verificação de órfão no build |
| Trabalho manual de manter o rastro | `DocumentationAgent` mantém; humano confere |
| Falsa sensação de cobertura | "Critério sem teste" é reprovação, não aviso |

## 9. Dependências

- [Spec-Driven Development](spec-driven-development.md)
- [Definition of Done](definition-of-done.md)
- [Indexação do RAG](../rag/indexing.md)
- [Catálogo de agentes](../agents/catalog.md)

## 10. Critérios de aceite

- `CA-1` `traceability spec SPEC-014` lista arquivos, testes e documentos ligados.
- `CA-2` `traceability gaps` lista critérios de aceite sem teste.
- `CA-3` Toda referência órfã reprova o build.
- `CA-4` O `DocumentationAgent` descobre a documentação afetada por um commit sem
  intervenção humana.
