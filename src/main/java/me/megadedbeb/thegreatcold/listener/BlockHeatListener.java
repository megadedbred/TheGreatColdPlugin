package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class BlockHeatListener implements Listener {
    private final HeatSourceManager heatManager;
    public BlockHeatListener(HeatSourceManager h) { this.heatManager = h; }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        Block block = e.getBlockPlaced();
        // либо регистрируем, либо удаляем в зависимости от состояния
        if (heatManager.providesHeat(block)) heatManager.registerHeatSource(block);
        else heatManager.removeHeatSource(block);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        heatManager.removeHeatSource(block);
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent e) {
        Block block = e.getBlock();
        heatManager.removeHeatSource(block);
    }

    @EventHandler
    public void onBlockPhysics(BlockPhysicsEvent e) {
        Block block = e.getBlock();
        // При обновлениях блока проверяем его текущее состояние
        if (heatManager.providesHeat(block)) heatManager.registerHeatSource(block);
        else heatManager.removeHeatSource(block);
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent e) {
        Block block = e.getBlock();
        if (block != null && heatManager.providesHeat(block)) heatManager.registerHeatSource(block);
    }

    @EventHandler
    public void onBlockFade(BlockFadeEvent e) {
        // при исчезновении/потухании проверим — если блок больше не даёт тепло, удалим его
        Block block = e.getBlock();
        if (block != null && !heatManager.providesHeat(block)) heatManager.removeHeatSource(block);
    }

    @EventHandler
    public void onFurnaceBurn(FurnaceBurnEvent e) {
        Block block = e.getBlock();
        if (block != null && heatManager.providesHeat(block)) heatManager.registerHeatSource(block);
    }

    @EventHandler
    public void onFurnaceExtract(FurnaceExtractEvent e) {
        Block block = e.getBlock();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (block != null && heatManager.providesHeat(block)) heatManager.registerHeatSource(block);
                else heatManager.removeHeatSource(block);
            }
        }.runTaskLater((org.bukkit.plugin.Plugin) heatManager.getPlugin(), 2L);
    }
}