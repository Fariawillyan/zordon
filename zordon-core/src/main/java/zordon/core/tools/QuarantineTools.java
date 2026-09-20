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
package zordon.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.Spec;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;
import zordon.security.vault.QuarantineVault;

/** {@code fs.quarantine} e {@code fs.restore} (SPEC-017): o "apagar" que dá para desfazer. */
@Spec("SPEC-017")
public final class QuarantineTools {

    private QuarantineTools() {}

    public static Tool quarantine(PathPolicy paths, ZPath base, QuarantineVault vault) {
        return new Tool() {
            @Override public String name() { return "fs.quarantine"; }
            @Override public String description() { return "Move arquivos para a quarentena; dá para restaurar."; }
            @Override public Map<String, Object> inputSchema() { return Tool.schema("path", "arquivo ou pasta"); }
            @Override public RiskLevel baseRisk() { return RiskLevel.RED; }
            @Override public Set<Effect> effects() { return Set.of(Effect.QUARANTINE_FS); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                ZPath target = FileTools.resolve(paths, base, args);
                Path local = Path.of(target.toWsl());
                if (!Files.exists(local)) {
                    throw new ToolException("não existe " + target);
                }
                List<Path> files;
                try {
                    files = QuarantineVault.filesUnder(local);
                } catch (IOException e) {
                    throw new ToolException(e.getMessage());
                }
                if (files.isEmpty()) {
                    throw new ToolException("não há arquivos em " + target);
                }
                // Os alvos concretos vão para o diálogo (SPEC-017 CA-3); a tela mostra os 50 primeiros e o total.
                List<ZPath> touched = files.stream().map(file -> ZPath.ofWsl(file.toString()))
                        .map(path -> target.origin() == ZPath.Origin.WINDOWS && path.canonical().matches("/mnt/[a-z]/.*")
                                ? ZPath.ofWindows(Character.toUpperCase(path.canonical().charAt(5)) + ":"
                                        + path.canonical().substring(6))
                                : path)
                        .toList();
                String what = files.size() == 1 ? "1 arquivo" : files.size() + " arquivos";
                return new ActionDescriptor(name(), Map.of("path", target.toString(), "files", files.size()),
                        baseRisk(), effects(), touched, files.size(), List.of(),
                        "Mover " + what + " de " + target + " para a quarentena (reversível)");
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                List<Path> files = permit.action().touchedPaths().stream().map(path -> Path.of(path.toWsl())).toList();
                String root = String.valueOf(permit.action().args().get("path")).replace('\\', '/');
                String reason = args.get("reason") instanceof String text && !text.isBlank() ? text
                        : "pedido do usuário";
                QuarantineVault.Item item = vault.store(files, Path.of(root), reason);
                String text = "Movi " + (item.files() == 1 ? "1 arquivo" : item.files() + " arquivos")
                        + " para a quarentena. Para desfazer: restaurar " + item.vaultId() + "."
                        + (item.notes().isEmpty() ? "" : " Atenção: " + String.join("; ", item.notes()) + ".");
                return new ToolResult(text, Map.of("vaultId", item.vaultId(), "files", item.files(), "root", root));
            }
        };
    }

    public static Tool restore(PathPolicy paths, QuarantineVault vault) {
        return new Tool() {
            @Override public String name() { return "fs.restore"; }
            @Override public String description() { return "Devolve um item da quarentena ao lugar de origem."; }
            @Override public Map<String, Object> inputSchema() { return Tool.schema("vaultId", "id do item (q-…)"); }
            @Override public RiskLevel baseRisk() { return RiskLevel.YELLOW; }
            @Override public Set<Effect> effects() { return Set.of(Effect.WRITE_FS); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                String vaultId = Tool.text(args, "vaultId");
                QuarantineVault.Item item;
                try {
                    item = vault.get(vaultId);
                } catch (IOException e) {
                    throw new ToolException(e.getMessage());
                }
                if (item.restored()) {
                    throw new ToolException("o item " + vaultId + " já foi restaurado");
                }
                ZPath destination = item.root().matches("^[A-Za-z]:.*") ? ZPath.ofWindows(item.root())
                        : ZPath.ofWsl(item.root());
                return new ActionDescriptor(name(), Map.of("vaultId", vaultId), baseRisk(), effects(),
                        List.of(destination), item.files(), List.of(),
                        "Devolver " + item.files() + (item.files() == 1 ? " arquivo" : " arquivos")
                                + " da quarentena para " + destination);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                QuarantineVault.Item item = vault.restore(String.valueOf(permit.action().args().get("vaultId")));
                return ToolResult.of(item.restored() ? "Restaurei " + item.files() + " arquivos."
                        : "Restaurei em parte; ficaram no cofre: " + String.join("; ", item.notes()) + ".");
            }
        };
    }
}
