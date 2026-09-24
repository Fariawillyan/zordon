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
package zordon.desktop.ui;

import static zordon.desktop.ui.FxTestSupport.onFx;

import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.trace.Spec;
import zordon.desktop.audio.SoundPlayer;

/**
 * O núcleo visual desenha em todo estado e em todo nível de microfone.
 *
 * <p>Um alfa fora de faixa faz {@code Color.web} lançar, e a exceção sobe do
 * {@code AnimationTimer}: o quadro morre no meio e some tudo o que viria depois
 * — o emblema, a moldura e os textos. Só aparecia ouvindo, porque só o
 * microfone empurrava o pulso do anel além de 1. A varredura cobre um período
 * inteiro do pulso, que é onde o valor chega ao topo.
 *
 * <p>Com {@code -Dprobe.out=<pasta>} grava um PNG de cada estado para revisão
 * visual; sem ela, só verifica que nada lança.
 */
@Spec("SPEC-012")
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class VoiceVisualizerLevelsTest {

    private static SoundPlayer player;
    private static VoiceVisualizer visualizer;
    private static Scene scene;

    @BeforeAll static void toolkit() throws Exception {
        FxTestSupport.start();
        player = new SoundPlayer();
        visualizer = onFx(() -> new VoiceVisualizer(player));
        scene = onFx(() -> FxTestSupport.styledScene(new StackPane(visualizer), 1280, 800));
        // Sem uma janela a cena não faz layout, o Canvas fica 0 × 0 e draw()
        // desiste na primeira linha: o teste passaria sem desenhar nada.
        onFx(() -> {
            visualizer.resize(1280, 800);
            visualizer.layout();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-012/CA-6")
    @ParameterizedTest
    @ValueSource(strings = {"idle", "listening", "understanding", "planning",
        "speaking", "executing", "agents", "attention", "error", "done"})
    void desenhaEmQualquerNivel(String state) throws Exception {
        // Do silêncio ao fundo de escala: o pulso do anel só estourava no topo.
        for (double dbfs = -90; dbfs <= 0; dbfs += 7.5) {
            double level = dbfs;
            onFx(() -> {
                visualizer.activity(state);
                for (int i = 0; i < 12; i++) {
                    visualizer.micLevel(new double[] {level, level, level, level, level});
                }
                visualizer.activity(state);
                return null;
            });
            // O pulso completa um período em ~0,6 s; a espera varre a fase.
            Thread.sleep(60);
        }
        String out = System.getProperty("probe.out");
        if (out != null) {
            onFx(() -> {
                FxTestSupport.save(scene, Path.of(out).resolve(state + ".png"));
                return null;
            });
        }
    }
}
