package com.strongest.enderdragon.event;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * ドラゴン討伐時の報酬を管理するハンドラ。
 * 経験値を100倍に増加し、討伐メッセージを表示。
 */
public class DragonRewardHandler {

    private static final int XP_MULTIPLIER = 100;

    @SubscribeEvent
    public void onLivingExperienceDrop(LivingExperienceDropEvent event) {
        if (event.getEntity() instanceof EnderDragon) {
            int boostedXP = event.getDroppedExperience() * XP_MULTIPLIER;
            event.setDroppedExperience(boostedXP);
        }
    }

    @SubscribeEvent
    public void onDragonDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon) {
            if (event.getEntity().level() instanceof ServerLevel serverLevel) {
                Component message = Component.literal("★ 最強のエンダードラゴンが倒された！ ★")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

                for (ServerPlayer player : serverLevel.players()) {
                    player.sendSystemMessage(message);
                }
            }
        }
    }
}
