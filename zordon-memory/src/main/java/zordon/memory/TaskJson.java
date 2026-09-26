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
package zordon.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;

/** JSON dos passos de uma tarefa. */
final class TaskJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskJson() {}

    static String write(List<String> dependsOn) {
        try {
            return MAPPER.writeValueAsString(dependsOn);
        } catch (IOException e) {
            throw new IllegalStateException("dependências de tarefa inválidas", e);
        }
    }

    static List<String> read(String json) {
        try {
            return List.of(MAPPER.readValue(json, String[].class));
        } catch (IOException e) {
            throw new IllegalStateException("dependências de tarefa inválidas", e);
        }
    }
}
