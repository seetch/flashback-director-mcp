package dev.seetch.flashbackdirector.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages the ~/.flashback-director-mcp/bridge.json discovery file with owner-only permissions.
 */
public final class BridgeDiscoveryFile {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final Path filePath;

    public BridgeDiscoveryFile(Path filePath) {
        this.filePath = filePath;
    }

    public static Path defaultPath() {
        String override = System.getenv("FLASHBACK_DIRECTOR_BRIDGE_FILE");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".flashback-director-mcp", "bridge.json");
    }

    public static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public Path path() {
        return filePath;
    }

    public void write(int port, String token, String modVersion, String minecraftVersion) throws IOException {
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            restrictToOwner(parent);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("host", "127.0.0.1");
        payload.put("port", port);
        payload.put("token", token);
        payload.put("pid", ProcessHandle.current().pid());
        payload.put("mod_version", modVersion);
        payload.put("minecraft_version", minecraftVersion);

        Path temp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        Files.writeString(temp, MiniJson.stringify(payload) + "\n", StandardCharsets.UTF_8);
        restrictToOwner(temp);
        Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        restrictToOwner(filePath);
    }

    public void deleteQuietly() {
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException ignored) {
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> perms = Files.isDirectory(path)
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, perms);
                return;
            }
            AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (aclView != null) {
                UserPrincipal owner = Files.getOwner(path);
                AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
                aclView.setAcl(List.of(entry));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
