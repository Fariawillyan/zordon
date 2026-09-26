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

/** A voz: o modo, o dispositivo, o teste do microfone e a escuta (SPEC-006 a SPEC-011). */
public interface ShellVoiceActions {

    /** {@code voice.setMode}; {@code mode} é {@code off}, {@code wake}, {@code push} ou {@code open}. */
    void setVoiceMode(String mode);

    void loadVoiceDevices();

    void selectVoiceDevice(String deviceId);

    /** {@code voice.testMicrophone} por 5 s (SPEC-009). */
    void testMicrophone();

    /** Começa uma escuta pedida pelo usuário (SPEC-011). */
    void startListening();

    /** Encerra a escuta em curso. */
    void stopListening();
}
