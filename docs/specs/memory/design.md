---
document: spec-memory-design
module: memory
section: storage
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [sqlite,fts5,vetorial,destilacao]
specId: null
---

# Memória

## 1. Três níveis

```text
┌─────────────────────────────────────────────────────────────┐
│ CURTO PRAZO            RAM, escopo do turno                 │
│ o que está acontecendo agora                                │
│ mensagens do turno, resultados de ferramenta, entidades     │
│ ativas, foco atual                    descarta ao fim       │
├─────────────────────────────────────────────────────────────┤
│ CONVERSA               SQLite, escopo da sessão             │
│ o que foi dito                                              │
│ transcrição completa + resumos progressivos                 │
│                                       retenção configurável │
├─────────────────────────────────────────────────────────────┤
│ LONGO PRAZO            SQLite + índices, escopo do usuário   │
│ o que é verdade sobre o usuário e o mundo dele              │
│ preferências, projetos, entidades, procedimentos, eventos   │
│                                       permanente com TTL    │
└─────────────────────────────────────────────────────────────┘
```

A diferença entre CONVERSA e LONGO PRAZO é a que costuma ser ignorada e a que faz
a memória funcionar: conversa é **registro**, longo prazo é **conhecimento
destilado**. Buscar na transcrição bruta devolve ruído; buscar em fatos
destilados devolve resposta.

## 2. Tecnologia

