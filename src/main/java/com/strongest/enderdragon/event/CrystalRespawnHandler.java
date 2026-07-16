package com.strongest.enderdragon.event;

import com.strongest.enderdragon.DragonConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
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

    // Tracked crystals (UUID -> position) that are currently alive/loaded.
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

    /**
     * EntityLeaveLevelEvent fires whenever an entity leaves the level for ANY reason
     * (killed, discarded/despawned, or simply unloaded to chunk), unlike the previous
     * tick-based polling which only cleaned up trackedCrystals when the crystal's
     * chunk happened to still be loaded. Relying solely on that poll meant a crystal
     * destroyed in (or permanently unloaded from) a chunk that never reloads would
     * leave its UUID in trackedCrystals forever, leaking memory. Handling the event
     * directly guarantees the entry is always removed promptly and correctly.
     */
    @SubscribeEvent
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof EndCrystal crystal)) return;
        if (event.getLevel().dimension() != Level.END) return;

        Vec3 lastPos = trackedCrystals.remove(crystal.getUUID());
        if (lastPos == null) return;

        Entity.RemovalReason reason = crystal.getRemovalReason();
        // Only schedule a respawn if the crystal was actually destroyed. Reasons such as
        // UNLOADED_TO_CHUNK/UNLOADED_WITH_PLAYER/CHANGED_DIMENSION do not destroy the
        // entity - it will simply rejoin the level later and be re-tracked automatically
        // via onEntityJoinLevel, so no respawn should be scheduled for those cases.
        if (reason == null || !reason.shouldDestroy()) return;
        if (!DragonConfig.CRYSTAL_RESPAWN_ENABLED.get()) return;

        scheduleRespawn(lastPos);
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.dimension() != Level.END) return;

        tickCounter++;
        processRespawns(serverLevel);
    }

    private void scheduleRespawn(Vec3 pos) {
        int latestRespawnTick = tickCounter;
        for (ScheduledRespawn existing : scheduledRespawns) {
            if (existing.respawnTick > latestRespawnTick) {
                latestRespawnTick = existing.respawnTick;
            }
        }
        int delay = DragonConfig.CRYSTAL_RESPAWN_DELAY_TICKS.get();
        int respawnAt = latestRespawnTick + delay;

        scheduledRespawns.add(new ScheduledRespawn(pos, respawnAt));

        int minutesFromNow = (respawnAt - tickCounter) / 1200;
        LOGGER.info("End Crystal destroyed. Respawn in ~{} min at {}", minutesFromNow, pos);
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
