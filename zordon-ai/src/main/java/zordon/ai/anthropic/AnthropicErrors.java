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

import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiException;

/**
 * Traduz falhas do SDK em erros que o usuário entende e sabe resolver.
 *
 * <p>"provider respondeu 400" é verdade e não ajuda ninguém. A mesma resposta pode
 * significar conta sem crédito, pedido malformado ou modelo inexistente — e cada
 * um tem uma ação diferente. A mensagem mostrada diz a causa e o que fazer.
 */
final class AnthropicErrors {

    private static final Logger log = LoggerFactory.getLogger(AnthropicErrors.class);

    static final String DEFAULT_ENDPOINT = "https://api.anthropic.com";

    private AnthropicErrors() {}

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    static AiException translate(RuntimeException e) {
        return translate(e, DEFAULT_ENDPOINT);
    }

    /**
     * @param endpoint para onde o adaptador fala — entra na mensagem quando nada
     *     responde, igual ao adaptador compatível com OpenAI (SPEC-004 CA-7)
     */
    static AiException translate(RuntimeException e, String endpoint) {
        if (e instanceof AiException already) {
            return already;
        }
        if (causedBy(e, java.net.ConnectException.class) || causedBy(e, java.nio.channels.UnresolvedAddressException.class)) {
            return new AiException(AiException.Kind.UNAVAILABLE,
                    "Nada respondendo em " + endpoint + " — há conexão com a internet?", e);
        }
        if (e instanceof RateLimitException rateLimited) {
            return new AiException(AiException.Kind.RATE_LIMITED,
                    "A API limitou a taxa de requisições. Tente de novo em instantes.", rateLimited);
        }
        if (e instanceof AnthropicServiceException service) {
            return fromStatus(service);
        }
        if (e instanceof java.io.UncheckedIOException || e.getCause() instanceof java.io.IOException) {
            return new AiException(AiException.Kind.TIMEOUT,
                    "Falha de rede ao falar com a API da Anthropic.", e);
        }
        return new AiException(AiException.Kind.UNAVAILABLE, "Falha inesperada ao falar com a API.", e);
    }

    private static AiException fromStatus(AnthropicServiceException service) {
        int status = service.statusCode();
        String detail = apiMessage(service).orElse("sem detalhe");
        log.warn("API da Anthropic respondeu HTTP {}: {}", status, detail);

        if (status == 401) {
            return new AiException(AiException.Kind.NO_CREDENTIALS,
                    "A API recusou a chave (401). O caminho preferido é a assinatura: entre com `claude` no WSL"
                            + " e a conversa passa por ela. Se quiser mesmo a API, troque a chave com"
                            + " packaging/wsl/set-api-key.sh.", service);
        }
        if (status == 403) {
            return new AiException(AiException.Kind.NO_CREDENTIALS,
                    "A chave não tem permissão para este pedido (403): " + detail, service);
        }
        // A falta de crédito chega como um invalid_request_error genérico: o texto é o
        // único sinal que a distingue. Se ele mudar, cai no caso geral abaixo, que
        // ainda mostra a mensagem da própria API — degrada, não esconde.
        if (status == 400 && detail.toLowerCase(Locale.ROOT).contains("credit balance")) {
            return new AiException(AiException.Kind.QUOTA_EXHAUSTED,
                    "A conta da API está sem crédito. Adicione créditos em "
                            + "console.anthropic.com → Plans & Billing.", service);
        }
        if (status >= 500) {
            return new AiException(AiException.Kind.UNAVAILABLE,
                    "A API da Anthropic está indisponível agora (HTTP " + status + ").", service);
        }
        return new AiException(AiException.Kind.INVALID_REQUEST,
                "A API recusou o pedido (HTTP " + status + "): " + detail, service);
    }

    /** {@code error.message} do corpo da resposta, quando há um. */
    static Optional<String> apiMessage(AnthropicServiceException service) {
        return field(service.body(), "error").flatMap(error -> field(error, "message")).flatMap(AnthropicErrors::text);
    }

    // Os acessores do SDK (escrito em Kotlin) chegam ao Java como Optional sem tipo;
    // o instanceof recupera o tipo sem cast cego nem supressão de aviso.
    private static Optional<JsonValue> field(JsonValue value, String name) {
        Optional<?> object = value.asObject();
        return object.isPresent() && object.get() instanceof Map<?, ?> map && map.get(name) instanceof JsonValue child
                ? Optional.of(child)
                : Optional.empty();
    }

    private static Optional<String> text(JsonValue value) {
        Optional<?> text = value.asString();
        return text.isPresent() && text.get() instanceof String string ? Optional.of(string) : Optional.empty();
    }
}
