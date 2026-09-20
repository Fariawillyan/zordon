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

import java.util.List;

/**
 * A fronteira com o áudio do sistema. Falsa nos testes; {@link JavaSoundSystem}
 * em produção.
 */
public interface SoundSystem {

    /** Id do dispositivo padrão do Windows. */
    String DEFAULT = "default";

    /** @param isDefault o padrão do Windows, que é também o de {@link #DEFAULT}. */
    record Device(String id, String name, boolean isDefault) {}

    /** Linha de captura aberta e iniciada. */
    interface CaptureLine extends AutoCloseable {

        /** Bloqueia até encher o buffer; menos que isso (ou -1) quando a linha fecha. */
        int read(byte[] buffer);

        @Override
        void close();
    }

    /** Dispositivos que capturam no formato do Zordon, com {@link #DEFAULT} primeiro. */
    List<Device> devices();

    /** Abre e inicia a captura. Lança com uma mensagem legível se não conseguir. */
    CaptureLine open(String deviceId) throws Exception;

    /** Saída de áudio aberta: a fala do Zordon (SPEC-011). */
    interface PlaybackLine extends AutoCloseable {

        /** Bloqueia enquanto o buffer da placa estiver cheio. */
        void write(byte[] pcm);

        /** Espera tocar o que foi escrito. */
        void drain();

        @Override
        void close();
    }

    /** Abre a saída padrão do Windows em PCM s16le mono na taxa pedida. */
    PlaybackLine openPlayback(int rate) throws Exception;
}
