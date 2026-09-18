---
document: spec-ui-design
module: ui
section: desktop
version: 2
updatedAt: 2026-09-17
securityLevel: public
tags: [javafx,tray,overlay,design-system]
specId: null
---

# UI — Desktop JavaFX

## 1. Papel

`zordon-desktop` é **apresentação e interação, nada mais**. Ele não tem regra de
negócio, não decide permissão, não conhece agente nem ferramenta além do que o
protocolo lhe conta. Fechá-lo não pode quebrar nada.

Teste do design: a aplicação inteira deve ser reimplementável em outra tecnologia
(web, terminal) sem alterar uma linha do núcleo. Se algo na UI não puder ser
feito por um cliente ZWP qualquer, está no lugar errado.

## 2. Layout

A composição e a arquitetura de informação vivem em
[Layout desktop inicial](desktop-layout.md): medidas, adaptação à janela,
navegação, Início/Chat, composer, inspector, fluxos e estados degradados.

Referência de design: três regiões — navegação, trabalho e contexto — com
comando sempre acessível. A proposta visual é **DRAFT**; sua
[prévia navegável](preview/index.html) usa dados fictícios e não implementa ZWP.
O aplicativo continua sendo JavaFX no Windows, conforme as decisões existentes.

## 3. Identidade visual

[Zordon Control — design system](design-system.md) é a fonte dos tokens,
tipografia, medidas, componentes, movimento e acessibilidade. Estética escura,
ciano como identidade, conteúdo legível e brilho restrito à marca.

A [revisão de design](design-review.md) registra fundamentos, divergências e
contratos ainda necessários. Não manter uma segunda paleta neste documento.

## 4. Regras de threading

JavaFX tem uma thread de UI e ela é sagrada. Em uma aplicação dirigida por um
fluxo de eventos de rede, ignorar isso trava a interface em uma semana de uso.

| Regra | Implementação |
|---|---|
| WebSocket nunca na thread de UI | Cliente ZWP em thread virtual própria |
| Eventos entram por uma fila | `Platform.runLater` em lote, não por evento |
| Lote com teto de taxa | Máximo 30 atualizações de UI por segundo |
| `SYSTEM_METRICS` e `VOICE_LEVEL` coalescem | Último valor vence; não enfileira |
| Nenhuma chamada bloqueante na UI | Toda requisição ZWP é assíncrona |
| Chat com muitas mensagens é virtualizado | Renderiza apenas o visível |

Sem o lote, uma rajada de 500 eventos (início de conexão, replay) gera 500
`runLater` e congela a janela por segundos. Com lote de 33 ms, vira 1 atualização.

## 5. System tray

Fechar a janela **não** encerra o processo. `Platform.setImplicitExit(false)` e a
janela é recriada sob demanda a partir do tray.

```text
   ZORDON
   ─────────────────────
   Abrir Zordon
   ─────────────────────
   🎤 Escuta ativa        ▸ Desligada / Wake word / Push / Contínua
   🧠 Core online         ▸ (submenu: reconectar, reiniciar núcleo)
   🤖 Agentes             ▸ (execuções ativas, cancelar)
   ─────────────────────
   🛡 Segurança            ▸ incidentes, quarentena, disjuntores
   ⏸ Defense Lockdown     ← kill switch (07 §7.8, 19 §19.8)
   ⚙ Configurações
   ─────────────────────
   ⏻ Encerrar interface   ← encerra só a UI; o núcleo continua
```

O rótulo do último item é deliberado: "Encerrar interface", não "Sair". O usuário
precisa entender que o Zordon continua. Encerrar o núcleo é uma ação do submenu
"Core", com confirmação.

Estado do ícone:

| Ícone | Significado |
|---|---|
| Ciano sólido | Núcleo online, ocioso |
| Ciano pulsante | Escutando |
| Roxo | Processando |
| Âmbar | Degradado (voz ou MCP indisponível) ou alerta HIGH pendente |
| Vermelho | Núcleo offline, **ou alerta CRITICAL pendente**, ou Defense Lockdown |
| Vermelho estático + estado no menu | Defense Lockdown ativo; sem pulsação obrigatória |

`java.awt.SystemTray` mistura AWT com JavaFX, o que exige cuidado com threads,
mas funciona e evita dependência nativa. Se o ícone se mostrar limitado (por
exemplo, sem suporte a alta DPI adequado), a alternativa é JNA com Shell_NotifyIcon
— decisão adiada para quando o problema aparecer.

## 6. Diálogo de permissão

A tela mais importante da aplicação. Contrato de exibição obrigatório:

```text
┌──────────────────────────────────────────────────────┐
│  ⚠  AÇÃO DE RISCO ALTO                    ● RED      │
├──────────────────────────────────────────────────────┤
│                                                      │
│  Mover 43 arquivos para a quarentena                 │
│                                                      │
│  Ferramenta   skill:files.quarantine                 │
│  Agente       DeveloperAgent                         │
│  Alvo         D:\projetos\aurora\logs\               │
│                                                      │
│  Reversível   Sim — restauração a um clique          │
│                                                      │
│  ▾ Ver os 43 arquivos                                │
│                                                      │
│  ┌──────────────────────────────────────────────┐    │
│  │ O Zordon disse:                              │    │
│  │ "Vou limpar os logs antigos para liberar     │    │
│  │  espaço."                                    │    │
│  └──────────────────────────────────────────────┘    │
│                                                      │
│  Expira em 47 s                                      │
│                                                      │
│              [ Negar ]        [ Permitir ]           │
└──────────────────────────────────────────────────────┘
```

