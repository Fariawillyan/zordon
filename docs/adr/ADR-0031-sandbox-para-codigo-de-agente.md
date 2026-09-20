---
document: adr-0031
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,seguranca,sandbox,execucao,agentes]
specId: null
---

# ADR-0031 — Código de agente roda em sandbox, não no computador do usuário

**Status:** Aceito · 2026-09-18

## Contexto

A execução hoje é **mediada**: programas em lista de permissão, sem shell, com
política de caminhos ([Segurança §4](../security/model.md#4-commandvalidator),
[ADR-0007](ADR-0007-permissao-sobre-acao-estruturada.md)). Isso impede o
Zordon de executar o que não deve, mas não impede que um programa **permitido** —
um `gradle test`, um script de build do projeto do usuário — faça estrago: o
build de um projeto baixado pode ler `~/.ssh`, abrir conexões ou escrever fora da
pasta. Os agentes de engenharia (M8) e as skills YELLOW de build (M3) rodam
exatamente esse tipo de código.

## Alternativas

| Alternativa | Por que não |
|---|---|
| Só a lista de permissão | Não isola o que o programa permitido faz por dentro |
| VM dedicada | Pesada; o WSL já é uma VM, e uma segunda dentro dele é cara e lenta |
| Docker com daemon | Daemon com root é superfície maior que o problema |
| **Sandbox sem daemon, por tarefa: worktree git + `bubblewrap` (namespaces), rede desligada por padrão** | — |

## Decisão

Código que o Zordon executa em nome de um agente — build, testes, scripts de
projeto — roda num **sandbox por tarefa**:

- **Arquivos:** uma worktree git descartável do projeto; o resto do sistema
  somente leitura, e `~/.ssh`, `~/.zordon`, `/mnt/c` invisíveis.
- **Rede:** desligada por padrão; ligada só com permissão explícita e lista de
  destinos (ex.: repositório de dependências).
- **Recursos:** teto de CPU, memória, tempo e processos (cgroups via systemd-run
  do usuário).
- **Saída:** o que sai do sandbox é o diff da worktree e os relatórios; aplicar o
  diff no projeto é uma ação YELLOW separada.

O `ProcessRunner` do `zordon-security` é o único que cria sandboxes. Detalhes em
[Sandbox](../security/sandbox.md).

## Consequências

- `bubblewrap` vira dependência do WSL (pacote `bubblewrap`), verificada na
  instalação.
- Worktrees descartáveis não são apagadas pelo Zordon ([ADR-0015](ADR-0015-exclusao-impossivel-por-construcao.md)):
  ficam em `~/.zordon/sandbox/` e o usuário limpa; o Diagnóstico mostra o tamanho.
- Skills GREEN de leitura (métricas, listar containers) não precisam de sandbox;
  a regra vale para execução de código de projeto.
