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
package zordon.core.zwp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpErrorKind;
import zordon.api.zwp.ZwpRequest;
import zordon.api.zwp.ZwpResponse;
import zordon.zwp.ZwpCodecException;

/** Os métodos que o servidor atende, e como cada falha vira um erro do protocolo. */
final class MethodDispatch {

    private static final Logger log = LoggerFactory.getLogger(ZwpServer.class);

    private final Map<String, MethodHandler> methods = new ConcurrentHashMap<>();
    private final Map<String, AsyncMethodHandler> asyncMethods = new ConcurrentHashMap<>();

    void register(String method, MethodHandler handler) {
        methods.put(method, handler);
    }

    void registerAsync(String method, AsyncMethodHandler handler) {
        asyncMethods.put(method, handler);
    }

    boolean async(String method) {
        return asyncMethods.containsKey(method);
    }

    ZwpResponse dispatch(ZwpSession session, ZwpRequest request) {
        MethodHandler handler = methods.get(request.method());
        if (handler == null) {
            return ZwpResponse.failed(request.id(), ZwpError.protocol(
                    ZwpError.METHOD_NOT_FOUND, "método desconhecido: " + request.method()));
        }
        if (!session.helloCompleted() && !"session.hello".equals(request.method())) {
            return ZwpResponse.failed(request.id(), ZwpError.of(
                    ZwpErrorKind.ERR_UNAUTHORIZED, "session.hello precisa vir primeiro"));
        }
        try {
            return ZwpResponse.ok(request.id(), handler.handle(session, request.params()));
        } catch (ZwpMethodException e) {
            return ZwpResponse.failed(request.id(), e.error());
        } catch (IllegalArgumentException | ZwpCodecException e) {
            return ZwpResponse.failed(request.id(), ZwpError.of(
                    ZwpErrorKind.ERR_INVALID_ARGUMENT, String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            log.error("falha ao tratar {}", request.method(), e);
            return ZwpResponse.failed(request.id(), ZwpError.protocol(
                    ZwpError.INTERNAL_ERROR, "falha interna ao tratar " + request.method()));
        }
    }

    /** Responde depois, sem prender a thread do WebSocket. */
    CompletableFuture<ZwpResponse> dispatchAsync(ZwpSession session, ZwpRequest request) {
        if (!session.helloCompleted()) {
            return CompletableFuture.completedFuture(ZwpResponse.failed(request.id(), ZwpError.of(
                    ZwpErrorKind.ERR_UNAUTHORIZED, "session.hello precisa vir primeiro")));
        }
        CompletableFuture<Map<String, Object>> result;
        try {
            result = asyncMethods.get(request.method()).handle(session, request.params());
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        return result.handle((value, failure) -> failure == null
                ? ZwpResponse.ok(request.id(), value)
                : ZwpResponse.failed(request.id(), asError(request.method(), failure)));
    }

    private ZwpError asError(String method, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        return switch (cause) {
            case ZwpMethodException e -> e.error();
            case IllegalArgumentException e -> ZwpError.of(ZwpErrorKind.ERR_INVALID_ARGUMENT, String.valueOf(e.getMessage()));
            default -> {
                log.error("falha ao tratar {}", method, cause);
                yield ZwpError.protocol(ZwpError.INTERNAL_ERROR, "falha interna ao tratar " + method);
            }
        };
    }
}
