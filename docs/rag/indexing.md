---
document: rag-indexing
module: rag
section: indexing
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [rag,chunking,incremental,versionamento,reindex]
specId: null
---

# Indexação

## 1. Objetivo

Manter o índice sincronizado com a documentação, reprocessando apenas o que
mudou.

## 2. Chunking

Chunk por **seção**, não por número fixo de caracteres. A documentação do Zordon
é estruturada em headings numerados, e a seção é a unidade semântica natural:
quem pergunta "como funciona o mascaramento de segredos?" quer aquela subseção
inteira, não 512 caracteres cortados no meio de uma tabela.

| Regra | Valor |
|---|---|
| Unidade base | `###` quando existe; senão `##` |
| Alvo | 200–600 tokens |
| Teto | 1.000 tokens — acima disso, divide no próximo `###` ou parágrafo |
| Piso | 80 tokens — abaixo disso, funde com a seção irmã seguinte |
| Sobreposição | Nenhuma. O `headingPath` dá o contexto que a sobreposição daria |
| Tabelas e blocos de código | **Nunca divididos.** Uma tabela partida perde o cabeçalho e engana |
| Front-matter | Não vira chunk; vira metadado de todos os chunks do documento |

Cada chunk é prefixado, na montagem do contexto, pelo seu `headingPath`:

```text
[security/model.md › 5. Segredos › Mascaramento]
Segredo nunca é exibido por inteiro. Em lugar nenhum: ...
```

Isso custa ~15 tokens por chunk e resolve o problema mais comum de RAG sobre
documentação técnica: o modelo recebe um trecho correto e não sabe de onde ele
veio, então cita errado ou generaliza.

## 3. Identidade estável do chunk

```text
chunkId = sha256(path + "|" + headingPath)[:16]
```

O id depende de **onde** o chunk está, não do conteúdo. Consequência: editar um
parágrafo mantém o id e re-embeda um chunk; renomear uma seção cria um id novo e
aposenta o antigo. É o que torna a indexação incremental possível.

## 4. Indexação incremental

```text
   git diff ──► arquivos alterados
       │
       ▼
   para cada documento alterado:
       │
       ├── parse → lista de chunks com id e hash de conteúdo
       │
       ├── comparar com o índice:
       │     id existe e hash igual   → REUSA (não re-embeda)
       │     id existe e hash mudou   → RE-EMBEDA
       │     id novo                  → EMBEDA
       │     id sumiu                 → APOSENTA
       │
       └── atualizar documentVersion, embeddingVersion, indexedAt
```

**Não reprocessar o knowledge base inteiro sem necessidade.** Uma correção de
typo em uma seção re-embeda um chunk, não 400.

Reindexação completa acontece apenas quando: o modelo de embeddings muda
(`embeddingVersion`), o algoritmo de chunking muda, ou o índice é detectado
corrompido. Nesses casos é uma operação explícita, anunciada, com barra de
progresso — não um efeito colateral silencioso.

## 5. Versionamento

Três campos, com papéis distintos:

| Campo | O que registra | Quando muda |
|---|---|---|
| `documentVersion` | Hash do conteúdo do documento | A cada alteração do arquivo |
| `embeddingVersion` | Modelo + versão (`bge-m3@1`) | Ao trocar o modelo de embeddings |
| `indexedAt` | Quando aquele chunk foi indexado | A cada (re)embedding |

Um chunk cujo `embeddingVersion` difere do atual é **inutilizável para
comparação vetorial** — vetores de modelos diferentes não são comparáveis. O
retriever ignora esses chunks e o indexador os reprocessa em segundo plano, com o
sistema continuando a funcionar por BM25 enquanto isso.

## 6. Gatilhos

| Gatilho | Comportamento |
|---|---|
| `DocumentationAgent` atualizou documentação | Reindexa os documentos tocados |
| Merge em `main` | Reindexa o diff |
| Documento alterado fora do fluxo | Detectado por hash na inicialização → `RAG_STALE` |
| Modelo de embeddings alterado | Reindexação completa explícita |
| Índice corrompido | Reindexação completa + notificação |

O ciclo do [DocumentationAgent](../agents/catalog.md#documentationagent):

```text
   Código mudou
      ↓
   DocumentationAgent detecta documentação afetada  (via TraceabilityIndex)
      ↓
   Atualiza os documentos
      ↓
   Reindexa apenas os chunks alterados
      ↓
   RAG_INDEXED {reused: 387, reembedded: 4}
```

## 7. Custo

Com ~9.000 linhas de documentação, o índice fica na ordem de **400 a 600
chunks**. Indexação completa em CPU leva poucos minutos; incremental, milissegundos.

O índice vive no mesmo SQLite da memória
([ADR-0008](../adr/ADR-0008-sqlite-como-memoria.md)), em tabelas próprias — uma
transação, um backup, nenhum serviço adicional. No ext4, **nunca** em `/mnt/c`
([Windows↔WSL §R9](../architecture/windows-wsl.md#r9--io-em-mntc-é-ordens-de-grandeza-mais-lento)).

## 8. Interfaces

```java
public interface DocumentIndexer {
    IndexResult indexIncremental(List<DocumentRef> changed);
    IndexResult reindexAll(ReindexReason reason);
    List<DocumentRef> staleDocuments();
}

public record IndexResult(
        int documents, int chunksReused, int chunksReembedded,
        int chunksRetired, Duration duration) {}
```

## 9. Casos de erro

| Falha | Comportamento |
|---|---|
| Front-matter ausente ou inválido | Documento não indexado; reprova o build |
| Documento excede o teto de chunk sem ponto de divisão | Divide por parágrafo e registra aviso |
| Provider de embeddings fora durante a indexação | Indexa o texto (BM25 funciona), marca chunks para embedding posterior |
| Escrita no índice falha | Transação revertida; `RAG_STALE` mantido |

## 10. Riscos

| Risco | Mitigação |
|---|---|
| Índice diverge da documentação em silêncio | `RAG_STALE` é medido e bloqueia a [DoD](../process/definition-of-done.md) |
| Reindexação completa acidental | Só por gatilho explícito de §6 |
| Mistura de `embeddingVersion` no mesmo índice | Retriever ignora versões antigas; reprocessamento em segundo plano |
| Chunking ruim degrada a recuperação | Casos golden de recuperação reprovam mudança de chunking que piore o resultado |

## 11. Testes

- Indexar duas vezes sem alteração produz `reembedded: 0`.
- Alterar um parágrafo produz `reembedded: 1`.
- Renomear uma seção aposenta o chunk antigo e cria um novo.
- Tabela e bloco de código nunca aparecem divididos entre chunks.
- Reconstrução do zero produz os mesmos `chunkId`.

## 12. Critérios de aceite

- `CA-1` Editar uma seção re-embeda apenas os chunks daquela seção.
- `CA-2` Todo chunk carrega `documentVersion`, `embeddingVersion` e `indexedAt`.
- `CA-3` Documento sem front-matter válido reprova o build.
- `CA-4` `zordon.rag.stale_documents` é zero após um ciclo do `DocumentationAgent`.
