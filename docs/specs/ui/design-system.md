---
document: ui-design-system
module: ui
section: design-system
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [design-system,tokens,componentes,acessibilidade,javafx]
specId: null
---

# Zordon Control — design system

## 1. Objetivo e status

Definir a linguagem visual do desktop: **uma central de trabalho escura, precisa
e calma, com identidade ciano e ações verificáveis**. Esta é a recomendação
inicial de design, em **DRAFT**, para orientar as futuras SPECs de implementação.
O produto continua em `DESIGN`; este documento não declara funcionalidades prontas.

O [layout desktop](desktop-layout.md) aplica este sistema. A
[prévia navegável](preview/index.html) demonstra proporções e estados com dados
fictícios. [Fundamentos e pendências](design-review.md) ligam as escolhas à
documentação do projeto e registram o que ainda precisa de contrato.

## 2. Direção visual

Da imagem de referência, preservar o fundo azul-escuro, o símbolo geométrico,
a navegação lateral, a conversa central, os resultados estruturados e o estado
do sistema à direita. Usar a energia visual da referência em poucos pontos:
marca, seleção, foco e entrada de voz.

| Decisão | Aplicação | Motivo |
|---|---|---|
| Superfícies opacas | Fundo carvão azulado, painéis discretos | Legibilidade estável durante horas de uso |
| Ciano como identidade | Seleção, links, ação primária e foco | Hierarquia reconhecível |
| Brilho restrito à marca | Halo pequeno e estático no símbolo | Mantém personalidade sem competir com o conteúdo |
| Conteúdo em primeiro plano | Resultados, tarefas e permissões ocupam o centro | O valor do produto está na execução verificável |
| Holograma opcional | Ilustração estática no primeiro uso, até 120 px de altura | Evita reservar espaço permanente para decoração |
| Componentes próprios sobre JavaFX | Tokens e controles nativos estilizados | Preserva a arquitetura e evita uma dependência visual obrigatória |

Não usar rosto animado permanente, texto com glow, borda neon em cada cartão,
scanlines, partículas, frases motivacionais fixas ou relógio grande. A imagem
inspira a identidade; as necessidades documentadas determinam a interface.

## 3. Cores

Os nomes abaixo são os tokens canônicos. Valores hexadecimais são sRGB opacos.
O tema inicial é escuro; tema claro poderá implementar os mesmos papéis.

### Superfícies e texto

| Token | Valor | Uso |
|---|---|---|
| `surface.canvas` | `#0A0D12` | Fundo da janela |
| `surface.panel` | `#11151D` | Navegação, painéis e inspector |
| `surface.raised` | `#181E28` | Campos, cartões internos e menus |
| `surface.selected` | `#123345` | Item selecionado |
| `border.subtle` | `#232B38` | Separação decorativa de superfícies |
| `border.control` | `#60758E` | Limite necessário para reconhecer campos e controles |
| `text.primary` | `#E6EDF7` | Conteúdo, títulos e valores |
| `text.secondary` | `#9AA7BC` | Descrições, metadados e placeholders |
| `text.muted` | `#8796AB` | Texto auxiliar sobre canvas/panel/raised |
| `text.onAccent` | `#0A0D12` | Texto/ícone sobre botão ciano |

Em `surface.selected`, usar `text.primary` ou `text.secondary`; `text.muted`
não é permitido ali. Não reduzir opacidade de texto informativo para criar
hierarquia. O motivo de um controle desabilitado continua legível.

### Identidade e semântica

| Token | Valor | Uso |
|---|---|---|
| `accent.default` | `#3DDCFF` | Ação principal, link, foco, seleção |
| `accent.hover` | `#7CEAFF` | Hover da ação principal |
| `accent.pressed` | `#22B8D8` | Pressionado |
| `status.success` | `#35D48A` | Sucesso e disponibilidade confirmada |
| `status.warning` | `#F0B341` | Aviso, estado degradado, risco YELLOW |
| `status.high` | `#FF9B54` | Severidade HIGH |
| `status.danger` | `#FF5B6B` | Erro, risco RED, CRITICAL e lockdown |
| `status.processing` | `#A78BFA` | Processamento de IA |

