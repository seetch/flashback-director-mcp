package dev.seetch.flashbackdirector.mc262;

import dev.seetch.flashbackdirector.fabric.DirectorBridgeLifecycle;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Fabric client entrypoint for Minecraft 26.2.
 */
public final class FlashbackDirectorMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        DirectorBridgeLifecycle lifecycle = DirectorBridgeLifecycle.init(new FlashbackVersionBridge262());

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> lifecycle.stop());

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof TitleScreen) && !(screen instanceof PauseScreen)) {
                return;
            }
            int buttonWidth = 72;
            int buttonHeight = 20;
            int x = scaledWidth - buttonWidth - 6;
            int y = 6;
            Button toggle = Button.builder(Component.literal(lifecycle.buttonLabel()), btn -> {
                lifecycle.toggle();
                btn.setMessage(Component.literal(lifecycle.buttonLabel()));
            }).bounds(x, y, buttonWidth, buttonHeight).build();
            Screens.getWidgets(screen).add(toggle);
        });
    }
}
