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
import zordon.ai.AiProvider;
import zordon.ai.Pricing;
import zordon.ai.openai.OpenAiCompatibleProvider;

/** O kit de contrato aplicado ao adaptador compatível com OpenAI, com pedaços de Chat Completions. */
class OpenAiCompatibleContractTest extends AiProviderContract {

    @Override
    protected AiProvider provider(URI baseUrl) {
        return new OpenAiCompatibleProvider(
                "teste", URI.create(baseUrl + "/v1"), "chave-de-teste", "max_completion_tokens", Pricing.defaults());
    }

    @Override
    protected FakeBackend.Script textStream(List<String> deltas, long inputTokens, long outputTokens) {
        List<String> events = new ArrayList<>(firstEvents(null));
        deltas.forEach(delta -> events.add(chunk("{\"content\":\"" + delta + "\"}", null)));
        events.add(chunk("{}", "\"stop\""));
        events.add("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":" + inputTokens
                + ",\"completion_tokens\":" + outputTokens + ",\"total_tokens\":" + (inputTokens + outputTokens) + "}}");
        events.add("data: [DONE]");
        return FakeBackend.sse(events);
    }

    @Override
    protected List<String> firstEvents(String delta) {
        List<String> events = new ArrayList<>();
        events.add(chunk("{\"role\":\"assistant\",\"content\":\"\"}", null));
        if (delta != null) {
            events.add(chunk("{\"content\":\"" + delta + "\"}", null));
        }
        return events;
    }

    @Override
    protected String errorBody(String type, String message) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"" + type + "\"}}";
    }

    static String chunk(String delta, String finishReason) {
        return "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":" + delta
                + ",\"finish_reason\":" + finishReason + "}]}";
    }
}
