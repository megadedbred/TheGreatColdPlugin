package me.megadedbeb.thegreatcold.data;

import me.megadedbeb.thegreatcold.freeze.FreezeStage;

import java.util.UUID;

public class PlayerFreezeData {
    private final UUID uuid;
    private FreezeStage freezeStage = FreezeStage.NONE;
    private long timeInHeat = 0L;          // ms
    private long timeWithoutHeat = 0L;     // ms
    private boolean inHeat = false;
    private long damageAccumulatorMs = 0L; // ms - аккум для урона

    // дополнительные поля для новых правил
    private boolean heatResetApplied = false; // флаг: уже применили сброс таймера после 30s в зоне тепла

    // Для стадии 4: запомним предыдущую скорость передвижения игрока, чтобы восстановить её при снятии стадии
    private Float storedWalkSpeed = null;
    private boolean walkSpeedModified = false;

    // Новые поля для механики "ниже Y=20"
    private long timeBelowY = 0L; // ms — накопленное время под Y=20
    private long timeAboveY = 0L; // ms — накопленное время над Y=20
    private boolean undergroundMode = false; // true если игрок перешёл в подземный режим (после 60s)

    public PlayerFreezeData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() { return uuid; }

    public FreezeStage getFreezeStage() { return freezeStage; }
    public void setFreezeStage(FreezeStage fs) { this.freezeStage = fs; }

    public long getTimeInHeat() { return timeInHeat; }
    public void setTimeInHeat(long ms) { this.timeInHeat = ms; }

    public long getTimeWithoutHeat() { return timeWithoutHeat; }
    public void setTimeWithoutHeat(long ms) { this.timeWithoutHeat = ms; }

    public boolean isInHeat() { return inHeat; }
    public void setInHeat(boolean inHeat) { this.inHeat = inHeat; }

    public long getDamageAccumulatorMs() { return damageAccumulatorMs; }
    public void setDamageAccumulatorMs(long ms) { this.damageAccumulatorMs = ms; }

    public boolean isHeatResetApplied() { return heatResetApplied; }
    public void setHeatResetApplied(boolean heatResetApplied) { this.heatResetApplied = heatResetApplied; }

    public Float getStoredWalkSpeed() { return storedWalkSpeed; }
    public void setStoredWalkSpeed(Float storedWalkSpeed) { this.storedWalkSpeed = storedWalkSpeed; }

    public boolean isWalkSpeedModified() { return walkSpeedModified; }
    public void setWalkSpeedModified(boolean walkSpeedModified) { this.walkSpeedModified = walkSpeedModified; }

    public long getTimeBelowY() { return timeBelowY; }
    public void setTimeBelowY(long timeBelowY) { this.timeBelowY = timeBelowY; }

    public long getTimeAboveY() { return timeAboveY; }
    public void setTimeAboveY(long timeAboveY) { this.timeAboveY = timeAboveY; }

    public boolean isUndergroundMode() { return undergroundMode; }
    public void setUndergroundMode(boolean undergroundMode) { this.undergroundMode = undergroundMode; }

    public void reset() {
        freezeStage = FreezeStage.NONE;
        timeInHeat = 0L;
        timeWithoutHeat = 0L;
        inHeat = false;
        damageAccumulatorMs = 0L;
        heatResetApplied = false;
        // не меняем storedWalkSpeed тут — вызов clearFreeze на уровне менеджера должен восстановить walkSpeed
        walkSpeedModified = false;

        // сбрасываем новую подземную логику при полном ресете
        timeBelowY = 0L;
        timeAboveY = 0L;
        undergroundMode = false;
    }
}