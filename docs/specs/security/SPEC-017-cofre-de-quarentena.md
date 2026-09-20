---
document: spec-017
module: security
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,seguranca,quarentena,cofre,reversivel,m3]
specId: SPEC-017
---

# SPEC-017 — Cofre de quarentena

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `SecurityAgent` |
| **Revisores** | ArchitectureAgent |
| **Marco** | M3 (item 12 do [roadmap](../../roadmap.md#m3--ação-com-segurança-primeiro)) |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

Dar ao usuário o que ele quer quando pede para "apagar", sem que nada seja
destruído. Os arquivos saem do lugar para um cofre, com hash e origem, e voltam
quando ele quiser ([ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md),
[Defesa §6](../../security/defense.md#6-quarantine-vault)).

## 2. Problema

A SPEC-016 recusa "apague" e oferece a quarentena, mas a quarentena não existe.
Sem ela, o usuário que quer limpar uma pasta de logs não tem caminho pelo Zordon.

## 3. Escopo

**`QuarantineVault`** (`zordon-security`, `zordon.security.vault`)

- **Guardar:** mover um arquivo ou pasta para `~/.zordon/quarantine/<vaultId>/`,
  registrando para cada arquivo o caminho original, o SHA-256 antes de mover e o
  tamanho.
  - O manifesto (`manifest.json`) é gravado antes do primeiro arquivo sair do
    lugar e atualizado a cada arquivo movido.
  - O hash é conferido depois de mover.
- **Restaurar:** devolve cada arquivo ao caminho original, só se o lugar estiver
  livre; nunca sobrescreve. O manifesto registra a restauração.
- **Listar:** os itens do cofre, com data, motivo, número de arquivos, tamanho e
  se já foram restaurados.
- **Nada é apagado:** nem o original (é movido), nem o cofre (não existe
  "esvaziar"). A retenção fica com o M7.

**Ferramentas**

| Ferramenta | Piso | Efeito | Resumo mostrado |
|---|---|---|---|
| `fs.quarantine {path}` | RED | `QUARANTINE_FS` | "Mover N arquivos de X para a quarentena (reversível)" |
| `fs.restore {vaultId}` | YELLOW | `WRITE_FS` | "Devolver N arquivos da quarentena para X" |

- O diálogo lista os alvos concretos (até 50, com o total), diz que é reversível
  e nega sozinho em 60 s (SPEC-015).
- Rota rápida: **"mova para a quarentena <caminho>"** e **"coloque em quarentena
  <caminho>"** viram `fs.quarantine`.

**ZWP e tela**

- `security.quarantine.list` e `security.quarantine.restore {vaultId}`.
- A restauração passa pela ferramenta `fs.restore`, com origem `ui`.
- Os Ajustes ganham a lista da quarentena, com "Restaurar" em cada item.

## 4. Não escopo

- Quarentena automática pela defesa (`AutoContain`), retenção e "esvaziar": M7.
- Quarentena de processo (suspender): M7.

## 5. Arquitetura

```text
"mova para a quarentena D:\projeto\logs" ─► fs.quarantine ─► Gatekeeper (RED, diálogo)
                                                   │ Granted
                                                   ▼
                              QuarantineVault.store ─► ~/.zordon/quarantine/<id>/{manifest.json, files/…}
Ajustes › Quarentena ─► security.quarantine.restore ─► fs.restore (YELLOW) ─► QuarantineVault.restore
```

## 6. Fluxo

1. O usuário pede a quarentena de uma pasta. A ferramenta lista os arquivos e
   monta o resumo com o total.
2. RED: o diálogo mostra os alvos e "reversível". Sem clique, nega em 60 s.
3. Autorizado: o manifesto é gravado; cada arquivo tem o hash calculado, é
   movido, e o hash é conferido.
4. O Zordon diz quantos arquivos foram para a quarentena e o id para restaurar.

## 7. Interfaces

```java
public final class QuarantineVault {
    Item store(List<Path> files, Path root, String reason) throws IOException;   // root: o que o usuário apontou
    Item restore(String vaultId) throws IOException;
    List<Item> list();
}
```

| Método ZWP | Params | Retorno |
|---|---|---|
| `security.quarantine.list` | `{}` | `{items[{vaultId, ts, reason, root, files, bytes, restored}]}` |
| `security.quarantine.restore` | `{vaultId}` | `{text}` — o resultado da ferramenta |

## 8. Eventos

Os de ferramenta (SPEC-016). `QUARANTINE_ADDED` e `QUARANTINE_RESTORED` ficam
para o M7, quando a defesa também usar o cofre.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Arquivos em quarentena | `~/.zordon/quarantine/<vaultId>/files/` | Até o usuário restaurar; nunca apagados pelo Zordon |
| Manifesto | `~/.zordon/quarantine/<vaultId>/manifest.json` | Idem |

## 10. Segurança

- Sem `Files.delete` em lugar nenhum; o cofre só move.
- Restaurar nunca sobrescreve: se o original foi recriado, aquele arquivo fica
  no cofre e é informado.
- O cofre é caminho proibido para as ferramentas de arquivo: ninguém lê ou
  escreve lá fora do próprio cofre.

## 11. Permissões

RED para guardar (por ação, sempre na tela) e YELLOW para restaurar.

## 12. Observabilidade

Log INFO de cada guarda e restauração, com o id, o número de arquivos e o
tamanho. A auditoria tem o resto.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Caminho inexistente | "Não existe X"; nada é movido |
| Mais de 10 000 arquivos | Recusado com o motivo: grande demais para uma confirmação legível |
| Falha no meio da guarda | O manifesto diz o que foi movido; o resto fica no lugar |
| Hash diferente depois de mover | O arquivo fica marcado como suspeito no manifesto e é informado |
| Restaurar com o original ocupado | Aquele arquivo fica no cofre; os demais voltam |

## 14. Testes

Diretório temporário: guardar e restaurar uma pasta com subpastas, conferência
de hash, restauração sem sobrescrever, manifesto depois de uma falha simulada e
a ferramenta pelo `Gatekeeper` com aprovador falso.

## 15. Critérios de aceite

- `CA-1` Dada uma pasta, então os arquivos são movidos para o cofre com o
  manifesto (caminho original, hash e tamanho) e nada é apagado.
- `CA-2` Dada uma restauração, então cada arquivo volta ao lugar com o mesmo
  hash, e um lugar ocupado não é sobrescrito.
- `CA-3` Dado `fs.quarantine`, então o diálogo recebe os alvos concretos, o
  total e "reversível", e sem autorização nada sai do lugar.
- `CA-4` Dado "mova para a quarentena <caminho>", então a rota chama
  `fs.quarantine` com o caminho.
- `CA-5` Dada a lista, então cada item mostra data, motivo, arquivos, tamanho e
  se foi restaurado.

## 16. Impacto em outros módulos

- `zordon-security`: `zordon.security.vault`.
- `zordon-core`: as duas ferramentas, a rota e os métodos ZWP.
- `zordon-desktop`: a lista da quarentena nos Ajustes.

## 17. Dependências

- [SPEC-016](SPEC-016-execucao-mediada-ferramentas-e-ponte-windows.md) ·
  [ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md) ·
  [Defesa §6](../../security/defense.md#6-quarantine-vault)
