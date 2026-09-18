---
document: spec-001
module: process
section: spec
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [spec,documentacao,rastreabilidade,build,portao]
specId: SPEC-001
---

# SPEC-001 — Validação de documentação e rastreabilidade no build

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `DocumentationAgent` |
| **Revisores** | ArchitectureAgent |
| **Marco** | M0 |
| **Supera** | — |

## 1. Objetivo

Fazer o build reprovar documentação quebrada e referência órfã, para que a
documentação continue confiável enquanto o repositório cresce.

## 2. Problema

Este projeto decidiu que a documentação **alimenta o RAG, que alimenta os agentes**
que escrevem o código ([ADR-0020](../../adr/ADR-0020-rag-como-conhecimento.md)).
Isso muda o custo de um documento errado: ele deixa de ser um incômodo estético e
passa a ser conhecimento errado servido com confiança para quem escreve o commit
seguinte.

Evidência concreta, medida neste repositório antes desta SPEC existir: uma
reestruturação de pastas quebrou 7 âncoras e 5 caminhos do `CODEOWNERS` sem que
nada falhasse. O `CODEOWNERS` com caminho errado é o pior caso: ele não dá erro,
apenas **deixa de proteger, em silêncio**.

## 3. Escopo

- Verificação de front-matter obrigatório em todo documento de `docs/`.
- Verificação de todos os links internos, incluindo âncoras, também nos
  documentos da raiz (`README.md`, `CONTRIBUTING.md`, `SECURITY.md`).
- Verificação de que toda SPEC tem as 17 seções do
  [template](../../process/spec-template.md).
- Índice de rastreabilidade SPEC ↔ código ↔ teste, com reprovação de referência
  órfã e de SPEC `DONE` com critério sem teste.
- Duas tarefas Gradle: `validateDocs` e `traceability`, ambas dentro de
  `verifyAll`.

## 4. Não escopo

- **Verificação de links externos (`http`).** Depende de rede, quebra por motivo
  alheio ao repositório e treinaria todo mundo a ignorar o build vermelho.
- **Análise de conteúdo** (se o texto está correto ou atualizado). Isso é
  julgamento, e é trabalho do `DocumentationAgent` e da revisão humana.
- **Reindexação do RAG.** É portão da Definition of Done, mas depende do
  `ZordonKnowledgeBase`, que chega no M8.
- **Verificação da mensagem de commit** (`Spec:`). Depende de um hook de commit,
  que não é build.

## 5. Arquitetura

Vive no build-logic (`buildSrc`), não em um módulo do produto: é ferramenta de
construção, não capacidade do Zordon.

```text
buildSrc/src/main/kotlin/zordon/build/
  Markdown.kt              parsing de front-matter, títulos, âncoras e links
  SpecIndex.kt             leitura das SPECs de docs/specs
  DocsValidator.kt         a regra de validação          ← testável com `new`
  TraceabilityIndexer.kt   a regra de rastreabilidade    ← testável com `new`
  ValidateDocsTask.kt      tarefa Gradle (só coordena)
  TraceabilityTask.kt      tarefa Gradle (só coordena)
```

