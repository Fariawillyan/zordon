---
document: security-sandbox
module: security
section: sandbox
version: 1
updatedAt: 2026-09-18
securityLevel: internal
tags: [seguranca,sandbox,execucao,bubblewrap,worktree,rede]
specId: null
---

# Sandbox de execução

Onde e como o Zordon executa código em nome de um agente. Decisão em
[ADR-0031](../adr/ADR-0031-sandbox-para-codigo-de-agente.md).

## 1. O que vai para o sandbox

| Vai | Não vai |
|---|---|
| Build e testes de projeto (`gradle`, `npm`, `pytest`) | Leitura de métricas, listar containers (skills GREEN de leitura) |
| Scripts do projeto do usuário | Abrir aplicativo no Windows (vai pelo host) |
| Código gerado por agente para verificar algo | Operações do próprio núcleo |
| Ferramentas de terceiro sem histórico | — |

A regra: **código que o Zordon não escreveu nem revisou linha a linha, ou que o
modelo escreveu, roda isolado.**

## 2. Camadas

```text
 ProcessRunner (zordon-security)
    │  lista de programas permitidos, sem shell (Segurança §4)
    ▼
 systemd-run --user --scope   ← teto de CPU, memória, processos, tempo (cgroups)
    ▼
 bwrap (bubblewrap)           ← namespaces: arquivos, rede, processos, usuário
    ▼
 processo do projeto, dentro de uma worktree git
```

## 3. Arquivos

- A tarefa recebe uma **worktree git** do projeto em
  `~/.zordon/sandbox/<tarefa>/`, criada a partir do commit atual.
- Montagens: a worktree em leitura e escrita; o JDK, o Python e os caches de
  dependência em somente leitura; nada mais do `$HOME`.
- Invisíveis: `~/.ssh`, `~/.zordon` (fora da própria worktree), `/mnt/c`, o
  socket do motor de voz, o `endpoint.json`.
- O resultado sai como **diff** da worktree e relatórios. Aplicar o diff no
  projeto real é outra ação, YELLOW, com a origem da tarefa
  ([Identidade](identity.md)).

## 4. Rede

- **Desligada por padrão** (`--unshare-net`).
- Ligada só com permissão explícita e lista de destinos (ex.: `repo.maven.apache.org`,
  `registry.npmjs.org`), por um proxy local que registra cada conexão no trace.
- Nenhuma credencial entra por variável de ambiente; quando necessária, o
  `SecretBroker` a injeta ([ADR-0032](../adr/ADR-0032-segredo-se-usa-nao-se-entrega.md)).

## 5. Recursos

| Recurso | Teto padrão | Ao estourar |
|---|---|---|
| CPU | 4 núcleos | Throttling |
| Memória | 4 GB | OOM do sandbox, não do WSL |
| Processos | 512 | Novos `fork` falham |
| Tempo | 15 min | Encerrado; tarefa vai a `failed` com motivo |

Tetos por tarefa podem ser maiores com aprovação.

## 6. O que o Zordon não faz

- Não apaga worktrees ([ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md)):
  elas ficam em `~/.zordon/sandbox/`, o Diagnóstico mostra o espaço usado e o
  usuário limpa.
- Não usa Docker com daemon: o daemon com root seria superfície maior que o
  problema.

## 7. Casos de erro

| Caso | Comportamento |
|---|---|
| `bwrap` ausente | Execução de código de projeto recusada, com instrução de instalação |
| Processo tenta ler `~/.ssh` | Não existe no namespace; o sinal vai para a defesa ([Defesa](defense.md)) |
| Tempo esgotado | Encerrado; resultado parcial preservado na worktree |

## 8. Testes

- Teste de fuga: dentro do sandbox, ler `~/.ssh`, abrir conexão, escrever fora da
  worktree — tudo falha.
- Teste de teto: processo que aloca memória é morto sem afetar o núcleo.
