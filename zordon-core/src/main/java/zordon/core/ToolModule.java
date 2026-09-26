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

import java.time.Clock;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.ZPath;
import zordon.core.tools.FileTools;
import zordon.core.tools.GatekeptCliRunner;
import zordon.core.tools.NetTools;
import zordon.core.tools.ProcessTools;
import zordon.core.tools.QuarantineTools;
import zordon.core.tools.SkillRuntime;
import zordon.core.tools.ToolResult;
import zordon.core.tools.WindowsBridge;
import zordon.security.PathPolicy;
import zordon.security.vault.QuarantineVault;

/** A execução mediada (SPEC-016): as ferramentas, a ponte com o Windows, o cofre e o CLI por assinatura. */
final class ToolModule {

    private final ZPath userHome;
    private final PathPolicy policy;
    private final WindowsBridge windows;
    private final QuarantineVault vault;
    private final SkillRuntime tools;

    ToolModule(CoreBase base, TrustModule trust) {
        this.userHome = ZPath.ofWsl(base.environment().getOrDefault("HOME", System.getProperty("user.home")));
        this.policy = base.settings().load().paths();
        this.windows = new WindowsBridge(base.server());
        base.cli().set(new GatekeptCliRunner(trust.gatekeeper(), trust.runner(), base.config().home().resolve("cli-work"),
                program -> base.settings().load().catalog().containsKey(program)));
        this.vault = new QuarantineVault(base.config().home().resolve("quarantine"), Clock.systemUTC());
        this.tools = new SkillRuntime(trust.gatekeeper(), base.bus(), trust.lockdown()::active)
                .register(windows.openTool())
                .register(FileTools.list(policy, userHome))
                .register(FileTools.read(policy, userHome))
                .register(FileTools.write(policy, userHome))
                .register(ProcessTools.metrics())
                .register(NetTools.httpCheck())
                .register(ProcessTools.gitStatus(policy, userHome, trust.runner()))
                .register(ProcessTools.build(policy, userHome, trust.runner()))
                .register(QuarantineTools.quarantine(policy, userHome, vault))
                .register(QuarantineTools.restore(policy, vault));
        // A rota rápida de ferramenta do turno, pelo mesmo caminho mediado e com a origem certa.
        base.turns().hooks().onTool((tool, args, source, turnId) -> tools.invoke(tool, args,
                Principal.user("voice".equals(source) ? RequestOrigin.VOICE : RequestOrigin.UI),
                turnId).thenApply(ToolResult::text));
    }

    ZPath userHome() {
        return userHome;
    }

    PathPolicy policy() {
        return policy;
    }

    WindowsBridge windows() {
        return windows;
    }

    QuarantineVault vault() {
        return vault;
    }

    SkillRuntime tools() {
        return tools;
    }
}