A separação entre regra e tarefa é a de
[padrões §9](../../process/code-standards.md#9-testabilidade): uma tarefa Gradle é
um controller, e regra importante não mora em controller.

## 6. Fluxo

```text
./gradlew verifyAll
   │
   ├─► validateDocs
   │      lê docs/**/*.md + *.md da raiz
   │      front-matter obrigatório · securityLevel válido · updatedAt ISO
   │      specId existente · 17 seções nas SPECs
   │      cada link: arquivo existe? âncora existe?
   │      problema → build vermelho, com arquivo e linha
   │
   └─► traceability
          lê as SPECs (id, status, critérios)
          varre *.java e *.kt por @Spec e @AcceptanceCriteria
          referência órfã → build vermelho
          SPEC DONE com critério sem teste → build vermelho
          publica build/traceability/index.json
```

## 7. Interfaces

```kotlin
class DocsValidator(docsRoot: File, rootDocs: List<File>, repositoryRoot: File) {
    fun validate(): Report          // Report(documents, internalLinks, problems)
}

class TraceabilityIndexer(docsRoot: File, sources: List<File>, repositoryRoot: File) {
    fun index(): Report             // Report(specs, implementations, validations, problems)
    fun toJson(report: Report): String
}
```

As anotações ficam em `zordon-api`, sem dependência alguma:

```java
@Retention(SOURCE) @Target(TYPE)             public @interface Spec { String value(); }
@Retention(SOURCE) @Target({METHOD, TYPE})   public @interface AcceptanceCriteria { String value(); }
```

Retenção `SOURCE` porque o índice é construído a partir do texto do repositório —
a anotação não precisa existir em tempo de execução, e não deve: rastreabilidade
é leitura, nunca autoridade
([Rastreabilidade §7](../../process/traceability.md#7-segurança)).

## 8. Eventos

Não se aplica. É uma verificação de build; não há núcleo rodando, não há
barramento.

## 9. Dados

Não se aplica a esquema de banco. A única saída persistida é
`build/traceability/index.json`, derivada do repositório e recriada a cada build.

## 10. Segurança

- **Que `Effect` produz?** Nenhum: o build lê o repositório e escreve em `build/`.
- **Classificação de risco?** Não se aplica — não é ação do Zordon.
- **Superfície nova para conteúdo não confiável?** Não. Lê apenas arquivos
  versionados do próprio repositório.
- **Toca segredo?** Não. O índice guarda identificadores e caminhos; nenhum
  conteúdo de documento entra nele.
- **Ação autônoma?** Não. Roda quando alguém pede o build.

O valor de segurança é indireto e real: um link quebrado para
`docs/security/model.md` é uma norma que ninguém lê, e um `CODEOWNERS` com
caminho errado é uma proteção que não existe.

## 11. Permissões

Não se aplica. Nenhum agente executa isto; é o build.

## 12. Observabilidade

A saída das tarefas é o sinal:

```text
validateDocs: 76 documentos, 773 links internos, 0 problemas
traceability: 2 SPECs, 9 ligações de código, 23 critérios com teste
```

Um problema é reportado com caminho e linha, no formato que um editor abre com
um clique.

## 13. Casos de erro

| Falha | Comportamento | O que o usuário vê |
|---|---|---|
| Documento sem front-matter | Build reprova | `docs/x.md: sem front-matter` |
| Campo obrigatório ausente | Build reprova | `docs/x.md: front-matter sem 'updatedAt'` |
| Link para arquivo inexistente | Build reprova | `docs/x.md:42: alvo inexistente '../y.md'` |
| Âncora inexistente | Build reprova | `docs/x.md:42: âncora inexistente '#secao' em docs/y.md` |
| `@Spec` órfão | Build reprova | `Arquivo.java: @Spec("SPEC-099") não corresponde a nenhuma SPEC` |
| SPEC `DONE` com critério sem teste | Build reprova | `SPEC-002 está DONE mas CA-7 não tem teste ligado` |
| SPEC sem alguma das 17 seções | Build reprova | lista das seções ausentes |
| `docs/` ausente | Tarefa falha na configuração | erro do Gradle, não silêncio |

## 14. Testes

`buildSrc/src/test/kotlin/zordon/build/` — unidade, sem subir build:

| Teste | Cobre |
|---|---|
| `MarkdownTest` | Geração de âncora, front-matter, títulos repetidos, bloco de código |
| `DocsValidatorTest` | Front-matter ausente, link e âncora quebrados, seções de SPEC |
| `TraceabilityIndexerTest` | Referência órfã, critério inexistente, SPEC `DONE` sem teste |

A geração de âncora tem teste próprio porque já produziu **52 falsos positivos**
em duas variações: colapsar espaços consecutivos e remover `_` como se fosse
ênfase dentro de crase. Os dois casos estão congelados em teste.

## 15. Critérios de aceite

- `CA-1` Dado um documento em `docs/` sem um campo obrigatório de front-matter,
  quando o build roda, então `validateDocs` reprova nomeando o arquivo e o campo.
- `CA-2` Dado um link interno cujo arquivo ou âncora não existe, quando o build
  roda, então `validateDocs` reprova com arquivo e linha.
- `CA-3` Dada uma anotação `@Spec` ou `@AcceptanceCriteria` que referencia SPEC
  ou critério inexistente, quando o build roda, então `traceability` reprova.
- `CA-4` Dada uma SPEC em estado `DONE` com um critério sem teste ligado, quando
  o build roda, então `traceability` reprova nomeando o critério.
- `CA-5` Dada uma SPEC sem alguma das 17 seções do template, quando o build roda,
  então `validateDocs` reprova listando as seções ausentes.

## 16. Impacto em outros módulos

- `zordon-api` ganha o pacote `zordon.api.trace` com as duas anotações.
- Todo módulo passa a poder ser reprovado por documentação, não só por código —
  que é exatamente o ponto da
  [Definition of Done §2](../../process/definition-of-done.md#2-a-regra-principal).
- Nenhuma interface de produto muda.

## 17. Dependências

- [ADR-0019](../../adr/ADR-0019-spec-driven-development.md) — Spec-Driven Development
- [ADR-0023](../../adr/ADR-0023-documentacao-como-fonte-de-verdade.md) — documentação como fonte de verdade
- [Rastreabilidade](../../process/traceability.md)
- [Template de SPEC](../../process/spec-template.md)
