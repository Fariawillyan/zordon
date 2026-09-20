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
package zordon.api.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Uma ação proposta, já com argumentos validados e caminhos resolvidos.
 *
 * @param baseRisk o piso declarado pela ferramenta; o motor só sobe
 * @param humanSummary gerado pelo núcleo a partir dos argumentos, nunca pelo modelo
 * @param targets quantos alvos a ação atinge (um glob que casa 43 arquivos conta 43)
 * @param command programa e argumentos, quando a ação executa um processo
 */
public record ActionDescriptor(
        String tool,
        Map<String, Object> args,
        RiskLevel baseRisk,
        Set<Effect> effects,
        List<ZPath> touchedPaths,
        int targets,
        List<String> command,
        String humanSummary) {

    public ActionDescriptor {
        Objects.requireNonNull(tool, "tool");
        args = Map.copyOf(args);
        Objects.requireNonNull(baseRisk, "baseRisk");
        effects = Set.copyOf(effects);
        touchedPaths = List.copyOf(touchedPaths);
        command = List.copyOf(command);
        Objects.requireNonNull(humanSummary, "humanSummary");
    }
}
