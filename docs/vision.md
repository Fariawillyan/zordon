---
document: vision
module: product
section: vision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [escopo,principios,requisitos,casos-de-uso]
specId: null
---

# Visão e escopo

## 1. Objetivo

Zordon é um **assistente e guardião residente**: um serviço permanente na
máquina do usuário que conversa por voz e texto, entende intenção, executa ações
reais no sistema sob permissão explícita, lembra do contexto, **defende a máquina
contra comportamento hostil** e reporta tudo o que fez.

É software livre sob [Apache License 2.0](../LICENSE)
([ADR-0013](adr/ADR-0013-apache-2.md)).

A frase que define o projeto:

> O núcleo é o produto. A interface é descartável.

Toda decisão de design deriva disso. Se uma funcionalidade só existe enquanto a
janela JavaFX está aberta, ela está no lugar errado.

## 2. Casos de uso de referência

Estes casos são o critério de aceitação do design. Cada um exercita um caminho
diferente da arquitetura e é citado ao longo dos outros documentos.

| # | Comando | Exercita |
|---|---------|----------|
| UC1 | "Zordon." → resposta curta por voz | Wake word, VAD, STT, TTS, latência |
| UC2 | "Zordon, abra o IntelliJ." | Intent Router rápido, Skill Windows, Windows Bridge, permissão GREEN |
| UC3 | "Zordon, quais containers estão rodando?" | SystemAgent, MCP Docker, ToolRegistry, seleção de ferramentas |
| UC4 | "Zordon, veja por que minha API caiu." | DeveloperAgent multi-passo, leitura de logs, correlação, orçamento de passos |
| UC5 | "Zordon, abra o projeto que trabalhamos ontem." | Memória de longo prazo, recuperação híbrida, resolução de entidade |
| UC6 | "Zordon, verifica minha API a cada 10 minutos." | Scheduler, automação persistente, sobrevivência a reinício |
| UC7 | "Zordon, me avise quando esse build terminar." | EventWatcher, notificação nativa Windows, evento assíncrono com UI fechada |
| UC8 | "Zordon, apague os logs antigos do projeto." | **Recusa da exclusão** + contraproposta de quarentena, permissão RED, auditoria, reversível |
| UC9 | Container da API cai às 3h da manhã | Monitoramento, ConditionWatcher, notificação sem UI aberta |
| UC10 | "Zordon, abra o projeto Aurora." | Agente especializado, contexto de projeto, composição de várias Skills |
| UC11 | Um processo desconhecido tenta ler `~/.ssh/id_rsa` | Detecção, contenção reversível, notificação CRITICAL em 2 s |
| UC12 | Um MCP atualizado passa a expor `readAnyFile` | `ai.mcp-drift`, isolamento, circuit breaker |
| UC13 | Um log de container contém instrução dirigida ao assistente | Prompt injection: envelopamento, caminho proibido, notificação |
| UC14 | 137 falhas de autenticação SSH em 4 minutos | Brute force: bloqueio temporizado, reversível, com prazo visível |

UC8, UC9 e UC11 são os mais importantes para a arquitetura: UC8 prova que o motor
de permissão funciona; UC9 prova que o núcleo é de fato residente; UC11 prova que
a defesa detecta, contém **e comunica** — os três, na ordem certa, sem nenhum
passo silencioso.

## 3. Princípios de design

1. **O núcleo é o produto.** Toda inteligência, estado e capacidade de ação vive
   no `zordon-core`. JavaFX é um cliente do protocolo, e não o único possível
   (CLI, web e mobile devem ser viáveis sem tocar no núcleo).

2. **Nenhuma ação sem intenção estruturada.** O LLM nunca produz um comando de
   shell que é executado. Ele produz uma *chamada de ferramenta tipada*, que é
   validada, classificada por risco e executada por código nosso.
   Ver [ADR-0007](adr/ADR-0007-permissao-sobre-acao-estruturada.md).

3. **Extensível sem recompilar o núcleo.** Skill nova, MCP server novo e Agent
   novo entram por descoberta e configuração. Se adicionar uma capacidade exige
   alterar `zordon-core`, o design falhou.

4. **Local-first por padrão.** Áudio bruto, conteúdo de arquivos e caminhos do
   sistema não saem da máquina a menos que o usuário autorize, por ação e por
   provider. O default de wake word, VAD e STT é processamento local.

5. **Tudo é evento, tudo é auditável.** Cada ação relevante publica um evento no
   barramento e grava uma entrada de auditoria. O log de atividades da UI é uma
   projeção desse fluxo, não uma feature separada.

