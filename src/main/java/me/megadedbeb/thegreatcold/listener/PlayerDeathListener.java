package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.freeze.FreezeManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class PlayerDeathListener implements Listener {
    private final FreezeManager freezeManager;

    public PlayerDeathListener(FreezeManager freezeManager) {
        this.freezeManager = freezeManager;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        freezeManager.onDeath(event.getEntity().getUniqueId());
    }
}
