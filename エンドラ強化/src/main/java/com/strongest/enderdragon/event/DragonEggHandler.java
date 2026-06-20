package com.strongest.enderdragon.event;

import com.strongest.enderdragon.DragonConfig;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Dragon Egg passive heal handler.
 * Any LivingEntity carrying Dragon Eggs (full inventory for players,
 * equipment slots for other entities) heals (eggCount * healPerEgg) HP
 * every second. Scales linearly with the number of eggs held.
 * Controlled via config (strongest_ender_dragon-common.toml).
 */
public class DragonEggHandler {

    @SubscribeEvent
    public void onLivingTick(LivingTickEvent event) {
        if (!DragonConfig.DRAGON_EGG_HEAL_ENABLED.get()) return;

        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        if (!entity.isAlive()) return;

        // Stagger per-entity using its own tickCount: once per second
        if (entity.tickCount % 20 != 0) return;

        int eggCount = countDragonEggs(entity);
        if (eggCount <= 0) return;

        double healAmount = eggCount * DragonConfig.DRAGON_EGG_HEAL_PER_EGG.get();
        entity.heal((float) healAmount); // heal() naturally caps at max health
    }

    private int countDragonEggs(LivingEntity entity) {
        int count = 0;
        if (entity instanceof Player player) {
            Inventory inv = player.getInventory();
            count += countInList(inv.items);
            count += countInList(inv.armor);
            count += countInList(inv.offhand);
        } else {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = entity.getItemBySlot(slot);
                if (stack.is(Items.DRAGON_EGG)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    private int countInList(Iterable<ItemStack> list) {
        int count = 0;
        for (ItemStack stack : list) {
            if (stack.is(Items.DRAGON_EGG)) count += stack.getCount();
        }
        return count;
    }
}
