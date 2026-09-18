---
document: adr-0013
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,apache,2]
specId: null
---

# ADR-0013 — Apache License 2.0 e projeto aberto

**Status:** Aceito · 2026-09-17

## Contexto

O Zordon será publicado como software livre. A licença precisa servir a um
projeto que: roda na máquina pessoal de quem o instala; se apresenta como camada
de defesa; pode ser adotado em ambiente corporativo; e integra dependências de
licenças variadas (modelos ONNX, servidores MCP, bibliotecas Java e Python).

## Alternativas

**A. MIT / BSD-2.** Máxima permissividade, texto curto, adoção sem atrito. Mas
não trata patentes explicitamente e não exige preservação de aviso além do
copyright. Para um projeto de segurança que faz afirmações técnicas sobre
proteção, a ausência de cláusula de patente é um risco assimétrico: um
contribuidor poderia contribuir e depois alegar patente sobre a contribuição.

**B. GPL-3.0 / AGPL-3.0.** Garante que derivados permaneçam livres — atraente
para um projeto de segurança, em que fork fechado é um risco real (alguém
distribui um "Zordon" modificado com o Defense Engine desativado). Mas o
copyleft forte é incompatível com integração em ferramentas proprietárias e
afasta adoção corporativa, que é onde o escrutínio de segurança de verdade
acontece. AGPL, além disso, é comumente banida por política em empresas.

**C. Apache License 2.0.** Permissiva, com **concessão expressa de patentes**,
**encerramento da licença de patente em caso de litígio**, exigência de `NOTICE`
e de sinalização de arquivos modificados.

## Decisão

**Alternativa C**, Apache License 2.0.

Os três atributos que decidem:

1. **Cláusula de patente (§3).** Todo contribuidor concede licença de patente
   sobre a contribuição, e ela se encerra para quem move ação de patente contra
   o projeto. Para software de segurança, que costuma tocar técnicas
   patenteáveis (detecção, isolamento, análise), isso protege os usuários.
2. **`NOTICE` (§4d).** Obriga a preservação de atribuição em redistribuição.
   Um fork precisa dizer de onde veio — relevante para que um "Zordon" modificado
   e comprometido não circule com aparência de original.
3. **Sinalização de modificação (§4b).** Arquivos alterados devem ser marcados.
   É a exigência mínima de rastreabilidade num projeto que o usuário instala com
   permissão de sistema.

Sobre o risco do fork fechado com defesa desativada: a licença não é o
instrumento certo para resolver isso — GPL também não impediria alguém de
distribuir um fork malicioso, só o obrigaria a publicar o código. A defesa real
é **proveniência**: artefatos assinados, SBOM por release, atestação de build
pelo CI e um canal oficial de distribuição claro
([Cadeia de suprimentos §5](../security/supply-chain.md#5-release-e-distribuição)).

## Consequências

**Positivas.** Adoção sem atrito jurídico, inclusive corporativo. Proteção de
patente para usuários e contribuidores. Atribuição preservada. Compatível com a
maior parte do ecossistema (Apache-2.0, MIT, BSD).

**Negativas.** Um fork proprietário é permitido. Contribuições podem ser
absorvidas por produto fechado sem retorno. Texto longo e cabeçalho obrigatório
em cada arquivo (mitigado: Spotless aplica e verifica).

**Compromissos que derivam.** Nenhuma dependência GPL/AGPL/SSPL pode entrar na
árvore — verificado por license check na pipeline
([Cadeia de suprimentos §4](../security/supply-chain.md#4-verificações-em-detalhe)).
Isso restringe algumas escolhas de biblioteca e é um custo aceito
conscientemente.

**Sem CLA.** Não exigimos Contributor License Agreement. A cláusula §5 do
Apache 2.0 já estabelece que contribuições entram sob a mesma licença, e um CLA
adicionaria atrito de adoção sem benefício proporcional para um projeto deste
tamanho.
