package me.megadedbeb.thegreatcold.freeze;

public enum FreezeStage {
    NONE(0),
    STAGE_1(1),
    STAGE_2(2),
    STAGE_3(3),
    STAGE_4(4);

    private final int id;
    FreezeStage(int id) { this.id = id; }
    public int id() { return id; }

    public static FreezeStage fromId(int id) {
        for (FreezeStage s : values()) if (s.id == id) return s;
        return NONE;
    }
}
