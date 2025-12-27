package me.megadedbeb.thegreatcold.heat;

import org.bukkit.Location;

public class HeatSourceRegion {
    private final Location center;
    private final HeatSourceType type;
    private final int radius;

    public HeatSourceRegion(Location center, HeatSourceType type, int radius) {
        this.center = center.clone();
        // normalize to block coordinates
        this.center.setX(center.getBlockX());
        this.center.setY(center.getBlockY());
        this.center.setZ(center.getBlockZ());
        this.type = type;
        this.radius = radius;
    }

    public Location getCenter() {
        return center;
    }

    public HeatSourceType getType() {
        return type;
    }

    public int getRadius() {
        return radius;
    }

    public boolean isInside(Location loc) {
        if (!loc.getWorld().equals(center.getWorld())) return false;
        return Math.abs(loc.getBlockX() - center.getBlockX()) <= radius &&
                Math.abs(loc.getBlockY() - center.getBlockY()) <= radius &&
                Math.abs(loc.getBlockZ() - center.getBlockZ()) <= radius;
    }
}
