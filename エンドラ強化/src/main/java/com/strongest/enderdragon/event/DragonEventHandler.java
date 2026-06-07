package com.strongest.enderdragon.event;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * エンダードラゴンの基本ステータスを強化するハンドラ。
 * HP を 200 → 1000 に変更。
 */
public class DragonEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double DRAGON_MAX_HEALTH = 1000.0;

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            if (!event.getLevel().isClientSide()) {
                // 既にHP1000に設定済みなら再設定しない（チャンク再読み込み対策）
                if (dragon.getMaxHealth() < DRAGON_MAX_HEALTH) {
                    AttributeInstance healthAttr = dragon.getAttribute(Attributes.MAX_HEALTH);
                    if (healthAttr != null) {
                        healthAttr.setBaseValue(DRAGON_MAX_HEALTH);
                        dragon.setHealth((float) DRAGON_MAX_HEALTH);
                        LOGGER.info("エンダードラゴンのHPを{}に強化しました", DRAGON_MAX_HEALTH);
                    }
                }
            }
        }
    }
}
