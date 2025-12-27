package me.megadedbeb.thegreatcold.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.World;

public class NmsHelper {
    // True если над блоком нет "настоящего" блока, который перекрывает небо.
    // Проход по вертикали вверх игнорирует набор "легких" блоков, которые не считаются
    // перекрывающими небо: листву (_LEAVES), ковры (_CARPET), слои снега (SNOW),
    // ограды/ворота (FENCE, FENCE_GATE), двери (_DOOR), а также ранее игнорируемые
    // ступени/плиты/стены (SLAB/STAIRS/WALL).
    public static boolean isOpenToSky(Block b) {
        if (b == null) return true;
        int x = b.getX(), y = b.getY(), z = b.getZ();
        World world = b.getWorld();
        if (world == null) return true;
        int maxY = world.getMaxHeight();

        for (int i = y + 1; i < maxY; i++) {
            Material up = world.getBlockAt(x, i, z).getType();
            // Воздух/пустота — не блокирует
            if (up == Material.AIR || up == Material.CAVE_AIR || up == Material.VOID_AIR) continue;

            String n = up.name();

            // Игнорируем "легкие" блоки — они НЕ считаются за перекрытие неба:
            // - листья: *_LEAVES
            // - ковры: *_CARPET
            // - слой снега: SNOW (тонкий слой)
            // - ограды / ворота: содержат FENCE (включая FENCE_GATE)
            // - двери: *_DOOR
            // - плиты/ступени/стены: SLAB / STAIRS / WALL (оставляем прежнее поведение)
            if (n.endsWith("_LEAVES")
                    || n.endsWith("_CARPET")
                    || n.equals("SNOW")
                    || n.contains("FENCE")
                    || n.endsWith("_DOOR")
                    || n.contains("SLAB")
                    || n.contains("STAIRS")
                    || n.contains("WALL")
            ) {
                // считаем как "прозрачное" для цели определения открытого неба
                continue;
            }

            // Во всех остальных случаях — найден блок, который действительно перекрывает небо
            return false;
        }
        // не найдено ничего, что бы перекрывало небо
        return true;
    }
}