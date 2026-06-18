package com.strongest.enderdragon.event;

import com.strongest.enderdragon.DragonConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.*;

/**
 * End Crystal auto-respawn system.
 * Respawn enabled/delay/heal amount are controlled via config (strongest_ender_dragon-common.toml).
 */
public class CrystalRespawnHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Tracked crystals (UUID -> position)
    private final Map<UUID, Vec3> trackedCrystals = new HashMap<>();
    // Scheduled respawns
    private final List<ScheduledRespawn> scheduledRespawns = new ArrayList<>();
    private int tickCounter = 0;

    private record ScheduledRespawn(Vec3 pos, int respawnTick) {}

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof EndCrystal crystal) {
            if (event.getLevel().dimension() == Level.END && !event.getLevel().isClientSide()) {
                trackedCrystals.put(crystal.getUUID(), crystal.position());
            }
        }
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.dimension() != Level.END) return;

        tickCounter++;

        if (tickCounter % 20 == 0) {
            checkCrystals(serverLevel);
        }

        processRespawns(serverLevel);
    }

    private void checkCrystals(ServerLevel level) {
        // Return early if crystal respawn is disabled in config
        if (!DragonConfig.CRYSTAL_RESPAWN_ENABLED.get()) return;

        Iterator<Map.Entry<UUID, Vec3>> iter = trackedCrystals.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<UUID, Vec3> entry = iter.next();
            if (level.getEntity(entry.getKey()) == null) {
                BlockPos pos = BlockPos.containing(entry.getValue());
                if (level.isLoaded(pos)) {
                    int latestRespawnTick = tickCounter;
                    for (ScheduledRespawn existing : scheduledRespawns) {
                        if (existing.respawnTick > latestRespawnTick) {
                            latestRespawnTick = existing.respawnTick;
                        }
                    }
                    int delay = DragonConfig.CRYSTAL_RESPAWN_DELAY_TICKS.get();
                    int respawnAt = latestRespawnTick + delay;

                    scheduledRespawns.add(new ScheduledRespawn(entry.getValue(), respawnAt));
                    iter.remove();

                    int minutesFromNow = (respawnAt - tickCounter) / 1200;
                    LOGGER.info("End Crystal destroyed. Respawn in ~{} min at {}", minutesFromNow, entry.getValue());
                }
            }
        }
    }

    private void processRespawns(ServerLevel level) {
        Iterator<ScheduledRespawn> iter = scheduledRespawns.iterator();
        while (iter.hasNext()) {
            ScheduledRespawn respawn = iter.next();
            if (tickCounter >= respawn.respawnTick) {
                EndCrystal crystal = new EndCrystal(EntityType.END_CRYSTAL, level);
                crystal.setPos(respawn.pos.x, respawn.pos.y, respawn.pos.z);
                crystal.setShowBottom(true);
                crystal.setInvulnerable(false);
                level.addFreshEntity(crystal);

                LOGGER.info("End Crystal respawned at {}", respawn.pos);

                healDragon(level);

                iter.remove();
            }
        }
    }

    private void healDragon(ServerLevel level) {
        float healAmount = DragonConfig.DRAGON_HEAL_AMOUNT.get().floatValue();
        List<EnderDragon> dragons = level.getEntitiesOfClass(EnderDragon.class,
                new AABB(-300, -64, -300, 300, 320, 300));
        for (EnderDragon dragon : dragons) {
            float newHealth = Math.min(dragon.getHealth() + healAmount, dragon.getMaxHealth());
            dragon.setHealth(newHealth);
            LOGGER.info("Dragon healed by {} (current HP: {})", healAmount, newHealth);
        }
    }
}
