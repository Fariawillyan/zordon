---
document: adr-0008
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,sqlite,como,memoria]
specId: null
---

# ADR-0008 — SQLite único para memória, auditoria e estado

**Status:** Aceito · 2026-09-17

## Contexto

O Zordon precisa persistir: conversas, fatos de longo prazo com busca semântica,
entidades, automações, auditoria append-only e fila de notificações. O briefing
sugeriu SQLite inicialmente, com arquitetura preparada para Postgres, banco
vetorial e embeddings.

## Alternativas

**A. SQLite + arquivos.** Relacional no SQLite, vetores em arquivo/índice à
parte (FAISS, hnswlib). Funciona, mas cria duas fontes de verdade que precisam
ser mantidas em sincronia; uma queda entre a escrita do fato e a do índice deixa
o sistema inconsistente de forma difícil de detectar.

**B. Postgres + pgvector.** A solução "adulta". Mas exige um serviço adicional
rodando no WSL, subindo antes do Zordon, com backup próprio, atualizações e
possibilidade de falha. Para um assistente pessoal de um usuário, é um
componente crítico a mais sem ganho correspondente.

**C. Banco vetorial dedicado (Qdrant, Chroma) + SQLite.** Melhor busca vetorial,
mas mais um serviço, mais uma dependência, e a mesma divisão de fonte de verdade
da alternativa A.

**D. SQLite único com extensões — FTS5 para busca lexical e `sqlite-vec` para
vetores.** Tudo num arquivo, uma transação, um backup.

## Decisão

**Alternativa D.** `~/.zordon/zordon.db`, no ext4 — **nunca** em `/mnt/c`
([R9](../architecture/windows-wsl.md#r9--io-em-mntc-é-ordens-de-grandeza-mais-lento):
**[medido]** 47× mais lento em metadados; SQLite com WAL sobre 9p é uma fonte de
corrupção).

| Necessidade | Solução |
|---|---|
| Relacional | SQLite com WAL |
| Busca lexical | FTS5 (embutido) |
| Busca vetorial | `sqlite-vec` |
| Recuperação | Híbrida, fundida por RRF |

**Sobre escala.** Um assistente pessoal acumula na ordem de dezenas de milhares
de fatos. Busca vetorial linear sobre 50 mil vetores de 1024 dimensões é questão
de dezenas de milissegundos; `sqlite-vec` melhora isso. O gargalo de latência do
sistema é o LLM, não o banco, por duas ordens de grandeza.

**O caminho para Postgres é preservado pela interface, não pela tecnologia.**
`MemoryStore` ([Interfaces §8](../api/core-interfaces.md#8-memorystore)) é a única
fronteira; nenhuma consulta SQL vaza de `zordon-memory`. Migrar significa uma
segunda implementação: `sqlite-vec` → `pgvector`, FTS5 → `tsvector`, RRF
inalterado.

## Consequências

**Positivas.** Zero serviço adicional — o Zordon não depende de outro processo
para lembrar. Transação única cobre fato + índice lexical + índice vetorial, sem
inconsistência possível. Backup é um arquivo (via `VACUUM INTO`, não cópia
bruta). Inspecionável com `sqlite3` durante o desenvolvimento.

**Negativas.** `sqlite-vec` é uma extensão nativa que precisa ser carregada e
empacotada por plataforma. Escrita concorrente é serializada (irrelevante: um
escritor). Sem replicação. Busca vetorial menos sofisticada que um banco
dedicado.

**Gatilhos para reavaliar.** Acesso de múltiplas máquinas; mais de ~100 mil fatos
com latência de busca degradada; necessidade de replicação. Nenhum se aplica
hoje, e o primeiro contradiz o escopo de [Visão §4](../vision.md#fora-do-escopo-v1).

**Disciplina obrigatória desde o primeiro commit.** Migrações numeradas,
unidirecionais, com backup automático antes de rodar, e falha de migração
impedindo a inicialização. Memória e auditoria são dados que o usuário não pode
recriar ([Memória §7](../specs/memory/design.md#7-migrações)).