6. **Zordon é voice-first.** A comunicação operacional com o usuário ocorre
   prioritariamente por voz e estados visuais; detalhes técnicos permanecem
   ocultos por padrão. O Zordon narra ações reais ("Encontrei uma falha no
   Docker", "Preciso da sua autorização para continuar"), nunca o pensamento
   interno do modelo, e agrupa o que é técnico em etapas. Logs, comandos,
   arquivos e diffs ficam no trace completo, visível só no modo técnico.
   Ver [ADR-0029](adr/ADR-0029-voice-first.md).

6. **Degradação graciosa.** Cada camada tem modo reduzido: sem MCP, o Zordon
   funciona com Skills; sem voz, funciona por texto; sem núcleo, a UI mostra
   estado offline e enfileira; sem internet, agentes locais continuam.

7. **Orçamento explícito.** Tokens, tempo de parede, passos de agente, chamadas
   de ferramenta e custo em dólares são recursos finitos com teto configurável.
   Um agente que não converge é interrompido, não deixado rodando.

8. **Nada silencioso.** Toda iniciativa autônoma é comunicada ao usuário,
   imediatamente, com o motivo, o que foi feito e o que ele pode fazer. Um
   assistente residente que age sem contar é indistinguível de malware — e a
   única diferença que importa é a que o usuário consegue verificar.
   ([ADR-0014](adr/ADR-0014-nenhuma-iniciativa-silenciosa.md))

9. **Nada irreversível.** Nenhuma ação autônoma produz mudança permanente, e o
   Zordon não apaga arquivos — nunca, nem sob ordem.
   ([ADR-0015](adr/ADR-0015-exclusao-impossivel-por-construcao.md))

10. **Zero Trust.** Nada é confiável por posição. Todo componente tem identidade,
    capacidades explícitas com prazo, e nenhum nível concede privilégio a um
    nível acima. ([Defesa §2](security/defense.md#2-zero-trust-aplicado))

## 4. Escopo do produto

### Dentro do escopo

- Conversa por voz e texto, com streaming de resposta.
- Wake word local ("Zordon"), VAD, STT e TTS.
- Execução de ações no Windows e no WSL sob motor de permissão.
- Agentes especializados com escopo de ferramentas próprio.
- Cliente MCP com descoberta dinâmica de tools/resources/prompts.
- Sistema próprio de Skills.
- Memória de curto prazo, de conversa e de longo prazo.
- Automações persistentes (agendadas, por evento e por condição).
- Monitoramento de CPU, RAM, GPU, disco, rede, Docker, WSL, Git e processos.
- Interface JavaFX com tray, overlay e tela de diagnóstico.
- Auditoria e observabilidade.
- **Defesa ativa:** detecção comportamental, contenção reversível, quarentena,
  circuit breaker e Defense Lockdown ([Defesa](security/defense.md)).
- **Comunicação proativa** de toda iniciativa autônoma ([Comunicação](security/communication.md)).

### Fora do escopo (v1)

| Item | Motivo |
|---|---|
| Multiusuário / multi-máquina | O modelo de confiança assume um único usuário local |
| Servidor MCP (expor o Zordon para terceiros) | Inverte o modelo de ameaça; reavaliar depois |
| Visão de tela (screen understanding) | Adiado para pós-M6; ver §6 |
| Sincronização em nuvem de memória | Conflita com o princípio local-first |
| Android/iOS | Sem requisito; o protocolo não impede |
| Treinamento/fine-tuning de modelos | Não é um problema deste projeto |
| Suporte a Linux/macOS nativo | O `WindowsBridge` é específico; o núcleo é portável |
| **Antivírus / varredura por assinatura** | Impossível a partir do WSL2; o Defender faz isso — [ADR-0017](adr/ADR-0017-zordon-nao-e-antivirus.md) |
| **Remoção de malware** | Quarentena, sempre. Remoção é do antivírus ou do usuário |
| Skills de terceiros | O caminho para código externo é MCP, que já isola por processo |

### Explicitamente não-objetivos

- Não é um substituto de terminal. Se o usuário quer rodar um comando exato, ele
  roda no terminal. O Zordon opera em intenções.
- Não é um IDE. Ele integra com o IntelliJ, não o reimplementa.
- Não é um framework genérico de agentes. É um produto pessoal com opinião.
- **Não é um antivírus.** Ele não varre assinatura, não intercepta em kernel e
  não remove malware. Ele detecta comportamento, contém de forma reversível e
  orquestra a resposta — inclusive a do Defender.

## 5. Requisitos não-funcionais

Números são metas de projeto, não medições. Servem para reprovar design cedo.
Ver [Observabilidade](operations/observability.md) para como medir.

### Latência

| Métrica | Alvo p50 | Alvo p95 |
|---|---|---|
| Wake word detectada → feedback visual/sonoro | 250 ms | 400 ms |
| Fim da fala → transcrição final disponível | 700 ms | 1,5 s |
| Fim da fala → primeiro fonema de resposta (TTS) | 1,8 s | 3,5 s |
| Comando de rota rápida (UC2) fim-a-fim | 900 ms | 1,8 s |
| Primeiro token do LLM em chat de texto | 800 ms | 2,0 s |
| Reconexão da UI ao núcleo após queda | 1,0 s | 2,5 s |

O orçamento detalhado de voz está em [Voz](specs/voice/design.md#4-orçamento-de-latência).

### Recursos

| Recurso | Limite em repouso | Limite sob carga |
|---|---|---|
| RAM do `zordon-core` | 600 MB | 1,5 GB |
| RAM do `zordon-host` (Windows) | 150 MB | 300 MB |
| RAM do `zordon-desktop` (Windows) | 400 MB | 700 MB |
| CPU agregada em repouso com escuta ativa | < 3% de um núcleo | — |
| Escrita em disco em repouso | < 1 MB/min | — |

"Repouso com escuta ativa" significa wake word e VAD rodando, nenhum agente
executando. Se ultrapassar 3%, o usuário desliga o Zordon — esse número é um
requisito de produto, não um detalhe.

### Disponibilidade e robustez

- O núcleo deve reiniciar sozinho em até 5 s após crash (`Restart=always`).
- Estado durável (memória, automações, auditoria) deve sobreviver a `wsl --shutdown`.
- Automação agendada não deve disparar em duplicidade após reinício.
- Queda de um MCP server não pode derrubar o núcleo nem bloquear um turno.
- A UI deve ser utilizável, em modo somente-leitura, com o núcleo offline.

### Segurança

- Nenhuma ação classificada RED executa sem confirmação humana no turno atual.
- Toda ação executada tem entrada de auditoria com ator, agente, ferramenta,
  argumentos, decisão de permissão e resultado.
- Segredos nunca aparecem, **completos ou não**, em logs, eventos, prompts, voz
  ou tela — só mascarados (`sk-proj-****************92F`).
- A porta do núcleo é acessível apenas ao usuário local autenticado por token.
- Nenhuma ação autônoma executa sem notificação persistida **antes** da execução.
- Toda contenção autônoma é reversível e tem prazo.
- Nenhum caminho de código consegue apagar arquivo do usuário.

### Defesa e comunicação

| Métrica | Alvo |
|---|---|
| Detecção → contenção (CRITICAL) | < 1 s |
| Contenção → notificação entregue | **< 2 s** (teto duro, monitorado) |
| Falso positivo que interrompe o usuário | < 1 por semana |
| Interrupções CRITICAL por hora | máximo 3 (a partir daí, agrupa) |
| Entrega de notificação pendente após reconexão | < 5 s |
| Ação autônoma sem `userMessageId` | **zero** — é bug de severidade máxima |

## 6. Visão de tela (adiada, mas planejada)

O objetivo de longo prazo "enxergar a tela" tem impacto de arquitetura e por isso
é citado aqui mesmo estando fora do v1:

- A captura é uma operação do `zordon-host` (Windows), exposta como Skill
  `windows.screenshot` com permissão **YELLOW** por padrão e **RED** quando
  disparada automaticamente por um agente.
- A imagem trafega como frame binário ZWP, nunca como base64 dentro de JSON.
- O provider de IA precisa declarar capacidade `vision`; ver
  [Interfaces](api/core-interfaces.md#2-aiprovider).
- Captura contínua de tela é um risco de privacidade categoricamente diferente
  de captura sob demanda e exigirá o seu próprio ADR.

Nada no design atual deve impedir isso; nada no design atual deve assumir isso.

## 7. Critério de sucesso do MVP

O MVP (M1+M2) está pronto quando, com a janela JavaFX **fechada** e apenas o
tray ativo:

1. O usuário diz "Zordon, que horas são?" e ouve a resposta.
2. O núcleo é reiniciado (`systemctl restart zordon`) e, em menos de 10 s, a
   mesma frase volta a funcionar sem intervenção.
3. O Windows é reiniciado e o item 1 volta a funcionar sem nenhuma ação manual.

O item 3 é o teste que mais reprova projetos deste tipo. Ver
[Windows↔WSL](architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows).
