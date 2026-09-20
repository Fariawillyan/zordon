---
document: adr-0029
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,voz,ux,narracao,trace,observabilidade]
specId: null
---

# ADR-0029 — Zordon é voice-first

**Status:** Aceito · 2026-09-18

## Contexto

O desktop nasceu como painel: conversa, logs, diagnóstico, consumo, tudo em
texto na tela ([SPEC-005](../specs/ui/SPEC-005-shell-do-desktop.md)). A
[SPEC-010](../specs/ui/SPEC-010-shell-compacto-centrado-na-voz.md) trouxe o console
de voz para o centro, mas o Zordon ainda comunicaria o que faz em texto: rótulos,
pílulas, linhas de log. O owner definiu a experiência que quer: o Zordon fala o
que está fazendo, o núcleo visual mostra o estado, e o técnico fica escondido
até alguém pedir.

Há dois riscos opostos. Falar demais — cada arquivo, cada comando — cansa e
esconde o que importa. Falar o que o modelo "pensa" confunde intenção com ação e
cria a impressão de que algo aconteceu quando só foi cogitado.

## Decisão

**Zordon é voice-first. A comunicação operacional com o usuário ocorre
prioritariamente por voz e estados visuais; detalhes técnicos permanecem ocultos
por padrão.**

1. Eventos técnicos passam por um **intérprete de atividade**, no núcleo, que os
   agrupa em etapas e define o estado visual.
2. Um **narrador de voz** decide o que falar: mudança de etapa, problema,
   decisão, pedido de autorização e resultado. Ele fala ações reais, escritas a
   partir de campos estruturados dos eventos, nunca do texto de raciocínio do
   modelo. Tem prioridade, janela de agrupamento e não repete.
3. A experiência principal (a tela de Voz) não mostra texto operacional: estados
   são animações, partículas, ondas e órbitas.
4. Um **Live Trace** completo — comandos, arquivos, diffs, agentes, ferramentas,
   horários, erros, resultados — é gravado sempre e fica visível só no **modo
   técnico**, ligado explicitamente pelo usuário.

## Consequências

- Privacidade e segurança continuam visíveis sem texto: microfone ligado é ícone
  e animação, com o botão de desligar sempre à mão; autorização é falada e mostra
  estado de atenção (o diálogo de permissão do M3 continua obrigatório para ação
  de risco).
- Acessibilidade: tudo o que é só visual tem texto acessível para leitor de tela.
- O trace passa a ser a fonte da observabilidade; o log da tela é uma janela dele.
- Mensagens faladas precisam de modelos de frase por tipo de evento; um tipo de
  evento novo sem frase não é narrado, mas continua no trace.
