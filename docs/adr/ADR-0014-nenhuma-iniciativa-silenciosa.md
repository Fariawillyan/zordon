---
document: adr-0014
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,nenhuma,iniciativa,silenciosa]
specId: null
---

# ADR-0014 — Nenhuma iniciativa autônoma acontece silenciosamente

**Status:** Aceito · 2026-09-17

## Contexto

O Zordon age sozinho: automações disparam, watchers reagem, o Defense Engine
contém ameaças, agentes executam tarefas longas. Em algum momento ele vai fazer
algo que o usuário não pediu naquele instante.

Isso cria um problema que não é técnico, é de confiança. Um processo residente
com acesso a arquivos, processos, rede e credenciais, que age por conta própria e
não conta o que fez, é — do ponto de vista observável do usuário — indistinguível
de malware. A única diferença que importa é a que ele consegue **verificar**.

## Alternativas

**A. Notificar apenas o relevante, a critério do componente.** Cada subsistema
decide o que merece aviso. É o padrão da indústria e é como se chega a um sistema
em que ninguém sabe o que ele faz: cada decisão isolada é razoável, o agregado é
opaco.

**B. Notificar tudo.** Transparência máxima, fadiga máxima. O usuário desliga as
notificações em uma semana e o resultado prático é pior que a alternativa A —
ele passa a não saber de nada, agora com a falsa impressão de que saberia.

**C. Notificar toda iniciativa autônoma, com correlação, deduplicação e
severidade — e nunca suprimir as classes que indicam quebra de premissa.**

## Decisão

**Alternativa C**, elevada a **invariante do produto**:

> Nenhuma iniciativa autônoma relevante do Zordon pode acontecer sem que o
> usuário saiba.

Fluxo obrigatório:

```text
DETECTAR → COMUNICAR → AVALIAR → AGIR → COMUNICAR RESULTADO → REGISTRAR
```

Com uma única exceção, sujeita a **três** condições conjuntivas verificadas por
código: o risco cresce com a espera, **e** a ação é reversível, **e** a ação é
contenção (não remediação). Só então:

```text
DETECTAR → CONTER → AVISAR IMEDIATAMENTE   (janela máxima de 2 s)
```

Garantias estruturais, não disciplina:

1. **`ZordonMessage` exige os oito campos de explicação** como parâmetros de
   record. Uma mensagem sem `whySuspicious` ou sem `actionTaken` não compila.
2. **`SecurityEvent` carrega `userMessageId`.** Um evento com ação executada e
   `userMessageId` nulo é bug de severidade máxima, e há teste que falha o build
   se essa combinação for construível.
3. **A notificação é persistida antes da execução.** Se o processo morrer no
   meio, a fila conta ao usuário o que estava sendo tentado.
4. **O tópico `security` não pode ser desassinado.** `session.unsubscribe`
   rejeita — um cliente que recusasse eventos de segurança quebraria a invariante.
5. **Nenhuma ação autônoma é permanente.** Toda contenção sem usuário é
   reversível e com prazo.

Contra a fadiga, que é o que mata a alternativa B: correlação em incidente,
deduplicação por assinatura, resumo diário para INFO/WARNING, linha de base de
7 dias antes de alertar sobre anomalia, orçamento de 3 interrupções CRITICAL por
hora, e a regra "sem ação possível ⇒ é registro, não notificação"
([Comunicação §7](../security/communication.md#7-anti-fadiga)).

**Supressão nunca se aplica** a `ai.policy-tamper`, `integrity.audit-chain`,
`integrity.self` e `ai.capability-violation`. Esses significam que uma premissa
do sistema quebrou, e sempre interrompem.

## Consequências

**Positivas.** O usuário pode auditar o comportamento do assistente sem ler
código ou log. A confiança é verificável, não pedida. Um Zordon comprometido
fica mais difícil de esconder: para agir em silêncio, o atacante precisaria
derrotar também a fila de notificação e a cadeia de auditoria. A invariante
força bom design — um componente que não consegue explicar o que fez em oito
campos provavelmente não deveria estar fazendo aquilo.

**Negativas.** Mais trabalho por funcionalidade: toda ação autônoma precisa de
texto, opções e estado. Risco real de fadiga, que exige investimento contínuo em
correlação. A janela de 2 s entre conter e avisar é uma meta de engenharia com
custo. Alguns fluxos ficam mais lentos por esperarem decisão do usuário.

**Métrica de vigilância.** `zordon.notify.dismissed_without_reading`. Se subir, o
sistema está gritando demais — o problema é a política de severidade, nunca o
usuário.
