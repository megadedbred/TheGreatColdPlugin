package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.stage.StageManager;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.World;

public class EntitySpawnListener implements Listener {
    private final StageManager stageManager;
    public EntitySpawnListener(StageManager stageManager) { this.stageManager = stageManager; }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!event.getEntity().getWorld().getName().equals("world")) return;

        if (event.getEntityType() == EntityType.SKELETON) {
            Skeleton skel = (Skeleton)event.getEntity();
            // Только на поверхности ночью и под открытым небом!
            if (skel.getWorld().getTime() > 13000 && skel.getWorld().getTime() < 23000 &&
                    skel.getLocation().getBlock().getLightFromSky() == 15) {
                // Сравнить с уровнем поверхности, можно уточнить условия
                event.setCancelled(true);
                skel.getWorld().spawnEntity(skel.getLocation(), EntityType.STRAY);
            }
        }
    }
}
