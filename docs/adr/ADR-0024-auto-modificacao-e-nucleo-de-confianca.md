---
document: adr-0024
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,auto-modificacao,nucleo-de-confianca,invariante]
specId: null
---

# ADR-0024 — O Zordon escreve o próprio código, mas não se instala

**Status:** Aceito · 2026-09-17

## Contexto

O Zordon precisa poder alterar o próprio projeto: corrigir os próprios bugs,
implementar as próprias SPECs, manter a própria documentação. É o objetivo do
[M8](../roadmap.md) e é o que torna o projeto sustentável por uma pessoa só.

Isso cria um problema de circularidade que nenhuma das decisões anteriores
resolve. As quatro invariantes do projeto são garantidas por **código**:

| Invariante | Garantida por |
|---|---|
| Sem shell arbitrário | `CommandValidator`, regras ArchUnit |
| Não apaga arquivos | `FileAccess`, enum `Effect`, ArchUnit |
| Nada silencioso | `NotificationCenter`, tipo de `SecurityEvent` |
| LLM não decide segurança | Separação de módulos, ArchUnit |

Se o Zordon pode editar esse código, todas viram sugestões. E não é preciso
má-fé: um agente otimizando para "completar a tarefa" encontra a remoção da
verificação como o caminho mais curto — o mesmo mecanismo que faz um agente de
teste ajustar a asserção até passar.

## Alternativas

**A. Proibir auto-modificação.** Resolve o problema e mata o objetivo. Também é
inconsistente: o Zordon já altera projetos do usuário, e o próprio repositório
não é mais sagrado que qualquer outro deles.

**B. Permitir tudo, com confirmação do usuário.** O usuário aprova cada
alteração. Mas o diff de uma mudança em `PermissionEngine.java` é exatamente o
tipo de coisa que se aprova sem ler de verdade, e o custo de errar uma vez é o
sistema inteiro. Confirmação não é proteção suficiente quando o item aprovado é
o mecanismo de aprovação.

**C. Permitir, com sandbox de execução.** Rodar o Zordon alterado em isolamento
antes de promover. Forte, e caro: exigiria um segundo ambiente completo com WSL,
áudio e bridge. Desproporcional nesta fase.

**D. Núcleo de confiança protegido + separação entre escrever e executar.**

## Decisão

**Alternativa D**, em duas partes que só funcionam juntas.

### Parte 1 — Núcleo de confiança

Um conjunto declarado de caminhos que o Zordon pode **propor** alterar, nunca
aplicar: `zordon-security/**`, `zordon-defense/**`, `zordon-api/**`, regras
ArchUnit, tabelas golden, política de segurança, workflows de CI, `CODEOWNERS`,
catálogo de versões, `docs/security/**`, `docs/adr/**` e a governança da raiz.

Toda alteração que o toque é **RED**, independentemente do tamanho, exige PR com
revisão humana via CODEOWNERS, e nunca é executada por automação — automação não
executa RED.

### Parte 2 — Escrever ≠ executar

```text
   CÓDIGO-FONTE  <repo>/**          o Zordon escreve, em branch
        │
        │  build + instalação — ação humana
        ▼
   INSTALADO     ~/.local/share/zordon/**    o Zordon NUNCA escreve
                 %LOCALAPPDATA%\...\Zordon\
```

O Zordon nunca escreve nos diretórios de instalação e nunca reinicia a si mesmo
com código que escreveu. Os caminhos estão em `forbidden`, e uma tentativa
dispara `ai.policy-tamper` → CRITICAL → Defense Lockdown.

**Esta é a parte que quebra o laço.** Um Zordon comprometido consegue escrever
código malicioso no repositório; não consegue fazê-lo rodar. Entre a escrita e a
execução existem um PR revisado, um build e uma instalação — três ações humanas.

### Parte 3 — Preflight tipado

Os nove passos obrigatórios antes de alterar qualquer projeto
([Auto-modificação §6](../process/self-modification.md#6-preflight-obrigatório))
não são convenção. `ChangeExecutor.execute` só aceita `ApprovedPlan`, e
`ApprovedPlan` só é construível pelo planejador, com `userMessageId` não nulo.
O preflight é o tipo do parâmetro, não a disciplina de quem escreve.

### Quinta invariante

Isto eleva-se ao mesmo nível das outras quatro:

> **O Zordon não altera o próprio núcleo de confiança em execução, nem sem
> revisão humana.**

## Consequências

**Positivas.** A segurança do sistema deixa de ser circular: ela não depende de o
Zordon escolher não se enfraquecer. Auto-modificação fica possível para 95% do
repositório, que é onde o trabalho real acontece. Git torna toda alteração
reversível, o que alinha com [ADR-0015](ADR-0015-exclusao-impossivel-por-construcao.md).
A separação fonte/instalado já existia por outras razões (empacotamento) e passa
a ter valor de segurança.

**Negativas.** Melhorar o próprio `PermissionEngine` exige ciclo humano — o
Zordon não consegue evoluir sozinho a parte mais sensível de si mesmo, o que é
deliberado e às vezes será frustrante. Manter a lista do núcleo de confiança
atualizada é trabalho contínuo: um módulo novo que passe a conter lógica de
segurança precisa entrar na lista, e esquecer disso é a falha mais provável.
A instalação passa a ser um passo manual de verdade, não uma conveniência.

**Mitigação da falha mais provável:** ArchUnit verifica que classes que tocam
`PermissionEngine`, `AuditLog`, `SecurityPolicy` ou `NotificationCenter` vivem
dentro dos módulos protegidos. Lógica de segurança fora do núcleo reprova o
build — ela não pode existir em um lugar que o Zordon poderia editar livremente.

## O que esta decisão não cobre

Não há defesa contra um usuário que aprove um PR malicioso sem ler. O modelo de
confiança assume o usuário como ator confiável
([Segurança §1](../security/model.md#1-modelo-de-ameaças)); esta decisão o
protege de **acidente e de agente desalinhado**, não de si mesmo.

O que ela garante é que a decisão chegue até ele de forma visível — um diff em um
PR, com CODEOWNERS, e não uma alteração que já aconteceu.
