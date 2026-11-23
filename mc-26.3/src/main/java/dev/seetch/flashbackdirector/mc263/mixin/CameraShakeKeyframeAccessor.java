package dev.seetch.flashbackdirector.mc263.mixin;

import com.moulberry.flashback.keyframe.impl.CameraShakeKeyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CameraShakeKeyframe.class, remap = false)
public interface CameraShakeKeyframeAccessor {
    @Accessor("frequencyX")
    float flashbackDirector$getFrequencyX();

    @Accessor("amplitudeX")
    float flashbackDirector$getAmplitudeX();

    @Accessor("frequencyY")
    float flashbackDirector$getFrequencyY();

    @Accessor("amplitudeY")
    float flashbackDirector$getAmplitudeY();

    @Accessor("splitParams")
    boolean flashbackDirector$isSplitParams();
}
