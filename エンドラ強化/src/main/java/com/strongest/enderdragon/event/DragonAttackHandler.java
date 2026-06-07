package com.strongest.enderdragon.event;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.*;

/**
 * エンダードラゴンの14種の攻撃パターンを管理するハンドラ。
 * 放射系攻撃は5秒間持続する弾幕スタイル。
 * 各攻撃には個別のクールダウンがあり、ランダムに選択・実行される。
 */
public class DragonAttackHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ========== 攻撃タイプ定義 ==========

    private enum AttackType {
        METEOR_STRIKE(200),        // 10秒
        LIGHTNING_STRIKE(160),     // 8秒
        TELEPORT_ATTACK(300),      // 15秒
        ENHANCED_BREATH(240),      // 12秒
        SHOCKWAVE(100),            // 5秒（着地時用チェック）
        ACID_RAIN(400),            // 20秒
        CHARGE_ATTACK(160),        // 8秒
        BLAZE_FIREBALL(240),       // 12秒
        SCATTER_ARROWS(200),       // 10秒
        TRIDENT_STORM(300),        // 15秒
        POTION_RAIN(400),          // 20秒
        SUMMON_ENDERMEN(3600),     // 3分
        DRAGON_BREATH_SPRAY(300);  // 15秒

        final int cooldownTicks;

        AttackType(int cooldownTicks) {
            this.cooldownTicks = cooldownTicks;
        }
    }

    // ========== 持続攻撃（弾幕）システム ==========

    /**
     * 5秒間持続する弾幕攻撃の状態を管理するクラス。
     * 毎tick少しずつ発射体を生成して弾幕を演出する。
     */
    private static class BarrageState {
        final AttackType type;
        final int startTick;
        final int endTick;
        final UUID dragonUUID;
        final UUID targetUUID;

        BarrageState(AttackType type, int startTick, UUID dragonUUID, UUID targetUUID) {
            this.type = type;
            this.startTick = startTick;
            this.endTick = startTick + 100; // 5秒 = 100 ticks
            this.dragonUUID = dragonUUID;
            this.targetUUID = targetUUID;
        }

        boolean isFinished(int currentTick) {
            return currentTick >= endTick;
        }
    }

    // ========== 状態管理 ==========

    private final Map<AttackType, Integer> lastUsedTick = new EnumMap<>(AttackType.class);
    private final Set<UUID> dragonTridents = new HashSet<>();
    private final List<BarrageState> activeBarrages = new ArrayList<>();
    private int tickCounter = 0;
    private static final int GLOBAL_COOLDOWN = 60; // 攻撃間の最低間隔（3秒）
    private static final double CENTER_RANGE = 20.0; // ファング発動判定の中心からの距離
    private static final AABB SEARCH_AABB = new AABB(-300, -64, -300, 300, 320, 300);
    private int lastAttackTick = -999;
    private final Random random = new Random();

    // デバフ効果の候補
    private static final MobEffect[] DEBUFFS = {
            MobEffects.POISON, MobEffects.WEAKNESS, MobEffects.MOVEMENT_SLOWDOWN,
            MobEffects.WITHER, MobEffects.BLINDNESS, MobEffects.DIG_SLOWDOWN
    };

    // 弾幕攻撃かどうか判定
    private static final Set<AttackType> BARRAGE_ATTACKS = EnumSet.of(
            AttackType.METEOR_STRIKE,
            AttackType.BLAZE_FIREBALL,
            AttackType.SCATTER_ARROWS,
            AttackType.TRIDENT_STORM,
            AttackType.POTION_RAIN,
            AttackType.ACID_RAIN,
            AttackType.DRAGON_BREATH_SPRAY
    );

    // ========== メインティックハンドラ ==========

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.dimension() != Level.END) return;

        tickCounter++;

        // 持続中の弾幕攻撃を処理（毎tick）
        processActiveBarrages(serverLevel);

        // 火の玉の速度上限チェック（10tickごと）
        if (tickCounter % 10 == 0) {
            clampProjectileVelocities(serverLevel);
        }

        // ドラゴンが中心付近にいるとき、常時ファングを回転させる（10tickごと）
        if (tickCounter % 10 == 0) {
            processPassiveFangs(serverLevel);
        }

        // 毎秒のみ新しい攻撃をチェック
        if (tickCounter % 20 != 0) return;

        // ドラゴンを探す
        List<EnderDragon> dragons = serverLevel.getEntitiesOfClass(EnderDragon.class,
                new AABB(-300, -64, -300, 300, 320, 300));
        if (dragons.isEmpty()) return;
        EnderDragon dragon = dragons.get(0);
        if (!dragon.isAlive()) return;

        // 最寄りのプレイヤーを探す
        ServerPlayer target = findNearestPlayer(serverLevel, dragon);
        if (target == null) return;

        // グローバルクールダウンチェック
        if (tickCounter - lastAttackTick < GLOBAL_COOLDOWN) return;

        // 使用可能な攻撃を取得
        List<AttackType> available = new ArrayList<>();
        for (AttackType type : AttackType.values()) {
            int lastUsed = lastUsedTick.getOrDefault(type, -99999);
            if (tickCounter - lastUsed >= type.cooldownTicks) {
                available.add(type);
            }
        }
        if (available.isEmpty()) return;

        // ランダムに攻撃を選択・実行
        AttackType chosen = available.get(random.nextInt(available.size()));

        if (BARRAGE_ATTACKS.contains(chosen)) {
            // 弾幕攻撃: 5秒間持続する状態を登録
            activeBarrages.add(new BarrageState(chosen, tickCounter, dragon.getUUID(), target.getUUID()));
        } else {
            // 瞬発攻撃: 即座に実行
            executeInstantAttack(chosen, serverLevel, dragon, target);
        }

        lastUsedTick.put(chosen, tickCounter);
        lastAttackTick = tickCounter;
    }

    /**
     * アクティブな弾幕攻撃を毎tick処理する。
     */
    private void processActiveBarrages(ServerLevel level) {
        Iterator<BarrageState> iter = activeBarrages.iterator();
        while (iter.hasNext()) {
            BarrageState barrage = iter.next();

            if (barrage.isFinished(tickCounter)) {
                iter.remove();
                continue;
            }

            // ドラゴンとターゲットを取得
            if (!(level.getEntity(barrage.dragonUUID) instanceof EnderDragon dragon)) {
                iter.remove();
                continue;
            }
            if (!dragon.isAlive()) {
                iter.remove();
                continue;
            }

            ServerPlayer target = null;
            for (ServerPlayer player : level.players()) {
                if (player.getUUID().equals(barrage.targetUUID)) {
                    target = player;
                    break;
                }
            }
            if (target == null) {
                // ターゲットがいなくなったら最寄りのプレイヤーに切り替え
                target = findNearestPlayer(level, dragon);
                if (target == null) {
                    iter.remove();
                    continue;
                }
            }

            // 弾幕の1tick分を発射
            executeBarrageTick(barrage.type, level, dragon, target);
        }
    }

    // ========== トライデント着弾イベント（雷生成） ==========

    @SubscribeEvent
    public void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getProjectile() instanceof ThrownTrident trident) {
            if (dragonTridents.remove(trident.getUUID())) {
                if (trident.level() instanceof ServerLevel serverLevel) {
                    LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(serverLevel);
                    if (lightning != null) {
                        lightning.setPos(trident.getX(), trident.getY(), trident.getZ());
                        lightning.setVisualOnly(false);
                        serverLevel.addFreshEntity(lightning);
                    }
                }
            }
        }
    }

    // ========== ユーティリティ ==========

    private ServerPlayer findNearestPlayer(ServerLevel level, EnderDragon dragon) {
        ServerPlayer nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double dist = player.distanceToSqr(dragon);
            if (dist < nearestDist && dist < 200 * 200) {
                nearestDist = dist;
                nearest = player;
            }
        }
        return nearest;
    }

    private static final int MAX_PROJECTILE_LIFETIME = 2400; // 2分 = 2400 ticks
    private static final double DESPAWN_DISTANCE_SQ = 128.0 * 128.0; // 128ブロック（敵対Mobと同じ）

    /**
     * ジ・エンドの全AbstractHurtingProjectileを監視。
     * - プレイヤーから128ブロック以上離れたらデスポーン
     * - 2分経過したら自動削除
     */
    private void clampProjectileVelocities(ServerLevel level) {
        List<ServerPlayer> players = level.players();

        for (AbstractHurtingProjectile p : level.getEntitiesOfClass(
                AbstractHurtingProjectile.class, SEARCH_AABB)) {
            // 2分経過で自動削除
            if (p.tickCount > MAX_PROJECTILE_LIFETIME) {
                p.discard();
                continue;
            }

            // 最寄りプレイヤーから128ブロック以上離れていたらデスポーン
            boolean nearPlayer = false;
            for (ServerPlayer player : players) {
                if (player.distanceToSqr(p) < DESPAWN_DISTANCE_SQ) {
                    nearPlayer = true;
                    break;
                }
            }
            if (!nearPlayer) {
                p.discard();
            }
        }
    }

    private int getSurfaceY(ServerLevel level, double x, double z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) x, (int) z);
        return y <= 0 ? 64 : y; // Void対策
    }

    // ========== 瞬発攻撃ディスパッチャ ==========

    private void executeInstantAttack(AttackType type, ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        switch (type) {
            case LIGHTNING_STRIKE -> lightningStrike(level, dragon, target);
            case TELEPORT_ATTACK -> teleportAttack(level, dragon, target);
            case ENHANCED_BREATH -> enhancedBreath(level, dragon, target);
            case SHOCKWAVE -> shockwave(level, dragon, target);
            case CHARGE_ATTACK -> chargeAttack(level, dragon, target);
            case SUMMON_ENDERMEN -> summonEndermen(level, dragon, target);
            default -> {} // 弾幕攻撃はここに来ない
        }
    }

    // ========== 弾幕攻撃ディスパッチャ（毎tick呼ばれる） ==========

    private void executeBarrageTick(AttackType type, ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        switch (type) {
            case METEOR_STRIKE -> meteorBarrageTick(level, dragon, target);
            case BLAZE_FIREBALL -> blazeFireballBarrageTick(level, dragon, target);
            case SCATTER_ARROWS -> scatterArrowsBarrageTick(level, dragon, target);
            case TRIDENT_STORM -> tridentStormBarrageTick(level, dragon, target);
            case POTION_RAIN -> potionRainBarrageTick(level, dragon, target);
            case ACID_RAIN -> acidRainBarrageTick(level, dragon, target);
            case DRAGON_BREATH_SPRAY -> dragonBreathSprayBarrageTick(level, dragon, target);
            default -> {}
        }
    }

    // ================================================================
    // ============= 弾幕攻撃パターン（5秒間持続） ===================
    // ================================================================

    // ---------- 1. 隕石落とし（弾幕版） ----------
    /**
     * 5秒間、4tickごとにプレイヤー周囲に上空からDragonFireballを1発ずつ降らせる。
     * 合計約25発の弾幕。
     */
    private void meteorBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 4 != 0) return; // 4tickごとに1発

        double x = target.getX() + (random.nextDouble() - 0.5) * 20;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 20;
        double y = target.getY() + 35 + random.nextDouble() * 10;

        DragonFireball fireball = new DragonFireball(level, dragon, 0, -1, 0);
        fireball.setPos(x, y, z);
        level.addFreshEntity(fireball);

        level.sendParticles(ParticleTypes.FLAME, x, y, z, 5, 1, 1, 1, 0.02);
    }

    // ---------- 9. ブレイズ火の玉の雨（弾幕版） ----------
    /**
     * 5秒間、2tickごとにプレイヤー周辺の上空からSmallFireballを1発ずつ降らせる。
     * 合計約50発の火の玉の雨。
     */
    private void blazeFireballBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 2 != 0) return; // 2tickごとに1発

        double x = target.getX() + (random.nextDouble() - 0.5) * 20;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 20;
        double y = target.getY() + 30 + random.nextDouble() * 10;

        // SmallFireballはinertia=0.95でドラッグがあるため、terminal velocityに収束して安全
        SmallFireball fireball = new SmallFireball(level, x, y, z,
                (random.nextDouble() - 0.5) * 0.1, -1.0, (random.nextDouble() - 0.5) * 0.1);
        fireball.setOwner(dragon);
        level.addFreshEntity(fireball);

        level.sendParticles(ParticleTypes.FLAME,
                x, y, z, 3, 0.5, 0.5, 0.5, 0.02);
    }

    // ---------- 10. 矢の雨（弾幕版） ----------
    /**
     * 5秒間、2tickごとに火矢+デバフ付きの矢をプレイヤー周辺の上空から1本ずつ降らせる。
     * 合計約50本の矢の雨。
     */
    private void scatterArrowsBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 2 != 0) return;

        double x = target.getX() + (random.nextDouble() - 0.5) * 20;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 20;
        double y = target.getY() + 30 + random.nextDouble() * 10;

        Arrow arrow = new Arrow(level, x, y, z);
        arrow.setOwner(dragon);
        arrow.shoot((random.nextDouble() - 0.5) * 0.1, -1.0, (random.nextDouble() - 0.5) * 0.1, 2.0f, 3.0f);
        arrow.setSecondsOnFire(100);
        arrow.setBaseDamage(6.0);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;

        MobEffect debuff = DEBUFFS[random.nextInt(DEBUFFS.length)];
        arrow.addEffect(new MobEffectInstance(debuff, 200, 1));

        level.addFreshEntity(arrow);

        level.sendParticles(ParticleTypes.CRIT,
                x, y, z, 3, 0.5, 0.5, 0.5, 0.1);
    }

    // ---------- 11. トライデントの雨（弾幕版） ----------
    /**
     * 5秒間、5tickごとにプレイヤー周辺の上空からトライデントを1本ずつ降らせる。
     * 着弾時に雷が発生する雷雨スタイル。合計約20本。
     */
    private void tridentStormBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 5 != 0) return; // 5tickごとに1本

        double x = target.getX() + (random.nextDouble() - 0.5) * 20;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 20;
        double y = target.getY() + 30 + random.nextDouble() * 10;

        ThrownTrident trident = new ThrownTrident(EntityType.TRIDENT, level);
        trident.setOwner(dragon);
        trident.setPos(x, y, z);
        trident.shoot((random.nextDouble() - 0.5) * 0.1, -1.0, (random.nextDouble() - 0.5) * 0.1, 2.5f, 3.0f);
        trident.pickup = AbstractArrow.Pickup.DISALLOWED;

        dragonTridents.add(trident.getUUID());
        level.addFreshEntity(trident);

        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                x, y, z, 5, 1, 1, 1, 0.2);
    }

    // ---------- 12. 残留ポーション雨（弾幕版） ----------
    /**
     * 5秒間、5tickごとにランダムデバフの残留ポーションを上空から1発ずつ降らせる。
     * 合計約20発のポーション弾幕。
     */
    private void potionRainBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 5 != 0) return;

        double x = target.getX() + (random.nextDouble() - 0.5) * 24;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 24;
        double y = target.getY() + 25 + random.nextDouble() * 10;

        ThrownPotion potion = new ThrownPotion(level, x, y, z);
        potion.setOwner(dragon);

        ItemStack potionStack = new ItemStack(Items.LINGERING_POTION);
        MobEffect debuff = DEBUFFS[random.nextInt(DEBUFFS.length)];
        PotionUtils.setCustomEffects(potionStack,
                List.of(new MobEffectInstance(debuff, 200, 1)));
        potion.setItem(potionStack);

        potion.shoot(0, -1, 0, 0.5f, 5.0f);
        level.addFreshEntity(potion);

        level.sendParticles(ParticleTypes.WITCH,
                x, y, z, 5, 1, 1, 1, 0.05);
    }

    // ---------- 6. エンダーアシッドの雨（弾幕版） ----------
    /**
     * 5秒間、7tickごとにAreaEffectCloudを1個ずつ広範囲に撒き散らす。
     * 合計約14個のアシッドエリア。
     */
    private void acidRainBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 7 != 0) return;

        double x = target.getX() + (random.nextDouble() - 0.5) * 30;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 30;
        int y = getSurfaceY(level, x, z);

        AreaEffectCloud cloud = new AreaEffectCloud(level, x, y, z);
        cloud.setRadius(3.0f);
        cloud.setDuration(200);
        cloud.setRadiusPerTick(-0.005f);
        cloud.setWaitTime(5);
        cloud.addEffect(new MobEffectInstance(MobEffects.HARM, 1, 0));
        cloud.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
        cloud.setOwner(dragon);
        level.addFreshEntity(cloud);

        level.sendParticles(ParticleTypes.DRAGON_BREATH,
                x, y + 1, z, 10, 1.5, 0.5, 1.5, 0.02);
    }

    // ---------- 14. ドラゴン火の玉拡散ブレス（弾幕版・新攻撃） ----------
    /**
     * 5秒間、3tickごとにDragonFireballを45度扇状に火炎放射のように連射。
     * 合計約33発のドラゴンファイアボール弾幕。
     */
    private void dragonBreathSprayBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 5 != 0) return; // 5tickごとに10発

        Vec3 dragonPos = new Vec3(dragon.getX(), dragon.getY() + 5, dragon.getZ());
        Vec3 toTarget = target.position().subtract(dragonPos).normalize();
        double baseAngle = Math.atan2(toTarget.z, toTarget.x);

        for (int i = 0; i < 10; i++) {
            double spread = (random.nextDouble() - 0.5) * (Math.PI / 4.0);
            double angle = baseAngle + spread;
            double ySpread = toTarget.y + (random.nextDouble() - 0.5) * 0.4;

            double dx = Math.cos(angle);
            double dz = Math.sin(angle);

            DragonFireball fireball = new DragonFireball(level, dragon, dx, ySpread, dz);
            fireball.setPos(dragonPos.x, dragonPos.y, dragonPos.z);
            level.addFreshEntity(fireball);
        }

        level.sendParticles(ParticleTypes.DRAGON_BREATH,
                dragonPos.x, dragonPos.y, dragonPos.z,
                20, 2, 2, 2, 0.15);
    }

    // ================================================================
    // ==================== 瞬発攻撃パターン ==========================
    // ================================================================

    // ---------- 2. 雷撃 ----------
    /**
     * プレイヤーの位置周辺に雷を3発落とす。
     */
    private void lightningStrike(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        for (int i = 0; i < 3; i++) {
            double offsetX = (random.nextDouble() - 0.5) * 6;
            double offsetZ = (random.nextDouble() - 0.5) * 6;

            LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(level);
            if (lightning != null) {
                lightning.setPos(
                        target.getX() + offsetX,
                        target.getY(),
                        target.getZ() + offsetZ
                );
                lightning.setVisualOnly(false);
                level.addFreshEntity(lightning);
            }
        }
    }

    // ---------- 3. テレポート急襲 ----------
    /**
     * ドラゴンをプレイヤーの背後にテレポートさせ、周囲にダメージを与える。
     */
    private void teleportAttack(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        Vec3 lookDir = target.getLookAngle().normalize();
        double behindX = target.getX() - lookDir.x * 10;
        double behindZ = target.getZ() - lookDir.z * 10;
        double behindY = target.getY() + 5;

        level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                dragon.getX(), dragon.getY() + 3, dragon.getZ(),
                150, 3, 3, 3, 0.5);

        dragon.setPos(behindX, behindY, behindZ);

        level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                behindX, behindY, behindZ,
                150, 3, 3, 3, 0.5);

        for (ServerPlayer nearby : level.getEntitiesOfClass(ServerPlayer.class,
                dragon.getBoundingBox().inflate(8))) {
            nearby.hurt(level.damageSources().dragonBreath(), 12.0f);
            Vec3 knockback = nearby.position().subtract(dragon.position()).normalize().scale(2.0);
            nearby.push(knockback.x, 0.5, knockback.z);
            nearby.hurtMarked = true;
        }
    }

    // ---------- 4. エンダーブレス強化 ----------
    /**
     * 通常の2倍の範囲、1.5倍のダメージのブレス攻撃エリアをプレイヤー周辺に生成。
     */
    private void enhancedBreath(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        for (int i = 0; i < 5; i++) {
            double x = target.getX() + (random.nextDouble() - 0.5) * 12;
            double z = target.getZ() + (random.nextDouble() - 0.5) * 12;

            AreaEffectCloud cloud = new AreaEffectCloud(level, x, target.getY(), z);
            cloud.setRadius(6.0f);
            cloud.setDuration(300);
            cloud.setRadiusPerTick(-0.005f);
            cloud.setWaitTime(10);
            cloud.addEffect(new MobEffectInstance(MobEffects.HARM, 1, 1));
            cloud.setOwner(dragon);
            level.addFreshEntity(cloud);
        }

        level.sendParticles(ParticleTypes.DRAGON_BREATH,
                target.getX(), target.getY() + 1, target.getZ(),
                100, 6, 1, 6, 0.02);
    }

    // ---------- 5. 衝撃波 ----------
    /**
     * ドラゴンが地表近くにいるとき、周囲のプレイヤーを吹き飛ばしてダメージを与える。
     */
    private void shockwave(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        int surfaceY = getSurfaceY(level, dragon.getX(), dragon.getZ());

        if (dragon.getY() > surfaceY + 10) return;

        List<ServerPlayer> players = level.getEntitiesOfClass(ServerPlayer.class,
                dragon.getBoundingBox().inflate(20));
        for (ServerPlayer player : players) {
            Vec3 knockback = player.position().subtract(dragon.position()).normalize().scale(3.5);
            player.push(knockback.x, 1.2, knockback.z);
            player.hurtMarked = true;
            player.hurt(level.damageSources().dragonBreath(), 10.0f);
        }

        level.sendParticles(ParticleTypes.EXPLOSION,
                dragon.getX(), dragon.getY(), dragon.getZ(),
                40, 8, 2, 8, 0.1);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                dragon.getX(), dragon.getY() + 1, dragon.getZ(),
                30, 6, 1, 6, 0.5);
    }

    // ---------- 7. 突進攻撃 ----------
    /**
     * ドラゴンからプレイヤーに向けてドラゴンファイアボールを連射し、
     * 近距離の場合は直接ダメージを与える。
     */
    private void chargeAttack(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        Vec3 dir = target.position().subtract(dragon.position()).normalize();

        for (int i = 0; i < 5; i++) {
            double spreadX = (random.nextDouble() - 0.5) * 0.3;
            double spreadZ = (random.nextDouble() - 0.5) * 0.3;

            DragonFireball fireball = new DragonFireball(level, dragon,
                    dir.x + spreadX, dir.y, dir.z + spreadZ);
            fireball.setPos(dragon.getX(), dragon.getY() + 3, dragon.getZ());
            level.addFreshEntity(fireball);
        }

        if (dragon.distanceTo(target) < 20) {
            target.hurt(level.damageSources().dragonBreath(), 15.0f);
            Vec3 knockback = dir.scale(3.0).add(0, 0.5, 0);
            target.push(knockback.x, knockback.y, knockback.z);
            target.hurtMarked = true;
        }

        for (double d = 0; d < 15; d += 1.0) {
            Vec3 pos = dragon.position().add(dir.scale(d));
            level.sendParticles(ParticleTypes.DRAGON_BREATH,
                    pos.x, pos.y + 3, pos.z, 3, 0.3, 0.3, 0.3, 0.01);
        }
    }

    // ---------- 8. ファング攻撃（パッシブ：ドラゴンが中心にいる間常時発動） ----------

    /**
     * ドラゴンが中心付近にいるかチェックし、いればファングを回転生成する。
     */
    private void processPassiveFangs(ServerLevel level) {
        List<EnderDragon> dragons = level.getEntitiesOfClass(EnderDragon.class,
                new AABB(-300, -64, -300, 300, 320, 300));
        if (dragons.isEmpty()) return;
        EnderDragon dragon = dragons.get(0);
        if (!dragon.isAlive()) return;

        // ドラゴンが中心(0, 0)付近にいるか判定
        double distFromCenter = Math.sqrt(dragon.getX() * dragon.getX() + dragon.getZ() * dragon.getZ());
        if (distFromCenter > CENTER_RANGE) return;

        spawnRotatingFangs(level, dragon);
    }

    /**
     * ドラゴンの位置を中心に8方向にファングを1波生成する。
     * 毎回回転角度が変わるため、連続呼び出しで回転して見える。
     */
    private void spawnRotatingFangs(ServerLevel level, EnderDragon dragon) {
        double cx = dragon.getX();
        double cz = dragon.getZ();

        double rotationOffset = (level.getGameTime() % 360) * Math.PI / 180.0;

        for (int line = 0; line < 8; line++) {
            double angle = rotationOffset + (line * Math.PI / 4.0);
            for (int i = 1; i <= 8; i++) {
                double dist = i * 1.8;
                double fx = cx + Math.cos(angle) * dist;
                double fz = cz + Math.sin(angle) * dist;
                int fy = getSurfaceY(level, fx, fz);

                EvokerFangs fang = new EvokerFangs(level, fx, fy, fz,
                        (float) angle, i * 2, dragon);
                level.addFreshEntity(fang);
            }
        }
    }

    // ---------- 13. エンダーマン召喚 ----------
    /**
     * プレイヤー周辺のランダムな位置に敵対状態のエンダーマンを10体召喚する。
     * 上限なし、ドラゴン死亡後も残留する。
     */
    private void summonEndermen(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        int summoned = 0;

        for (int i = 0; i < 10; i++) {
            double x = target.getX() + (random.nextDouble() - 0.5) * 40;
            double z = target.getZ() + (random.nextDouble() - 0.5) * 40;
            int y = getSurfaceY(level, x, z);

            EnderMan enderman = EntityType.ENDERMAN.create(level);
            if (enderman != null) {
                enderman.setPos(x, y, z);
                enderman.setTarget(target);
                enderman.setRemainingPersistentAngerTime(Integer.MAX_VALUE);
                enderman.setPersistentAngerTarget(target.getUUID());
                level.addFreshEntity(enderman);
                summoned++;
            }
        }

        if (summoned > 0) {
            Component message = Component.literal("§5§lエンダードラゴンがエンダーマンを" + summoned + "体召喚した！")
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD);
            for (ServerPlayer player : level.players()) {
                player.sendSystemMessage(message);
            }

            level.sendParticles(ParticleTypes.PORTAL,
                    dragon.getX(), dragon.getY(), dragon.getZ(),
                    200, 5, 5, 5, 1.0);
        }
    }
}
