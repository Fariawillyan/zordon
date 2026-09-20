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
package zordon.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

class IntentRouterTest {

    private final IntentRouter router =
            new IntentRouter(Clock.fixed(Instant.parse("2026-09-17T22:31:04Z"), ZoneId.of("UTC")));

    @AcceptanceCriteria("SPEC-003/CA-6")
    @Test
    void perguntaDeHoraEhRespondidaSemChamarModelo() {
        assertThat(router.route("que horas são?"))
                .isInstanceOfSatisfying(Intent.Immediate.class,
                        immediate -> assertThat(immediate.answer()).isEqualTo("São 22h31."));
    }

    @AcceptanceCriteria("SPEC-003/CA-6")
    @Test
    void aPalavraDeAtivacaoNaoAtrapalhaOCasamento() {
        assertThat(router.route("Zordon, que horas são")).isInstanceOf(Intent.Immediate.class);
    }

    @AcceptanceCriteria("SPEC-003/CA-7")
    @Test
    void pararCancelaOTurnoEmAndamento() {
        assertThat(router.route("cancela")).isInstanceOf(Intent.CancelCurrent.class);
    }

    @AcceptanceCriteria("SPEC-003/CA-6")
    @Test
    void qualquerOutraCoisaVaiParaOAgenteGeral() {
        // Sem o agente geral, toda intenção não classificada morreria sem resposta.
        assertThat(router.route("me explica como o WSL monta o /mnt/c"))
                .isInstanceOfSatisfying(Intent.Model.class,
                        model -> assertThat(model.agentId()).isEqualTo(IntentRouter.GENERAL_AGENT));
    }

    @Test
    void normalizacaoTiraAcentoCaixaEEspacoSobrando() {
        assertThat(IntentRouter.normalize("  Zordon,  QUE   Horas São ")).isEqualTo("que horas sao");
    }

    @zordon.api.trace.AcceptanceCriteria("SPEC-011/CA-8")
    @org.junit.jupiter.api.Test
    void aTranscricaoComPontoFinalCaiNaRotaDaHora() {
        IntentRouter router = new IntentRouter();

        org.assertj.core.api.Assertions.assertThat(router.route("Que horas são."))
                .isInstanceOf(Intent.Immediate.class);
        org.assertj.core.api.Assertions.assertThat(router.route("Zordon, que horas são?"))
                .isInstanceOf(Intent.Immediate.class);
        org.assertj.core.api.Assertions.assertThat(router.route("Qual é a data de hoje."))
                .isInstanceOf(Intent.Model.class);
    }
}