Risco de ação (`GREEN/YELLOW/RED`) e severidade de incidente
(`INFO/WARNING/HIGH/CRITICAL`) são domínios distintos. Sempre mostrar o rótulo
por extenso no detalhe; uma ação RED não significa que há um incidente CRITICAL.
INFO de segurança mantém o verde definido pela
[norma de comunicação](../../security/communication.md#3-níveis-de-alerta).

Estado nunca depende só de cor: ícone + texto. `Conectado`, `Executando`,
`Aguardando permissão` e `Concluído` têm significados diferentes.

### Contraste verificado

Pares calculados com luminância relativa sRGB; valores apresentados arredondados,
comparação de aprovação feita sem arredondamento.

| Frente / fundo | Razão | Aplicação |
|---|---:|---|
| `text.primary` / `surface.raised` | 14,20:1 | Conteúdo |
| `text.secondary` / `surface.raised` | 6,87:1 | Metadados e placeholder |
| `text.muted` / `surface.raised` | 5,56:1 | Auxiliares |
| `text.secondary` / `surface.selected` | 5,44:1 | Seleção |
| `text.onAccent` / `accent.default` | 11,93:1 | Botão principal |
| `status.danger` / `surface.raised` | 5,55:1 | Erro textual |
| `border.control` / `surface.raised` | 3,53:1 | Identificação de campo |

O antigo `#5C6779` tinha apenas **3,40:1** sobre `#0A0D12` e 2,92:1 sobre
`#181E28`; por isso foi substituído. `border.subtle` é decorativo: não pode ser
a única forma de identificar um campo, foco ou estado.

Adotar 4,5:1 para todo texto informativo e 3:1 para informação visual necessária
em controles, seguindo as referências de
[contraste textual](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)
e [contraste não textual](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).
São metas de projeto; conformidade do aplicativo depende da validação nativa.

## 4. Tipografia, medidas e densidade

| Papel | Família | Tamanho / entrelinha | Peso |
|---|---|---|---|
| Título de página | Inter | 24 / 32 | 600 |
| Título de seção | Inter | 18 / 26 | 600 |
| Corpo e conversa | Inter | 15 / 24 | 400 |
| Controle e navegação | Inter | 14 / 20 | 500 |
| Metadado | Inter | 12 / 18 | 400 |
| Código, ferramenta, caminho e log | JetBrains Mono | 13 / 20 | 400 |
| Marca | Inter com espaçamento de letras | 20 / 28 | 700 |

Fontes empacotadas localmente com suas licenças verificadas antes da inclusão;
sem download em runtime. Fallback: Segoe UI para interface e Consolas para código.
A prévia usa fontes do sistema se Inter/JetBrains Mono não estiverem instaladas.
Nunca aplicar a fonte de marca ao conteúdo de trabalho.

Medidas em **unidades lógicas**, não pixels físicos. Base de espaçamento: 4;
escala: 4, 8, 12, 16, 24, 32, 48. Padding de cartão: 16; áreas de leitura: 24.
Texto corrido com largura máxima de 80 caracteres; tabelas podem usar o centro
inteiro. Números em colunas usam algarismos tabulares e alinhamento à direita.

| Token de medida | Valor |
|---|---:|
| `radius.control` | 8 |
| `radius.card` | 12 |
| `radius.dialog` | 16 |
| `size.control` | 40 |
| `size.controlCompact` | 32 |
| `size.iconTarget` | 40 |
| `size.row` | 40 |
| `size.rowCompact` | 32 |
| `size.icon` | 20 |
| `size.iconSmall` | 16 |
| `border.width` | 1 |
| `focus.width` | 2 |

Densidade confortável por padrão. Compacta é opção para tabelas e logs, sem
reduzir a fonte nem o diálogo de permissão. A interface deve tolerar texto a
200%, crescimento vertical e quebra de linha.

## 5. Componentes

| Componente | Contrato visual e de interação |
|---|---|
| `AppShell` | Navegação, conteúdo, inspector e barra de estado; regras no layout |
| `NavigationItem` | Ícone + nome; selecionado com fundo e barra ciano; badge só para pendência/execução |
| `StatusBadge` | Ícone + rótulo, sem aparência de botão quando informativo |
| `Button` | Primário ciano com texto escuro; secundário outlined; terciário textual; uma ação primária por região |
| `IconButton` | Área 40 × 40, nome acessível e tooltip no hover e no foco |
| `CommandComposer` | Campo multilinha, agente/contexto, anexos, voz, enviar/cancelar e estado explícito |
| `Message` | Autor, horário, conteúdo e relação com a execução; streaming sem roubar rolagem |
| `ToolResultCard` | Título factual, origem, estado, resultado tipado, horário, detalhes e vínculo com log |
| `RunCard` | Agente, etapa atual, filhos, tempo, tokens/orçamento, saída parcial e cancelar |
| `MetricCard` | Valor, unidade, origem Windows/WSL, horário de amostra e indisponibilidade |
| `DataTable` | Cabeçalho, ordenação quando suportada, linhas 40/32, coluna de estado textual, rolagem horizontal localizada |
| `UsageMeter` | Consumo e teto numéricos, barra e marcação de estimativa; sem porcentagem inventada |
| `IncidentCard` | Campos determinísticos da norma, evidências e opções do núcleo; análise da IA separada |
| `PermissionDialog` | Apresentação do `ActionDescriptor`; contrato de segurança em [UI §6](design.md#6-diálogo-de-permissão) |
| `EmptyState` | O que falta, por quê e uma ação aplicável; ausência de dado nunca vira zero |
| `InlineError` | O que falhou, impacto e recuperação permitida; preserva a entrada |
| `Banner` | Estado global persistente; CRITICAL não some por temporizador |
| `NotificationCenter` visual | Incidentes agrupados; leitura, decisão e resolução são estados diferentes |

Cada controle interativo define `default`, `hover`, `pressed`, `focused`,
`disabled` e `busy`. Seleção e erro são estados adicionais quando aplicáveis.
Foco ciano de 2 unidades com afastamento de 2; nunca removido por estética.
Em botões ciano, usar anel externo separado por fundo escuro.

Ícones lineares em grade 24, traço visual 1,5–2, com geometria consistente.
Não usar emoji como iconografia de produção. O símbolo geométrico da prévia é
provisório; o produto não depende de um asset bitmap nem de um rosto licenciado.

## 6. Movimento, gráficos e desempenho

Transições locais de 120–180 ms, ease-out. Nada pisca ou anima em repouso.
Waveform só com amostras reais de áudio; indicador de escuta só durante captura
confirmada. Processamento sem total conhecido usa texto de etapa e tempo
decorrido; não fabrica porcentagem. Com redução de movimento, feedback é estático.

No painel lateral, métricas usam valores e barras curtas; gráficos de série
temporal ficam em Sistema/Diagnóstico. CPU percentual sem origem e disco sem
unidade são dados incompletos. Valores ausentes usam `—` com motivo.

Renderização em lotes e virtualização seguem [UI §4](design.md#4-regras-de-threading).
O teto de 30 atualizações/s não autoriza amostragem de sistema a 30 Hz. Preservar
a frequência por fonte de [Automação §5](../automation/design.md#5-coleta-sem-polling).
Canvas decorativo, vídeo e shaders contínuos não fazem parte do shell.

## 7. Acessibilidade e conteúdo

Todos os fluxos operam por teclado. Ordem: cabeçalho → navegação → conteúdo →
composer → inspector. Oferecer salto entre regiões. Diálogo captura foco e o
devolve ao acionador; a regra especial de confirmação RED prevalece.

Controles com nome, função, estado e associação de rótulo expostos à tecnologia
assistiva. Testar Narrator no Windows; um ícone visível não garante nome acessível.
Anunciar mudanças de etapa e conclusão, não cada token nem cada amostra de CPU.
Sem informação disponível apenas no hover. Caminhos truncados têm acesso ao valor
completo e ação explícita de copiar o valor já mascarado.

Português brasileiro. Verbos concretos: `Nova conversa`, `Ver execução`,
`Negar`, `Permitir esta ação`, `Desligar microfone`. Usar `Nova conversa` em vez
de `Limpar`, que poderia sugerir apagar histórico ou auditoria. `Esquecer fato`
é exclusivo da memória e segue sua norma.

Horários locais com zona disponível no detalhe; não fixar data, nome de usuário,
modelo, hardware, projeto ou IDE da imagem. Valores monetários declaram moeda.
Contagens de tokens aproximadas usam `~` **e** a palavra `estimado`.

## 8. Aplicação no JavaFX

Manter cores semânticas em um stylesheet do desktop; medidas e tipografia em
definições compartilhadas do módulo. JavaFX usa *looked-up colors*, não as
custom properties `--var` do CSS web. Exemplo ilustrativo:

```css
.root {
    -z-surface-canvas: #0A0D12;
    -z-text-primary: #E6EDF7;
    -z-accent: #3DDCFF;
    -fx-background-color: -z-surface-canvas;
    -fx-font-family: "Inter";
    -fx-font-size: 14px;
}
.z-primary-button {
    -fx-background-color: -z-accent;
    -fx-text-fill: -z-surface-canvas;
    -fx-background-radius: 8;
}
```

Esse trecho não é um tema completo. Mapeamento e limites de CSS seguem a
[referência oficial do JavaFX](https://openjfx.io/javadoc/25/javafx.graphics/javafx/scene/doc-files/cssref.html).
Usar controles acessíveis e virtualizados, como `ListView`/`TableView`, e
composição de layout; não desenhar toda a interface como uma imagem.

## 9. Validação e limites

Antes da implementação ser considerada pronta: verificar todos os pares reais
de contraste, teclado, Narrator, DPI 100/125/150/200%, texto ampliado, nomes
longos, estados de erro e ausência de dados. A prévia HTML valida direção visual
e interações ilustrativas; não valida JavaFX, áudio, ZWP, Windows nem permissões.

Riscos principais: brilho que prejudica leitura, cartões que parecem controles,
cinza ilegível e personalização que apaga semântica de segurança. A paleta,
os contratos de componentes e os cenários do [layout](desktop-layout.md)
são os critérios de revisão.
