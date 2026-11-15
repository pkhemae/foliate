package dev.foliate.core;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;

/** What the plugin is running on. */
public final class Platform {

    private static final Key FOLIA = Key.key("papermc", "folia");

    private Platform() {}

    /**
     * True on Folia, where regions tick in parallel. A fork of Folia that ships under its own
     * brand is only recognised if it still declares itself compatible with Folia's.
     */
    public static boolean isFolia() {
        return ServerBuildInfo.buildInfo().isBrandCompatible(FOLIA);
    }

    /** Server brand and Minecraft version, for example {@code Folia 26.2}. */
    public static String describe() {
        ServerBuildInfo info = ServerBuildInfo.buildInfo();
        return info.brandName() + " " + info.minecraftVersionId();
    }
}
