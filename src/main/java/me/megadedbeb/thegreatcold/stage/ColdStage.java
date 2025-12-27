package me.megadedbeb.thegreatcold.stage;

public enum ColdStage {
    COLD_0(0),
    COLD_1(1),
    COLD_2(2),
    COLD_3(3);

    private final int id;

    ColdStage(int id) { this.id = id; }
    public int id() { return id; }

    public static ColdStage fromId(int id) {
        for (ColdStage x : values()) if (x.id == id) return x;
        return null;
    }
}
