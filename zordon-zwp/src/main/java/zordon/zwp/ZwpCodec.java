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
package zordon.zwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.LinkedHashMap;
import java.util.Map;
import zordon.api.event.EventEnvelope;
import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpMessage;
import zordon.api.zwp.ZwpNotification;
import zordon.api.zwp.ZwpRequest;
import zordon.api.zwp.ZwpResponse;
import zordon.api.trace.Spec;

/**
 * Tradução entre o envelope JSON-RPC do ZWP e os records de {@code zordon-api}.
 *
 * <p>Vive aqui, e não em {@code zordon-api}, porque o contrato compartilhado entre
 * Windows e WSL não pode depender de um serializador
 * (docs/architecture/components.md §4, regra 1).
 */
@Spec("SPEC-002")
public final class ZwpCodec {

    private static final String JSONRPC = "jsonrpc";
    private static final String ID = "id";
    private static final String METHOD = "method";
    private static final String PARAMS = "params";
    private static final String RESULT = "result";
    private static final String ERROR = "error";

    private final ObjectMapper mapper;

    public ZwpCodec() {
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                // Ordem estável de campos e de chaves. Sem isto, duas execuções da
                // mesma JVM produzem bytes diferentes para a mesma mensagem: o teste
                // de contrato vira loteria e o diff de protocolo deixa de ser legível.
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                // Um cliente mais novo pode mandar campo que este núcleo não conhece.
                // Ignorar é requisito de evolução do protocolo (ZWP §11), não gentileza.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }

    public String encode(ZwpMessage message) {
        try {
            return mapper.writeValueAsString(toWire(message));
        } catch (Exception e) {
            throw new ZwpCodecException("falha ao serializar mensagem ZWP", e);
        }
    }

    public ZwpMessage decode(String text) {
        Map<String, Object> wire = readMap(text);
        if (!ZwpMessage.JSONRPC_VERSION.equals(wire.get(JSONRPC))) {
            throw new ZwpCodecException("envelope sem jsonrpc=\"2.0\"");
        }
        boolean hasId = wire.get(ID) != null;
        boolean hasMethod = wire.get(METHOD) != null;

        if (hasMethod && !hasId) {
            return new ZwpNotification(string(wire, METHOD), params(wire));
        }
        if (hasMethod) {
            return new ZwpRequest(id(wire), string(wire, METHOD), params(wire));
        }
        if (!hasId) {
            throw new ZwpCodecException("envelope sem id e sem method");
        }
        if (wire.get(ERROR) != null) {
            return ZwpResponse.failed(id(wire), convert(wire.get(ERROR), ZwpError.class));
        }
        return ZwpResponse.ok(id(wire), asMap(wire.get(RESULT)));
    }

    /** Projeta um evento do barramento como notificação {@code event} (ZWP §6). */
    public ZwpNotification asNotification(EventEnvelope event) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", event.type().name());
        params.put("seq", event.seq());
        params.put("ts", event.ts().toString());
        params.put("topic", event.topic());
        params.put("payload", event.payload());
        return new ZwpNotification(ZwpNotification.EVENT_METHOD, params);
    }

    /** Converte os parâmetros de uma mensagem no record tipado do método. */
    public <T> T params(Map<String, Object> params, Class<T> type) {
        try {
            return mapper.convertValue(params, type);
        } catch (IllegalArgumentException e) {
            throw new ZwpCodecException("parâmetros inválidos para " + type.getSimpleName(), e);
        }
    }

    /** Converte um record de resultado no mapa que viaja no envelope. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toParams(Object value) {
        return mapper.convertValue(value, Map.class);
    }

    private Map<String, Object> toWire(ZwpMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put(JSONRPC, ZwpMessage.JSONRPC_VERSION);
        switch (message) {
            case ZwpRequest request -> {
                wire.put(ID, request.id());
                wire.put(METHOD, request.method());
                wire.put(PARAMS, request.params());
            }
            case ZwpResponse response -> {
                wire.put(ID, response.id());
                if (response.isError()) {
                    wire.put(ERROR, response.error());
                } else {
                    wire.put(RESULT, response.result());
                }
            }
            case ZwpNotification notification -> {
                wire.put(METHOD, notification.method());
                wire.put(PARAMS, notification.params());
            }
        }
        return wire;
    }

    private Map<String, Object> readMap(String text) {
        try {
            return asMap(mapper.readValue(text, Map.class));
        } catch (Exception e) {
            throw new ZwpCodecException("JSON inválido", e);
        }
    }

    private <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ZwpCodecException("esperava objeto JSON, veio " + value.getClass().getSimpleName());
    }

    private Map<String, Object> params(Map<String, Object> wire) {
        return asMap(wire.get(PARAMS));
    }

    private String string(Map<String, Object> wire, String field) {
        if (wire.get(field) instanceof String value) {
            return value;
        }
        throw new ZwpCodecException("campo '" + field + "' ausente ou não textual");
    }

    private long id(Map<String, Object> wire) {
        if (wire.get(ID) instanceof Number value) {
            return value.longValue();
        }
        throw new ZwpCodecException("id do JSON-RPC precisa ser inteiro neste protocolo");
    }
}
