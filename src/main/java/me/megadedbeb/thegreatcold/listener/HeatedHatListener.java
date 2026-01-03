package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.inventory.InventoryType;

import java.util.Map;

public class HeatedHatListener implements Listener {
    private final TheGreatColdPlugin plugin;
    private final org.bukkit.NamespacedKey hatKey;
    private final org.bukkit.NamespacedKey hatDurKey;

    public HeatedHatListener(TheGreatColdPlugin plugin) {
        this.plugin = plugin;
        this.hatKey = plugin.getHeatedHatKey();
        this.hatDurKey = plugin.getHeatedHatDurKey();
    }

    private boolean isHeatedHat(ItemStack s) {
        if (s == null) return false;
        ItemMeta m = s.getItemMeta();
        if (m == null) return false;
        String v = m.getPersistentDataContainer().get(hatKey, PersistentDataType.STRING);
        return v != null && v.equals("true");
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent ev) {
        ItemStack result = ev.getInventory().getResult();
        if (result == null) return;

        boolean anyHeated = false;
        for (ItemStack s : ev.getInventory().getMatrix()) {
            if (isHeatedHat(s)) { anyHeated = true; break; }
        }
        if (!anyHeated) return;

        Material rmat = result.getType();
        if (rmat == Material.COPPER_HELMET || isHeatedHat(result)) {
            ev.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent ev) {
        AnvilInventory inv = ev.getInventory();
        ItemStack first = inv.getItem(0);
        ItemStack second = inv.getItem(1);
        if (isHeatedHat(first) || isHeatedHat(second)) {
            ev.setResult(null);
        }
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent ev) {
        ItemStack item = ev.getItem();
        if (!isHeatedHat(item)) return;
        Map<Enchantment, Integer> map = ev.getEnchantsToAdd();
        if (map.containsKey(Enchantment.MENDING)) {
            map.remove(Enchantment.MENDING);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent ev) {
        // Prevent placing heated hat into enchantment table (inventory type ENCHANTING)
        if (ev.getInventory() == null) return;
        if (ev.getInventory().getType() != InventoryType.ENCHANTING) return;

        ItemStack cursor = ev.getCursor();       // item on cursor (when placing)
        ItemStack current = ev.getCurrentItem(); // item in slot
        if (isHeatedHat(cursor) || isHeatedHat(current)) {
            ev.setCancelled(true);
        }
    }
}