/*
 * Copyright 2026 Willyan Faria
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zordon.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zordon.ai.AiException;
import zordon.ai.AiProvider;
import zordon.ai.ModelPolicy;
import zordon.ai.ModelRole;
import zordon.ai.Pricing;
import zordon.ai.registry.AiSettings;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.SessionId;
import zordon.api.TurnId;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;

class TurnManagerTest {

    private final ZordonEventBus bus = new ZordonEventBus("01TESTE00000000000000000000");
    private final ConversationStore conversations = new ConversationStore();
    private final List<EventEnvelope> events = new CopyOnWriteArrayList<>();

    private SessionId session;

    @BeforeEach
    void subscribe() {
        bus.subscribe("teste", java.util.Set.of(Topic.CHAT), QueuePolicy.dropOldest(256), events::add);
        session = conversations.newSession("teste");
    }

    @AcceptanceCriteria("SPEC-003/CA-1")
    @Test
    void oTurnoPublicaComandoPensamentoFragmentosERespostaFinal() throws Exception {
        TurnManager turns = managerWith(FakeAiProvider.answering("Os ", "containers ", "estão de pé."));

        TurnId turn = turns.send(session, "quais containers estão rodando", "text");

        awaitDone(turn);
        assertThat(typesOf(turn)).startsWith(EventType.USER_COMMAND, EventType.AI_THINKING, EventType.AI_RESPONSE);
        assertThat(deltasOf(turn)).containsExactly("Os ", "containers ", "estão de pé.");
        assertThat(finalPayload(turn).get("text")).isEqualTo("Os containers estão de pé.");
    }

    @AcceptanceCriteria("SPEC-003/CA-2")
    @Test
    void aRespostaCompletaEntraNoHistorico() throws Exception {
        TurnManager turns = managerWith(FakeAiProvider.answering("Pronto."));

        TurnId turn = turns.send(session, "faça algo", "text");
        awaitDone(turn);

        assertThat(conversations.history(session, Optional.empty(), 10))
                .extracting(StoredMessage::role, StoredMessage::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("assistant", "Pronto."),
                        org.assertj.core.groups.Tuple.tuple("user", "faça algo"));
    }

    @AcceptanceCriteria("SPEC-003/CA-3")
    @Test
    void oUsoDeCacheAparaceNaRespostaFinal() throws Exception {
        TurnManager turns = managerWith(FakeAiProvider.answering("ok"));

        TurnId turn = turns.send(session, "oi", "text");
        awaitDone(turn);

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) finalPayload(turn).get("usage");
        assertThat(usage.get("cacheReadTokens")).isEqualTo(100L);
        assertThat(finalPayload(turn).get("costUsd")).isEqualTo("0.0012");
    }

    @AcceptanceCriteria("SPEC-003/CA-4")
    @Test
    void recusaDoModeloViraErroVisivelENaoRespostaVazia() throws Exception {
        TurnManager turns = managerWith(FakeAiProvider.refusing("não posso ajudar com isso"));

        TurnId turn = turns.send(session, "algo recusável", "text");

        awaitEvent(turn, EventType.AI_ERROR);
        assertThat(payloadOf(turn, EventType.AI_ERROR).get("message")).isEqualTo("não posso ajudar com isso");
    }

    @AcceptanceCriteria("SPEC-003/CA-5")
    @Test
    void falhaDoProviderInformaSePodeTentarDeNovo() throws Exception {
        TurnManager turns = managerWith(
                FakeAiProvider.failing(new AiException(AiException.Kind.RATE_LIMITED, "limite de taxa")));

        TurnId turn = turns.send(session, "oi", "text");

        awaitEvent(turn, EventType.AI_ERROR);
        assertThat(payloadOf(turn, EventType.AI_ERROR))
                .containsEntry("kind", "RATE_LIMITED")
                .containsEntry("retryable", true);
    }

    @AcceptanceCriteria("SPEC-003/CA-15")
    @Test
    void umaFalhaGeraExatamenteUmErroComACategoriaDoProvider() throws Exception {
        // Regressão: a falha era publicada duas vezes — pelo listener e pelo fim do
        // turno —, e a segunda saía como UNAVAILABLE "tente de novo" para uma conta
        // sem crédito. A tela mostrava dois erros, e o segundo mentia.
        TurnManager turns = managerWith(FakeAiProvider.failing(new AiException(
                AiException.Kind.QUOTA_EXHAUSTED, "A conta da API está sem crédito.")));

        TurnId turn = turns.send(session, "oi", "text");
        awaitEvent(turn, EventType.AI_ERROR);
        java.util.concurrent.TimeUnit.MILLISECONDS.sleep(200);

        List<Map<String, Object>> errors = events.stream()
                .filter(event -> event.type() == EventType.AI_ERROR)
                .filter(event -> turn.value().equals(event.payload().get("turnId")))
                .map(EventEnvelope::payload)
                .toList();
        assertThat(errors).singleElement().satisfies(error -> assertThat(error)
                .containsEntry("kind", "QUOTA_EXHAUSTED")
                .containsEntry("retryable", false)
                .containsEntry("message", "A conta da API está sem crédito."));
    }

    @AcceptanceCriteria("SPEC-004/CA-9")
    @Test
    void aReservaRespondeQuandoOPrincipalFicaSemCredito() throws Exception {
        TurnManager turns = managerWithFallback(
                FakeAiProvider.failing(new AiException(AiException.Kind.QUOTA_EXHAUSTED, "A conta está sem crédito.")),
                FakeAiProvider.answering("resposta ", "local"));

        TurnId turn = turns.send(session, "oi", "text");
        awaitDone(turn);

        assertThat(finalPayload(turn))
                .containsEntry("text", "resposta local")
                .containsEntry("provider", "reserva")
                .containsEntry("fallbackFrom", "principal")
                .containsEntry("fallbackReason", "A conta está sem crédito.");
        assertThat(errorsOf(turn)).isEmpty();
    }

    @AcceptanceCriteria("SPEC-004/CA-9")
    @Test
    void aReservaAvisaQueEntrouAntesDeResponder() throws Exception {
        TurnManager turns = managerWithFallback(
                FakeAiProvider.failing(new AiException(AiException.Kind.UNAVAILABLE, "fora do ar")),
                FakeAiProvider.answering("ok"));

        TurnId turn = turns.send(session, "oi", "text");
        awaitDone(turn);

        // A tela precisa saber, enquanto espera, que quem vai responder mudou.
        assertThat(events.stream()
                        .filter(event -> event.type() == EventType.AI_THINKING)
                        .filter(event -> turn.value().equals(event.payload().get("turnId")))
                        .map(EventEnvelope::payload))
                .anySatisfy(thinking -> assertThat(thinking)
                        .containsEntry("provider", "reserva")
                        .containsEntry("fallbackFrom", "principal")
                        .containsEntry("reason", "fora do ar"));
    }

    @AcceptanceCriteria("SPEC-004/CA-9")
    @Test
    void aReservaEntraQuandoOPrincipalNemExiste() throws Exception {
        // O caso de quem só configurou um modelo local como reserva, sem chave de API.
        FakeAiProvider reserve = FakeAiProvider.answering("sem chave, mas respondi");
        TurnManager turns = new TurnManager(bus, conversations, new IntentRouter(), new PromptComposer(),
                ProviderRegistry.of(Map.of("reserva", reserve), roles("reserva", "modelo-local")));

        TurnId turn = turns.send(session, "oi", "text");
        awaitDone(turn);

        assertThat(finalPayload(turn)).containsEntry("provider", "reserva").containsEntry("fallbackFrom", "principal");
    }

    @AcceptanceCriteria("SPEC-004/CA-10")
    @Test
    void naoHaReservaDepoisQueOTextoComecouAAparecer() throws Exception {
        FakeAiProvider reserve = FakeAiProvider.answering("não deveria ser chamada");
        TurnManager turns = managerWithFallback(
                FakeAiProvider.failingAfter("Come", new AiException(AiException.Kind.UNAVAILABLE, "caiu no meio")),
                reserve);

        TurnId turn = turns.send(session, "oi", "text");
        awaitEvent(turn, EventType.AI_ERROR);

        // Emendar dois modelos numa mesma frase produziria uma resposta que ninguém escreveu.
        assertThat(reserve.received()).isEmpty();
        assertThat(errorsOf(turn)).singleElement().satisfies(error -> assertThat(error)
                .containsEntry("kind", "UNAVAILABLE"));
    }

    @AcceptanceCriteria("SPEC-004/CA-10")
    @Test
    void naoHaReservaParaPedidoInvalido() throws Exception {
        FakeAiProvider reserve = FakeAiProvider.answering("não deveria ser chamada");
        TurnManager turns = managerWithFallback(
                FakeAiProvider.failing(new AiException(AiException.Kind.INVALID_REQUEST, "modelo inexistente")),
                reserve);

        TurnId turn = turns.send(session, "oi", "text");
        awaitEvent(turn, EventType.AI_ERROR);

        assertThat(reserve.received()).isEmpty();
    }

    @AcceptanceCriteria("SPEC-004/CA-10")
    @Test
    void reservaIgualAoPrincipalNaoEhTentada() throws Exception {
        FakeAiProvider principal = FakeAiProvider.failing(new AiException(AiException.Kind.UNAVAILABLE, "fora do ar"));
        TurnManager turns = new TurnManager(bus, conversations, new IntentRouter(), new PromptComposer(),
                ProviderRegistry.of(Map.of("principal", principal), roles("principal", "modelo-remoto")));

        TurnId turn = turns.send(session, "oi", "text");
        awaitEvent(turn, EventType.AI_ERROR);

        assertThat(principal.received()).hasSize(1);
    }

    @AcceptanceCriteria("SPEC-004/CA-9")
    @Test
    void quandoAReservaTambemFalhaOErroCitaAsDuasCausas() throws Exception {
        TurnManager turns = managerWithFallback(
                FakeAiProvider.failing(new AiException(AiException.Kind.QUOTA_EXHAUSTED, "sem crédito")),
                FakeAiProvider.failing(new AiException(AiException.Kind.UNAVAILABLE, "Nada respondendo em localhost")));

        TurnId turn = turns.send(session, "oi", "text");
        awaitEvent(turn, EventType.AI_ERROR);
        java.util.concurrent.TimeUnit.MILLISECONDS.sleep(100);

        assertThat(errorsOf(turn)).singleElement().satisfies(error -> assertThat(error.get("message").toString())
                .contains("Nada respondendo em localhost")
                .contains("sem crédito"));
    }

    @AcceptanceCriteria("SPEC-003/CA-5")
    @Test
    void semChaveDeApiOTurnoFalhaDizendoOQueFalta() throws Exception {
        TurnManager turns = managerWithoutKeys();

        TurnId turn = turns.send(session, "oi", "text");

        awaitEvent(turn, EventType.AI_ERROR);
        assertThat(payloadOf(turn, EventType.AI_ERROR))
                .containsEntry("kind", "NO_CREDENTIALS")
                .containsEntry("retryable", false);
        assertThat(payloadOf(turn, EventType.AI_ERROR).get("message").toString())
                .contains("ANTHROPIC_API_KEY");
    }

    @AcceptanceCriteria("SPEC-003/CA-7")
    @Test
    void cancelarInterrompeOTurnoEmAndamento() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        TurnManager turns = managerWith(FakeAiProvider.hanging(started));

        TurnId turn = turns.send(session, "algo demorado", "text");
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(turns.cancel(turn)).isTrue();
        awaitCondition(() -> !turns.isRunning(turn));
    }

    @AcceptanceCriteria("SPEC-003/CA-7")
    @Test
    void cancelarLogoDepoisDeEnviarNaoSePerde() throws Exception {
        // Regressão: o turno só era registrado depois de o provider começar a
        // transmitir. Um cancelamento nessa janela devolvia false e o modelo seguia.
        FakeAiProvider provider = FakeAiProvider.answering("não deveria chegar aqui");
        TurnManager turns = managerWith(provider);

        TurnId turn = turns.send(session, "algo", "text");

        assertThat(turns.cancel(turn)).isTrue();
        awaitCondition(() -> !turns.isRunning(turn));
        assertThat(turns.activeTurns()).isEmpty();
    }

    @AcceptanceCriteria("SPEC-003/CA-6")
    @Test
    void rotaRapidaRespondeSemProviderEAindaAssimPublicaOsEventos() throws Exception {
        TurnManager turns = managerWithoutKeys();

        TurnId turn = turns.send(session, "que horas são", "text");

        awaitDone(turn);
        assertThat(typesOf(turn)).containsExactly(EventType.USER_COMMAND, EventType.AI_RESPONSE);
        assertThat(finalPayload(turn).get("route")).isEqualTo("fast:hora");
    }

    @AcceptanceCriteria("SPEC-003/CA-8")
    @Test
    void oHistoricoInteiroVaiParaOModeloEmOrdemCronologica() throws Exception {
        FakeAiProvider provider = FakeAiProvider.answering("certo");
        TurnManager turns = managerWith(provider);

        awaitDone(turns.send(session, "primeira", "text"));
        awaitDone(turns.send(session, "segunda", "text"));

        var messages = provider.received().getLast().messages();
        assertThat(messages).extracting(zordon.ai.AiMessage::text)
                .containsExactly("primeira", "certo", "segunda");
    }

    @AcceptanceCriteria("SPEC-003/CA-9")
    @Test
    void oPromptDeSistemaNaoCarregaNadaQueMudeACadaTurno() throws Exception {
        FakeAiProvider provider = FakeAiProvider.answering("ok");
        TurnManager turns = managerWith(provider);

        awaitDone(turns.send(session, "primeira", "text"));
        awaitDone(turns.send(session, "segunda", "text"));

        // Prefixo instável é o que zera o cache de prompt — o maior risco do M1.
        assertThat(provider.received().getFirst().systemPrompt())
                .isEqualTo(provider.received().getLast().systemPrompt());
        assertThat(provider.received().getFirst().cacheSystemPrompt()).isTrue();
    }

    private TurnManager managerWith(AiProvider provider) {
        return new TurnManager(bus, conversations, new IntentRouter(), new PromptComposer(),
                ProviderRegistry.of(Map.of(ModelPolicy.DEFAULT_PROVIDER, provider), ModelPolicy.defaults()));
    }

    /** Registro montado da configuração padrão, num ambiente sem chave nenhuma. */
    private TurnManager managerWithoutKeys() {
        return new TurnManager(bus, conversations, new IntentRouter(), new PromptComposer(),
                ProviderRegistry.build(AiSettings.defaults(), Pricing.defaults(), Map.of()));
    }

    /** Principal e reserva, cada um num provider falso. */
    private TurnManager managerWithFallback(AiProvider principal, AiProvider reserve) {
        return new TurnManager(bus, conversations, new IntentRouter(), new PromptComposer(),
                ProviderRegistry.of(Map.of("principal", principal, "reserva", reserve), roles("reserva", "modelo-local")));
    }

    private static ModelPolicy roles(String fallbackProvider, String fallbackModel) {
        return new ModelPolicy(Map.of(
                ModelRole.CONVERSATION, new ModelPolicy.ModelChoice("principal", "modelo-remoto", null),
                ModelRole.FALLBACK, new ModelPolicy.ModelChoice(fallbackProvider, fallbackModel, null)));
    }

    private List<Map<String, Object>> errorsOf(TurnId turn) {
        return events.stream()
                .filter(event -> event.type() == EventType.AI_ERROR)
                .filter(event -> turn.value().equals(event.payload().get("turnId")))
                .map(EventEnvelope::payload)
                .toList();
    }

    private List<EventType> typesOf(TurnId turn) {
        return events.stream()
                .filter(event -> turn.value().equals(event.payload().get("turnId")))
                .map(EventEnvelope::type)
                .toList();
    }

    private List<String> deltasOf(TurnId turn) {
        return events.stream()
                .filter(event -> event.type() == EventType.AI_RESPONSE)
                .filter(event -> turn.value().equals(event.payload().get("turnId")))
                .filter(event -> event.payload().containsKey("delta"))
                .map(event -> (String) event.payload().get("delta"))
                .toList();
    }

    private Map<String, Object> finalPayload(TurnId turn) {
        return events.stream()
                .filter(event -> event.type() == EventType.AI_RESPONSE)
                .filter(event -> turn.value().equals(event.payload().get("turnId")))
                .filter(event -> Boolean.TRUE.equals(event.payload().get("done")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("o turno não terminou"))
                .payload();
    }

    private Map<String, Object> payloadOf(TurnId turn, EventType type) {
        return events.stream()
                .filter(event -> event.type() == type)
                .filter(event -> turn.value().equals(event.payload().get("turnId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("evento " + type + " não chegou"))
                .payload();
    }

    private void awaitDone(TurnId turn) throws InterruptedException {
        awaitCondition(() -> events.stream()
                .anyMatch(event -> event.type() == EventType.AI_RESPONSE
                        && turn.value().equals(event.payload().get("turnId"))
                        && Boolean.TRUE.equals(event.payload().get("done"))));
    }

    private void awaitEvent(TurnId turn, EventType type) throws InterruptedException {
        awaitCondition(() -> events.stream()
                .anyMatch(event -> event.type() == type && turn.value().equals(event.payload().get("turnId"))));
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("condição não ocorreu em 5 s");
    }
}
