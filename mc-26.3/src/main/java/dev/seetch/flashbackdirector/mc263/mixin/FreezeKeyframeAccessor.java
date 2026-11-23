package dev.seetch.flashbackdirector.mc263.mixin;

import com.moulberry.flashback.keyframe.impl.FreezeKeyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FreezeKeyframe.class, remap = false)
public interface FreezeKeyframeAccessor {
    @Accessor("frozen")
    boolean flashbackDirector$isFrozen();

    @Accessor("frozenDelay")
    int flashbackDirector$getFrozenDelay();
}
