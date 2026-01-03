package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.heat.CustomHeatManager;
import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import me.megadedbeb.thegreatcold.util.NmsHelper;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Контролирует размножение животных:
 * - Если игрок пытается покормить животное (вход в love mode) на этапах >=2 и животное
 *   находится вне зоны тепла и под открытым небом — корм съедается, но животное НЕ входит в love mode.
 * - Также отменяет EntityBreedEvent при тех же условиях (страховка).
 *
 * Исключения (не блокируем): GOAT, FOX, NAUTILUS.
 */
public class AnimalBreedListener implements Listener {
    private final HeatSourceManager heatManager;
    private final CustomHeatManager customHeatManager;

    // Список породимых типов, которые мы контролируем (многие породы покрываются)
    // Мы также используем метод isBreedingItem для выявления подходящего корма.
    private static final Set<EntityType> EXCLUDED = Set.of(EntityType.GOAT, EntityType.FOX, EntityType.DROWNED /* placeholder for nautilus check */);

    public AnimalBreedListener() {
        this.heatManager = TheGreatColdPlugin.getInstance().getHeatSourceManager();
        this.customHeatManager = TheGreatColdPlugin.getInstance().getCustomHeatManager();
    }

    /**
     * PlayerInteractEntityEvent — этот метод перехватывает попытку игрока покормить животное.
     * Если условие (stage >= 2 && animal вне тепла && под открытым небом && животное породимое и не исключено),
     * то мы отменяем исходное событие (чтобы предотвратить переход в love mode) и вручную потребляем предмет из руки игрока.
     * Таким образом игрок может кормить животных, но они не переходят в любовный режим.
     */
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent ev) {
        if (ev.getRightClicked() == null) return;
        Entity clicked = ev.getRightClicked();

        // We care only about Ageable / Animals or specific breedable types
        if (!(clicked instanceof Ageable) && !(clicked instanceof Animals)) return;

        EntityType type = clicked.getType();
        // Excluded types: do nothing special
        if (type == EntityType.GOAT || type == EntityType.FOX || type == EntityType.NAUTILUS) return;

        // Stage check
        int globalStageId = TheGreatColdPlugin.getInstance().getStageManager().getCurrentStage().id();
        if (globalStageId < 2) return; // no blocking before stage 2

        ItemStack hand = ev.getPlayer().getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) return;

        // If the item is a breeding item for this entity type
        if (!isBreedingItemFor(type, hand.getType())) return;

        // Check heat conditions: if animal is outside heat and under open sky -> block love mode
        boolean rawInHeat = heatManager.isLocationInHeat(clicked.getLocation());
        boolean inCustom = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(clicked.getLocation());
        boolean openToSky = NmsHelper.isOpenToSky(clicked.getLocation().getBlock());
        boolean effectiveInHeat = inCustom || rawInHeat;

        if (!effectiveInHeat && openToSky) {
            // Cancel default interaction (prevents entering love mode) but still consume the item manually
            ev.setCancelled(true);

            // consume one item from player's main hand (respect stack)
            consumeOneFromPlayerMainHand(ev.getPlayer(), hand);
            // do not send messages (per previous requests)
        }
    }

    /**
     * Additional safety: cancel actual breeding/baby spawn if it somehow occurs.
     */
    @EventHandler
    public void onEntityBreed(EntityBreedEvent ev) {
        Entity father = ev.getFather();
        Entity mother = ev.getMother();
        if (father == null || mother == null) return;

        // If either parent is excluded type -> allow
        if (father.getType() == EntityType.GOAT || father.getType() == EntityType.FOX || father.getType() == EntityType.NAUTILUS) return;
        if (mother.getType() == EntityType.GOAT || mother.getType() == EntityType.FOX || mother.getType() == EntityType.NAUTILUS) return;

        int globalStageId = TheGreatColdPlugin.getInstance().getStageManager().getCurrentStage().id();
        if (globalStageId < 2) return;

        // if both parents are under bad conditions (or at least one?), we cancel.
        // Requirement: "if animals at 2 and 3 that are outside heat and under open sky and player begins to feed them, they do not go into love mode"
        // Here as a safety, if either parent is outside heat & open sky, cancel the breed event.
        boolean fatherHot = isEffectivelyInHeat(father);
        boolean motherHot = isEffectivelyInHeat(mother);

        // If either parent is NOT in heat (i.e., outside heat & open sky), cancel breeding
        if (!fatherHot || !motherHot) {
            ev.setCancelled(true);
        }
    }

    private boolean isEffectivelyInHeat(Entity e) {
        boolean rawInHeat = heatManager.isLocationInHeat(e.getLocation());
        boolean inCustom = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(e.getLocation());
        boolean openToSky = NmsHelper.isOpenToSky(e.getLocation().getBlock());
        boolean effectiveInHeat = inCustom || rawInHeat;
        // Effective heat must be true OR not under open sky to be considered safe
        return effectiveInHeat || !openToSky;
    }

    private void consumeOneFromPlayerMainHand(org.bukkit.entity.Player p, ItemStack hand) {
        if (hand == null || hand.getType() == Material.AIR) return;
        int amount = hand.getAmount();
        if (amount <= 1) {
            p.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(amount - 1);
            p.getInventory().setItemInMainHand(hand);
        }
        // play eat sound for feedback
        try {
            p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EAT, 0.0f, 0.0f);
        } catch (Throwable ignored) {}
    }

    /**
     * Heuristic: decides whether a given Material is a breeding item for given EntityType.
     * Covers common breedable mobs. Not exhaustive for mods/very new mobs, but covers Vanilla list.
     */
    private boolean isBreedingItemFor(EntityType type, Material mat) {
        switch (type) {
            case COW, SHEEP, MOOSHROOM -> {
                return mat == Material.WHEAT;
            }
            case PIG -> {
                return mat == Material.CARROT || mat == Material.POTATO || mat == Material.BEETROOT;
            }
            case CHICKEN -> {
                return mat == Material.WHEAT_SEEDS || mat == Material.BEETROOT_SEEDS || mat == Material.MELON_SEEDS || mat == Material.PUMPKIN_SEEDS;
            }
            case RABBIT -> {
                return mat == Material.CARROT || mat == Material.GOLDEN_CARROT || mat == Material.DANDELION;
            }
            case TURTLE -> {
                return mat == Material.SEAGRASS;
            }
            case WOLF -> {
                return mat == Material.BEEF || mat == Material.COOKED_BEEF || mat == Material.CHICKEN || mat == Material.COOKED_CHICKEN
                        || mat == Material.PORKCHOP || mat == Material.COOKED_PORKCHOP || mat == Material.RABBIT || mat == Material.COOKED_RABBIT;
            }
            case CAT -> {
                return mat == Material.COD || mat == Material.SALMON || mat == Material.TROPICAL_FISH || mat == Material.PUFFERFISH;
            }
            case HORSE, DONKEY, MULE, LLAMA -> {
                return mat == Material.GOLDEN_APPLE || mat == Material.GOLDEN_CARROT;
            }
            case FOX -> {
                // excluded earlier, but if reached: foxes breed with sweet berries
                return mat == Material.SWEET_BERRIES;
            }
            case BEE -> {
                return mat == Material.SWEET_BERRIES || mat == Material.POPPY || mat == Material.DANDELION;
            }
            case CAMEL -> {
                return mat == Material.WHEAT; // if present in your server version
            }
            case GOAT -> {
                return mat == Material.WHEAT; // excluded anyway
            }
            case TADPOLE -> {
                return mat == Material.COD || mat == Material.SALMON; // not standard; kept safe
            }
            default -> {
                // Fallback: common breeding items (wheat / seeds / carrot / beetroot / potato / golden carrot / golden apple)
                return mat == Material.WHEAT || mat == Material.WHEAT_SEEDS || mat == Material.CARROT
                        || mat == Material.POTATO || mat == Material.BEETROOT || mat == Material.BEETROOT_SEEDS
                        || mat == Material.GOLDEN_CARROT || mat == Material.GOLDEN_APPLE
                        || mat == Material.MELON_SEEDS || mat == Material.PUMPKIN_SEEDS || mat == Material.SEAGRASS;
            }
        }
    }
}