package com.mr_toad.palladium.core;
import com.mr_toad.palladium.common.Deduplicator;
import com.mr_toad.palladium.core.config.ResourceLocationDeduplication;
public final class Palladium {
    public static boolean enabled = true;
    public static Deduplicator<String> PROPERTIES;
    public static boolean isResourceDedup(ResourceLocationDeduplication ignored) { return enabled; }
}
