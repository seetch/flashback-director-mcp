package dev.seetch.flashbackdirector.fabric;

import dev.seetch.flashbackdirector.core.BridgeDiscoveryFile;
import dev.seetch.flashbackdirector.core.LocalBridgeServer;
import dev.seetch.flashbackdirector.core.ToolDispatcher;

import java.io.IOException;

/**
 * Singleton lifecycle controller for the in-game loopback MCP bridge server and UI toggle button.
 */
public final class DirectorBridgeLifecycle {

    private static DirectorBridgeLifecycle instance;

    private final DirectorModConfig config;
    private final LocalBridgeServer server;

    private DirectorBridgeLifecycle(FlashbackVersionBridge versionBridge) {
        this.config = DirectorModConfig.load();
        FabricDirectorBackend backend = new FabricDirectorBackend(versionBridge);
        ToolDispatcher dispatcher = new ToolDispatcher(backend);
        BridgeDiscoveryFile discovery = new BridgeDiscoveryFile(BridgeDiscoveryFile.defaultPath());
        this.server = new LocalBridgeServer(
            dispatcher,
            discovery,
            versionBridge.modVersion(),
            versionBridge.minecraftVersion()
        );
    }

    public static synchronized DirectorBridgeLifecycle init(FlashbackVersionBridge versionBridge) {
        if (instance == null) {
            instance = new DirectorBridgeLifecycle(versionBridge);
            if (instance.config.autoStart()) {
                instance.startQuietly();
            }
        }
        return instance;
    }

    public static DirectorBridgeLifecycle get() {
        return instance;
    }

    public synchronized boolean toggle() {
        if (server.isRunning()) {
            server.stop();
            config.setAutoStart(false);
            config.save();
            return false;
        }
        try {
            server.start();
            config.setAutoStart(true);
            config.save();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized void startQuietly() {
        try {
            server.start();
        } catch (IOException ignored) {
        }
    }

    public synchronized void stop() {
        server.stop();
    }

    public boolean isRunning() {
        return server.isRunning();
    }

    public String buttonLabel() {
        return server.isRunning() ? "MCP: ON" : "MCP: OFF";
    }
}
