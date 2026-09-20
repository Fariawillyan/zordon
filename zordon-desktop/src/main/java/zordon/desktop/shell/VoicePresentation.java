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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import zordon.api.trace.Spec;

/**
 * O que o cabeçalho e a tela de Voz dizem sobre o microfone. Puro, para ser
 * testado sem toolkit.
 *
 * <p>Regra de SPEC-006 §5: com um host conectado, "desligado" só aparece depois
 * de o host confirmar. Antes disso é "Desligando microfone…" ou "Captura não
 * confirmada".
 */
@Spec("SPEC-006")
public final class VoicePresentation {

    public static final String TURN_OFF = "Desligar microfone";
    public static final String TESTING = "Testando microfone…";
    public static final String TEST_BUTTON = "Testar microfone (5 s)";

    /** O cartão de teste do microfone (SPEC-009 §3), já em texto. */
    public record TestCard(boolean enabled, String hint, String result, boolean resultOk) {}
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** O que a tela mostra, já em texto. */
    public record Screen(
            String desired, String effective, String reason, String capture, String host, String device, String engine) {}

    private VoicePresentation() {}

    public static String modeLabel(String mode) {
        return switch (mode) {
            case "off" -> "Desligada";
            case "wake" -> "Aguardar “Zordon”";
            case "push" -> "Por atalho";
            case "open" -> "Conversa aberta (5 min)";
            default -> mode;
        };
    }

    public static String headerLabel(VoiceStatus voice, ZoneId zone) {
        if (voice == null) {
            return DesktopState.VOICE_UNAVAILABLE;
        }
        if (voice.hostConnected()) {
            if ("pending".equals(voice.capture())) {
                return Boolean.TRUE.equals(voice.requested()) ? "Ligando microfone…" : "Desligando microfone…";
            }
            if ("unknown".equals(voice.capture())) {
                return "Captura não confirmada";
            }
            if (voice.testing()) {
                return TESTING;
            }
        }
        return switch (voice.effective()) {
            case "off" -> "Voz desligada";
            case "wake", "push", "open" -> active(voice, zone);
            default -> DesktopState.VOICE_UNAVAILABLE;
        };
    }

    public static String headerHint(VoiceStatus voice) {
        if (voice == null) {
            return "Estado da voz desconhecido enquanto o núcleo não responde.";
        }
        if (voice.reason() != null) {
            return voice.reason();
        }
        return voice.captureOn() ? "O microfone está ligado." : "Nada está sendo captado.";
    }

    /**
     * Repouso (SPEC-010): núcleo conectado e voz desligada, com o microfone
     * confirmado desligado ou sem host. Só em repouso a pílula some.
     */
    public static boolean atRest(zordon.zwp.CoreConnection.State connection, VoiceStatus voice, ZoneId zone) {
        return connection == zordon.zwp.CoreConnection.State.ONLINE
                && voice != null
                && "Voz desligada".equals(headerLabel(voice, zone));
    }

    /** O texto da pílula: o núcleo, se ele não estiver conectado; senão, a voz. */
    public static String pillText(zordon.zwp.CoreConnection.State connection, VoiceStatus voice, ZoneId zone) {
        return switch (connection) {
            case OFFLINE -> "Núcleo offline — reconectando";
            case CONNECTING -> "Conectando ao núcleo…";
            case ONLINE -> headerLabel(voice, zone);
        };
    }

    /** "Desligar microfone" aparece sempre que o host confirmou captura ligada (Layout §5). */
    public static boolean offersTurnOff(VoiceStatus voice) {
        return voice != null && voice.captureOn();
    }

    /** Botão, dica e o resultado do último teste. Sem host, não há o que testar. */
    public static TestCard testCard(VoiceStatus voice) {
        if (voice == null) {
            return new TestCard(false, "O núcleo não está respondendo.", "", false);
        }
        if (!voice.hostConnected()) {
            return new TestCard(false, "Sem o host do Windows (zordon-host) não há microfone para testar.", result(voice), false);
        }
        if (voice.testing()) {
            return new TestCard(false, "Fale normalmente por alguns segundos.", "", false);
        }
        VoiceStatus.LastTest last = voice.lastTest();
        return new TestCard(true, "Liga o microfone por 5 s e mostra o nível que chega ao núcleo.", result(voice),
                last != null && "ok".equals(last.verdict()));
    }

