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
package zordon.core.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JSON-RPC framing helpers for MCP stdio. */
final class McpJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private McpJson() {}
    static byte[] encode(Map<String, Object> message) throws IOException { return MAPPER.writeValueAsBytes(message); }
    static Map<String, Object> decode(String line) throws IOException {
        return MAPPER.readValue(line, new TypeReference<Map<String, Object>>() { });
    }
    static Long id(CharSequence line) {
        Matcher matcher = ID.matcher(line);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }
}
