package dev.seetch.flashbackdirector.fabric;

import dev.seetch.flashbackdirector.core.MiniJson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local JSON config stored at <gameDir>/config/flashback-director-mcp.json.
 * Defaults to autoStart = false so the bridge requires explicit user opt-in on first launch.
 */
public final class DirectorModConfig {

    private static final String FILE_NAME = "flashback-director-mcp.json";

    private boolean autoStart = false;

    public boolean autoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static DirectorModConfig load() {
        DirectorModConfig cfg = new DirectorModConfig();
        Path path = configPath();
        if (!Files.exists(path)) {
            return cfg;
        }
        try {
            Map<String, Object> parsed = MiniJson.parseObject(Files.readString(path, StandardCharsets.UTF_8));
            if (parsed.get("autoStart") instanceof Boolean b) {
                cfg.autoStart = b;
            }
        } catch (IOException | IllegalArgumentException ignored) {
        }
        return cfg;
    }

    public void save() {
        Path path = configPath();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("autoStart", autoStart);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, MiniJson.stringify(data) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
