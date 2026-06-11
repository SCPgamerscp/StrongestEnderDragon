package com.strongest.enderdragon.event;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Ender Dragon status enhancement handler.
 * MAX_HEALTH: 200 -> 1000
 * ARMOR: 0 -> 30
 * ARMOR_TOUGHNESS: 0 -> 20
 */
public class DragonEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double DRAGON_MAX_HEALTH = 1000.0;
    private static final double DRAGON_ARMOR = 30.0;
    private static final double DRAGON_ARMOR_TOUGHNESS = 20.0;

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            if (!event.getLevel().isClientSide()) {
                // MAX_HEALTH: skip if already set
                if (dragon.getMaxHealth() < DRAGON_MAX_HEALTH) {
                    AttributeInstance healthAttr = dragon.getAttribute(Attributes.MAX_HEALTH);
                    if (healthAttr != null) {
                        healthAttr.setBaseValue(DRAGON_MAX_HEALTH);
                        dragon.setHealth((float) DRAGON_MAX_HEALTH);
                        LOGGER.info("EnderDragon HP set to {}", DRAGON_MAX_HEALTH);
                    }
                }

                // ARMOR: skip if already set
                AttributeInstance armorAttr = dragon.getAttribute(Attributes.ARMOR);
                if (armorAttr != null && armorAttr.getBaseValue() < DRAGON_ARMOR) {
                    armorAttr.setBaseValue(DRAGON_ARMOR);
                    LOGGER.info("EnderDragon ARMOR set to {}", DRAGON_ARMOR);
                }

                // ARMOR_TOUGHNESS: skip if already set
                AttributeInstance armorToughnessAttr = dragon.getAttribute(Attributes.ARMOR_TOUGHNESS);
                if (armorToughnessAttr != null && armorToughnessAttr.getBaseValue() < DRAGON_ARMOR_TOUGHNESS) {
                    armorToughnessAttr.setBaseValue(DRAGON_ARMOR_TOUGHNESS);
                    LOGGER.info("EnderDragon ARMOR_TOUGHNESS set to {}", DRAGON_ARMOR_TOUGHNESS);
                }
            }
        }
    }
}
