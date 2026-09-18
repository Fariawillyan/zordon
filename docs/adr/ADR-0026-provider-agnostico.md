---
document: adr-0026
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,provider,modelo,agnostico,configuracao]
specId: null
---

# ADR-0026 — O núcleo não pertence a nenhum provider de IA

**Status:** Aceito · 2026-09-18

## Contexto

O contrato `AiProvider` nasceu neutro
([Interfaces §2](../api/core-interfaces.md#2-aiprovider)), e a documentação sempre
previu providers escolhidos por papel ([Core §1](../specs/core/design.md)). A
implementação do M1, porém, amarrou o resto do sistema à Anthropic em quatro
pontos: o provider era criado diretamente no composition root, os modelos padrão
e a tabela de preços só conheciam o Claude, e o instalador só sabia configurar a
chave dele.

Dois fatos tornaram isso um problema concreto, e não teórico:

- **Crédito.** A primeira conversa real do Zordon foi recusada porque a conta da
  API estava sem crédito. Com um único provider possível, "sem crédito" é
  "sem Zordon".
- **Quem vai usar.** O Zordon será usado por qualquer pessoa, cada uma com as suas
  contas: uma tem API da Anthropic, outra da OpenAI, outra só um modelo local.
  Um núcleo que só fala com um fornecedor exclui as demais.

## Alternativas

**A. Manter um provider e trocá-lo por código.** Simples, e faz cada usuário
recompilar o Zordon para mudar de modelo. Descartado de saída.

**B. Um adaptador por fornecedor, todos com SDK oficial.** Máxima fidelidade a
cada API. Mas cada SDK é uma dependência, com as suas versões e transitivas, e
dezenas de serviços expõem a mesma API da OpenAI — Ollama, LM Studio, vLLM,
OpenRouter, Groq, DeepSeek, o endpoint compatível do Gemini. Um SDK por serviço
multiplicaria dependências para falar o mesmo protocolo.

**C. Dois adaptadores cobrindo quase tudo, escolhidos por configuração.** O da
Anthropic, que já existe e usa recursos próprios (cache explícito, pensamento
adaptativo), e um **compatível com OpenAI**, escrito sobre o cliente HTTP do JDK,
sem dependência nova, que fala com qualquer servidor desse protocolo. Qual
provider atende cada papel sai de `~/.zordon/config.toml`.

**D. Uma biblioteca de abstração de terceiros** (estilo LangChain). Resolve a
variedade, mas coloca entre o núcleo e o modelo uma camada que decide coisas —
formato de ferramenta, retentativa, composição de prompt — que o Zordon precisa
controlar, porque segurança e custo dependem delas.

## Decisão

**Alternativa C**, com quatro regras.

**1. O núcleo não conhece fornecedor.** Ele pede ao registro "o provider do papel
`conversation`" e recebe um `AiProvider`. Uma regra do ArchUnit reprova qualquer
classe do núcleo que importe um adaptador, e qualquer classe fora do adaptador
Anthropic que importe o SDK da Anthropic.

**2. Configuração escolhe; o código não presume.** Providers e papéis vivem em
`config.toml`. Sem o arquivo, o comportamento é o de hoje — Anthropic pela
`ANTHROPIC_API_KEY` —, para que ninguém perca o que já funciona.

**3. Segredo nunca entra na configuração.** O arquivo guarda uma **referência**
(`api_key = "env:OPENAI_API_KEY"`), nunca o valor. Um valor literal é recusado
na leitura. O esquema `secret://` do Credential Manager
([Segurança §5](../security/model.md#5-segredos)) entra no mesmo campo quando
existir.

**4. "Local" é verificado, não declarado.** Um provider só é tratado como local
— o que, a partir do M3, permite que conteúdo confidencial vá para ele — se o
endereço dele for de loopback. Um Ollama em outra máquina da rede não é local:
os dados saem deste computador.

E uma consequência operacional: **cada papel pode ter reserva.** Se o provider da
conversa falha por indisponibilidade, cota, crédito ou chave, e ainda não
respondeu nada, o turno tenta o papel `fallback` uma vez, e a tela diz quem
respondeu e por quê. Reserva nunca é silenciosa.

## Consequências

**Positivas.** Qualquer pessoa usa o Zordon com o que tem: API paga, modelo local
gratuito ou um serviço intermediário. Falta de crédito deixa de derrubar a
conversa quando há reserva. Trocar de modelo é editar um arquivo. O adaptador
compatível não traz dependência nova.

**Negativas.** Os providers não são iguais, e fingir que são seria pior:
cache explícito, esforço de raciocínio e resumo do pensamento existem em uns e
não em outros. O contrato passa a admitir ausência — esforço opcional, consumo
estimado quando o provider não o informa —, e a tela precisa mostrar isso.
Um conjunto de testes de contrato, que todo provider precisa passar, é o que
impede a diferença de virar bug.

**Riscos registrados para o M3.** As APIs de modelo aceitam nomes de ferramenta
só com letras, números, `_` e `-`; o `ToolRegistry` documenta nomes como
`skill:files.read`. O adaptador vai precisar traduzir nomes na ida e na volta.

**Fora desta decisão.** Usar assinaturas (Claude, ChatGPT) por meio dos programas
`claude` e `codex` é a fase seguinte, com ADR próprio: envolve iniciar processo,
isolamento e termos de uso — questões que este ADR não precisa resolver para
valer.
