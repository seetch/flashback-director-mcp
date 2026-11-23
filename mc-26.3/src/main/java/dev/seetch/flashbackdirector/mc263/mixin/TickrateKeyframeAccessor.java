package dev.seetch.flashbackdirector.mc263.mixin;

import com.moulberry.flashback.keyframe.impl.TickrateKeyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TickrateKeyframe.class, remap = false)
public interface TickrateKeyframeAccessor {
    @Accessor("tickrate")
    float flashbackDirector$getTickrate();
}
