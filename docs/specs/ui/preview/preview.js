/* Copyright 2026 Willyan Faria. SPDX-License-Identifier: Apache-2.0
 *
 * Prévia navegável do Zordon Control. Referência visual apenas:
 * dados fictícios, nenhuma conexão ZWP, nenhuma ação real na máquina.
 * Ver ../desktop-layout.md e ../design-system.md.
 */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, html) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html != null) n.innerHTML = html;
    return n;
  };

  // ── Ícones ──────────────────────────────────────────────────────────────────
  const P = {
    arrow: 'M5 12h14M13 6l6 6-6 6',
    attach: 'M21 11l-8.5 8.5a5 5 0 0 1-7-7L14 4a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L16 6',
    bell: 'M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8M13.7 21a2 2 0 0 1-3.4 0',
    chip: 'M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3M6 6h12v12H6zM10 10h4v4h-4z',
    close: 'M6 6l12 12M18 6L6 18',
    mic: 'M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3M19 10v1a7 7 0 0 1-14 0v-1M12 18v4',
    panel: 'M3 4h18v16H3zM15 4v16',
    pause: 'M9 5v14M15 5v14',
    plus: 'M12 5v14M5 12h14',
    settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 7.9 19.4a2 2 0 1 1-2.8-2.8l.1-.1A1.6 1.6 0 0 0 4.3 14H4a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.1-2.7 2 2 0 1 1 2.8-2.8l.1.1A1.6 1.6 0 0 0 10 4.6V4a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1',
    shield: 'M12 2l8 4v6c0 5-3.4 8.9-8 10-4.6-1.1-8-5-8-10V6zM9 12l2 2 4-4',
    spark: 'M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z',
    home: 'M4 10l8-6 8 6v10H4zM10 20v-6h4v6',
    chat: 'M21 12a8 8 0 0 1-8 8H5l-2 2V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8',
    plug: 'M9 3v6M15 3v6M6 9h12v3a6 6 0 0 1-12 0zM12 18v3',
    tool: 'M14 6a4 4 0 0 1 5.5 5.2L21 13l-2 2-1.8-1.5A4 4 0 0 1 12 8zM12 12l-7 7 2 2 7-7',
    clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18M12 7v5l3 2',
    brain: 'M8 4a3 3 0 0 0-3 3 3 3 0 0 0-1 5.8A3 3 0 0 0 7 18a3 3 0 0 0 5 1 3 3 0 0 0 5-1 3 3 0 0 0 3-5.2A3 3 0 0 0 19 7a3 3 0 0 0-3-3 3 3 0 0 0-4 1 3 3 0 0 0-4-1M12 5v14',
    book: 'M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2zM8 7h8M8 11h6',
    cpu: 'M6 6h12v12H6zM10 10h4v4h-4zM9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3',
    coin: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18M12 7v10M9.5 9.5h4a1.5 1.5 0 0 1 0 3h-3a1.5 1.5 0 0 0 0 3h4',
    list: 'M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01',
    pulse: 'M3 12h4l3 8 4-16 3 8h4',
    check: 'M20 6L9 17l-5-5',
  };
  const icon = (name) =>
    `<svg viewBox="0 0 24 24" aria-hidden="true">${(P[name] || P.check)
      .split('M').filter(Boolean).map((d) => `<path d="M${d}"/>`).join('')}</svg>`;

  document.querySelectorAll('[data-icon]').forEach((n) => {
    n.innerHTML = icon(n.dataset.icon);
  });

  // ── Navegação (docs/specs/ui/desktop-layout.md §3) ──────────────────────────
  // `milestone` marca destinos de marcos futuros. Eles NÃO simulam
  // funcionalidade: aparecem identificados como indisponíveis, com motivo.
  const NAV = [
    { group: 'TRABALHO', items: [
      { id: 'Início', icon: 'home' },
      { id: 'Chat', icon: 'chat', count: '3' },
      { id: 'Voz', icon: 'mic', milestone: 'M2' },
    ]},
    { group: 'RECURSOS', items: [
      { id: 'Agentes', icon: 'chip', count: '6', milestone: 'M5' },
      { id: 'MCP', icon: 'plug', count: '4' },
      { id: 'Skills', icon: 'tool', milestone: 'M3' },
      { id: 'Automações', icon: 'clock', milestone: 'M6' },
      { id: 'Memória', icon: 'brain', milestone: 'M5' },
      { id: 'Conhecimento', icon: 'book', milestone: 'M8', pending: true },
    ]},
    { group: 'OPERAÇÃO', items: [
      { id: 'Sistema', icon: 'cpu' },
      { id: 'Uso', icon: 'coin', milestone: 'M8' },
      { id: 'Logs', icon: 'list' },
      { id: 'Segurança', icon: 'shield', milestone: 'M3' },
      { id: 'Diagnóstico', icon: 'pulse' },
    ]},
  ];

  const MILESTONE_REASON = {
    M2: 'Voz entra no marco M2. Nesta prévia o destino existe para mostrar o lugar dele na navegação.',
    M3: 'Skills entram no marco M3, junto do motor de permissão.',
    M5: 'Agentes e memória entram no marco M5.',
    M6: 'Automações entram no marco M6.',
    M7: 'A camada de defesa entra no marco M7.',
    M8: 'Entra no marco M8, quando o RAG e a plataforma de agentes ficam prontos.',
  };

  let current = 'Chat';

  function renderNav() {
    const nav = $('navigation');
    nav.replaceChildren();
    NAV.forEach((g) => {
      const box = el('div', 'nav-group');
      box.append(el('div', 'group-label', g.group));
      g.items.forEach((item) => {
        const b = el('button', 'nav-item' + (item.id === current ? ' active' : ''));
        b.type = 'button';
        b.dataset.page = item.id;
        if (item.id === current) b.setAttribute('aria-current', 'page');
        b.innerHTML =
          icon(item.icon) +
          `<span class="nav-label">${item.id}</span>` +
          (item.count ? `<span class="nav-count">${item.count}</span>` : '') +
          (item.milestone && !item.count ? `<span class="nav-count">${item.milestone}</span>` : '');
        b.title = item.milestone ? `${item.id} — ${item.milestone}` : item.id;
        b.addEventListener('click', () => go(item.id));
        box.append(b);
      });
      nav.append(box);
    });
  }

  // ── Conteúdo das páginas ────────────────────────────────────────────────────
  const CONTAINERS = [
    ['api', 'node:20-alpine', '2 h', 'rodando'],
    ['postgres', 'postgres:16', '2 h', 'rodando'],
    ['redis', 'redis:7', '2 h', 'rodando'],
    ['worker', 'node:20-alpine', '41 min', 'rodando'],
  ];

  function chatPage() {
    return `
      <p class="session-date">Hoje · sessão de exemplo</p>
      <div class="message">
        <span class="avatar">W</span>
        <div>
          <div class="message-author"><strong>Você</strong><time>22:31</time></div>
          <p>Quais containers estão rodando?</p>
        </div>
      </div>
      <div class="message">
        <span class="avatar zordon-avatar">△</span>
        <div>
          <div class="message-author">
            <strong>Zordon</strong><span class="agent-name">SystemAgent</span><time>22:31</time>
          </div>
          <p>Encontrei 4 containers ativos no Docker do WSL. Nenhum parado.</p>
          <div class="run-trace">
            ${icon('tool')}<code>mcp:docker.listContainers</code>
            <span class="status success">✓ GREEN · 142 ms</span>
          </div>
          <div class="result-card">
            <div class="result-header">
              <div><h3>Containers ativos</h3><small>Leitura · nenhuma alteração feita</small></div>
              <span class="status success">✓ 4 de 4</span>
            </div>
            <div class="table-wrap"><table>
              <thead><tr><th>Nome</th><th>Imagem</th><th>Ativo há</th><th>Estado</th></tr></thead>
              <tbody>${CONTAINERS.map(([n, i, u, s]) =>
                `<tr><td><code>${n}</code></td><td>${i}</td><td>${u}</td>
                 <td><span class="table-status"><i></i>${s}</span></td></tr>`).join('')}
              </tbody>
            </table></div>
            <div class="result-footer"><span>Origem: servidor MCP docker</span><span>Dados fictícios</span></div>
          </div>
          <div class="result-actions">
            <button class="secondary-button" type="button" data-notice="Exemplo: abriria os logs do container na tela de Logs.">${icon('list')}<span>Ver logs</span></button>
            <button class="secondary-button" type="button" data-notice="Exemplo: reiniciar container é YELLOW e pediria confirmação.">${icon('clock')}<span>Reiniciar api</span></button>
          </div>
          <div class="completion">${icon('check')}<span>Resposta concluída · 1.240 tokens · ~US$ 0,004 (estimado)</span></div>
        </div>
      </div>`;
  }

  function homePage() {
    const cards = [
      ['chat', 'Retomar conversa', 'Containers do projeto · há 4 min'],
      ['cpu', 'Ver o sistema', 'CPU, memória, Docker e WSL'],
      ['shield', 'Revisar segurança', 'Sem pendências no momento'],
      ['coin', 'Acompanhar uso', 'US$ 1,84 de 10,00 hoje'],
    ];
    return `
      <div class="hero">
        <div class="intro-label">ESPAÇO DE TRABALHO</div>
        <h2>No que vamos trabalhar?</h2>
        <p>Retome de onde parou ou comece uma tarefa nova. O painel à direita mostra
           o estado da máquina enquanto você trabalha.</p>
      </div>
      <div class="card-grid">${cards.map(([ic, t, s]) =>
        `<button class="action-card" type="button" data-notice="Exemplo ilustrativo: ${t}.">
           ${icon(ic)}<strong>${t}</strong><small>${s}</small></button>`).join('')}
      </div>
      <h3 class="section-heading">Eventos recentes</h3>
      ${[['✓', 'success', 'MCP docker conectado', '4 ferramentas descobertas · 22:28'],
         ['✓', 'success', 'SystemAgent concluiu', 'Consulta de containers · 22:31'],
         ['·', '', 'Índice de documentação atualizado', '4 trechos reindexados · 22:15']]
        .map(([m, c, t, s]) =>
          `<div class="list-card"><div><strong>${t}</strong><small>${s}</small></div>
           <span class="status ${c}">${m}</span></div>`).join('')}
      <div class="info-box"><strong>Prévia ilustrativa.</strong> Nada aqui reflete
        a sua máquina: são dados fictícios para avaliar layout, hierarquia e estados.</div>`;
  }

  function systemPage() {
    return `
      <p class="page-description">Métricas do host e da distro WSL, com a origem de
        cada número. Nesta prévia todos os valores são fictícios.</p>
      <div class="card-grid">
        ${[['CPU', '12%', 'média de 60 s'], ['Memória', '38%', '3,1 GB de 8 GB'],
           ['GPU', '22%', 'compartilhada'], ['Disco', '54%', 'ext4 da distro']]
          .map(([t, v, s]) =>
            `<div class="page-kpi"><span class="eyebrow">${t}</span>
             <strong>${v}</strong><span class="micro">${s}</span></div>`).join('')}
      </div>
      <h3 class="section-heading">Docker</h3>
      ${CONTAINERS.map(([n, i]) =>
        `<div class="list-card">${icon('plug')}<div><strong>${n}</strong><small>${i}</small></div>
         <span class="status success">✓ rodando</span></div>`).join('')}`;
  }

  function mcpPage() {
    const rows = [
      ['docker', 'stdio', 'success', '✓ conectado', '4 ferramentas'],
      ['filesystem', 'stdio', 'success', '✓ conectado', '6 ferramentas'],
      ['git', 'stdio', 'success', '✓ conectado', '5 ferramentas'],
      ['browser', 'http', 'warning-text', '⟳ reconectando', 'disjuntor fechado'],
    ];
    return `
      <p class="page-description">Servidores conectados e as ferramentas que cada um
        expõe. O risco base vem da configuração local, nunca do próprio servidor.</p>
      ${rows.map(([n, t, c, s, d]) =>
        `<div class="list-card">${icon('plug')}
         <div><strong>${n}</strong><small>transporte ${t} · ${d}</small></div>
         <span class="status ${c}">${s}</span></div>`).join('')}
      <div class="info-box">Uma mudança na superfície declarada de um servidor entre
        conexões é tratada como achado e pede revisão antes do uso.</div>`;
  }

  function logsPage() {
    const rows = [
      ['22:31:04', 'Usuário', '"quais containers estão rodando"', ''],
      ['22:31:04', 'IntentRouter', 'consulta_sistema → SystemAgent', '5 ms'],
      ['22:31:05', 'mcp:docker.listContainers', 'GREEN · 4 resultados', '142 ms'],
      ['22:31:06', 'Resposta enviada', '1.240 tokens', ''],
    ];
    return `
      <p class="page-description">Projeção do fluxo de eventos. Cada linha liga-se à
        execução e à auditoria correspondentes.</p>
      <div class="result-card"><div class="table-wrap"><table>
        <thead><tr><th>Hora</th><th>Origem</th><th>Detalhe</th><th>Duração</th></tr></thead>
        <tbody>${rows.map(([h, o, d, t]) =>
          `<tr><td><code>${h}</code></td><td>${o}</td><td>${d}</td><td>${t}</td></tr>`).join('')}
        </tbody></table></div></div>`;
  }

  function securityPage() {
    return `
      <p class="page-description">Estado da defesa, incidentes e o que está contido.
        Regras temporárias e exceções ficam visíveis e revogáveis.</p>
      <div class="card-grid">
        <div class="page-kpi"><span class="eyebrow">DEFENSE ENGINE</span>
          <strong class="success-text">Ativo</strong><span class="micro">24 detectores</span></div>
        <div class="page-kpi"><span class="eyebrow">LOCKDOWN</span>
          <strong>Inativo</strong><span class="micro">só o usuário sai</span></div>
        <div class="page-kpi"><span class="eyebrow">DISJUNTORES</span>
          <strong>0 abertos</strong><span class="micro">nenhum sujeito contido</span></div>
        <div class="page-kpi"><span class="eyebrow">QUARENTENA</span>
          <strong>0 itens</strong><span class="micro">nada foi apagado</span></div>
      </div>
      <div class="info-box"><strong>O Zordon não apaga arquivos.</strong> A operação
        mais forte disponível é mover para a quarentena, que é reversível.</div>
      <button class="secondary-button" type="button" id="open-permission-from-page">
        ${icon('shield')}<span>Ver exemplo de pedido de permissão</span></button>`;
  }

  function usagePage() {
    const rows = [['SystemAgent', '12.800', 'US$ 0,14'], ['ZordonAgent', '9.400', 'US$ 0,11'],
                  ['DeveloperAgent', '6.100', 'US$ 0,07']];
    return `
      <p class="page-description">Consumo por agente, modelo e tarefa. Valor estimado
        aparece sempre marcado como estimado.</p>
      <div class="card-grid">
        <div class="page-kpi"><span class="eyebrow">HOJE</span><strong>US$ 1,84</strong>
          <span class="micro">de US$ 10,00</span></div>
        <div class="page-kpi"><span class="eyebrow">CACHE</span><strong>69%</strong>
          <span class="micro">de acerto · exemplo</span></div>
      </div>
      <h3 class="section-heading">Por agente</h3>
      ${rows.map(([a, t, c]) =>
        `<div class="list-card">${icon('chip')}<div><strong>${a}</strong>
         <small><span class="token-chip">~${t} tokens</span> estimado</small></div>
         <span class="status">${c}</span></div>`).join('')}`;
  }

  function pendingPage(name, milestone) {
    return `
      <div class="empty-state">
        <div class="empty-orbit">${milestone === 'M8' ? '◍' : '◌'}</div>
        <h2>${name} ainda não está disponível</h2>
        <p>${MILESTONE_REASON[milestone] || 'Este destino entra em um marco futuro.'}
           O lugar dele existe na navegação para que a informação não mude de casa
           quando a funcionalidade chegar.</p>
        <div class="info-box">Destinos de marcos futuros não simulam funcionalidade.
          O que governa a disponibilidade é a capacidade negociada com o núcleo,
          não a existência do botão.</div>
      </div>`;
  }

  const PAGES = {
    'Início': homePage, 'Chat': chatPage, 'Sistema': systemPage,
    'MCP': mcpPage, 'Logs': logsPage, 'Segurança': securityPage, 'Uso': usagePage,
  };

  const SUBTITLE = {
    'Início': ['ESPAÇO DE TRABALHO / INÍCIO', 'Bom te ver de volta'],
    'Chat': ['ESPAÇO DE TRABALHO / CHAT', 'Containers do projeto'],
    'Sistema': ['OPERAÇÃO / SISTEMA', 'Estado da máquina'],
    'MCP': ['RECURSOS / MCP', 'Conexões e ferramentas'],
    'Logs': ['OPERAÇÃO / LOGS', 'Timeline de atividades'],
    'Segurança': ['OPERAÇÃO / SEGURANÇA', 'Defesa e incidentes'],
    'Uso': ['OPERAÇÃO / USO', 'Tokens e custo'],
  };

  function go(page) {
    current = page;
    renderNav();
    const meta = SUBTITLE[page];
    $('breadcrumb').textContent = meta ? meta[0] : page.toUpperCase();
    $('page-title').textContent = meta ? meta[1] : page;

    const item = NAV.flatMap((g) => g.items).find((i) => i.id === page);
    const render = PAGES[page];
    // Um destino de marco futuro que já tem layout desenhado é rotulado como
    // proposta — a prévia mostra o lugar dele sem sugerir que está pronto.
    const future = item?.milestone && ['M5', 'M6', 'M7', 'M8'].includes(item.milestone);
    $('breadcrumb').innerHTML = (SUBTITLE[page] ? SUBTITLE[page][0] : page.toUpperCase()) +
      (future && render ? ` <span class="nav-count">proposta ${item.milestone}</span>` : '');
    $('content').innerHTML = render && !item?.pending
      ? render()
      : pendingPage(page, item?.milestone || 'M8');
    $('content').scrollTop = 0;

    $('composer-context').textContent =
      page === 'Chat' ? 'Sessão: Containers do projeto' : `Contexto: ${page}`;
    wireContent();
    applyScenario(scenario, { keepPage: true });
    $('main').focus({ preventScroll: true });
  }

  function wireContent() {
    document.querySelectorAll('[data-notice]').forEach((b) =>
      b.addEventListener('click', () => notify(b.dataset.notice)));
    document.querySelectorAll('[data-page]').forEach((b) => {
      if (b.closest('#navigation')) return;
      b.addEventListener('click', () => go(b.dataset.page));
    });
    const p = $('open-permission-from-page');
    if (p) p.addEventListener('click', openPermission);
  }

  // ── Inspector ───────────────────────────────────────────────────────────────
  $('metrics').innerHTML = [['CPU', 12], ['Memória', 38], ['GPU', 22]]
    .map(([l, v]) => `
      <div class="metric">
        <div class="metric-line"><span>${l}</span><strong>${v}%</strong></div>
        <div class="meter"><span style="width:${v}%"></span></div>
      </div>`).join('');

  $('connections').innerHTML = [
    ['docker', 'success'], ['filesystem', 'success'], ['git', 'success'], ['browser', 'warning'],
  ].map(([n, s]) => `
      <div class="connection">
        <span>${icon('plug')}${n}</span>
        <span class="status"><i class="dot" style="background:var(--${s})"></i>
          ${s === 'success' ? 'conectado' : 'reconectando'}</span>
      </div>`).join('');

  $('swatches').innerHTML = [
    ['canvas', '#0A0D12'], ['panel', '#11151D'], ['raised', '#181E28'], ['selected', '#123345'],
    ['accent', '#3DDCFF'], ['success', '#35D48A'], ['warning', '#F0B341'], ['danger', '#FF5B6B'],
  ].map(([n, v]) => `
      <div class="swatch">
        <div class="swatch-color" style="background:${v}"></div>
        ${n}<small>${v}</small>
      </div>`).join('');

  // ── Cenários de estado ──────────────────────────────────────────────────────
  let scenario = 'normal';

  const SCENARIOS = {
    normal: {
      core: ['Núcleo conectado', 'var(--success)'],
      status: 'Pronto para uma nova tarefa',
      voice: ['Voz desligada', false],
    },
    offline: {
      banner: ['danger', 'Núcleo offline.',
        'O histórico já carregado continua legível e o seu rascunho é preservado. ' +
        'Reconectando automaticamente — nova tentativa em 4 s.'],
      core: ['Núcleo offline', 'var(--danger)'],
      status: 'Sem conexão com o núcleo',
      voice: ['Voz indisponível', true],
      block: 'Envio desabilitado: o núcleo está offline.',
    },
    host: {
      banner: ['warning', 'Host Windows indisponível.',
        'Voz e ações no Windows ficam suspensas. Conversa por texto, memória e ' +
        'monitoramento continuam funcionando.'],
      core: ['Núcleo conectado · host offline', 'var(--warning)'],
      status: 'Funcionando sem o host Windows',
      voice: ['Voz indisponível', true],
    },
    lockdown: {
      banner: ['danger', 'Defense Lockdown ativo — ações de alteração bloqueadas.',
        'Motivo: exemplo ilustrativo de contenção. Conversa e leitura continuam. ' +
        'Somente você pode sair do lockdown.'],
      core: ['Núcleo conectado · lockdown', 'var(--danger)'],
      status: 'Lockdown ativo — somente leitura',
      voice: ['Voz desligada', false],
      block: 'Ações de alteração bloqueadas durante o lockdown.',
    },
    empty: {
      core: ['Núcleo conectado', 'var(--success)'],
      status: 'Nenhuma conversa ainda',
      voice: ['Voz desligada', false],
    },
  };

  function applyScenario(name, opts = {}) {
    scenario = name;
    const s = SCENARIOS[name];

    const banner = $('global-banner');
    if (s.banner) {
      const [kind, title, body] = s.banner;
      banner.className = 'global-banner' + (kind === 'warning' ? ' warning' : '');
      banner.innerHTML = `<strong>${title}</strong>${body}`;
      banner.hidden = false;
    } else {
      banner.hidden = true;
    }

    const core = $('core-state');
    core.querySelector('.dot').style.background = s.core[1];
    core.querySelector('strong').textContent = s.core[0];

    $('footer-status').textContent = s.status;
    $('footer-dot').style.background = s.core[1];

    $('voice-label').textContent = s.voice[0];
    $('voice-button').disabled = s.voice[1];
    $('voice-button').title = s.voice[1]
      ? 'Indisponível: o host Windows fornece o microfone.' : 'Alternar escuta (ilustrativo)';

    const blocked = Boolean(s.block);
    $('send-button').disabled = blocked;
    $('command').disabled = blocked && name === 'offline';
    $('composer-hint').textContent = blocked
      ? s.block : 'Enter para enviar · Shift + Enter para nova linha';
    $('pause-button').querySelector('span:last-child').textContent =
      name === 'lockdown' ? 'Sair do lockdown' : 'Pausar ações';

    $('defense-summary').innerHTML = name === 'lockdown'
      ? '<span class="status danger">■ Lockdown ativo</span><p>Alterações bloqueadas</p>'
      : '<span class="status success">✓ Sem pendências</span><p>Defense Engine ativo</p>';
    $('defender-state').textContent = name === 'offline' ? 'Desconhecido' : 'Ativo';
    $('sample-label').textContent = name === 'offline'
      ? 'Últimos valores conhecidos · sem atualização'
      : 'Amostras ilustrativas · não são medições';

    if (!opts.keepPage) {
      if (name === 'empty') {
        current = 'Início';
        renderNav();
        $('breadcrumb').textContent = 'ESPAÇO DE TRABALHO / INÍCIO';
        $('page-title').textContent = 'Primeiro uso';
        $('content').innerHTML = `
          <div class="empty-state">
            <div class="empty-orbit">△</div>
            <h2>Tudo pronto para começar</h2>
            <p>Peça alguma coisa por texto, ou ative a voz e diga "Zordon".
               O que ele fizer aparece aqui e fica registrado.</p>
            <div class="card-grid">
              ${[['cpu', 'Como está o sistema?'], ['plug', 'Quais containers estão rodando?'],
                 ['list', 'Mostre os eventos de hoje'], ['shield', 'Há algo em quarentena?']]
                .map(([ic, t]) => `<button class="action-card" type="button"
                  data-notice="Exemplo: enviaria “${t}”.">${icon(ic)}<strong>${t}</strong>
                  <small>Sugestão ilustrativa</small></button>`).join('')}
            </div>
          </div>`;
        wireContent();
      } else if (current === 'Início' && SCENARIOS[name] && $('page-title').textContent === 'Primeiro uso') {
        go('Chat');
      }
    }
  }

  // ── Aviso ───────────────────────────────────────────────────────────────────
  let noticeTimer;
  function notify(text) {
    $('notice-text').textContent = text;
    $('notice').hidden = false;
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => { $('notice').hidden = true; }, 6000);
  }
  $('dismiss-notice').addEventListener('click', () => {
    $('notice').hidden = true;
    clearTimeout(noticeTimer);
  });

  // ── Diálogo de permissão ────────────────────────────────────────────────────
  // Contrato de ../design.md §6: nenhum botão pré-focado, "Permitir" só habilita
  // após 1,5 s, contador visível, e negar é o padrão ao expirar.
  const dialog = $('permission-dialog');
  let countdown, enableTimer;

  function openPermission() {
    if (dialog.open) return;
    const allow = $('allow-button');
    allow.disabled = true;
    let left = 60;
    $('permission-expiry').textContent = `Expira em ${left} s → negar`;
    dialog.showModal();
    $('permission-title').focus();               // foco no título, nunca num botão

    enableTimer = setTimeout(() => { allow.disabled = false; }, 1500);
    countdown = setInterval(() => {
      left -= 1;
      $('permission-expiry').textContent = `Expira em ${left} s → negar`;
      if (left <= 0) closePermission('timeout');
    }, 1000);
  }

  function closePermission(outcome) {
    clearInterval(countdown);
    clearTimeout(enableTimer);
    if (dialog.open) dialog.close();
    if (outcome === 'allow') notify('Exemplo: a ação seria executada e registrada em auditoria. Nada foi feito.');
    if (outcome === 'deny') notify('Exemplo: a ação seria negada e registrada.');
    if (outcome === 'timeout') notify('O pedido expirou. Ao expirar, a decisão padrão é negar.');
  }

  $('permission-example').addEventListener('click', openPermission);
  $('allow-button').addEventListener('click', () => closePermission('allow'));
  $('deny-button').addEventListener('click', () => closePermission('deny'));
  dialog.addEventListener('cancel', (e) => { e.preventDefault(); closePermission('deny'); });

  // ── Diálogo do design system ────────────────────────────────────────────────
  const design = $('design-dialog');
  $('design-button').addEventListener('click', () => design.showModal());
  $('close-design').addEventListener('click', () => design.close());

  // ── Inspector recolhível ────────────────────────────────────────────────────
  const wide = window.matchMedia('(min-width:1440px)');
  function toggleInspector() {
    const inspector = $('inspector');
    const open = wide.matches
      ? !document.body.classList.toggle('inspector-hidden')
      : inspector.classList.toggle('open');
    $('context-button').setAttribute('aria-expanded', String(open));
    if (open && !wide.matches) inspector.focus({ preventScroll: true });
  }
  $('context-button').addEventListener('click', toggleInspector);
  $('close-context').addEventListener('click', () => {
    $('inspector').classList.remove('open');
    $('context-button').setAttribute('aria-expanded', 'false');
    $('context-button').focus();
  });

  // ── Composer e cabeçalho ────────────────────────────────────────────────────
  $('composer').addEventListener('submit', (e) => {
    e.preventDefault();
    const text = $('command').value.trim();
    notify(text
      ? `Prévia visual: “${text.slice(0, 60)}” não é enviado a lugar nenhum.`
      : 'Digite algo para ver como o envio se comporta nesta prévia.');
    $('command').value = '';
  });
  $('command').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      $('composer').requestSubmit();
    }
  });

  let listening = false;
  $('voice-button').addEventListener('click', () => {
    listening = !listening;
    $('voice-label').textContent = listening ? 'Ouvindo…' : 'Voz desligada';
    notify(listening
      ? 'Ilustrativo: o microfone é fornecido pelo host Windows. Nada é capturado aqui.'
      : 'Escuta desligada. Desligar significa não capturar, não capturar e descartar.');
  });

  $('alerts-button').addEventListener('click', () =>
    notify('Exemplo: alertas HIGH e CRITICAL chegam por aqui e não podem ser suprimidos.'));
  $('pause-button').addEventListener('click', () =>
    notify(scenario === 'lockdown'
      ? 'Somente você pode sair do lockdown — e a saída é registrada.'
      : 'Exemplo: pausar coloca o Zordon em somente leitura imediatamente.'));
  $('new-chat').addEventListener('click', () => { go('Chat'); notify('Exemplo: abriria uma sessão nova.'); });
  $('attach-button').addEventListener('click', () =>
    notify('Anexos ainda não têm contrato definido no protocolo.'));
  $('composer-voice').addEventListener('click', () => $('voice-button').click());
  $('scenario').addEventListener('change', (e) => applyScenario(e.target.value));

  // ── Início ──────────────────────────────────────────────────────────────────
  go('Chat');
  applyScenario('normal');
})();
