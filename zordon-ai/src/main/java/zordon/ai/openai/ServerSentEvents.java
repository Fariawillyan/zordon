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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Server-Sent Events: linhas {@code data: …}, terminadas por {@code data: [DONE]}. */
final class ServerSentEvents {

    private ServerSentEvents() {}

    static void read(InputStream body, ChatCompletionsAccumulator accumulator, String providerId) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).strip();
                if ("[DONE]".equals(data)) {
                    return;
                }
                if (data.isEmpty()) {
                    continue;
                }
                JsonNode chunk = ChatCompletionsRequests.MAPPER.readTree(data);
                if (chunk.has("error")) {
                    throw OpenAiErrors.fromStreamError(chunk.get("error"), providerId);
                }
                accumulator.accept(chunk);
            }
        }
    }
}
