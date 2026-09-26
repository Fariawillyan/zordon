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

import zordon.desktop.shell.ComposerTarget;

/**
 * Ações que não fazem nada, com todos os grupos no mesmo objeto: o teste
 * sobrescreve só o que quer observar, sem montar um objeto por grupo.
 */
abstract class FakeShellActions
        implements ShellActions, ShellVoiceActions, ShellSecurityActions, ShellMemoryActions, ShellDataActions {

    @Override
    public void send(String text, ComposerTarget target) {}

    @Override
    public void newConversation() {}

    @Override
    public void cancelTurn(String turnId) {}

    @Override
    public void refreshDiagnostics() {}

    @Override
    public void setVoiceMode(String mode) {}

    @Override
    public void loadVoiceDevices() {}

    @Override
    public void selectVoiceDevice(String deviceId) {}

    @Override
    public void testMicrophone() {}

    @Override
    public void startListening() {}

    @Override
    public void stopListening() {}

    @Override
    public ShellVoiceActions voice() {
        return this;
    }

    @Override
    public ShellSecurityActions security() {
        return this;
    }

    @Override
    public ShellMemoryActions memory() {
        return this;
    }

    @Override
    public ShellDataActions data() {
        return this;
    }
}
