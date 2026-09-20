---
document: spec-028
module: rag
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: internal
tags: [spec,rag,conhecimento,fts5,citacao,m8]
specId: SPEC-028
---

# SPEC-028 — Base de conhecimento e RAG

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `RagAgent` |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M8 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

O Zordon passa a responder sobre o próprio projeto **citando a fonte**: a
documentação vira índice consultável, e cada resposta aponta arquivo e seção
([Knowledge Base](../../rag/knowledge-base.md), [Auto-modificação §6](../../process/self-modification.md#6-preflight-obrigatório)).

## 2. Problema

Toda decisão do projeto está em Markdown versionado, e o modelo não alcança nada
disso. Sem base de conhecimento, o passo 2 do preflight ("consultar RAG e
documentação existente") não existe, e o agente de engenharia adivinha.

## 3. Escopo

**Indexação**
- Fontes: as pastas do `[rag] roots` no `config.toml`. Sem configuração, o
  `docs/` do repositório do Zordon, quando ele estiver no disco.
- Só Markdown. Cada arquivo vira pedaços por cabeçalho:
  - o pedaço carrega o **caminho de cabeçalhos** ("overview.md › 3. Componentes");
  - até 1.200 caracteres, quebrando em parágrafo;
  - o front matter (`document`, `module`, `securityLevel`, `specId`) vai junto.
- **Incremental:** hash do arquivo. Igual, não reindexa. Arquivo que sumiu sai do
  índice — é dado derivado, não documento do usuário.
- Índice em FTS5 (`doc_fts`), no mesmo `zordon.db` (V007).

**Busca**
- BM25 com a mesma consulta saneada da memória (sem acento, com prefixo).
- Cada resultado devolve: arquivo, caminho de cabeçalhos, trecho e pontuação.
- Teto de contexto: no máximo 8 pedaços e 6.000 caracteres por consulta, para
  caber no orçamento de 8.000 tokens do critério do marco.

**Índice velho (`RAG_STALE`)**
- `rag.status` diz quantos arquivos mudaram desde a última indexação.
- Com índice velho, a busca **responde assim mesmo**, mas marca o resultado com
  o aviso. Quem bloqueia a Definition of Done com isso é o processo, não o
  motor.

**Ferramenta e agente**
- `rag.search {query, limit?}`: GREEN, sem efeito, visível ao modelo. Resultado
  é dado, com a citação em cada linha.
- Agente embutido `rag`: teto GREEN, `rag.search` fixada, e prompt que **exige**
  citar arquivo e seção em cada afirmação.

**ZWP**

| Método | Params | Retorno |
|---|---|---|
| `rag.status` | `{}` | `{files, chunks, indexedAt, staleFiles, roots}` |
| `rag.search` | `{query, limit?}` | `{hits[{path, heading, text, score}]}` |
| `rag.reindex` | `{}` | `{files, chunks, tookMs}`; só de um desktop |

## 4. Não escopo

- Embeddings locais e `sqlite-vec`: o ponto de extensão é o mesmo `Embedder` da
  SPEC-021. Entram quando houver modelo local com licença e hash fixados — é
  decisão de cadeia de suprimentos do owner.
- Código-fonte no índice (só Markdown por enquanto).
- `ContextRouter` com orçamento por intenção e filtro por `securityScope`.
- `KnowledgeGraph` (ADR-0037): as tabelas de relação existem vazias desde a
  SPEC-021.

## 5. Arquitetura

```text
docs/**.md ─► MarkdownChunker (cabeçalhos, front matter) ─► KnowledgeStore (doc_chunk + doc_fts, V007)
                                                                  │
                          rag.search (ferramenta e ZWP) ◄─────────┘
                                   │
                          agente `rag` cita arquivo › seção
```

## 6. Fluxo

"Zordon, pergunta pro rag: por que a auditoria é append-only?"
1. O agente `rag` chama `rag.search {query: "auditoria append-only"}`.
2. Voltam pedaços de `docs/security/model.md › 7. Auditoria` e do
   `SPEC-014`.
3. A resposta cita os dois, com caminho e seção.

## 7. Interfaces

```java
public final class MarkdownChunker { List<Chunk> chunks(Path file, String text); }
public interface KnowledgeStore {
    int indexDocument(String path, String hash, List<Chunk> chunks);
    List<Hit> searchDocs(String query, int limit);
    Map<String, Object> knowledgeStats();
}
```

## 8. Eventos

Nenhum novo. A reindexação aparece em `rag.status` e no diagnóstico.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Pedaços e índice | `zordon.db`: `doc_chunk`, `doc_fts` (V007) | Derivado; refeito a cada indexação |

## 10. Segurança

- **Só leitura**, e só nas raízes configuradas.
- O conteúdo indexado é documentação do próprio projeto; mesmo assim, o
  resultado volta como **dado**, com a mesma marca dos resultados de ferramenta
  (SPEC-019). Documento não manda no assistente.
- Arquivo com `securityLevel: restricted` continua indexado (é local), mas o
  nível vai junto do pedaço para quando houver `ContextRouter`.

## 11. Permissões

- `rag.search`: GREEN, sem efeito.
- `rag.reindex`: só do desktop (é I/O de varredura, não uma ação no sistema).

## 12. Observabilidade

- Log INFO por indexação: arquivos, pedaços e duração.
- `system.diagnostics.rag`: arquivos, pedaços, quando indexou, quantos estão
  velhos.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Raiz inexistente | Ignorada, com o motivo em `rag.status` |
| Arquivo ilegível | Pulado, com log; os outros seguem |
| Consulta só com palavras comuns | Lista vazia |
| Índice vazio | A busca diz que não há índice e sugere `rag.reindex` |

## 14. Testes

- Chunker: cabeçalhos aninhados, front matter, quebra por tamanho em parágrafo.
- Indexação incremental: arquivo igual não reindexa; alterado reindexa; removido
  sai.
- Busca: acha por termo do texto e do cabeçalho; devolve citação; respeita o
  teto de pedaços e de caracteres.
- Índice velho: `rag.status` acusa, e a busca marca o resultado.
- Fim a fim: o agente `rag` responde citando arquivo e seção.

### Evidências (2026-09-19)

- `KnowledgeBaseTest` (5 testes):
  - CA-1: indexa dois arquivos com caminho de cabeçalhos; reindexar sem mudança
    não reescreve nada; arquivo alterado reindexa; arquivo removido sai;
  - CA-2: a busca acha "auditoria append-only" e devolve
    `model.md › Modelo de segurança › 7. Auditoria`, dentro dos tetos de 8
    pedaços e 6.000 caracteres; consulta sem correspondência diz que não há;
  - CA-3: arquivo alterado depois da indexação faz `rag.status` acusar e a
    resposta começar com "[índice velho: 1 arquivo(s)…]";
  - sem índice, a resposta diz como resolver;
  - o divisor entende front matter, cabeçalho aninhado e bloco de código (o
    `#` dentro de ``` não vira cabeçalho).
- Agente `rag` embutido, teto GREEN, só leitura, com `rag.search` fixado.
- **No serviço instalado (2026-09-19, 22h14), com a documentação real:**
  - `rag.reindex`: **124 arquivos, 1.494 pedaços, em 612 ms**;
  - `rag.search "por que a auditoria é append-only"` devolveu, nesta ordem,
    `model.md › Segurança — modelo › 7. Auditoria`, o `ADR-0008` e a própria
    SPEC-028 — a citação aponta o arquivo e a seção certos;
  - o critério do marco ("responder citando a fonte com menos de 8.000 tokens")
    está satisfeito na recuperação; a resposta falada depende do provider, que
    espera o login do owner no `claude` CLI.

## 15. Critérios de aceite

- `CA-1` Dada a pasta de documentação, então a indexação cria pedaços com
  caminho de cabeçalhos, e reindexar sem mudança não reescreve nada.
- `CA-2` Dada uma pergunta sobre o projeto, então `rag.search` devolve pedaços
  com arquivo e seção, dentro do teto de 8 pedaços e 6.000 caracteres.
- `CA-3` Dado um arquivo alterado depois da indexação, então `rag.status` acusa
  o índice velho e o resultado da busca vem marcado.
- `CA-4` Dado o agente `rag`, então a resposta cita arquivo e seção para cada
  afirmação, e ele não tem nenhuma ferramenta fora de leitura.

## 16. Impacto em outros módulos

- `zordon-memory`: migração V007 (`doc_chunk`, `doc_fts`).
- `zordon-core`: pacote `rag`, ferramenta `rag.search`, métodos ZWP, agente
  embutido.
- `packaging/wsl/config.toml.example`: bloco `[rag]`.

## 17. Dependências

- [RAG](README.md) · [SPEC-021](../memory/SPEC-021-memoria-de-longo-prazo.md) ·
  [SPEC-022](../agents/SPEC-022-agentes-como-configuracao.md) ·
  [Auto-modificação](../../process/self-modification.md)
