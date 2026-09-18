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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import zordon.ai.AiProvider;
import zordon.ai.Pricing;
import zordon.ai.anthropic.AnthropicProvider;

/** O kit de contrato aplicado ao adaptador Anthropic, com eventos no formato da API dela. */
class AnthropicContractTest extends AiProviderContract {

    @Override
    protected AiProvider provider(URI baseUrl) {
        return AnthropicProvider.create("chave-de-teste", Optional.of(baseUrl), Pricing.defaults());
    }

    @Override
    protected FakeBackend.Script textStream(List<String> deltas, long inputTokens, long outputTokens) {
        List<String> events = new ArrayList<>(start(inputTokens));
        deltas.forEach(delta -> events.add(textDelta(delta)));
        events.add(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
        events.add(event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + outputTokens + "}}"));
        events.add(event("message_stop", "{\"type\":\"message_stop\"}"));
        return FakeBackend.sse(events);
    }

    @Override
    protected List<String> firstEvents(String delta) {
        List<String> events = new ArrayList<>(start(10));
        events.add(textDelta(delta));
        return events;
    }

    @Override
    protected String errorBody(String type, String message) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }

    private static List<String> start(long inputTokens) {
        return List.of(
                event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"model\":\"modelo-de-teste\",\"content\":[],\"stop_reason\":null,"
                        + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":1}}}"),
                event("content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
    }

    private static String textDelta(String text) {
        return event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}");
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data;
    }
}
