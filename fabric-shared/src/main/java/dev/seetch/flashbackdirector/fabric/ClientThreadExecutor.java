package dev.seetch.flashbackdirector.fabric;

import dev.seetch.flashbackdirector.core.ToolError;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Safely marshals HTTP worker calls onto the Minecraft client thread with bounded timeouts.
 */
public final class ClientThreadExecutor {

    public static final long DEFAULT_TIMEOUT_MS = 15_000L;
    public static final long LONG_OPERATION_TIMEOUT_MS = 180_000L;

    private ClientThreadExecutor() {
    }

    public static <T> T runSync(Supplier<T> task) {
        return runSync(task, DEFAULT_TIMEOUT_MS, "client task");
    }

    public static <T> T runSync(Supplier<T> task, long timeoutMs, String label) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            throw new ToolError("GAME_NOT_READY", "Minecraft client instance is not initialized.");
        }
        if (mc.isSameThread()) {
            return task.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                if (future.isCancelled()) {
                    return;
                }
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw ToolError.timeout("Timed out waiting for " + label + " on the Minecraft client thread after " + timeoutMs + "ms.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw ToolError.timeout("Interrupted while waiting for " + label + ".");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolError toolError) {
                throw toolError;
            }
            String message = cause == null ? e.getMessage() : (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            throw ToolError.internal("Failed during " + label + ": " + message, cause);
        }
    }
}
