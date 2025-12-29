package me.megadedbeb.thegreatcold.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.PortalCreateEvent;

/**
 * Всегда блокируем создание портала в ад.
 * Работает для любых этапов и всегда (как вы просили).
 */
public class PortalCreateListener implements Listener {
    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        // отменяем создание портала (включая поджиг рамки)
        event.setCancelled(true);
    }
}
