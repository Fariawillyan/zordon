---
document: adr-0015
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,exclusao,impossivel,por,construcao]
specId: null
---

# ADR-0015 — Exclusão de arquivos é impossível por construção

**Status:** Aceito · 2026-09-17
**Supera parcialmente:** a Skill `files.delete` prevista antes em
[MCP §5](../specs/mcp/design.md) e o exemplo RED de
[Segurança §2](../security/model.md).

## Contexto

O Zordon precisa lidar com arquivos indesejados em dois cenários: o usuário pede
limpeza ("apague os logs antigos") e o Defense Engine encontra um arquivo
malicioso.

O desenho anterior tratava exclusão como uma ação **RED**: permitida mediante
confirmação explícita do usuário, com os alvos listados. Isso segue a prática
comum e está errado para este produto.

Três razões:

1. **Irreversibilidade não combina com um sistema probabilístico.** Toda outra
   ação do Zordon pode ser desfeita, contestada ou pelo menos investigada depois.
   Exclusão não. Um erro de resolução de alvo — um glob mais amplo do que o
   esperado, um caminho que o modelo trocou, uma entidade ambígua resolvida
   errada — é irrecuperável.
2. **Confirmação não protege o suficiente.** O diálogo RED lista os alvos, mas o
   usuário decide em segundos, sob a expectativa de que o Zordon acertou. É
   exatamente o cenário em que uma injeção de prompt bem construída compensa:
   induzir o modelo a propor uma exclusão plausível e contar com a aprovação
   apressada.
3. **Para malware, apagar é a resposta errada de qualquer forma.** Destrói a
   evidência, impede análise, impossibilita recuperação de falso positivo, e não
   é o que um produto de segurança sério faz.

## Alternativas

**A. Manter exclusão como RED.** Prática comum, flexível, resolve o pedido do
usuário diretamente. Riscos acima.

**B. Exclusão apenas para caminhos em uma lista de permissão.** Reduz o dano, mas
mantém a categoria existindo — e a lista tende a crescer com o uso.

**C. Exclusão não existe. Quarentena reversível no lugar.**

## Decisão

**Alternativa C.**

```text
   DETECTAR → BLOQUEAR → QUARENTENA → AVISAR       ✅
   DETECTAR → APAGAR                                ❌
```

A ausência é **estrutural**, em cinco camadas independentes — nenhuma delas é
uma regra de política que alguém possa afrouxar:

| Camada | Garantia |
|---|---|
| Interface | `FileAccess` não tem `delete`, `unlink` nem `rmdir` |
| Skills | Não existe `skill:files.delete`. Existe `skill:files.quarantine` |
| Validador | `rm`, `rmdir`, `unlink`, `del`, `erase`, `Remove-Item`, `shred` e equivalentes estão na lista de programas **negados** |
| Efeitos | `Effect.DELETE_FS` não existe no enum. Existe `Effect.QUARANTINE_FS` |
| Build | ArchUnit reprova `Files.delete`, `Files.deleteIfExists` e `File.delete` fora de `zordon-defense.vault` |

Mover para o cofre é `move`, não exclusão: o arquivo continua existindo, com
hash, origem, processo relacionado, motivo, evidências e restauração possível
([Defesa §6](../security/defense.md#6-quarantine-vault)).

### Fronteiras da regra

A regra é sobre **o sistema de arquivos do usuário**, e vale inclusive quando o
próprio usuário ordena através da IA. O que ela não cobre:

| Operação | Permitida? | Por quê |
|---|---|---|
| Usuário apaga arquivo no Explorer ou no terminal | Sim | O Zordon não controla a máquina, protege-a |
| `memory.forget` remove um fato | Sim | É a memória do Zordon sobre si, não arquivo do usuário. Reversível pela procedência |
| Poda de retenção da auditoria | Sim | Operação manual do operador, fora do alcance da IA, e ela própria auditada |
| Remoção de item expirado do cofre | Sim | Única exclusão do sistema. Manual, dentro do cofre, após o Zordon **perguntar** |
| `git clean`, `mvn clean`, `docker prune` | **Não** | São exclusão com outro nome. Ficam na lista de negados |

A última linha é a que gera atrito real, e é deliberada: `git clean -fdx` já
destruiu trabalho de muita gente sem ajuda de IA nenhuma.

## Consequências

**Positivas.** A classe inteira de dano irreversível desaparece. Toda mensagem de
quarentena pode dizer, com verdade literal, "nenhum arquivo foi apagado" — e essa
frase é a diferença entre o usuário confiar ou temer o próprio assistente.
Evidência preservada para análise. Falso positivo custa um clique de restauração,
não um backup.

**Negativas.** O usuário não consegue usar o Zordon para limpar disco. Pedidos
legítimos de exclusão são recusados. O cofre consome espaço (mitigado: retenção
de 90 dias com pergunta antes de expirar, e alerta quando passa de um limite
configurável). `git clean` e `mvn clean` precisam ser rodados pelo usuário.

**O atrito é o produto funcionando.** Quando o usuário quiser mesmo apagar algo,
ele apaga — no Explorer, no terminal, em dois segundos. O Zordon oferece a
alternativa útil: "movi 43 arquivos de log para a quarentena; eles somem em 90
dias se você não restaurar". Na prática, isso resolve a intenção real ("libere
espaço, tire isso do caminho") sem a propriedade perigosa.

**Se esta decisão for revista**, o caminho é um ADR que a supere explicitamente,
com argumentação sobre o que mudou — não uma exceção pontual para um caso de uso.
