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

interface ShellSecurityActions {
    default void pauseZordon() {}
    default void resumeZordon() {}
    default void enterOppressor(char[] password) {}
    default void exitOppressor() {}
    default void setOppressorPassword(char[] current, char[] next) {}
    default void loadQuarantine() {}
    default void restoreQuarantine(String vaultId) {}
    default void loadFindings() {}
    default void acknowledgeFinding(String findingId) {}
    default void releaseBreaker(String subject, String mode) {}
    default void loadSecurityEvents() {}
}
