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
package zordon.desktop.shell;

import java.util.List;
import java.util.Map;

/** Um microfone que o host oferece, de {@code voice.devices}. */
public record VoiceDevice(String id, String name, boolean isDefault, boolean selected) {

    static List<VoiceDevice> listFrom(Map<String, Object> result) {
        Object selected = result.get("selected");
        if (!(result.get("devices") instanceof List<?> devices)) {
            return List.of();
        }
        return devices.stream()
                .filter(Map.class::isInstance)
                .map(device -> (Map<?, ?>) device)
                .map(device -> new VoiceDevice(
                        String.valueOf(device.get("id")),
                        String.valueOf(device.get("name") != null ? device.get("name") : device.get("id")),
                        Boolean.TRUE.equals(device.get("default")),
                        String.valueOf(device.get("id")).equals(selected)))
                .toList();
    }

    @Override
    public String toString() {
        return isDefault ? name + " (padrão)" : name;
    }
}
