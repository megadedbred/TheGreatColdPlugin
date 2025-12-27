package me.megadedbeb.thegreatcold.heat;

import org.bukkit.Material;

public enum HeatSourceType {
    CAMPFIRE,
    SOUL_CAMPFIRE,
    LAVA,
    MAGMA_BLOCK,
    FIRE,
    FURNACE,
    BLAST_FURNACE,
    SMOKER;

    public static HeatSourceType fromMaterial(Material mat) {
        return switch (mat) {
            case CAMPFIRE -> CAMPFIRE;
            case SOUL_CAMPFIRE -> SOUL_CAMPFIRE;
            case LAVA -> LAVA;
            case MAGMA_BLOCK -> MAGMA_BLOCK;
            case FIRE -> FIRE;
            case FURNACE -> FURNACE;
            case BLAST_FURNACE -> BLAST_FURNACE;
            case SMOKER -> SMOKER;
            default -> null;
        };
    }
}
