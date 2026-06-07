package com.strongest.enderdragon.event;

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
 * エンドクリスタルの自動復活システム。
 * クリスタルが破壊されてから5分後に同じ位置に復活させ、
 * ドラゴンのHPを50回復させる。
 */
public class CrystalRespawnHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RESPAWN_DELAY_TICKS = 6000; // 5分 = 6000 ticks
    private static final float DRAGON_HEAL_AMOUNT = 50.0f;

    // 追跡中のクリスタル (UUID -> 位置)
    private final Map<UUID, Vec3> trackedCrystals = new HashMap<>();
    // 復活予定のクリスタル
    private final List<ScheduledRespawn> scheduledRespawns = new ArrayList<>();
    private int tickCounter = 0;

    private record ScheduledRespawn(Vec3 pos, int respawnTick) {}

    /**
     * エンドクリスタルがワールドに参加したとき、位置を記録する。
     */
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof EndCrystal crystal) {
            if (event.getLevel().dimension() == Level.END && !event.getLevel().isClientSide()) {
                trackedCrystals.put(crystal.getUUID(), crystal.position());
            }
        }
    }

    /**
     * 毎ティック、クリスタルの生存確認と復活処理を行う。
     */
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.dimension() != Level.END) return;

        tickCounter++;

        // 毎秒（20 ticks）クリスタルの生存を確認
        if (tickCounter % 20 == 0) {
            checkCrystals(serverLevel);
        }

        // 復活予定のクリスタルを処理
        processRespawns(serverLevel);
    }

    /**
     * 追跡中のクリスタルが破壊されたかチェックし、破壊されたら復活をスケジュール。
     * 複数同時に破壊された場合は5分ずつずらして1個ずつ復活させる。
     */
    private void checkCrystals(ServerLevel level) {
        Iterator<Map.Entry<UUID, Vec3>> iter = trackedCrystals.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<UUID, Vec3> entry = iter.next();
            if (level.getEntity(entry.getKey()) == null) {
                // チャンクがロードされているか確認（アンロードとの誤判定を防止）
                BlockPos pos = BlockPos.containing(entry.getValue());
                if (level.isLoaded(pos)) {
                    // 既存スケジュールの最後の復活時刻を取得し、そこから5分後にずらす
                    int latestRespawnTick = tickCounter;
                    for (ScheduledRespawn existing : scheduledRespawns) {
                        if (existing.respawnTick > latestRespawnTick) {
                            latestRespawnTick = existing.respawnTick;
                        }
                    }
                    int respawnAt = latestRespawnTick + RESPAWN_DELAY_TICKS;

                    scheduledRespawns.add(new ScheduledRespawn(entry.getValue(), respawnAt));
                    iter.remove();

                    int minutesFromNow = (respawnAt - tickCounter) / 1200;
                    LOGGER.info("エンドクリスタル破壊を検知。{}分後に復活予定: {}", minutesFromNow, entry.getValue());
                }
            }
        }
    }

    /**
     * 復活時刻に達したクリスタルを再生成し、ドラゴンのHPを回復。
     */
    private void processRespawns(ServerLevel level) {
        Iterator<ScheduledRespawn> iter = scheduledRespawns.iterator();
        while (iter.hasNext()) {
            ScheduledRespawn respawn = iter.next();
            if (tickCounter >= respawn.respawnTick) {
                // クリスタルを再生成
                EndCrystal crystal = new EndCrystal(EntityType.END_CRYSTAL, level);
                crystal.setPos(respawn.pos.x, respawn.pos.y, respawn.pos.z);
                crystal.setShowBottom(true);
                crystal.setInvulnerable(false);
                level.addFreshEntity(crystal);

                LOGGER.info("エンドクリスタルが復活: {}", respawn.pos);

                // ドラゴンのHPを回復
                healDragon(level);

                iter.remove();
            }
        }
    }

    /**
     * ジ・エンドにいるドラゴンのHPを回復する。
     */
    private void healDragon(ServerLevel level) {
        List<EnderDragon> dragons = level.getEntitiesOfClass(EnderDragon.class,
                new AABB(-300, -64, -300, 300, 320, 300));
        for (EnderDragon dragon : dragons) {
            float newHealth = Math.min(dragon.getHealth() + DRAGON_HEAL_AMOUNT, dragon.getMaxHealth());
            dragon.setHealth(newHealth);
            LOGGER.info("ドラゴンのHPを{}回復 (現在HP: {})", DRAGON_HEAL_AMOUNT, newHealth);
        }
    }
}
