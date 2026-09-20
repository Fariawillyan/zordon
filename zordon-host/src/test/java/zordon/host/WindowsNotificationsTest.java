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
package zordon.host;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

class WindowsNotificationsTest {
    @Test @AcceptanceCriteria("SPEC-025/CA-3")
    void entregaNoHostSemJanelaPelaBandejaInjetada() {
        var shown = new ArrayList<String>();
        WindowsNotifications notifications = new WindowsNotifications((title, body, severity) -> {
            shown.add(title + ":" + body + ":" + severity);
            return true;
        });
        assertThat(notifications.notify(Map.of("title", "Container caiu", "body", "api: 137", "severity", "warning")))
                .containsEntry("shown", true);
        assertThat(shown).containsExactly("Container caiu:api: 137:warning");
        assertThat(notifications.notify(Map.of("body", "sem título"))).containsEntry("shown", false);
    }
}
