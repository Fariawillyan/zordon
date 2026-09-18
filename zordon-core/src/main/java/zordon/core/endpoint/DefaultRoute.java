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
package zordon.core.endpoint;

import java.util.List;
import java.util.Optional;

/**
 * Descobre a interface da rota padrão a partir de {@code /proc/net/route}.
 *
 * <p>"A primeira interface ativa" não serve: numa máquina com Docker, as pontes
 * {@code docker0} e {@code br-*} também estão ativas, e o endereço delas é
 * inalcançável a partir do Windows. A interface por onde a VM sai para o mundo —
 * a da rota padrão — é a que o Windows enxerga.
 */
final class DefaultRoute {

    private static final String ANY_DESTINATION = "00000000";

    private DefaultRoute() {}

    /** Nome da interface da rota padrão, dado o conteúdo de {@code /proc/net/route}. */
    static Optional<String> interfaceName(List<String> routeTable) {
        return routeTable.stream()
                .skip(1)
                .map(line -> line.trim().split("\\s+"))
                .filter(columns -> columns.length > 1 && ANY_DESTINATION.equals(columns[1]))
                .map(columns -> columns[0])
                .findFirst();
    }
}
