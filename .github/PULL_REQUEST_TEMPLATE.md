## O que muda, e por quê

<!-- O problema primeiro; a solução depois. Se houver issue, referencie: Closes #N -->

## Evidência

<!--
Neste projeto, afirmação sem prova não entra. Mostre o que sustenta a mudança:
saída de teste, medição, captura de tela, log. "Testei aqui" não é evidência.
-->

- [ ] `./gradlew verifyAll` passou localmente
- [ ] Comportamento novo tem teste que **reprova sem a mudança** (contraprova feita)

## SPEC

<!--
Comportamento novo ou contrato alterado pede SPEC antes do código
(CONTRIBUTING.md). Se esta mudança supera um critério de aceite existente,
risque-o na SPEC antiga e aponte quem o substitui.
-->

- SPEC relacionada:
- [ ] Nenhuma SPEC é necessária, porque:

## Segurança

- [ ] Não amplia o que o Zordon pode fazer na máquina
- [ ] Não introduz `ProcessBuilder` fora de `zordon-security`
- [ ] Nenhum segredo, caminho pessoal ou nome de máquina entra no repositório
- [ ] Toda ação com efeito continua passando pelo motor de permissão

<!-- Se desmarcou algum item acima, explique aqui. Desmarcar não bloqueia; esconder, sim. -->

## O que ficou de fora

<!-- O que você decidiu não fazer nesta mudança, e por quê. -->
