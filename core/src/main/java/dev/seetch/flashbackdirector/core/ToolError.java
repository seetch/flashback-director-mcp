package dev.seetch.flashbackdirector.core;

/**
 * Structured error returned to the MCP client with a stable machine-readable code.
 */
public final class ToolError extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final String code;

    public ToolError(String code, String message) {
        super(message);
        this.code = code;
    }

    public ToolError(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ToolError invalidParams(String message) {
        return new ToolError("INVALID_PARAMS", message);
    }

    public static ToolError notInReplay(String message) {
        return new ToolError("NOT_IN_REPLAY", message);
    }

    public static ToolError fullscreenUnsupported() {
        return new ToolError(
            "FULLSCREEN_UNSUPPORTED",
            "Screenshots and video export are not available in fullscreen mode. Press F11 in Minecraft to switch to windowed mode and retry."
        );
    }

    public static ToolError timeout(String message) {
        return new ToolError("TIMEOUT", message);
    }

    public static ToolError internal(String message, Throwable cause) {
        return new ToolError("INTERNAL_ERROR", message, cause);
    }
}
