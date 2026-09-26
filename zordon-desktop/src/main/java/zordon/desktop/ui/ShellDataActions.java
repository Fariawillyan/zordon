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

interface ShellDataActions {
    default void loadMcp() {}
    default void approveMcp(String server) {}
    default void loadTasks() {}
    default void confirmStep(String taskId, String stepId, boolean pass) {}
    default void resumeTask(String taskId) {}
    default void loadAutomations() {}
    default void approveAutomation(String proposalId) {}
    default void rejectAutomation(String proposalId) {}
    default void enableAutomation(String id, boolean enabled) {}
    default void runAutomation(String id) {}
    default void loadAgents() {}
    default void loadSkills() {}
    default void loadUsage() {}
    default void loadSystem() {}
}
