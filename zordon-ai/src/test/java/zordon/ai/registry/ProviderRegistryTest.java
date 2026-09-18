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
package zordon.ai.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.ai.ModelPolicy;
import zordon.ai.ModelRole;
import zordon.ai.Pricing;
import zordon.api.trace.AcceptanceCriteria;

class ProviderRegistryTest {

    @AcceptanceCriteria("SPEC-004/CA-1")
    @Test
    void comAChaveNoAmbienteOPadraoFicaPronto() {
        var registry = ProviderRegistry.build(
                AiSettings.defaults(), Pricing.defaults(), Map.of("ANTHROPIC_API_KEY", "chave-de-teste"));

        assertThat(registry.select(ModelRole.CONVERSATION))
                .isInstanceOfSatisfying(ProviderRegistry.Resolution.Selected.class, selected -> {
                    assertThat(selected.selection().providerId()).isEqualTo("anthropic");
                    assertThat(selected.selection().choice().model()).isEqualTo("claude-opus-5");
                });
    }

    @Test
    void semAChaveOMotivoDizQualVariavelDefinir() {
        var registry = ProviderRegistry.build(AiSettings.defaults(), Pricing.defaults(), Map.of());

        assertThat(registry.select(ModelRole.CONVERSATION))
                .isInstanceOfSatisfying(ProviderRegistry.Resolution.Unresolved.class, unresolved -> {
                    assertThat(unresolved.providerId()).isEqualTo("anthropic");
                    assertThat(unresolved.reason()).contains("ANTHROPIC_API_KEY").contains("secrets.env");
                });
    }

    @Test
    void papelQueApontaParaProviderInexistenteDizIsso() {
        var settings = new AiSettings(Map.of(), Map.of(), new ModelPolicy(Map.of(
                ModelRole.CONVERSATION, new ModelPolicy.ModelChoice("fantasma", "m", null))));

        var resolution = ProviderRegistry.build(settings, Pricing.defaults(), Map.of()).select(ModelRole.CONVERSATION);

        assertThat(resolution).isInstanceOfSatisfying(ProviderRegistry.Resolution.Unresolved.class,
                unresolved -> assertThat(unresolved.reason()).contains("'fantasma'").contains("não existe"));
    }

    @Test
    void papelSemConfiguracaoDizQualPapelFalta() {
        var registry = ProviderRegistry.build(
                new AiSettings(Map.of(), Map.of(), new ModelPolicy(Map.of())), Pricing.defaults(), Map.of());

        assertThat(registry.select(ModelRole.FALLBACK))
                .isInstanceOfSatisfying(ProviderRegistry.Resolution.Unresolved.class,
                        unresolved -> assertThat(unresolved.reason()).contains("'fallback'"));
    }

    @AcceptanceCriteria("SPEC-004/CA-4")
    @Test
    void servidorLocalSemChaveFicaProntoEMarcadoComoLocal() {
        var ollama = new ProviderConfig("ollama", ProviderType.OPENAI_COMPATIBLE,
                URI.create("http://127.0.0.1:11434/v1"), null, null);
        var settings = new AiSettings(Map.of("ollama", ollama), Map.of(), new ModelPolicy(Map.of(
                ModelRole.CONVERSATION, new ModelPolicy.ModelChoice("ollama", "qwen2.5:7b", null))));

        var registry = ProviderRegistry.build(settings, Pricing.defaults(), Map.of());

        assertThat(registry.describe()).containsEntry("ollama", "configurado (local)");
    }
}
