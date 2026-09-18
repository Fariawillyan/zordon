---
document: specs-defense-index
module: defense
section: index
version: 1
updatedAt: 2026-09-17
securityLevel: restricted
tags: [specs,defesa,detectores,indice]
specId: null
---

# SPECs — defesa

A norma está em [Defesa e detecção](../../security/defense.md). Esta pasta guarda
SPECs de detectores, playbooks e componentes do Defense Engine — previstos para
o **M7** ([Roadmap](../../roadmap.md)).

Regras que toda SPEC aqui herda:

1. **Detector é determinístico.** Mesma entrada, mesmo `Finding`. ML produz
   `Signal` com peso; a severidade vem de regra versionada
   ([ADR-0016](../../adr/ADR-0016-defesa-deterministica.md)).
2. **Toda resposta é reversível e com prazo**, ou exige o usuário
   ([Defesa §5](../../security/defense.md#5-defense-engine-e-playbooks)).
3. **Nada é apagado.** Quarentena
   ([ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md)).
4. **Toda ação é comunicada** em até 2 s
   ([ADR-0014](../../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md)).
5. **Casos golden obrigatórios**, positivos e negativos, para cada detector.
6. **Orçamento de CPU declarado.** Detector `EXPENSIVE` não roda no caminho
   quente ([ADR-0017](../../adr/ADR-0017-zordon-nao-e-antivirus.md)).

| ID | Nome | Anel | Status |
|---|---|---|---|
| — | _nenhuma ainda_ | — | — |