SQLite único em `~/.zordon/zordon.db` — **no ext4, nunca em `/mnt/c`**
([R9](../../architecture/windows-wsl.md#r9--io-em-mntc-é-ordens-de-grandeza-mais-lento)).

| Necessidade | Solução |
|---|---|
| Relacional | SQLite com WAL |
| Busca lexical | FTS5 (embutido) |
| Busca vetorial | extensão `sqlite-vec` |
| Embeddings | provider local (§9.1) |
| Migrações | scripts versionados, unidirecionais |

Um arquivo só significa: um backup, uma transação, nenhum serviço extra para
manter vivo, nenhum container para o Zordon depender. Para um assistente pessoal,
a simplicidade operacional vale mais do que qualquer ganho de um Postgres.

O caminho para Postgres + pgvector está preservado pela interface `MemoryStore`
([Interfaces §8](../../api/core-interfaces.md#8-memorystore)): nenhuma consulta SQL vaza
para fora do módulo `zordon-memory`. Ver
[ADR-0008](../../adr/ADR-0008-sqlite-como-memoria.md).

## 3. Schema

```sql
-- sessões e mensagens ------------------------------------------------
CREATE TABLE session (
  id TEXT PRIMARY KEY, title TEXT,
  started_at TEXT NOT NULL, last_active_at TEXT NOT NULL,
  summary TEXT                       -- resumo progressivo
);

CREATE TABLE message (
  id INTEGER PRIMARY KEY,
  session_id TEXT NOT NULL REFERENCES session(id),
  turn_id TEXT, role TEXT NOT NULL,  -- user | assistant | tool | system
  content TEXT NOT NULL,
  agent_id TEXT, tokens INTEGER, ts TEXT NOT NULL
);
CREATE INDEX idx_message_session ON message(session_id, ts);

-- fatos de longo prazo -----------------------------------------------
CREATE TABLE fact (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,                -- PREFERENCE|PROJECT|ENTITY|EVENT|PROCEDURE
  subject TEXT NOT NULL,
  content TEXT NOT NULL,
  confidence REAL NOT NULL,
  observed_at TEXT NOT NULL,
  expires_at TEXT,
  provenance TEXT NOT NULL,          -- turn_id de origem
  access_count INTEGER DEFAULT 0,
  last_accessed_at TEXT,
  superseded_by TEXT REFERENCES fact(id)
);
CREATE INDEX idx_fact_subject ON fact(subject);
CREATE INDEX idx_fact_kind ON fact(kind, observed_at);

-- entidades ----------------------------------------------------------
CREATE TABLE entity (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,                -- project|app|container|repo|file|person
  canonical_name TEXT NOT NULL,
  aliases TEXT,                      -- JSON array
  attrs TEXT,                        -- JSON
  last_seen_at TEXT NOT NULL
);

-- índices de busca ---------------------------------------------------
CREATE VIRTUAL TABLE fact_fts USING fts5(
  content, subject, content=fact, content_rowid=rowid, tokenize='unicode61'
);
CREATE VIRTUAL TABLE fact_vec USING vec0(
  fact_id TEXT PRIMARY KEY, embedding FLOAT[1024]
);

-- auditoria: ver 07 §7.7
```

`superseded_by` em vez de `UPDATE`: quando um fato muda ("o projeto agora usa
Gradle"), o antigo é marcado como substituído, não sobrescrito. A história fica
recuperável e o `provenance` continua válido. Isso é o que permite responder
"desde quando?" e corrigir uma lembrança errada sem apagar o rastro.

## 4. Recuperação híbrida

Busca vetorial sozinha erra em nomes próprios e identificadores — e é exatamente
disso que uma memória de desenvolvedor é feita ("Aurora", "zordon-core",
`ERR_BRIDGE_UNAVAILABLE`). Busca lexical sozinha erra em paráfrase. Usa-se as
duas, fundidas por Reciprocal Rank Fusion:

```text
   consulta
      │
      ├──► FTS5 (BM25)        ──► lista A (top 30)
      │
      └──► embedding + vec    ──► lista B (top 30)
                                     │
              RRF: score(d) = Σ 1/(60 + rank_i(d))
                                     │
                                     ▼
                        reordenação por recência e confiança
                                     │
                                     ▼
                              top-K (padrão 10)
```

Reordenação final:

```text
score_final = score_rrf
            × (0,5 + 0,5 × confidence)
            × decaimento_de_recência(observed_at)
            × (1 + 0,1 × log(1 + access_count))
```

O decaimento é suave (meia-vida de 90 dias para `EVENT`, nenhuma para
`PREFERENCE` e `PROCEDURE`). Uma preferência declarada em 2024 continua válida;
um evento de 2024 provavelmente não é mais relevante.

## 5. Destilação

Fatos não são escritos no caminho quente do turno. Depois que a resposta é
entregue, uma tarefa assíncrona analisa o turno e propõe fatos.

```text
   turno termina  →  resposta já foi entregue ao usuário
        │
        ▼ (assíncrono, modelo `summarize`)
   ┌─────────────────────────────────────────┐
   │ Extrair:                                │
   │  preferências declaradas                │
   │  entidades mencionadas e seus atributos │
   │  decisões tomadas                       │
   │  procedimentos que funcionaram          │
   │  correções do usuário  ← peso maior     │
   └──────────────┬──────────────────────────┘
                  ▼
   deduplica contra fatos existentes (similaridade > 0,92)
                  ▼
   conflita com fato existente?
        sim → marca superseded_by, grava o novo
        não → grava
                  ▼
   evt MEMORY_WRITTEN
```

Regras:

- **Correção do usuário tem prioridade.** "Não, o Aurora usa Maven, não
  Gradle" gera fato de confiança alta que substitui o anterior. Aprender com
  correção é o comportamento mais valioso de uma memória.
- **Nada sensível vira fato.** Conteúdo redigido pelo `SecretManager` nunca entra.
- **Fato sempre tem procedência.** Sem `turn_id`, não grava.
- **Destilação não bloqueia.** Se o modelo de sumarização estiver indisponível, o
  turno já terminou; a destilação é enfileirada e tentada depois.

## 6. UC5 na prática

"Zordon, abra o projeto que trabalhamos ontem."

```text
 1. IntentRouter: intenção=abrir_projeto, alvo=ambíguo ("ontem")
 2. memory.search("projeto trabalhado", kinds=[PROJECT, EVENT],
                  timeWindow=[ontem 00:00, ontem 23:59])
 3. FTS5 + vetorial + filtro temporal → candidatos:
        fact: "trabalhou em Aurora"   (2026-09-16, conf 0,9, 14 acessos)
        fact: "abriu zordon-core"      (2026-09-16, conf 0,7, 2 acessos)
 4. desempate por access_count e duração da sessão → Aurora
 5. entity("Aurora") → path = D:/projetos/aurora, ide = intellij
 6. skill:windows.openApplication {target: "intellij",
                                   args: ["D:/projetos/aurora"]}
 7. GREEN (app do catálogo, caminho em workspace) → executa
 8. "Abri o Aurora no IntelliJ."
```

Se o passo 4 não desempatar com folga, o Zordon **pergunta** em vez de adivinhar:
"Ontem você mexeu no Aurora e no zordon-core. Qual deles?". Adivinhar errado
aqui é barato de corrigir mas caro em confiança.

## 7. Migrações

Memória e auditoria são dados que o usuário não pode recriar. Disciplina desde o
primeiro commit:

- Tabela `schema_version` com uma linha.
- Migrações numeradas em `zordon-memory/src/main/resources/migrations/V###__*.sql`.
- Só para frente. Não existe *downgrade*; para voltar, restaura-se backup.
- **Backup automático antes de migrar**: cópia de `zordon.db` para
  `zordon.db.bak.<versão>`, mantendo as 3 últimas.
- Migração roda em transação; falha → rollback e o núcleo **não sobe**, com erro
  claro. Subir com schema parcialmente migrado é pior do que não subir.
- Teste de migração no CI a partir de um banco de exemplo de cada versão anterior.

## 8. Controles do usuário

A memória precisa ser inspecionável e corrigível, senão vira superstição:

| Ação | Como |
|---|---|
| Ver o que o Zordon sabe | Tela Memória, navegável por tipo e assunto |
| Ver por que ele sabe | Cada fato mostra `provenance` e leva ao turno de origem |
| Corrigir | Editar o fato (gera novo, marca o antigo como substituído) |
| Esquecer um fato | `memory.forget` — apaga de fato, inclusive do índice vetorial |
| Esquecer um assunto | Apaga todos os fatos de um `subject` |
| Não lembrar deste turno | Marcar a sessão como efêmera antes ou depois; nada é destilado |
| Exportar | JSON completo de fatos e sessões |

"Esquecer" apaga de verdade — não é marcação lógica. A única exceção é a
auditoria, que é append-only por design e não guarda o conteúdo dos fatos.

**Isto não contradiz a regra de que o Zordon não apaga**
([ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md)). Aquela regra
protege o **sistema de arquivos do usuário**. `memory.forget` remove uma linha do
banco do próprio Zordon — é ele esquecendo algo sobre si mesmo, a pedido do dono,
e é a contrapartida necessária de ter memória: uma lembrança que não pode ser
apagada é uma lembrança que o usuário não controla.

## 9. Caminho para Postgres

Gatilhos que justificariam a migração: acesso concorrente de várias máquinas,
mais de ~100 mil fatos com latência de busca degradada, ou necessidade de
replicação. Nenhum deles se aplica a um assistente pessoal hoje.

Se acontecer: `MemoryStore` ganha uma segunda implementação, `sqlite-vec` vira
`pgvector`, FTS5 vira `tsvector`, e o RRF continua igual. O trabalho fica contido
em `zordon-memory` — que é precisamente o objetivo de a interface existir.

## 10. Relações (grafo de conhecimento)

O RAG responde "o que diz o documento"; a memória, "o que sabemos". Perguntas de
**relação** — qual serviço usa este banco, em que servidor roda, o que quebra se
ele parar — ficam em duas tabelas no mesmo SQLite
([ADR-0037](../../adr/ADR-0037-relacoes-na-memoria.md)):

```sql
CREATE TABLE entity   (id TEXT PRIMARY KEY, kind TEXT, name TEXT, attrs_json TEXT);
CREATE TABLE relation (from_id TEXT, kind TEXT, to_id TEXT,
                       source TEXT NOT NULL,        -- de onde veio: compose, config, rag:<doc>, usuário
                       confidence REAL, observed_at TEXT,
                       PRIMARY KEY (from_id, kind, to_id, source));
```

- `kind` de entidade: `project`, `service`, `database`, `server`, `container`,
  `person`, `repository`; de relação: `uses`, `runs_on`, `owned_by`, `depends_on`,
  `exposes`.
- Toda relação tem **fonte**; relação sem fonte não entra. Quando a fonte muda
  (o `docker-compose.yml` foi editado), as relações dela são revalidadas.
- Vizinhança e impacto até N saltos são `WITH RECURSIVE`. A interface
  `KnowledgeGraph` isola a troca por um banco de grafo, que só entra se as
  consultas ficarem lentas com dados reais.
- Entra no M8; antes disso as tabelas existem vazias.

