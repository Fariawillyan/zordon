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
package zordon.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

/**
 * O contrato da instalação do motor de voz (SPEC-011 CA-10): a CI não instala o
 * venv de 480 MB nem baixa 550 MB de modelos; o que dá para garantir aqui é que
 * os arquivos exigem hash e conferem SHA-256.
 */
class VoicePackagingTest {

    private static final Path REPO = Path.of(System.getProperty("zordon.repoRoot", ".."));

    /**
     * O modelo versionado é o do relatório: o relatório guarda o SHA-256 do
     * {@code .npz}, e o {@code models.lock} instala esse mesmo arquivo. Os números
     * do CA-1 da SPEC-013 ficam no relatório; hoje eles não atingem a meta, e a
     * SPEC registra isso (sem este teste fingir o contrário).
     */
    @Test
    void modeloDaPalavraVersionadoEOMesmoDoRelatorioEDoLock() throws Exception {
        Map<?, ?> metrics = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(REPO.resolve("voice/training/report-zordon-wake-v1.json").toFile(), Map.class);
        assertThat(metrics.keySet().stream().map(String::valueOf).toList())
                .contains("threshold", "recall", "falseActivationsMls", "mlsTestHours");
        assertThat(((Number) metrics.get("mlsTestHours")).doubleValue()).isGreaterThanOrEqualTo(10);

        byte[] model = Files.readAllBytes(REPO.resolve("voice/models/zordon-wake-v1.npz"));
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(model));
        assertThat(metrics.get("sha256")).isEqualTo(sha);
        assertThat(Files.readAllLines(REPO.resolve("voice/models.lock")))
                .contains("wake/zordon-wake-v1.npz\trepo:voice/models/zordon-wake-v1.npz\t" + sha + "\t" + model.length);
    }

    @AcceptanceCriteria("SPEC-011/CA-10")
    @Test
    void dependenciasSoComHashEModelosComRevisaoFixaESha256() throws Exception {
        List<String> lines = Files.readAllLines(REPO.resolve("voice/requirements.lock"));
        List<String> requirements = lines.stream().filter(line -> line.matches("^[A-Za-z0-9_.-]+==.*")).toList();
        assertThat(requirements).hasSize(25).anyMatch(line -> line.startsWith("faster-whisper==1.2.1"))
                .anyMatch(line -> line.startsWith("piper-tts==1.8.0"));
        // Cada requisito é seguido do seu hash: sem hash, o pip --require-hashes recusa a instalação.
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^[A-Za-z0-9_.-]+==.*")) {
                assertThat(lines.get(i + 1)).as(lines.get(i)).matches("\\s+--hash=sha256:[0-9a-f]{64}.*");
            }
        }

        List<String> models = Files.readAllLines(REPO.resolve("voice/models.lock")).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertThat(models).hasSize(9).allSatisfy(line -> {
            String[] fields = line.split("\\t");
            // Revisão fixa: commit do Hugging Face, release com versão do GitHub ou arquivo do próprio repositório.
            assertThat(fields[1]).matches("https://huggingface\\.co/.+/resolve/[0-9a-f]{40}/.+"
                    + "|https://github\\.com/.+/releases/download/v[0-9.]+/.+|repo:voice/models/.+");
            assertThat(fields[2]).matches("[0-9a-f]{64}");
        });

        String install = Files.readString(REPO.resolve("packaging/wsl/install-voice.sh"));
        assertThat(install).contains("--require-hashes").contains("sha256sum").contains(".part");
    }

    @AcceptanceCriteria("SPEC-011/CA-10")
    @Test
    void aUnitMorreComONucleoEOSocketESoDoUsuario() throws Exception {
        String unit = Files.readString(REPO.resolve("packaging/wsl/zordon-voice.service.template"));

        assertThat(unit).contains("PartOf=zordon.service")
                .contains("RuntimeDirectory=zordon-voice")
                .contains("RuntimeDirectoryMode=0700")
                .contains("ZORDON_VOICE_SOCKET=/run/zordon-voice/voice.sock")
                .contains("Restart=always");
        assertThat(Files.readString(REPO.resolve("packaging/wsl/zordon.service.template")))
                .contains("ZORDON_VOICE_SOCKET=/run/zordon-voice/voice.sock");
    }
}
