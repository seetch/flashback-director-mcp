package dev.seetch.flashbackdirector.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import dev.seetch.flashbackdirector.core.ToolError;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Captures frames via Minecraft's async Blaze3D Screenshot API (Vulkan-safe, no raw OpenGL).
 */
public final class SafeScreenshotCapturer {

    private static final long CAPTURE_TIMEOUT_MS = 15_000L;

    private SafeScreenshotCapturer() {
    }

    public static Map<String, Object> capture(
        FlashbackVersionBridge fb,
        int requestedWidth,
        int requestedHeight,
        String outputPath,
        Boolean noGui
    ) {
        Minecraft mc = Minecraft.getInstance();
        int targetW = Math.max(160, Math.min(requestedWidth, 3840));
        int targetH = Math.max(90, Math.min(requestedHeight, 2160));

        // Guard against fullscreen before attempting capture (Review Focus #1).
        boolean fullscreen = ClientThreadExecutor.runSync(fb::isFullscreen, 5_000L, "fullscreen check");
        if (fullscreen) {
            throw ToolError.fullscreenUnsupported();
        }

        CompletableFuture<byte[]> rawPngFuture = new CompletableFuture<>();

        try {
            // Schedule async Blaze3D screenshot on the render thread.
            mc.execute(() -> {
                try {
                    Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage image) -> {
                        try (image) {
                            Path tempPng = Files.createTempFile("flashback-director-shot-", ".png");
                            try {
                                image.writeToFile(tempPng);
                                rawPngFuture.complete(Files.readAllBytes(tempPng));
                            } finally {
                                Files.deleteIfExists(tempPng);
                            }
                        } catch (Throwable t) {
                            rawPngFuture.completeExceptionally(t);
                        }
                    });
                } catch (Throwable t) {
                    rawPngFuture.completeExceptionally(t);
                }
            });

            byte[] capturedPng = rawPngFuture.get(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            byte[] finalPng = resizePngIfNeeded(capturedPng, targetW, targetH);

            Path outPath = resolveOutputPath(mc, outputPath);
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            Files.write(outPath, finalPng);

            BufferedImage metaImg = ImageIO.read(new ByteArrayInputStream(finalPng));
            int actualW = metaImg != null ? metaImg.getWidth() : targetW;
            int actualH = metaImg != null ? metaImg.getHeight() : targetH;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", outPath.toAbsolutePath().toString());
            result.put("width", actualW);
            result.put("height", actualH);
            result.put("bytes", finalPng.length);
            result.put("image_base64", Base64.getEncoder().encodeToString(finalPng));
            return result;
        } catch (ToolError e) {
            throw e;
        } catch (Throwable t) {
            throw ToolError.internal("Failed to capture screenshot: " + t.getMessage(), t);
        }
    }

    private static Path resolveOutputPath(Minecraft mc, String outputPath) {
        if (outputPath != null && !outputPath.isBlank()) {
            return Path.of(outputPath);
        }
        Path base = mc.gameDirectory.toPath().resolve("flashback").resolve("director_screenshots");
        return base.resolve("shot_" + Instant.now().toEpochMilli() + ".png");
    }

    private static byte[] resizePngIfNeeded(byte[] pngBytes, int targetW, int targetH) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (src == null || (src.getWidth() == targetW && src.getHeight() == targetH)) {
            return pngBytes;
        }
        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", out);
        return out.toByteArray();
    }
}