Regras não-negociáveis:

1. **Título, ferramenta, agente e alvo vêm do núcleo**, derivados dos argumentos
   resolvidos. Não passam pelo modelo.
2. **A fala do modelo fica numa caixa visualmente separada e rotulada.** Ela é
   contexto, não a descrição da ação.
3. **Os alvos concretos são inspecionáveis.** "43 arquivos" precisa expandir para
   a lista real.
4. **Nenhum botão vem pré-focado.** Enter não confirma. Para RED, o botão
   "Permitir" só habilita após 1,5 s (evita clique reflexo em cima de uma janela
   que apareceu).
5. **O contador de expiração é visível** e negar é o padrão ao expirar.
6. Ações RED **não** oferecem "lembrar desta decisão".
7. Quando a ação é reversível, o diálogo **diz isso explicitamente** — é a
   informação que mais muda a decisão do usuário, e ela é derivada do
   `ActionDescriptor`, não de texto do modelo.

Note que o exemplo diz "mover para a quarentena", não "excluir": exclusão não
existe no sistema ([ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md)).
Nenhum diálogo do Zordon jamais pedirá autorização para apagar algo.

Se a janela estiver fechada quando chegar um `ui.requestPermission`, ela é aberta
e trazida para frente. Alertas seguem a
[norma de comunicação §7](../../security/communication.md#7-anti-fadiga), incluindo
o orçamento de interrupções CRITICAL e as exceções não suprimíveis de integridade,
capacidade e eventos `change`. A UI não pode descartar essas exceções.

## 7. Overlay

Janela pequena, sem decoração, sempre no topo, transparente, no canto inferior
direito. Aparece quando a interface principal está fechada.

```text
        ╭──────────────────────╮
        │   ◉ ZORDON           │
        │   Ouvindo...         │
        ╰──────────────────────╯
```

```text
        ╭──────────────────────╮
        │   ◉ ZORDON           │
        │   Verificando seus   │
        │   containers...      │
        ╰──────────────────────╯
```

| Aspecto | Decisão |
|---|---|
| Implementação | `Stage` com `StageStyle.TRANSPARENT`, `setAlwaysOnTop(true)` |
| Cliques | Passa por baixo por padrão; clicável apenas quando há ação |
| Aparece | Wake word, agente em execução com UI fechada, alerta |
| Some | 3 s após o fim, com fade |
| Posição | Configurável; padrão inferior direito, acima da barra de tarefas |
| Jogos | Suprimido quando há aplicação em tela cheia exclusiva (o host detecta) |

A supressão em tela cheia importa: o usuário desenvolve com Unreal e joga. Um
overlay aparecendo por cima de um jogo em tela cheia pode causar troca de
contexto gráfico e engasgo.

## 8. Log de atividades

A tela de Logs é uma projeção dos eventos, não um recurso separado.

```text
22:31:04  💬  Usuário            "quais containers estão rodando"
22:31:04  🧠  IntentRouter       system_query → SystemAgent        (5 ms)
22:31:04  🤖  SystemAgent        iniciado
22:31:05  🔧  mcp:docker.listContainers            ● GREEN         (142 ms)
22:31:05  ✓   8 containers encontrados
22:31:06  💬  Resposta enviada                     1.240 tok · US$ 0,004
22:31:06  🔊  TTS                                  (2,1 s de áudio)
```

Filtros: agente, ferramenta, servidor MCP, nível de risco, decisão de permissão,
faixa de tempo, apenas erros. Cada linha expande para mostrar argumentos
completos (redigidos), resultado e entrada de auditoria correspondente.

Exportação em JSON para análise fora da ferramenta.

## 9. Estados degradados

A UI precisa ser honesta sobre o que não está funcionando. Nunca mostrar tudo
verde quando algo está fora.

| Situação | Comportamento |
|---|---|
| Núcleo offline | Banner vermelho; histórico já carregado legível; envio desabilitado com motivo e rascunho preservado; reconexão automática com contador |
| Host offline | Badge âmbar "voz indisponível"; chat de texto normal |
| Voz indisponível | Microfone riscado, com dica sobre o motivo |
| MCP fora | Item cinza na lista com motivo e botão de reconectar |
| Provider de IA fora | Banner âmbar com o fallback em uso |
| Defense Lockdown | Faixa vermelha persistente no topo: "Defense Lockdown ativo — ações de alteração bloqueadas", com motivo e botão de sair (só o usuário); conversa e leitura continuam |
| Disjuntor aberto | Badge no agente/MCP afetado, com link para as evidências |
| Alerta CRITICAL pendente | Banner vermelho que não fecha até `security.acknowledge` |
| Windows Defender desativado | Badge âmbar: "o Zordon não substitui um antivírus" ([ADR-0017](../../adr/ADR-0017-zordon-nao-e-antivirus.md)) |

## 10. Acessibilidade e conforto

- Navegação completa por teclado; ordem de foco definida explicitamente.
- Atalhos globais: abrir/fechar janela, iniciar escuta (push-to-talk), cancelar.
- Contraste mínimo 4,5:1 para texto informativo; pares e correção do antigo
  cinza terciário estão no [design system](design-system.md#3-cores).
- Estado nunca é comunicado só por cor: cor + ícone + texto.
- Respeita a preferência do sistema de reduzir animações.
- Tema claro não é prioridade, mas as cores são tokens para não impedi-lo depois.
