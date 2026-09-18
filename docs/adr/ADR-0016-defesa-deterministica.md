---
document: adr-0016
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,defesa,deterministica]
specId: null
---

# ADR-0016 — Defesa determinística: o LLM aconselha, o código decide

**Status:** Aceito · 2026-09-17

## Contexto

O Zordon precisa decidir, continuamente, se um comportamento é hostil e o que
fazer a respeito. Ele tem um LLM capaz disponível, e a tentação é óbvia: modelos
modernos são muito bons em reconhecer padrões suspeitos e em raciocinar sobre
contexto, e usá-los como motor de detecção economizaria uma quantidade enorme de
regras.

Há um problema que invalida a ideia inteira: **o LLM consome conteúdo controlado
pelo atacante**. Ele lê logs, arquivos, páginas, saídas de ferramenta e
descrições de MCP — todas fontes que um adversário pode influenciar.

## Alternativas

**A. LLM como motor de detecção e decisão.** Máxima flexibilidade e cobertura,
sem escrever regras. Mas o componente que decide se o ataque é bloqueado é o
mesmo componente que o atacante consegue influenciar. É uma inversão completa do
modelo de ameaça: basta uma injeção bem-sucedida para o sistema concluir que
está tudo bem.

**B. LLM como detector, código como decisor.** O modelo classifica e o código
aplica a resposta. Melhor, mas a classificação continua manipulável — se o
atacante convence o modelo de que o evento é benigno, o código nunca é acionado.

**C. Código determinístico detecta e decide; o LLM explica, analisa e
recomenda — sempre fora do caminho de decisão.**

## Decisão

**Alternativa C.** A cadeia de defesa não contém o LLM:

```text
EVENTO → Detection Engine → Defense Engine → Security Policy
       → Permission Engine → Notification Center → Sandbox
       → Execução → Audit Log → Notification Center
```

| Papel | O LLM pode | O LLM nunca pode |
|---|---|---|
| Explicar | Traduzir um `Finding` para linguagem natural | Decidir a severidade |
| Analisar | Sugerir correlação entre eventos | Abrir ou fechar um disjuntor |
| Recomendar | Propor uma ação de resposta ao usuário | Autorizar a própria proposta |
| Investigar | Chamar ferramentas de leitura, sob teto | Alterar política ou capacidade |

Regras derivadas:

1. **Detectores são determinísticos.** Mesma entrada, mesmo `Finding`. Sem
   aleatoriedade, sem dependência de relógio além do carimbo.
2. **ML produz sinal, nunca veredito.** Um classificador — inclusive um LLM —
   pode emitir `Signal` com peso. A severidade final vem de regras versionadas
   que combinam sinais. Nenhum modelo escreve diretamente no campo `severity`.
3. **Casos golden.** Cada detector tem observações positivas e negativas no
   repositório. Alterar sensibilidade é um diff que alguém aprova.
4. **A explicação do modelo é visualmente separada.** Na notificação e no diálogo
   de permissão, o texto do LLM aparece em caixa rotulada como análise, distinta
   dos campos gerados por código
   ([Comunicação §4](../security/communication.md#4-contrato-de-explicação)).
5. **A `SecurityPolicy` é dado imutável em tempo de execução**, fora do alcance
   de qualquer caminho acessível ao modelo.

## Consequências

**Positivas.** Um ataque de injeção bem-sucedido consegue, no máximo, produzir
texto errado numa explicação — não desativar uma proteção. A defesa é auditável:
dá para responder "por que isso foi bloqueado?" apontando para uma regra, não
para um raciocínio irreproduzível. Funciona sem internet, sem cota e sem custo
por evento. Latência de detecção em microssegundos, não em segundos.

**Negativas.** Cobertura menor: detecção baseada em regra não reconhece o que
não foi previsto. Manutenção contínua de regras e limiares. Mais falsos positivos
do que um modelo bem ajustado produziria, o que empurra o investimento para
correlação e anti-fadiga. Escrever detectores é mais trabalhoso do que escrever
um prompt.

**A troca é deliberada.** Um sistema de defesa com cobertura menor e garantias
sólidas é preferível a um com cobertura maior e garantia nenhuma. O primeiro
falha de forma previsível — não detecta o que não conhece. O segundo falha de
forma catastrófica — é convencido de que o ataque não aconteceu, e reporta
tranquilidade.

**Onde o LLM é genuinamente valioso**, e é usado sem reservas: transformar um
`Finding` técnico em uma explicação que o usuário entende; sugerir onde procurar
numa investigação; correlacionar um incidente com o histórico da memória; e
responder "o que isso significa?" quando o usuário pergunta. Nada disso está no
caminho de decisão, e tudo isso melhora muito o produto.