    private static String result(VoiceStatus voice) {
        VoiceStatus.LastTest last = voice.lastTest();
        if (last == null) {
            return "";
        }
        if ("failed".equals(last.verdict())) {
            return "✗  Não deu para testar: " + last.message();
        }
        String levels = last.peakDbfs() == null ? "" : " · pico " + dbfs(last.peakDbfs())
                + " · média " + dbfs(last.averageDbfs());
        return ("ok".equals(last.verdict()) ? "✓  " : "!  ") + capitalized(last.message()) + levels;
    }

    /** Medidor de 0 a 1 a partir do RMS em dBFS: -60 dBFS é o fundo, 0 é o topo. */
    public static double meter(double rmsDbfs) {
        return Math.max(0, Math.min(1, (rmsDbfs + 60) / 60));
    }

    public static String dbfs(Double value) {
        return value == null ? "—" : String.format(java.util.Locale.of("pt", "BR"), "%.1f dBFS", value);
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public static Screen screen(VoiceStatus voice, ZoneId zone) {
        return new Screen(
                modeLabel(voice.mode()),
                effectiveLabel(voice, zone),
                voice.reason() == null ? "" : voice.reason(),
                captureLabel(voice, zone),
                voice.hostConnected() ? "Conectado" : "Não conectado",
                voice.device() != null ? voice.device() : voice.hostConnected() ? "Padrão do Windows" : "—",
                engineLabel(voice));
    }

    private static String active(VoiceStatus voice, ZoneId zone) {
        if ("listening".equals(voice.activity())) {
            return "Ouvindo comando";
        }
        return switch (voice.effective()) {
            case "open" -> voice.openUntil() == null
                    ? "Conversa aberta"
                    : "Conversa aberta até " + HOUR.format(voice.openUntil().atZone(zone));
            case "push" -> "Voz por atalho";
            default -> "Aguardando “Zordon”";
        };
    }

    private static String effectiveLabel(VoiceStatus voice, ZoneId zone) {
        return switch (voice.effective()) {
            case "off" -> "Desligada";
            case "unavailable" -> "Indisponível";
            default -> active(voice, zone);
        };
    }

    private static String captureLabel(VoiceStatus voice, ZoneId zone) {
        if (!voice.hostConnected()) {
            // Dizer a causa sem dizer o conserto deixa o usuário sabendo que está
            // quebrado e não como arrumar. O host não sobe sozinho até a tarefa
            // agendada ser registrada (quickstart §6).
            return "O Zordon não controla o microfone agora: host do Windows não conectado."
                    + " Rode packaging/windows/install-host.sh no WSL; para ele subir sozinho no logon,"
                    + " registre a tarefa com packaging/windows/register-tasks.ps1.";
        }
        String at = voice.confirmedAt() == null ? "" : " às " + SECOND.format(voice.confirmedAt().atZone(zone));
        return switch (voice.capture()) {
            case "on" -> "Microfone ligado — confirmado pelo host" + at;
            case "off" -> "Microfone desligado — confirmado pelo host" + at;
            case "pending" -> Boolean.TRUE.equals(voice.requested()) ? "Ligando microfone…" : "Desligando microfone…";
            default -> "Captura não confirmada pelo host";
        };
    }

    private static String engineLabel(VoiceStatus voice) {
        String state = switch (voice.engine()) {
            case "ready" -> "Pronto";
            case "starting" -> "Iniciando…";
            case "failed" -> "Falhou";
            default -> "Não instalado";
        };
        return voice.engineReason() == null || "absent".equals(voice.engine())
                ? state
                : state + " — " + voice.engineReason();
    }
}
