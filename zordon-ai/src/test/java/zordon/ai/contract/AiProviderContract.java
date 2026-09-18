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
package zordon.ai.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zordon.ai.AiException;
import zordon.ai.AiMessage;
import zordon.ai.AiProvider;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;
import zordon.ai.StopReason;
import zordon.api.trace.AcceptanceCriteria;

/**
 * O que <strong>todo</strong> provider precisa honrar.
 *
 * <p>Cada adaptador estende esta classe dizendo só como o seu fornecedor fala; os
 * testes são os mesmos para todos. É a forma verificável do princípio de que
 * qualquer {@code AiProvider} pode substituir outro sem o núcleo perceber
 * ([Padrões §3](../../../../../../docs/process/code-standards.md#3-solid-sem-cerimônia)) —
 * e de que "funciona com qualquer IA" não é só uma frase (ADR-0026).
 */
// Teto por teste: um provider que trava precisa reprovar em segundos, e não
// segurar o build pelos minutos do tempo limite do próprio SDK.
@org.junit.jupiter.api.Timeout(value = 30, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
public abstract class AiProviderContract {

    protected FakeBackend backend;

    /** O adaptador apontado para o servidor falso. */
    protected abstract AiProvider provider(URI baseUrl);

    /** Streaming de texto no formato do fornecedor, com o consumo informado. */
    protected abstract FakeBackend.Script textStream(List<String> deltas, long inputTokens, long outputTokens);

    /** O começo de um streaming, sem o fim. */
    protected abstract List<String> firstEvents(String delta);

    /** Corpo de erro no formato do fornecedor. */
    protected abstract String errorBody(String type, String message);

    @BeforeEach
    void startBackend() throws Exception {
        backend = FakeBackend.start();
    }

    @AfterEach
    void stopBackend() {
        backend.close();
    }

    @AcceptanceCriteria("SPEC-004/CA-5")
    @Test
    void osFragmentosChegamEmOrdemEOTextoFinalEhAConcatenacao() {
        backend.respondWith(textStream(List.of("Olá", ", ", "mundo."), 12, 4));
        List<String> received = new CopyOnWriteArrayList<>();

        AiResponse response = provider(backend.url()).stream(request(), new AiStreamListener() {
            @Override
            public void onTextDelta(String delta) {
                received.add(delta);
            }
        }).result().join();

        assertThat(received).containsExactly("Olá", ", ", "mundo.");
        assertThat(response.text()).isEqualTo("Olá, mundo.");
        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
    }

    @AcceptanceCriteria("SPEC-004/CA-8")
    @Test
    void oConsumoInformadoPeloProviderEhUsadoSemEstimativa() {
        backend.respondWith(textStream(List.of("ok"), 12, 4));

        AiResponse response = provider(backend.url()).stream(request(), new AiStreamListener() {}).result().join();

        assertThat(response.usage().inputTokens()).isEqualTo(12);
        assertThat(response.usage().outputTokens()).isEqualTo(4);
        assertThat(response.isUsageEstimated()).isFalse();
    }

    @AcceptanceCriteria("SPEC-004/CA-6")
    @Test
    void cancelarAbortaAConexaoEDevolveCancelled() throws Exception {
        backend.respondWith(backend.sseThenHang(firstEvents("pensando")));
        List<String> received = new CopyOnWriteArrayList<>();

        AiStream stream = provider(backend.url()).stream(request(), new AiStreamListener() {
            @Override
            public void onTextDelta(String delta) {
                received.add(delta);
            }
        });
        awaitTrue(() -> !received.isEmpty());
        long before = System.nanoTime();
        stream.cancel();

        // cancel() é chamado na thread que atende o usuário: se ele bloqueia, a tela
        // trava — e foi exatamente isso que este teste pegou no adaptador Anthropic.
        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(1));

        // O servidor continua pendurado: se o resultado sai, é porque a conexão caiu
        // do nosso lado — que é o que impede a geração de seguir cobrando.
        AiResponse response = stream.result().get(5, TimeUnit.SECONDS);
        assertThat(response.stopReason()).isEqualTo(StopReason.CANCELLED);
    }

    @AcceptanceCriteria("SPEC-004/CA-7")
    @Test
    void chaveRecusadaViraNoCredentials() {
        backend.respondWith(FakeBackend.error(401, errorBody("authentication_error", "invalid api key")));

        assertThat(failureOf(provider(backend.url())).kind()).isEqualTo(AiException.Kind.NO_CREDENTIALS);
    }

    @AcceptanceCriteria("SPEC-004/CA-7")
    @Test
    void limiteDeTaxaViraRateLimited() {
        backend.respondWith(FakeBackend.error(429, errorBody("rate_limit_error", "slow down")));

        assertThat(failureOf(provider(backend.url())).kind()).isEqualTo(AiException.Kind.RATE_LIMITED);
    }

    @AcceptanceCriteria("SPEC-004/CA-7")
    @Test
    void erroDoServidorViraUnavailableDepoisDeTentarDeNovo() {
        backend.respondWith(FakeBackend.error(500, errorBody("api_error", "boom")));

        AiException failure = failureOf(provider(backend.url()));

        assertThat(failure.kind()).isEqualTo(AiException.Kind.UNAVAILABLE);
        assertThat(failure.isRetryable()).isTrue();
        // Uma tentativa e duas retentativas, em todo provider (Core §4).
        assertThat(backend.requestCount()).isEqualTo(3);
    }

    @AcceptanceCriteria("SPEC-004/CA-7")
    @Test
    void servidorForaDoArViraUnavailableComOEndereco() throws Exception {
        URI nobody = FakeBackend.unreachable();

        AiException failure = failureOf(provider(nobody));

        assertThat(failure.kind()).isEqualTo(AiException.Kind.UNAVAILABLE);
        assertThat(failure.getMessage()).contains(String.valueOf(nobody.getPort()));
    }

    protected AiRequest request() {
        return AiRequest.builder("modelo-de-teste")
                .systemPrompt("Você é o Zordon.")
                .messages(List.of(AiMessage.user("oi")))
                .maxOutputTokens(256)
                .timeout(Duration.ofSeconds(20))
                .build();
    }

    private AiException failureOf(AiProvider provider) {
        try {
            provider.stream(request(), new AiStreamListener() {}).result().join();
        } catch (CompletionException e) {
            assertThat(e.getCause()).isInstanceOf(AiException.class);
            return (AiException) e.getCause();
        }
        throw new AssertionError("o provider deveria ter falhado");
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condição não ocorreu em 5 s");
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }
}
