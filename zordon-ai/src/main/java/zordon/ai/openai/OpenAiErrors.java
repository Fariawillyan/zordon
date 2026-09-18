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
package zordon.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;
import java.util.Optional;
import zordon.ai.AiException;

/**
 * Traduz falhas do protocolo compatível para as mesmas categorias de qualquer outro
 * provider — é o que permite ao núcleo e à tela tratarem todos igual.
 */
final class OpenAiErrors {

    private OpenAiErrors() {}

    static AiException fromStatus(int status, String body, String providerId) {
        String detail = message(body).orElse("sem detalhe");
        String lowered = (detail + " " + body).toLowerCase(Locale.ROOT);

        if (status == 401 || status == 403) {
            return new AiException(AiException.Kind.NO_CREDENTIALS,
                    "O provider '" + providerId + "' recusou a chave (HTTP " + status + "). "
                            + "Troque-a com packaging/wsl/set-api-key.sh.");
        }
        // 402 é como o OpenRouter diz "sem crédito"; a OpenAI usa 429 com
        // insufficient_quota. Os dois significam "pagar resolve, tentar de novo não".
        if (status == 402 || (status == 429 && lowered.contains("insufficient_quota"))) {
            return new AiException(AiException.Kind.QUOTA_EXHAUSTED,
                    "A conta do provider '" + providerId + "' está sem crédito ou cota: " + detail);
        }
        if (status == 429) {
            return new AiException(AiException.Kind.RATE_LIMITED,
                    "O provider '" + providerId + "' limitou a taxa de requisições. Tente de novo em instantes.");
        }
        if (status >= 500) {
            return new AiException(AiException.Kind.UNAVAILABLE,
                    "O provider '" + providerId + "' está indisponível agora (HTTP " + status + ").");
        }
        return new AiException(AiException.Kind.INVALID_REQUEST,
                "O provider '" + providerId + "' recusou o pedido (HTTP " + status + "): " + detail);
    }

    static AiException fromIo(IOException e, URI baseUrl) {
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException
                || e.getCause() instanceof ConnectException
                || e.getCause() instanceof UnresolvedAddressException) {
            // O caso mais comum com modelo local: o servidor simplesmente não está de pé.
            return new AiException(AiException.Kind.UNAVAILABLE,
                    "Nada respondendo em " + baseUrl + " — o servidor está rodando?", e);
        }
        if (e instanceof HttpTimeoutException) {
            return new AiException(AiException.Kind.TIMEOUT, "O provider em " + baseUrl + " não respondeu no prazo.", e);
        }
        return new AiException(AiException.Kind.TIMEOUT, "Falha de rede ao falar com " + baseUrl + ".", e);
    }

    /** Erro enviado no meio do fluxo, dentro de um pedaço {@code data:}. */
    static AiException fromStreamError(JsonNode error, String providerId) {
        String detail = error.isTextual() ? error.asText() : error.path("message").asText("sem detalhe");
        return fromStatus(error.path("code").asInt(400), "{\"error\":{\"message\":" + quote(detail) + "}}", providerId);
    }

    /**
     * {@code {"error":{"message":…}}} na OpenAI; {@code {"error":"…"}} no Ollama.
     * Os dois aparecem na prática, e a mensagem do servidor vale mais que o código.
     */
    static Optional<String> message(String body) {
        try {
            JsonNode node = ChatCompletionsRequests.MAPPER.readTree(body);
            JsonNode error = node.get("error");
            if (error != null && error.isTextual()) {
                return Optional.of(error.asText());
            }
            if (error != null && error.hasNonNull("message")) {
                return Optional.of(error.get("message").asText());
            }
            return Optional.ofNullable(node.get("message")).map(JsonNode::asText);
        } catch (IOException | RuntimeException e) {
            return body == null || body.isBlank() ? Optional.empty() : Optional.of(body.strip());
        }
    }

    private static String quote(String text) {
        return ChatCompletionsRequests.MAPPER.getNodeFactory().textNode(text).toString();
    }
}
