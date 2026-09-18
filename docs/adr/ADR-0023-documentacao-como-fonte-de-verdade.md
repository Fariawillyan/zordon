---
document: adr-0023
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,documentacao,estrutura,front-matter]
specId: null
---

# ADR-0023 — Documentação como fonte de verdade, com estrutura e metadados

**Status:** Aceito · 2026-09-17
**Supera:** a numeração plana `01-..21-` usada até aqui.

## Contexto

A documentação do Zordon deixou de ser apenas para humanos: ela alimenta o
[RAG](../rag/knowledge-base.md), que alimenta os agentes que constroem o produto.
Isso impõe requisitos que documentação comum não tem — cada documento precisa ser
localizável por módulo, filtrável por nível de segurança e chunkável por seção.

A organização anterior era 22 arquivos numerados em uma pasta plana. Funcionou
até ~9.000 linhas e não escala: o número no nome codifica uma ordem de leitura
que envelhece, e não diz nada sobre módulo ou domínio — que é justamente o que o
RAG precisa filtrar.

## Decisão

### 1. Estrutura por domínio

```text
docs/
├── process/        como se trabalha (SDD, DoD, padrões, rastreabilidade)
├── architecture/   overview, components, communication, event-driven, security, windows-wsl
├── adr/            decisões
├── specs/<módulo>/ design do módulo + SPEC-XXX
├── agents/         catálogo e orquestração
├── rag/            knowledge base, indexação, context router
├── api/            contratos: ZWP e interfaces
├── security/       norma de segurança (a autoridade)
├── operations/     instalação, observabilidade, tokens
└── testing/        estratégia
```

Sem número no nome. A ordem de leitura vive no índice, onde pode mudar sem
renomear arquivo.

### 2. Front-matter obrigatório

Todo documento carrega os metadados que o RAG usa, e a ausência reprova o build:

```yaml
---
document: security-model
module: security
section: model
version: 1
updatedAt: 2026-09-17
securityLevel: restricted      # public | internal | restricted
tags: [ameacas, permissao, auditoria]
specId: null                   # ou SPEC-XXX
---
```

`securityLevel` não é rótulo: ele governa qual agente pode recuperar aquele
conteúdo ([Context Router §4](../rag/context-router.md#4-pipeline)).

### 3. Sem redundância

Um conceito tem **um** dono. Os outros documentos referenciam por link.

Onde o pedido original criava sobreposição — `specs/security/` e `security/`,
`specs/defense/` e `security/defense.md` — a resolução é: `security/` é a
**norma**, e as pastas em `specs/` guardam apenas SPECs de funcionalidades, com
um README apontando para a norma. Duplicar a norma em dois lugares garantiria que
as duas cópias divergissem.

### 4. Estrutura interna proporcional ao tipo

O pedido original listava dez seções obrigatórias por documento (objetivo,
contexto, responsabilidades, dependências, interfaces, eventos, riscos,
segurança, testes, critérios de aceite). Isso é **exatamente o formato de uma
SPEC** e é excelente para specs. Aplicado ao glossário ou a um índice, produziria
seções vazias que ninguém lê — e seção vazia obrigatória ensina a preencher por
obrigação, o que destrói o valor do formato.

A resolução:

| Tipo | Estrutura |
|---|---|
| **SPEC** | As 17 seções do [template](../process/spec-template.md), completas. Seção sem conteúdo recebe "Não se aplica" + razão |
| **Documento de subsistema** | Objetivo, contexto, conteúdo técnico, riscos, segurança, testes, critérios de aceite |
| **Referência** (glossário, índice, ADR) | Front-matter + o que o tipo pede |

Todos têm front-matter. Todos têm objetivo declarado. A profundidade varia com o
que o documento é.

## Consequências

**Positivas.** O RAG filtra por módulo e nível de segurança de graça, porque a
informação está no caminho e no front-matter. Documento novo tem lugar óbvio.
Ordem de leitura muda sem renomear arquivo. `securityLevel` vira controle de
acesso real para agentes.

**Negativas.** Migração quebrou todos os links relativos — resolvida por
reescrita programática e validação de 386 links. Uma pasta a mais de profundidade
torna os caminhos relativos mais longos. Front-matter é ruído visual em
arquivos curtos.

**Custo aceito conscientemente:** a numeração antiga era mais fácil de citar em
conversa ("veja o 07"). Perdeu-se isso em troca de organização por domínio. O
índice em `docs/README.md` compensa parcialmente.

## Verificação

| Regra | Reprova o build |
|---|---|
| Front-matter presente e válido | Sim |
| `specId` referenciado existe | Sim |
| Nenhum link interno quebrado | Sim |
| Nenhum conceito documentado em dois lugares | Revisão do `DocumentationAgent` |
