package com.strongest.enderdragon;

import com.strongest.enderdragon.event.DragonAttackHandler;
import com.strongest.enderdragon.event.DragonEventHandler;
import com.strongest.enderdragon.event.CrystalRespawnHandler;
import com.strongest.enderdragon.event.DragonRewardHandler;
import com.strongest.enderdragon.event.DragonEggHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(StrongestEnderDragon.MODID)
public class StrongestEnderDragon {
    public static final String MODID = "strongest_ender_dragon";
    private static final Logger LOGGER = LogUtils.getLogger();

    public StrongestEnderDragon() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DragonConfig.SPEC);

        LOGGER.info("Strongest Ender Dragon Mod loading...");

        MinecraftForge.EVENT_BUS.register(new DragonEventHandler());
        MinecraftForge.EVENT_BUS.register(new DragonAttackHandler());
        MinecraftForge.EVENT_BUS.register(new CrystalRespawnHandler());
        MinecraftForge.EVENT_BUS.register(new DragonRewardHandler());
        MinecraftForge.EVENT_BUS.register(new DragonEggHandler());

        LOGGER.info("Strongest Ender Dragon Mod loaded.");
    }
}
