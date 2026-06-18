package com.strongest.enderdragon.event;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.*;

/**
 * Ender Dragon attack handler managing 16 attack patterns.
 * Barrage attacks last 10 seconds (200 ticks).
 */
public class DragonAttackHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private enum AttackType {
        METEOR_STRIKE(200),
        LIGHTNING_STRIKE(160),
        TELEPORT_ATTACK(300),
        ENHANCED_BREATH(240),
        SHOCKWAVE(100),
        ACID_RAIN(400),
        CHARGE_ATTACK(160),
        BLAZE_FIREBALL(240),
        SCATTER_ARROWS(200),
        TRIDENT_STORM(300),
        POTION_RAIN(400),
        SUMMON_ENDERMEN(3600),
        DRAGON_BREATH_SPRAY(300),
        FIREWORK_RAIN(240),
        SHULKER_BULLET(240),
        TNT_RAIN(300);

        final int cooldownTicks;
        AttackType(int cooldownTicks) { this.cooldownTicks = cooldownTicks; }
    }

    private static class BarrageState {
        final AttackType type;
        final int startTick;
        final int endTick;
        final UUID dragonUUID;
        final UUID targetUUID;

        BarrageState(AttackType type, int startTick, UUID dragonUUID, UUID targetUUID) {
            this.type = type;
            this.startTick = startTick;
            this.endTick = startTick + 200;
            this.dragonUUID = dragonUUID;
            this.targetUUID = targetUUID;
        }

        boolean isFinished(int currentTick) { return currentTick >= endTick; }
    }

    private final Map<AttackType, Integer> lastUsedTick = new EnumMap<>(AttackType.class);
    private final Set<UUID> dragonTridents = new HashSet<>();
    private final Set<UUID> dragonFireworks = new HashSet<>();
    private final Set<UUID> dragonTNTs = new HashSet<>();
    private final List<BarrageState> activeBarrages = new ArrayList<>();
    private int tickCounter = 0;
    private static final int GLOBAL_COOLDOWN = 60;
    private static final double CENTER_RANGE = 20.0;
    private static final AABB SEARCH_AABB = new AABB(-300, -64, -300, 300, 320, 300);
    private int lastAttackTick = -999;
    private final Random random = new Random();

    private static final MobEffect[] DEBUFFS = {
            MobEffects.POISON, MobEffects.WEAKNESS, MobEffects.MOVEMENT_SLOWDOWN,
            MobEffects.WITHER, MobEffects.BLINDNESS, MobEffects.DIG_SLOWDOWN
    };

    private static final int[] FIREWORK_COLORS = {
            0xFF0000, 0xFF7F00, 0xFFFF00, 0x00FF00, 0x0000FF, 0x4B0082, 0x9400D3
    };

    private static final Set<AttackType> BARRAGE_ATTACKS = EnumSet.of(
            AttackType.METEOR_STRIKE, AttackType.BLAZE_FIREBALL,
            AttackType.SCATTER_ARROWS, AttackType.TRIDENT_STORM,
            AttackType.POTION_RAIN, AttackType.ACID_RAIN,
            AttackType.DRAGON_BREATH_SPRAY, AttackType.FIREWORK_RAIN,
            AttackType.SHULKER_BULLET, AttackType.TNT_RAIN
    );

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.dimension() != Level.END) return;

        tickCounter++;
        processActiveBarrages(serverLevel);
        
        Iterator<UUID> fwIter = dragonFireworks.iterator();
        while (fwIter.hasNext()) {
            UUID uuid = fwIter.next();
            net.minecraft.world.entity.Entity e = serverLevel.getEntity(uuid);
            if (e instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity fw) {
                Vec3 v = fw.getDeltaMovement();
                fw.setDeltaMovement(v.x / 1.15, -0.5, v.z / 1.15);
            } else if (e == null) {
                fwIter.remove();
            }
        }

        if (tickCounter % 10 == 0) clampProjectileVelocities(serverLevel);
        if (tickCounter % 10 == 0) processPassiveFangs(serverLevel);
        if (tickCounter % 20 != 0) return;

        List<EnderDragon> dragons = serverLevel.getEntitiesOfClass(EnderDragon.class,
                new AABB(-300, -64, -300, 300, 320, 300));
        if (dragons.isEmpty()) return;
        EnderDragon dragon = dragons.get(0);
        if (!dragon.isAlive()) return;

        ServerPlayer target = findNearestPlayer(serverLevel, dragon);
        if (target == null) return;
        if (tickCounter - lastAttackTick < GLOBAL_COOLDOWN) return;

        List<AttackType> available = new ArrayList<>();
        for (AttackType type : AttackType.values()) {
            int lastUsed = lastUsedTick.getOrDefault(type, -99999);
            if (tickCounter - lastUsed >= type.cooldownTicks) available.add(type);
        }
        if (available.isEmpty()) return;

        AttackType chosen = available.get(random.nextInt(available.size()));
        if (BARRAGE_ATTACKS.contains(chosen)) {
            activeBarrages.add(new BarrageState(chosen, tickCounter, dragon.getUUID(), target.getUUID()));
            if (chosen == AttackType.FIREWORK_RAIN) {
                serverLevel.getServer().getPlayerList().broadcastSystemMessage(Component.literal("§d[Dragon] Firework Rain begins!"), false);
            }
        } else {
            executeInstantAttack(chosen, serverLevel, dragon, target);
        }
        lastUsedTick.put(chosen, tickCounter);
        lastAttackTick = tickCounter;
    }

    private void processActiveBarrages(ServerLevel level) {
        Iterator<BarrageState> iter = activeBarrages.iterator();
        while (iter.hasNext()) {
            BarrageState barrage = iter.next();
            if (barrage.isFinished(tickCounter)) { iter.remove(); continue; }
            if (!(level.getEntity(barrage.dragonUUID) instanceof EnderDragon dragon)) { iter.remove(); continue; }
            if (!dragon.isAlive()) { iter.remove(); continue; }

            ServerPlayer target = null;
            for (ServerPlayer player : level.players()) {
                if (player.getUUID().equals(barrage.targetUUID)) { target = player; break; }
            }
            if (target == null) {
                target = findNearestPlayer(level, dragon);
                if (target == null) { iter.remove(); continue; }
            }
            executeBarrageTick(barrage.type, level, dragon, target);
        }
    }

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
        } else if (event.getProjectile() instanceof FireworkRocketEntity fw) {
            if (dragonFireworks.contains(fw.getUUID())) {
                if (event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult ehr) {
                    net.minecraft.world.entity.Entity hitEntity = ehr.getEntity();
                    if (hitEntity instanceof EnderDragon || hitEntity.getClass().getSimpleName().contains("EnderDragonPart")) {
                        event.setCanceled(true);
                        return;
                    }
                }
                
                if (fw.level() instanceof ServerLevel serverLevel) {
                    serverLevel.broadcastEntityEvent(fw, (byte) 17);
                    List<ServerPlayer> nearby = serverLevel.getEntitiesOfClass(ServerPlayer.class, fw.getBoundingBox().inflate(5.0));
                    for (ServerPlayer p : nearby) {
                        p.hurt(serverLevel.damageSources().fireworks(fw, fw.getOwner()), 15.0f);
                    }
                    fw.discard();
                    dragonFireworks.remove(fw.getUUID());
                }
            }
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (event.getSource().getDirectEntity() instanceof FireworkRocketEntity fw) {
            if (dragonFireworks.contains(fw.getUUID())) {
                event.setAmount(15.0f);
            }
        }
    }

    private ServerPlayer findNearestPlayer(ServerLevel level, EnderDragon dragon) {
        ServerPlayer nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double dist = player.distanceToSqr(dragon);
            if (dist < nearestDist && dist < 200 * 200) { nearestDist = dist; nearest = player; }
        }
        return nearest;
    }

    private static final int MAX_PROJECTILE_LIFETIME = 2400;
    private static final double DESPAWN_DISTANCE_SQ = 128.0 * 128.0;

    private void clampProjectileVelocities(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        for (AbstractHurtingProjectile p : level.getEntitiesOfClass(AbstractHurtingProjectile.class, SEARCH_AABB)) {
            if (p.tickCount > MAX_PROJECTILE_LIFETIME) { p.discard(); continue; }
            boolean nearPlayer = false;
            for (ServerPlayer player : players) {
                if (player.distanceToSqr(p) < DESPAWN_DISTANCE_SQ) { nearPlayer = true; break; }
            }
            if (!nearPlayer) p.discard();
        }
    }

    private int getSurfaceY(ServerLevel level, double x, double z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) x, (int) z);
        return y <= 0 ? 64 : y;
    }

    private ItemStack createRandomFireworkStack() {
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        CompoundTag fireworksTag = new CompoundTag();
        ListTag explosionsList = new ListTag();
        CompoundTag explosion = new CompoundTag();
        byte[] shapes = {0, 1, 2, 4};
        explosion.putByte("Type", shapes[random.nextInt(shapes.length)]);
        explosion.putIntArray("Colors", FIREWORK_COLORS);
        explosion.putBoolean("Flicker", true);
        explosion.putBoolean("Trail", true);
        explosionsList.add(explosion);
        fireworksTag.put("Explosions", explosionsList);
        fireworksTag.putByte("Flight", (byte) 10);
        stack.getOrCreateTag().put("Fireworks", fireworksTag);
        return stack;
    }

    private void executeInstantAttack(AttackType type, ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        switch (type) {
            case LIGHTNING_STRIKE -> lightningStrike(level, dragon, target);
            case TELEPORT_ATTACK -> teleportAttack(level, dragon, target);
            case ENHANCED_BREATH -> enhancedBreath(level, dragon, target);
            case SHOCKWAVE -> shockwave(level, dragon, target);
            case CHARGE_ATTACK -> chargeAttack(level, dragon, target);
            case SUMMON_ENDERMEN -> summonEndermen(level, dragon, target);
            default -> {}
        }
    }

    private void executeBarrageTick(AttackType type, ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        switch (type) {
            case METEOR_STRIKE -> meteorBarrageTick(level, dragon, target);
            case BLAZE_FIREBALL -> blazeFireballBarrageTick(level, dragon, target);
            case SCATTER_ARROWS -> scatterArrowsBarrageTick(level, dragon, target);
            case TRIDENT_STORM -> tridentStormBarrageTick(level, dragon, target);
            case POTION_RAIN -> potionRainBarrageTick(level, dragon, target);
            case ACID_RAIN -> acidRainBarrageTick(level, dragon, target);
            case DRAGON_BREATH_SPRAY -> dragonBreathSprayBarrageTick(level, dragon, target);
            case FIREWORK_RAIN -> fireworkRainBarrageTick(level, dragon, target);
            case SHULKER_BULLET -> shulkerBulletBarrageTick(level, dragon, target);
            case TNT_RAIN -> tntRainBarrageTick(level, dragon, target);
            default -> {}
        }
    }

    private void meteorBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 4 != 0) return;
        double x = target.getX() + (random.nextDouble() - 0.5) * 20;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 20;
        double y = target.getY() + 35 + random.nextDouble() * 10;
        DragonFireball fireball = new DragonFireball(level, dragon, 0, -1, 0);
        fireball.setPos(x, y, z);
        level.addFreshEntity(fireball);
        level.sendParticles(ParticleTypes.FLAME, x, y, z, 5, 1, 1, 1, 0.02);
    }

    private void blazeFireballBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 2 != 0) return;
        double x = target.getX() + (random.nextDouble() - 0.5) * 20;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 20;
        double y = target.getY() + 30 + random.nextDouble() * 10;
        SmallFireball fireball = new SmallFireball(level, x, y, z,
                (random.nextDouble() - 0.5) * 0.1, -1.0, (random.nextDouble() - 0.5) * 0.1);
        fireball.setOwner(dragon);
        level.addFreshEntity(fireball);
        level.sendParticles(ParticleTypes.FLAME, x, y, z, 3, 0.5, 0.5, 0.5, 0.02);
    }

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
        level.sendParticles(ParticleTypes.CRIT, x, y, z, 3, 0.5, 0.5, 0.5, 0.1);
    }

    private void tridentStormBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 5 != 0) return;
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
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 5, 1, 1, 1, 0.2);
    }

    private void potionRainBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 5 != 0) return;
        double x = target.getX() + (random.nextDouble() - 0.5) * 24;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 24;
        double y = target.getY() + 25 + random.nextDouble() * 10;
        ThrownPotion potion = new ThrownPotion(level, x, y, z);
        potion.setOwner(dragon);
        ItemStack potionStack = new ItemStack(Items.LINGERING_POTION);
        MobEffect debuff = DEBUFFS[random.nextInt(DEBUFFS.length)];
        PotionUtils.setCustomEffects(potionStack, List.of(new MobEffectInstance(debuff, 200, 1)));
        potion.setItem(potionStack);
        potion.shoot(0, -1, 0, 0.5f, 5.0f);
        level.addFreshEntity(potion);
        level.sendParticles(ParticleTypes.WITCH, x, y, z, 5, 1, 1, 1, 0.05);
    }

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
        level.sendParticles(ParticleTypes.DRAGON_BREATH, x, y + 1, z, 10, 1.5, 0.5, 1.5, 0.02);
    }

    private void dragonBreathSprayBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 5 != 0) return;
        Vec3 dragonPos = new Vec3(dragon.getX(), dragon.getY() + 5, dragon.getZ());
        Vec3 toTarget = target.position().subtract(dragonPos).normalize();
        double baseYaw = Math.atan2(toTarget.z, toTarget.x);
        double horizontalDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
        double basePitch = Math.atan2(toTarget.y, horizontalDist);
        for (int i = 0; i < 10; i++) {
            double r = random.nextDouble() * (Math.PI / 8.0);
            double theta = random.nextDouble() * 2 * Math.PI;
            double finalYaw = baseYaw + r * Math.cos(theta);
            double finalPitch = basePitch + r * Math.sin(theta);
            double dx = Math.cos(finalPitch) * Math.cos(finalYaw);
            double dy = Math.sin(finalPitch);
            double dz = Math.cos(finalPitch) * Math.sin(finalYaw);
            DragonFireball fireball = new DragonFireball(level, dragon, dx, dy, dz);
            fireball.setPos(dragonPos.x, dragonPos.y, dragonPos.z);
            level.addFreshEntity(fireball);
        }
        level.sendParticles(ParticleTypes.DRAGON_BREATH, dragonPos.x, dragonPos.y, dragonPos.z, 20, 2, 2, 2, 0.15);
    }

    private void fireworkRainBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 2 != 0) return;
        double x = target.getX() + (random.nextDouble() - 0.5) * 20;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 20;
        double spawnY = target.getY() + 30 + random.nextDouble() * 10;
        ItemStack fireworkStack = createRandomFireworkStack();
        FireworkRocketEntity firework = new FireworkRocketEntity(level, x, spawnY, z, fireworkStack);
        firework.setOwner(dragon);
        firework.setDeltaMovement((random.nextDouble() - 0.5) * 0.3, -1.0, (random.nextDouble() - 0.5) * 0.3);
        dragonFireworks.add(firework.getUUID());
        level.addFreshEntity(firework);
        level.sendParticles(ParticleTypes.FIREWORK, x, spawnY, z, 5, 1, 1, 1, 0.1);
    }

    private void shulkerBulletBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        if (tickCounter % 10 != 0) return;
        for (int i = 0; i < 10; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double radius = 10 + random.nextDouble() * 5;
            double sx = target.getX() + Math.cos(angle) * radius;
            double sz = target.getZ() + Math.sin(angle) * radius;
            double sy = target.getY() + 1.0;
            ShulkerBullet bullet = new ShulkerBullet(level, dragon, target, null);
            bullet.setPos(sx, sy, sz);
            level.addFreshEntity(bullet);
        }
        level.sendParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + 1, target.getZ(), 30, 8, 1, 8, 0.1);
    }

    private void tntRainBarrageTick(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        double x = target.getX() + (random.nextDouble() - 0.5) * 20;
        double z = target.getZ() + (random.nextDouble() - 0.5) * 20;
        double y = target.getY() + 30 + random.nextDouble() * 10;
        PrimedTnt tnt = new PrimedTnt(level, x, y, z, dragon);
        tnt.setFuse(80);
        dragonTNTs.add(tnt.getUUID());
        level.addFreshEntity(tnt);
        level.sendParticles(ParticleTypes.FLAME, target.getX(), target.getY() + 30, target.getZ(), 10, 5, 2, 5, 0.05);
    }

    @SubscribeEvent
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        var explosion = event.getExplosion();
        if (explosion.getDirectSourceEntity() instanceof PrimedTnt tnt) {
            if (dragonTNTs.remove(tnt.getUUID())) {
                event.getAffectedBlocks().clear();
            }
        }
    }

    private void lightningStrike(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        for (int i = 0; i < 3; i++) {
            double offsetX = (random.nextDouble() - 0.5) * 6;
            double offsetZ = (random.nextDouble() - 0.5) * 6;
            LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(level);
            if (lightning != null) {
                lightning.setPos(target.getX() + offsetX, target.getY(), target.getZ() + offsetZ);
                lightning.setVisualOnly(false);
                level.addFreshEntity(lightning);
            }
        }
    }

    private void teleportAttack(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        Vec3 lookDir = target.getLookAngle().normalize();
        double behindX = target.getX() - lookDir.x * 10;
        double behindZ = target.getZ() - lookDir.z * 10;
        double behindY = target.getY() + 5;
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, dragon.getX(), dragon.getY() + 3, dragon.getZ(), 150, 3, 3, 3, 0.5);
        dragon.setPos(behindX, behindY, behindZ);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, behindX, behindY, behindZ, 150, 3, 3, 3, 0.5);
        for (ServerPlayer nearby : level.getEntitiesOfClass(ServerPlayer.class, dragon.getBoundingBox().inflate(8))) {
            nearby.hurt(level.damageSources().dragonBreath(), 12.0f);
            Vec3 knockback = nearby.position().subtract(dragon.position()).normalize().scale(2.0);
            nearby.push(knockback.x, 0.5, knockback.z);
            nearby.hurtMarked = true;
        }
    }

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
        level.sendParticles(ParticleTypes.DRAGON_BREATH, target.getX(), target.getY() + 1, target.getZ(), 100, 6, 1, 6, 0.02);
    }

    private void shockwave(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        int surfaceY = getSurfaceY(level, dragon.getX(), dragon.getZ());
        if (dragon.getY() > surfaceY + 10) return;
        List<ServerPlayer> players = level.getEntitiesOfClass(ServerPlayer.class, dragon.getBoundingBox().inflate(20));
        for (ServerPlayer player : players) {
            Vec3 knockback = player.position().subtract(dragon.position()).normalize().scale(3.5);
            player.push(knockback.x, 1.2, knockback.z);
            player.hurtMarked = true;
            player.hurt(level.damageSources().dragonBreath(), 10.0f);
        }
        level.sendParticles(ParticleTypes.EXPLOSION, dragon.getX(), dragon.getY(), dragon.getZ(), 40, 8, 2, 8, 0.1);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, dragon.getX(), dragon.getY() + 1, dragon.getZ(), 30, 6, 1, 6, 0.5);
    }

    private void chargeAttack(ServerLevel level, EnderDragon dragon, ServerPlayer target) {
        Vec3 dir = target.position().subtract(dragon.position()).normalize();
        for (int i = 0; i < 5; i++) {
            double spreadX = (random.nextDouble() - 0.5) * 0.3;
            double spreadZ = (random.nextDouble() - 0.5) * 0.3;
            DragonFireball fireball = new DragonFireball(level, dragon, dir.x + spreadX, dir.y, dir.z + spreadZ);
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
            level.sendParticles(ParticleTypes.DRAGON_BREATH, pos.x, pos.y + 3, pos.z, 3, 0.3, 0.3, 0.3, 0.01);
        }
    }

    private void processPassiveFangs(ServerLevel level) {
        List<EnderDragon> dragons = level.getEntitiesOfClass(EnderDragon.class, new AABB(-300, -64, -300, 300, 320, 300));
        if (dragons.isEmpty()) return;
        EnderDragon dragon = dragons.get(0);
        if (!dragon.isAlive()) return;
        double distFromCenter = Math.sqrt(dragon.getX() * dragon.getX() + dragon.getZ() * dragon.getZ());
        if (distFromCenter > CENTER_RANGE) return;
        spawnRotatingFangs(level, dragon);
    }

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
                EvokerFangs fang = new EvokerFangs(level, fx, fy, fz, (float) angle, i * 2, dragon);
                level.addFreshEntity(fang);
            }
        }
    }

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
            String msg = "\u00a75\u00a7l\u30a8\u30f3\u30c0\u30fc\u30c9\u30e9\u30b4\u30f3\u304c\u30a8\u30f3\u30c0\u30fc\u30de\u30f3\u3092" + summoned + "\u4f53\u53ec\u559a\u3057\u305f\uff01";
            Component message = Component.literal(msg).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD);
            for (ServerPlayer player : level.players()) player.sendSystemMessage(message);
            level.sendParticles(ParticleTypes.PORTAL, dragon.getX(), dragon.getY(), dragon.getZ(), 200, 5, 5, 5, 1.0);
        }
    }
}
