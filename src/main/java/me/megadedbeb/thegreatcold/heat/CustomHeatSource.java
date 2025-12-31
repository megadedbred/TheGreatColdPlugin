package me.megadedbeb.thegreatcold.heat;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.ChatColor;

/**
 * Модель кастомного источника тепла.
 *
 * Теперь хранит явную координату блока (blockLocation) — это гарантирует, что
 * все операции (замена блока, сохранение, удаление, отображение) выполняются
 * строго для одной и той же координаты.
 */
public class CustomHeatSource {
    public static final String TYPE_SMALL_HEATER = "small_heater";
    public static final String TYPE_SEA_HEATER = "sea_heater";

    private final String type;

    // точная локация блока источника (целые координаты)
    private final Location blockLocation;

    // координата центра/для визуализации (blockLocation + offset 0.5, 1.2, 0.5)
    private final Location center;

    private final int radius; // куб радиус (от центра ±radius по осям)
    private final long maxFuelMillis;

    private long fuelMillis; // текущее количество топлива в миллисекундах

    // runtime
    private ArmorStand nameLine; // верхняя строка (title)
    private ArmorStand fuelLine; // нижняя строка (bar)

    public CustomHeatSource(String type, Location blockLoc, int radius, long maxFuelMillis, long initialFuelMillis) {
        this.type = type;
        // normalize block location to integers (block coords)
        this.blockLocation = blockLoc.clone();
        this.blockLocation.setX(blockLoc.getBlockX());
        this.blockLocation.setY(blockLoc.getBlockY());
        this.blockLocation.setZ(blockLoc.getBlockZ());

        // center for particles / hologram base (slightly above the block center)
        this.center = this.blockLocation.clone().add(0.5, 1.2, 0.5);

        this.radius = radius;
        this.maxFuelMillis = maxFuelMillis;
        this.fuelMillis = Math.max(0L, Math.min(maxFuelMillis, initialFuelMillis));
    }

    public String getType() { return type; }

    /** Точная локация блока (целые координаты) */
    public Location getBlockLocation() { return blockLocation.clone(); }

    /** Центр (для частиц / позиционирования nameplates) */
    public Location getCenter() { return center.clone(); }

    public int getRadius() { return radius; }
    public long getMaxFuelMillis() { return maxFuelMillis; }
    public long getFuelMillis() { return fuelMillis; }
    public void setFuelMillis(long ms) { fuelMillis = Math.max(0L, Math.min(maxFuelMillis, ms)); }

    public boolean isActive() { return fuelMillis > 0L; }

    public void addFuelMillis(long ms) {
        if (ms <= 0) return;
        fuelMillis = Math.min(maxFuelMillis, fuelMillis + ms);
    }

    public void consumeMillis(long ms) {
        if (ms <= 0) return;
        fuelMillis = Math.max(0L, fuelMillis - ms);
    }

    /**
     * Уменьшение топлива за период (ms). Возвращает true если состояние (active/inactive) изменилось.
     * Используется внешне; для sea_heater вызов tick только если над блоком есть вода (см. CustomHeatManager).
     */
    public boolean tick(long ms) {
        boolean before = isActive();
        consumeMillis(ms);
        boolean after = isActive();
        return before != after;
    }

    /**
     * Возвращает percent [0..100]
     */
    public int getFuelPercent() {
        if (maxFuelMillis <= 0) return 0;
        return (int) ((fuelMillis * 100L) / maxFuelMillis);
    }

    /**
     * Возвращает отображаемую строку топлива: 10 слотов, '■' (full), '▬' (half/5%), '□' empty.
     * Округление: remainder == 5 -> one half; remainder > 5 -> round up to next full.
     */
    public String renderFuelBar() {
        int pct = getFuelPercent();
        int full = pct / 10;
        int rem = pct % 10;
        boolean half = false;
        if (rem == 5) half = true;
        else if (rem > 5) {
            full += 1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < full && i < 10; i++) sb.append('■');
        if (half && full < 10) sb.append('▬');
        while (sb.length() < 10) sb.append('□');
        return sb.toString();
    }

    /**
     * Текст верхней строки (название) — зависит от типа и активности.
     *
     * Учтите: для sea_heater фактическая "эффективная" активность (вода над блоком) не проверяется в этом методе,
     * поэтому CustomHeatManager при установке голограммы использует свою логику для корректного цвета имени.
     */
    public String getDisplayName() {
        String title;
        if (TYPE_SMALL_HEATER.equals(type)) {
            title = "Небольшой обогреватель";
        } else if (TYPE_SEA_HEATER.equals(type)) {
            title = "Морской обогреватель";
        } else {
            title = "Источник тепла";
        }
        // fallback color (не используется для sea_heater в тех местах, где нужна проверка воды)
        String color;
        if (isActive()) {
            if (TYPE_SMALL_HEATER.equals(type)) color = ChatColor.GOLD.toString();
            else if (TYPE_SEA_HEATER.equals(type)) color = ChatColor.BLUE.toString();
            else color = ChatColor.GOLD.toString();
        } else {
            color = ChatColor.AQUA.toString();
        }
        return color + title;
    }

    /**
     * Возвращает строку с индикатором топлива, с иконками по краям.
     * Формат: ❄ [bar] 🔥
     * Цвета: ❄ (голубой), пустые □ (голубой), ■/▬/🔥 (оранжевые)
     */
    public String getDisplayFuelLine() {
        StringBuilder sb = new StringBuilder();
        // left snow symbol - blue
        sb.append(ChatColor.AQUA).append("❄ ").append(ChatColor.RESET);
        String bar = renderFuelBar();
        for (int i = 0; i < bar.length(); i++) {
            char c = bar.charAt(i);
            if (c == '■' || c == '▬') {
                sb.append(ChatColor.GOLD).append(c);
            } else { // '□'
                sb.append(ChatColor.AQUA).append(c);
            }
        }
        // right fire symbol - orange
        sb.append(ChatColor.GOLD).append(" 🔥");
        return sb.toString();
    }

    // ArmorStand setter/getter (runtime entity references)
    public ArmorStand getNameLineEntity() { return nameLine; }
    public ArmorStand getFuelLineEntity() { return fuelLine; }
    public void setNameLineEntity(ArmorStand as) { this.nameLine = as; }
    public void setFuelLineEntity(ArmorStand as) { this.fuelLine = as; }
}