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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.CoreInfo;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.HelloResult;
import zordon.api.zwp.ResumeRequest;
import zordon.api.zwp.ZwpErrorKind;
import zordon.api.zwp.ZwpProtocol;
import zordon.core.event.ZordonEventBus;
import zordon.zwp.ZwpCodec;

/**
 * Métodos do ciclo de vida da sessão: {@code session.hello}, {@code subscribe},
 * {@code unsubscribe} e {@code ping} (docs/api/zwp-protocol.md §2 e §4).
 */
public final class SessionMethods {

    private static final Logger log = LoggerFactory.getLogger(SessionMethods.class);

    private final ZwpCodec codec = new ZwpCodec();
    private final ZordonEventBus bus;
    private final String coreVersion;
    private final Instant startedAt;
    private final List<String> capabilities;

    public SessionMethods(
            ZordonEventBus bus, String coreVersion, Instant startedAt, List<String> capabilities) {
        this.bus = bus;
        this.coreVersion = coreVersion;
        this.startedAt = startedAt;
        this.capabilities = List.copyOf(capabilities);
    }

    /** Registra os métodos deste grupo no servidor. */
    public void registerOn(ZwpServer server) {
        server.register("session.hello", this::hello)
                .register("session.subscribe", this::subscribe)
                .register("session.unsubscribe", this::unsubscribe)
                .register("session.ping", this::ping);
    }

    private Map<String, Object> hello(ZwpSession session, Map<String, Object> params) {
        HelloParams hello = codec.params(params, HelloParams.class);
        if (!hello.protocol().supports(ZwpProtocol.VERSION)) {
            throw new ZwpMethodException(
                    ZwpErrorKind.ERR_PROTOCOL_UNSUPPORTED,
                    "núcleo fala ZWP v" + ZwpProtocol.VERSION + ", cliente pediu " + hello.protocol());
        }
        session.completeHello(hello.client());
        boolean resumed = resume(session, hello.resumeIfAny().orElse(null));

        log.info(
                "sessão {} com {} {} (protocolo {}), resumed={}",
                session.id(),
                hello.client().kind(),
                hello.client().version(),
                ZwpProtocol.VERSION,
                resumed);

        return codec.toParams(new HelloResult(
                ZwpProtocol.VERSION,
                new CoreInfo(coreVersion, bus.startId(), startedAt),
                session.id(),
                capabilities,
                resumed,
                ZwpProtocol.DEFAULT_HEARTBEAT.toMillis()));
    }

    /**
     * Retomada só é concedida quando o núcleo é o mesmo e o anel ainda cobre o
     * ponto pedido. Nos outros casos o cliente descarta o estado volátil e refaz o
     * snapshot — nunca assume continuidade (ADR-0011).
     */
    private boolean resume(ZwpSession session, ResumeRequest request) {
        if (request == null
                || !request.startId().equals(bus.startId())
                || !bus.canReplayFrom(request.lastEventSeq())) {
            return false;
        }
        session.runAfterResponse(() ->
                bus.replay(request.lastEventSeq(), ZordonEventBus.REPLAY_CAPACITY).forEach(session::deliver));
        return true;
    }

    private Map<String, Object> subscribe(ZwpSession session, Map<String, Object> params) {
        return Map.of("topics", List.copyOf(session.subscribe(topics(params))));
    }

    private Map<String, Object> unsubscribe(ZwpSession session, Map<String, Object> params) {
        return Map.of("topics", List.copyOf(session.unsubscribe(topics(params))));
    }

    private Map<String, Object> ping(ZwpSession session, Map<String, Object> params) {
        return Map.of("serverTimeMs", System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    private Set<String> topics(Map<String, Object> params) {
        Object topics = params.get("topics");
        if (topics instanceof List<?> list) {
            return Set.copyOf((List<String>) list);
        }
        throw new IllegalArgumentException("params.topics precisa ser uma lista");
    }
}
