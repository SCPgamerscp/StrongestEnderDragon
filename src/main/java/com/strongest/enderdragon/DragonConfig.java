package com.strongest.enderdragon;

import net.minecraftforge.common.ForgeConfigSpec;

public class DragonConfig {

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue CRYSTAL_RESPAWN_ENABLED;
    public static final ForgeConfigSpec.IntValue     CRYSTAL_RESPAWN_DELAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue  DRAGON_HEAL_AMOUNT;

    static {
        BUILDER.comment("Strongest Ender Dragon - Crystal Respawn Settings");
        BUILDER.push("crystal_respawn");

        CRYSTAL_RESPAWN_ENABLED = BUILDER
            .comment("Enable or disable automatic End Crystal respawn. Default: true")
            .define("enabled", true);

        CRYSTAL_RESPAWN_DELAY_TICKS = BUILDER
            .comment("Ticks before a destroyed crystal respawns. 6000 = 5 minutes. Min: 20")
            .defineInRange("respawnDelayTicks", 6000, 20, Integer.MAX_VALUE);

        DRAGON_HEAL_AMOUNT = BUILDER
            .comment("HP restored to the Ender Dragon when a crystal respawns. Default: 50.0")
            .defineInRange("dragonHealAmount", 50.0, 0.0, 10000.0);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
