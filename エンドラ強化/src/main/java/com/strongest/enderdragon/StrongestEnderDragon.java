package com.strongest.enderdragon;

import com.strongest.enderdragon.event.DragonAttackHandler;
import com.strongest.enderdragon.event.DragonEventHandler;
import com.strongest.enderdragon.event.CrystalRespawnHandler;
import com.strongest.enderdragon.event.DragonRewardHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(StrongestEnderDragon.MODID)
public class StrongestEnderDragon {
    public static final String MODID = "strongest_ender_dragon";
    private static final Logger LOGGER = LogUtils.getLogger();

    public StrongestEnderDragon() {
        LOGGER.info("Strongest Ender Dragon Mod を読み込み中...");

        MinecraftForge.EVENT_BUS.register(new DragonEventHandler());
        MinecraftForge.EVENT_BUS.register(new DragonAttackHandler());
        MinecraftForge.EVENT_BUS.register(new CrystalRespawnHandler());
        MinecraftForge.EVENT_BUS.register(new DragonRewardHandler());

        LOGGER.info("Strongest Ender Dragon Mod の読み込み完了！");
    }
}
