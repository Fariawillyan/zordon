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
package zordon.ai.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnexpectedStatusCodeException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.ai.AiException;
import zordon.api.trace.AcceptanceCriteria;

/**
 * O que o usuário lê quando a API falha.
 *
 * <p>O caso da conta sem crédito não é hipotético: foi o primeiro erro real que o
 * Zordon recebeu, e a mensagem de então — "provider respondeu 400" — não dizia a
 * ninguém o que fazer.
 */
class AnthropicErrorsTest {

    @AcceptanceCriteria("SPEC-003/CA-15")
    @Test
    void contaSemCreditoDizOndeResolverENaoSugereTentarDeNovo() {
        AiException error = AnthropicErrors.translate(BadRequestException.builder()
                .headers(Headers.builder().build())
                .body(body("invalid_request_error",
                        "Your credit balance is too low to access the Anthropic API. "
                                + "Please go to Plans & Billing to upgrade or purchase credits."))
                .build());

        assertThat(error.kind()).isEqualTo(AiException.Kind.QUOTA_EXHAUSTED);
        assertThat(error.isRetryable()).isFalse();
        assertThat(error.getMessage()).contains("sem crédito").contains("Plans & Billing");
    }

    @AcceptanceCriteria("SPEC-003/CA-15")
    @Test
    void chaveRecusadaApontaOComandoQueATroca() {
        AiException error = AnthropicErrors.translate(UnauthorizedException.builder()
                .headers(Headers.builder().build())
                .body(body("authentication_error", "invalid x-api-key"))
                .build());

        assertThat(error.kind()).isEqualTo(AiException.Kind.NO_CREDENTIALS);
        assertThat(error.getMessage()).contains("set-api-key.sh");
    }

    @AcceptanceCriteria("SPEC-003/CA-15")
    @Test
    void pedidoRecusadoPorOutroMotivoMostraAMensagemDaPropriaApi() {
        // Sem categoria conhecida, a mensagem da API ainda é melhor do que o código.
        AiException error = AnthropicErrors.translate(BadRequestException.builder()
                .headers(Headers.builder().build())
                .body(body("invalid_request_error", "model: claude-inexistente not found"))
                .build());

        assertThat(error.kind()).isEqualTo(AiException.Kind.INVALID_REQUEST);
        assertThat(error.getMessage()).contains("HTTP 400").contains("claude-inexistente not found");
    }

    @AcceptanceCriteria("SPEC-003/CA-15")
    @Test
    void apiSobrecarregadaPodeSerTentadaDeNovo() {
        AiException error = AnthropicErrors.translate(UnexpectedStatusCodeException.builder()
                .statusCode(529)
                .headers(Headers.builder().build())
                .body(body("overloaded_error", "Overloaded"))
                .build());

        assertThat(error.kind()).isEqualTo(AiException.Kind.UNAVAILABLE);
        assertThat(error.isRetryable()).isTrue();
    }

    @AcceptanceCriteria("SPEC-003/CA-15")
    @Test
    void limiteDeTaxaPodeSerTentadoDeNovo() {
        AiException error = AnthropicErrors.translate(RateLimitException.builder()
                .headers(Headers.builder().build())
                .body(body("rate_limit_error", "Number of requests has exceeded your rate limit"))
                .build());

        assertThat(error.kind()).isEqualTo(AiException.Kind.RATE_LIMITED);
        assertThat(error.isRetryable()).isTrue();
    }

    @AcceptanceCriteria("SPEC-003/CA-15")
    @Test
    void corpoSemMensagemNaoQuebraATraducao() {
        AiException error = AnthropicErrors.translate(BadRequestException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of()))
                .build());

        assertThat(error.getMessage()).contains("sem detalhe");
    }

    private static JsonValue body(String type, String message) {
        return JsonValue.from(Map.of("type", "error", "error", Map.of("type", type, "message", message)));
    }
}
