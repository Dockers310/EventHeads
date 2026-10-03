package ru.doksi.eventheads.services;

// RU: Визуальная анимация найденного EventHead: подъём, вращение, частицы и звук.
// EN: Visual collection animation: rising item, rotation, particles and sound.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.util.SchedulerUtil;
import ru.doksi.eventheads.catalog.ParticleCatalog;
import ru.doksi.eventheads.catalog.ParticleSettings;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.events.EventInstance;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import java.util.List;
import java.util.UUID;

public final class AnimationService {
    private final EventHeadsPlugin plugin;

    public AnimationService(EventHeadsPlugin plugin) {
        this.plugin = plugin;
    }

    public void play(EventInstance inst, ItemStack visual, Player player) {
        if (inst == null || inst.location == null || inst.location.getWorld() == null) return;

        final EventDefinition eventDefinition = inst.eventId == null ? null : plugin.events().get(inst.eventId);
        final double entityYOffset = plugin.eventHeadEntityYOffset(eventDefinition);
        final double speed = Math.max(0.05D, eventDefinition != null && eventDefinition.animationSpeed > 0.0D
                ? eventDefinition.animationSpeed
                : plugin.getConfig().getDouble("animation.speed", 1.0D));
        final long baseTicks = Math.max(1L, plugin.getConfig().getLong("animation.duration-seconds", 5L) * 20L);
        final long ticks = Math.max(1L, Math.round(baseTicks / speed));
        final double risePerTick = plugin.getConfig().getDouble("animation.rise-blocks", 1.5D) / ticks;
        final double rotationPerTick = plugin.getConfig().getDouble("animation.rotate-degrees-per-tick", 10.0D) * speed;
        final List<Particle> selectedParticles = parseParticles(eventDefinition);
        final String configuredSound = chooseSound(eventDefinition);
        final int configuredEventCount = inst.eventId == null ? -1
                : plugin.events().getOrDefault(inst.eventId, new EventDefinition("_missing")).collectParticleCount;
        final int particleCount = Math.max(0, configuredEventCount >= 0
                ? configuredEventCount
                : plugin.getConfig().getInt("animation.particle-count", 4));
        final float volume = eventDefinition != null
                ? eventDefinition.soundVolume
                : (float) plugin.getConfig().getDouble("animation.volume", 1.0D);
        final float pitch = eventDefinition != null
                ? eventDefinition.soundPitch
                : (float) plugin.getConfig().getDouble("animation.pitch", 1.0D);
        final long startedAtNanos = System.nanoTime();

        // Paper + Folia: звук выполняется в scheduler самого игрока.
        if (player != null) {
            final Sound startSound = parseSound(configuredSound);
            if (startSound != null && (eventDefinition == null || eventDefinition.soundEnabled)) {
                SchedulerUtil.runEntity(plugin, player, () -> {
                    if (player.isOnline() && player.getWorld() != null) {
                        player.playSound(player.getLocation(), startSound, volume, pitch);
                    }
                });
            }
        }

        // Даже при выключенной анимации собранный EventHead должен исчезнуть.
        if (!plugin.getConfig().getBoolean("animation.enabled", true)) {
            SchedulerUtil.runRegion(plugin, inst.location, () -> {
                ArmorStand original = plugin.spawner().entity(inst.instanceId);
                if (original != null && !original.isDead()) original.remove();
                plugin.spawner().finishCollected(inst.instanceId);
            });
            return;
        }

        // 1) RegionScheduler: безопасно создаём временную сущность в регионе EventHead.
        // 2) EntityScheduler: весь цикл анимации и существование сущности принадлежат ей самой.
        SchedulerUtil.runRegion(plugin, inst.location, () -> {
            final ArmorStand original = plugin.spawner().entity(inst.instanceId);

            final World world = inst.location.getWorld();
            if (world == null) {
                plugin.spawner().finishCollected(inst.instanceId);
                return;
            }

            final Location animationStart = inst.location.clone();
            animationStart.setX(inst.location.getBlockX() + 0.5D);
            animationStart.setY(inst.location.getBlockY() + entityYOffset);
            animationStart.setZ(inst.location.getBlockZ() + 0.5D);
            animationStart.setYaw(inst.yaw);
            animationStart.setPitch(0.0F);

            final ArmorStand armorStand;
            final Location startLocation = animationStart.clone();
            try {
                armorStand = world.spawn(animationStart, ArmorStand.class, as -> {
                    as.setInvisible(true);
                    as.setGravity(false);
                    as.setInvulnerable(true);
                    as.setMarker(true);
                    as.setSmall(eventDefinition != null && eventDefinition.animationSmall);
                    as.setCollidable(false);
                    as.setPersistent(false);
                    as.setCanMove(false);
                    as.setRotation(inst.yaw, 0.0F);
                    as.getPersistentDataContainer().set(
                            new NamespacedKey(plugin, "eventheads_animation"),
                            org.bukkit.persistence.PersistentDataType.BYTE,
                            (byte) 1
                    );
                    if (as.getEquipment() != null && visual != null) as.getEquipment().setHelmet(visual.clone());
                });
            } catch (Throwable ex) {
                if (original != null && !original.isDead()) original.remove();
                plugin.spawner().finishCollected(inst.instanceId);
                plugin.getLogger().warning("Не удалось создать анимацию EventHead " + inst.instanceId
                        + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                return;
            }

            if (original != null && !original.isDead()) original.remove();
            plugin.spawner().trackAnimationEntity(inst.instanceId, armorStand);

            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("[animation] start instance=" + inst.instanceId
                        + " entity=" + armorStand.getUniqueId()
                        + " particle=" + selectedParticles
                        + " count=" + particleCount
                        + " ticks=" + ticks);
            }

            final long[] frame = new long[]{0L};
            final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
            io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask = null;
            try {
            scheduledTask = armorStand.getScheduler().runAtFixedRate(
                    plugin,
                    scheduled -> {
                        final long currentFrame = frame[0]++;
                        if (finished.get() || armorStand.isDead() || !armorStand.isValid() || currentFrame >= ticks) {
                            finished.set(true);
                            scheduled.cancel();
                            if (!armorStand.isDead()) armorStand.remove();
                            plugin.spawner().untrackAnimationEntity(inst.instanceId);
                            plugin.spawner().finishCollected(inst.instanceId);
                            if (plugin.getConfig().getBoolean("debug", false)) {
                                plugin.getLogger().info("[animation] finish instance=" + inst.instanceId + " frames=" + currentFrame);
                            }
                            return;
                        }

                        // Позиция считается от номера кадра: teleportAsync на Folia применяется не мгновенно,
                        // поэтому getLocation() нельзя использовать как источник истины.
                        Location location = startLocation.clone().add(0.0D, risePerTick * (currentFrame + 1), 0.0D);
                        moveEntity(armorStand, location);
                        // Частицы должны быть немного ниже предмета, а не над ним.
                        double particleY = eventDefinition != null && eventDefinition.animationSmall ? 1.15D : 1.60D;
                        Location particleLocation = location.clone().add(0.0D, particleY, 0.0D);
                        armorStand.setHeadPose(
                                armorStand.getHeadPose().setY(
                                        armorStand.getHeadPose().getY() + Math.toRadians(rotationPerTick)
                                )
                        );

                        if (particleCount > 0 && location.getWorld() != null) {
                            int particleKinds=Math.max(1,selectedParticles.size());
                            double elapsedSeconds=(System.nanoTime()-startedAtNanos)/1_000_000_000.0D;
                            for (int particleIndex=0; particleIndex<selectedParticles.size(); particleIndex++) {
                                Particle selectedParticle=selectedParticles.get(particleIndex);
                                try {
                                    String particleName=selectedParticle.name();
                                    ParticleSettings settings=eventDefinition==null?null:eventDefinition.particleSettings.get(particleName);
                                    if(settings==null){settings=new ParticleSettings();}
                                    long duration=settings.durationSeconds<0?Math.max(1,Math.round(baseTicks/20.0D)):settings.durationSeconds;
                                    if(elapsedSeconds>duration)continue;
                                    int kindBase=particleCount/particleKinds;
                                    int kindRemainder=particleCount%particleKinds;
                                    int kindCount=settings.count>=0?settings.count:(kindBase+(particleIndex<kindRemainder?1:0));
                                    if(kindCount<=0)continue;
                                    double spread=Math.max(0.0D,settings.radius);
                                    double extra=settings.speed;
                                    if (selectedParticle == Particle.DUST) {
                                        List<Particle.DustOptions> options=dustOptions(inst,settings);
                                        int colorKinds=Math.max(1,options.size());
                                        for (int colorIndex = 0; colorIndex < options.size(); colorIndex++) {
                                            int amount = kindCount / colorKinds + (colorIndex < kindCount % colorKinds ? 1 : 0);
                                            if (amount > 0) particleLocation.getWorld().spawnParticle(selectedParticle, particleLocation, amount, spread, spread, spread, extra, options.get(colorIndex));
                                        }
                                    } else if(selectedParticle==Particle.NOTE) {
                                        java.util.List<Integer> noteIds = settings.notes.isEmpty() ? (settings.note>=0 ? java.util.List.of(Math.max(0,Math.min(24,settings.note))) : java.util.List.of()) : settings.notes;
                                        int nkinds=Math.max(1,noteIds.size());
                                        if(noteIds.isEmpty()) continue;
                                        for(int ni=0;ni<noteIds.size();ni++){
                                            int amount=kindCount/nkinds+(ni<kindCount%nkinds?1:0);
                                            if(amount<=0)continue;
                                            double noteOffset=Math.max(0,Math.min(24,noteIds.get(ni)))/24.0D;
                                            // NOTE: count=0 is required; offsetX carries the note id (0..24) and therefore the note color/pitch.
                                            // Keep the configured speed as the particle extra value when possible, but clamp it to the NOTE-safe range.
                                            double noteExtra=Math.max(0.0001D,Math.min(1.0D,extra));
                                            for(int k=0;k<amount;k++){
                                                Location noteLocation=particleLocation.clone();
                                                if(spread>0.0D){
                                                    java.util.concurrent.ThreadLocalRandom r=java.util.concurrent.ThreadLocalRandom.current();
                                                    noteLocation.add((r.nextDouble()*2.0D-1.0D)*spread,(r.nextDouble()*2.0D-1.0D)*spread,(r.nextDouble()*2.0D-1.0D)*spread);
                                                }
                                                noteLocation.getWorld().spawnParticle(selectedParticle, noteLocation, 0, noteOffset, 0.0D, 0.0D, noteExtra);
                                            }
                                        }
                                    } else {
                                        Object data = particleData(selectedParticle, particleLocation, dustOptions(inst,settings), visual, settings);
                                        particleLocation.getWorld().spawnParticle(selectedParticle, particleLocation, kindCount, spread, spread, spread, extra, data);
                                    }
                                } catch (IllegalArgumentException ignored) {
                                    // Parameterized particle data must never break the collection animation.
                                }
                            }
                        }
                    },
                    () -> {
                        // Folia retired callback: сущность уже выведена из мира, ничего в мире здесь не меняем.
                        finished.set(true);
                        plugin.spawner().untrackAnimationEntity(inst.instanceId);
                        plugin.spawner().finishCollected(inst.instanceId);
                    },
                    1L,   // ВАЖНО: EntityScheduler требует initialDelay >= 1, иначе IllegalArgumentException
                    1L
            );
            } catch (Throwable ex) {
                plugin.getLogger().warning("Не удалось запустить анимацию EventHead " + inst.instanceId
                        + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            final io.papermc.paper.threadedregions.scheduler.ScheduledTask animationTask = scheduledTask;

            if (animationTask == null) {
                finished.set(true);
                plugin.spawner().untrackAnimationEntity(inst.instanceId);
                if (!armorStand.isDead()) armorStand.remove();
                plugin.spawner().finishCollected(inst.instanceId);
                plugin.getLogger().warning("EntityScheduler не принял анимацию EventHead " + inst.instanceId);
            }
        });
    }

    private static final boolean FOLIA = detectFolia();

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /** Paper: обычный teleport как в 3.4.8. Folia: только teleportAsync. */
    private void moveEntity(org.bukkit.entity.Entity entity, Location location) {
        if (FOLIA) {
            entity.teleportAsync(location);
        } else {
            entity.teleport(location);
        }
    }

    private List<Particle> parseParticles(EventDefinition eventDefinition) {
        java.util.LinkedHashSet<Particle> out = new java.util.LinkedHashSet<>();
        List<String> names = eventDefinition == null ? java.util.List.of() : eventDefinition.collectParticles;
        if (names.isEmpty() && eventDefinition != null && eventDefinition.collectParticle != null && !eventDefinition.collectParticle.isBlank()) names = java.util.List.of(eventDefinition.collectParticle);
        if (names.isEmpty()) names = java.util.List.of(plugin.getConfig().getString("animation.particle", "END_ROD"));
        for (String n : names) { String normalized=n==null?"":n.toUpperCase(java.util.Locale.ROOT); if(!ParticleCatalog.isSupported(normalized)) continue; try { out.add(Particle.valueOf(normalized)); } catch (Exception ignored) {} }
        if (out.isEmpty()) out.add(Particle.END_ROD);
        return new java.util.ArrayList<>(out);
    }

    /** Preview all selected particles and the configured sound near the player without creating an EventHead. */
    public void previewEffects(Player player, EventDefinition event) {
        if(player==null || event==null || player.getWorld()==null) return;
        Location location=player.getLocation().clone().add(0.0D,1.0D,0.0D);
        EventInstance preview=new EventInstance(UUID.randomUUID(),event.id,location,player.getLocation().getYaw(),System.currentTimeMillis(),System.currentTimeMillis()+1000L);
        List<Particle> selected=parseParticles(event);
        int globalCount=Math.max(0,event.collectParticleCount>=0?event.collectParticleCount:plugin.getConfig().getInt("animation.particle-count",4));
        int kinds=Math.max(1,selected.size());
        for(int index=0;index<selected.size();index++){
            Particle particle=selected.get(index);
            ParticleSettings settings=event.particleSettings.getOrDefault(particle.name(),new ParticleSettings());
            int base=globalCount/kinds, rem=globalCount%kinds;
            int count=settings.count>=0?settings.count:(base+(index<rem?1:0));
            if(count<=0)continue;
            double spread=Math.max(0.0D,settings.radius);
            double speed=Math.max(0.0D,settings.speed);
            try{
                if(particle==Particle.DUST){
                    List<Particle.DustOptions> options=dustOptions(preview,settings);
                    int colorKinds=Math.max(1,options.size());
                    for(int ci=0;ci<options.size();ci++){int amount=count/colorKinds+(ci<count%colorKinds?1:0);if(amount>0)location.getWorld().spawnParticle(particle,location,amount,spread,spread,spread,speed,options.get(ci));}
                }else if(particle==Particle.NOTE){
                    java.util.List<Integer> noteIds = settings.notes.isEmpty() ? (settings.note>=0 ? java.util.List.of(Math.max(0,Math.min(24,settings.note))) : java.util.List.of()) : settings.notes;
                    int nkinds=Math.max(1,noteIds.size());
                    if(noteIds.isEmpty()) continue;
                    for(int ni=0;ni<noteIds.size();ni++){
                        int amount=count/nkinds+(ni<count%nkinds?1:0);
                        if(amount<=0)continue;
                        double noteOffset=Math.max(0,Math.min(24,noteIds.get(ni)))/24.0D;
                        double noteExtra=Math.max(0.0001D,Math.min(1.0D,speed));
                        for(int k=0;k<amount;k++){
                            Location noteLocation=location.clone();
                            if(spread>0.0D){
                                java.util.concurrent.ThreadLocalRandom r=java.util.concurrent.ThreadLocalRandom.current();
                                noteLocation.add((r.nextDouble()*2.0D-1.0D)*spread,(r.nextDouble()*2.0D-1.0D)*spread,(r.nextDouble()*2.0D-1.0D)*spread);
                            }
                            noteLocation.getWorld().spawnParticle(particle,noteLocation,0,noteOffset,0.0D,0.0D,noteExtra);
                        }
                    }
                }else{
                    Object data=particleData(particle,location,dustOptions(preview,settings),event.visual,settings);
                    location.getWorld().spawnParticle(particle,location,count,spread,spread,spread,speed,data);
                }
            }catch(Exception ex){plugin.getLogger().fine("Particle preview failed for "+particle+": "+ex.getMessage());}
        }
        playConfiguredSound(player,event);
    }

    public Object previewParticleDataForGui(Particle particle, Location location, ItemStack visual, ParticleSettings settings) {
        return particleData(particle, location, dustOptions(new EventInstance(UUID.randomUUID(), null, location, 0F, 0L, 0L), settings), visual, settings);
    }

    /** Preview only the configured sound. */
    public void previewSound(Player player, EventDefinition event) {
        if(player==null || event==null) return;
        playConfiguredSound(player,event);
    }

    private String chooseSound(EventDefinition event){
        if(event!=null&&!event.soundEnabled)return "";
        if(event!=null){
            java.util.List<String> list=new java.util.ArrayList<>();
            for(String x:event.sounds) if(x!=null&&!x.isBlank()&&!list.contains(x)) list.add(x);
            if(!list.isEmpty()) return list.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(list.size()));
            if(event.sound!=null&&!event.sound.isBlank()) return event.sound;
        }
        return plugin.getConfig().getString("animation.sound","ENTITY_ALLAY_ITEM_GIVEN");
    }

    private void playConfiguredSound(Player player, EventDefinition event){
        if(event!=null&&!event.soundEnabled)return;
        String name=chooseSound(event);
        Sound sound=parseSound(name);
        if(sound==null)return;
        float volume=event!=null&&event.soundVolume>0?event.soundVolume:(float)plugin.getConfig().getDouble("animation.volume",1.0D);
        float pitch=event!=null&&event.soundPitch>0?event.soundPitch:(float)plugin.getConfig().getDouble("animation.pitch",1.0D);
        player.getWorld().playSound(player.getLocation(),sound,volume,pitch);
    }

    private Particle parseParticle(String name) {
        if (name == null || name.isBlank()) {
            return Particle.END_ROD;
        }
        try {
            String normalized=name.trim().toUpperCase(java.util.Locale.ROOT); if(!ParticleCatalog.isSupported(normalized)) return Particle.END_ROD; return Particle.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Unknown animation particle: " + name + ". Using END_ROD.");
            return Particle.END_ROD;
        }
    }

    /** Supplies the payload required by parameterized Java 26.2 particles. */
    private Object particleData(Particle particle, Location location, List<org.bukkit.Particle.DustOptions> dustOptions, ItemStack visual, ParticleSettings settings) {
        Color color = dustOptions.isEmpty() ? Color.WHITE : dustOptions.get(0).getColor();
        return switch (particle) {
            case DUST_COLOR_TRANSITION -> {
                Color from = dustOptions.isEmpty() ? Color.WHITE : dustOptions.get(0).getColor();
                Color to = dustOptions.size() > 1 ? dustOptions.get(dustOptions.size()-1).getColor() : from;
                yield new Particle.DustTransition(from, to, Math.max(0.01F,settings.size));
            }
            case EFFECT, INSTANT_EFFECT -> new Particle.Spell(color, Math.max(0.01F,settings.size));
            case ENTITY_EFFECT, FLASH, TINTED_LEAVES -> color;
            case ITEM -> visual == null ? new ItemStack(org.bukkit.Material.STONE) : visual.clone();
            case BLOCK, BLOCK_CRUMBLE, BLOCK_MARKER, DUST_PILLAR, FALLING_DUST -> location.getBlock().getBlockData();
            case DRAGON_BREATH -> 1.0F;
            case SCULK_CHARGE -> 0.0F;
            case SHRIEK -> 0;
            case TRAIL -> new Particle.Trail(location.clone(), color, (int)Math.max(1,Math.min(200,settings.durationSeconds<0?10:settings.durationSeconds)));
            case GEYSER, GEYSER_PLUME -> new Particle.Geyser(1);
            case GEYSER_BASE, GEYSER_POOF -> new Particle.GeyserBase(1, 0.25F);
            case VIBRATION -> new org.bukkit.Vibration(location.clone(), new org.bukkit.Vibration.Destination.BlockDestination(location.getBlock()), 10);
            default -> null;
        };
    }

    private Sound parseSound(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (!key.contains(":")) key = "minecraft:" + key;
        try {
            Sound byRegistry=Registry.SOUNDS.get(NamespacedKey.fromString(key));
            if(byRegistry!=null)return byRegistry;
            return Sound.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            try { return Sound.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)); } catch (Exception ignored2) {
                plugin.getLogger().warning("Unknown animation sound: " + name + ". Sound will be skipped.");
                return null;
            }
        }
    }

    private List<org.bukkit.Particle.DustOptions> dustOptions(EventInstance inst) {
        return dustOptions(inst,null);
    }

    private List<org.bukkit.Particle.DustOptions> dustOptions(EventInstance inst, ParticleSettings settings) {
        EventDefinition e = inst.eventId == null ? null : plugin.events().get(inst.eventId);
        List<String> colors = new java.util.ArrayList<>();
        if(settings!=null && settings.color!=null && !settings.color.isBlank()){
            for(String raw:settings.color.replace(';',',').split(",")){String c=raw.trim().toUpperCase(java.util.Locale.ROOT);if(c.matches("#[0-9A-F]{6}"))colors.add(c);}
        }
        if(colors.isEmpty() && e != null) colors.addAll(e.collectParticleColors);
        if(colors.isEmpty()) colors.addAll(plugin.getConfig().getStringList("animation.particle-colors"));
        if(colors.isEmpty()) colors = List.of(e == null ? "#FFFFFF" : e.collectParticleColor);
        List<org.bukkit.Particle.DustOptions> result = new java.util.ArrayList<>();
        float size=settings==null?1.0F:Math.max(0.01F,settings.size);
        for (String color : colors) result.add(createDustOptions(color,size));
        return result;
    }

    private org.bukkit.Particle.DustOptions createDustOptions(String hex) { return createDustOptions(hex,1.0F); }

    private org.bukkit.Particle.DustOptions createDustOptions(String hex,float size) {
        try {
            String h = hex == null ? "#FFFFFF" : hex.trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.matches("[0-9a-fA-F]{6}")) {
                int rgb = Integer.parseInt(h, 16);
                return new org.bukkit.Particle.DustOptions(Color.fromRGB(rgb), Math.max(0.01F,size));
            }
        } catch (Exception ignored) { }
        return new org.bukkit.Particle.DustOptions(Color.WHITE, 1.0F);
    }
}
