package ru.doksi.eventheads.events;

// RU: Поиск допустимых мест, спавн, восстановление после рестарта, лимиты и время жизни.
// EN: Location search, spawning, restart restoration, limits and lifetime management.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.players.PlayerLike;
import ru.doksi.eventheads.services.EconomyAdapter;
import ru.doksi.eventheads.util.SchedulerUtil;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

public final class SpawnManager {
    private final EventHeadsPlugin plugin;
    private final Map<UUID,EventInstance> instances=new ConcurrentHashMap<>();
    private final Set<UUID> lifecycleTracked=ConcurrentHashMap.newKeySet();
    private final Map<UUID,ArmorStand> entities=new ConcurrentHashMap<>();
    /** Instance IDs expected from active.yml; used to distinguish valid restored heads from old orphaned heads before restore finishes. */
    private final Set<UUID> expectedInstanceIds=ConcurrentHashMap.newKeySet();
    /** ArmorStand/EventHead, который временно используется как сущность анимации сбора. */
    private final Map<UUID,Entity> animationEntities=new ConcurrentHashMap<>();
    /** UUIDs of entities currently playing a collection animation; UUID-only to avoid cross-region entity access. */
    private final Set<UUID> animationEntityIds=ConcurrentHashMap.newKeySet();
    // Ключ карты — instanceId EventHead.
    /** Ложные невидимые ArmorStand для усложнения поиска EventHead через ESP/EntityGlow. */
    private final Map<UUID,List<ArmorStand>> decoys=new ConcurrentHashMap<>();
    /** O(1) lookup for outgoing equipment packets. */
    private final Set<Integer> decoyEntityIds=ConcurrentHashMap.newKeySet();
    private final NamespacedKey instanceKey;
    private final NamespacedKey decoyKey;
    private final NamespacedKey decoyEventKey;
    private final NamespacedKey decoyTierKey;
    private final Map<String,Long> nextAttempt=new ConcurrentHashMap<>();
    /** Следующее время появления очередного набора анти-ESP приманок для конкретного экземпляра. */
    private final Map<UUID,Long> decoyNextSpawn=new ConcurrentHashMap<>();
    private final Map<UUID,Location> playerLocations=new ConcurrentHashMap<>();
    public SpawnManager(EventHeadsPlugin p){plugin=p;instanceKey=new NamespacedKey(p,"instance");decoyKey=new NamespacedKey(p,"decoy");decoyEventKey=new NamespacedKey(p,"decoy_event");decoyTierKey=new NamespacedKey(p,"decoy_tier");}
    public Map<UUID,EventInstance> instances(){return Collections.unmodifiableMap(instances);}
    public ArmorStand entity(UUID id){return entities.get(id);}

    /** Loads the persisted runtime IDs before any chunk/entity cleanup runs. */
    public void prepareExpectedInstances(){
        expectedInstanceIds.clear();
        for(EventInstance instance:plugin.data().loadInstances().values()){
            if(instance!=null && !instance.collected && instance.instanceId!=null){
                expectedInstanceIds.add(instance.instanceId);
            }
        }
    }
    /** Cleans EventHead entities around a player without reading world state off-region. */
    public void cleanupAroundPlayer(Player player, int chunkRadius){
        if(player==null) return;
        SchedulerUtil.runEntity(plugin, player, () -> {
            Location center = player.getLocation().clone();
            World world = center.getWorld();
            if(world==null) return;
            int cx = center.getBlockX() >> 4;
            int cz = center.getBlockZ() >> 4;
            int radius = Math.max(0, Math.min(4, chunkRadius));
            for(int dx=-radius; dx<=radius; dx++){
                for(int dz=-radius; dz<=radius; dz++){
                    final int fx=cx+dx, fz=cz+dz;
                    final Location regionLocation = new Location(world, (fx<<4)+1, center.getY(), (fz<<4)+1);
                    SchedulerUtil.runRegion(plugin, regionLocation, () -> {
                        org.bukkit.Chunk chunk=world.getChunkAt(fx,fz);
                        cleanupChunk(chunk);
                    });
                }
            }
        });
    }

    /**
     * Ручная зачистка «залипших» стоек EventHeads вокруг игрока (Paper + Folia).
     * Удаляет только сущности с метками плагина: анимационные, приманки и EventHead,
     * которые не числятся в текущем реестре. Живые активные EventHead не трогаются.
     */
    public void purgeAroundPlayer(Player player,int chunkRadius,java.util.function.IntConsumer done){
        if(player==null){if(done!=null)done.accept(0);return;}
        SchedulerUtil.runEntity(plugin,player,()->{
            Location center=player.getLocation().clone();
            World world=center.getWorld();
            if(world==null){if(done!=null)done.accept(0);return;}
            int cx=center.getBlockX()>>4,cz=center.getBlockZ()>>4;
            int radius=Math.max(0,Math.min(12,chunkRadius));
            int total=(radius*2+1)*(radius*2+1);
            java.util.concurrent.atomic.AtomicInteger remaining=new java.util.concurrent.atomic.AtomicInteger(total);
            java.util.concurrent.atomic.AtomicInteger removed=new java.util.concurrent.atomic.AtomicInteger();
            for(int dx=-radius;dx<=radius;dx++){
                for(int dz=-radius;dz<=radius;dz++){
                    final int fx=cx+dx,fz=cz+dz;
                    Runnable finish=()->{
                        if(remaining.decrementAndGet()==0&&done!=null){
                            SchedulerUtil.runEntity(plugin,player,()->done.accept(removed.get()));
                        }
                    };
                    if(SchedulerUtil.runRegion(plugin,new Location(world,(fx<<4)+1,center.getY(),(fz<<4)+1),()->{
                        try{
                            if(world.isChunkLoaded(fx,fz)) removed.addAndGet(purgeChunk(world.getChunkAt(fx,fz)));
                        }finally{finish.run();}
                    })==null) finish.run();
                }
            }
        });
    }

    private int purgeChunk(org.bukkit.Chunk chunk){
        int count=0;
        for(Entity entity:chunk.getEntities().clone()){
            if(!(entity instanceof ArmorStand as)||as.isDead())continue;
            var pdc=as.getPersistentDataContainer();
            boolean kill=false;
            if(pdc.has(animationKey(),PersistentDataType.BYTE)){
                kill=!animationEntityIds.contains(as.getUniqueId());
            }else if(pdc.has(decoyKey,PersistentDataType.STRING)){
                kill=!decoyEntityIds.contains(as.getEntityId());
            }else{
                String raw=pdc.get(instanceKey,PersistentDataType.STRING);
                if(raw!=null){
                    try{
                        UUID id=UUID.fromString(raw);
                        ArmorStand registered=entities.get(id);
                        kill=!instances.containsKey(id)||registered==null||!registered.getUniqueId().equals(as.getUniqueId());
                    }catch(Exception ex){kill=true;}
                }
            }
            if(kill){as.remove();count++;}
        }
        return count;
    }

    public void trackPlayer(Player player){if(player!=null)SchedulerUtil.runEntity(plugin,player,()->playerLocations.put(player.getUniqueId(),player.getLocation().clone()));}
    public void untrackPlayer(Player player){if(player!=null)playerLocations.remove(player.getUniqueId());}
    /** Returns true only for currently registered Anti-ESP decoy ArmorStands. */
    public boolean isAntiEspDecoyEntity(int entityId){return decoyEntityIds.contains(entityId);}
    public void despawnEntitiesOnly(){
        for(ArmorStand as:new ArrayList<>(entities.values())) if(as!=null) SchedulerUtil.runEntity(plugin,as,as::remove);
        entities.clear();
        for(Entity display:new ArrayList<>(animationEntities.values())) if(display!=null) SchedulerUtil.runEntity(plugin,display,display::remove);
        animationEntities.clear();
        animationEntityIds.clear();
        for(UUID id:new ArrayList<>(decoys.keySet()))removeDecoys(id);
        decoyEntityIds.clear();
    }
    public int count(String id){return (int)instances.values().stream().filter(i->i.eventId.equals(id)).count();}
    /**
     * При старте сервера удаляем ВСЕ старые EventHeads ArmorStand.
     * После этого restore() создаст только те экземпляры, которые есть в active.yml
     * и ещё не просрочены. Это исключает двойные/фантомные события после рестарта.
     */
    public void cleanupOrphanedEntities(){
        // На старте сначала забываем старый runtime-реестр. Затем loaded chunks
        // будут проверены в своих регионах: так Folia не получает cross-region entity calls.
        entities.clear();
        animationEntities.clear();
        animationEntityIds.clear();
        decoyEntityIds.clear();
        decoys.clear();
    }

    /**
     * Удаляет старые визуальные сущности в конкретном регионе.
     * EventHead считается актуальным только если его UUID зарегистрирован в текущем runtime.
     */
    public void cleanupChunk(org.bukkit.Chunk chunk){
        if(chunk==null)return;
        Entity[] snapshot=chunk.getEntities().clone();
        cleanupEntities(Arrays.asList(snapshot));
    }

    /** Folia-safe cleanup used both by chunk loading and by the startup sweep. */
    public void cleanupEntities(Collection<? extends Entity> loadedEntities){
        if(loadedEntities==null)return;
        for(Entity entity:new ArrayList<>(loadedEntities)){
            if(entity==null||entity.isDead())continue;
            var pdc=entity.getPersistentDataContainer();

            // Сущность с animation marker удаляем только если она уже не числится
            // в активной анимации. Во время анимации Cleanup/EntitiesLoad не должны её прерывать.
            if(pdc.has(animationKey(),PersistentDataType.BYTE)){
                boolean tracked=animationEntityIds.contains(entity.getUniqueId());
                if(!tracked){
                    entity.remove();
                    animationEntities.entrySet().removeIf(entry -> entity.getUniqueId().equals(entry.getKey()));
                    animationEntityIds.remove(entity.getUniqueId());
                }
                continue;
            }

            if(entity instanceof ArmorStand as){
                if(pdc.has(decoyKey,PersistentDataType.STRING)){
                    if(!decoyEntityIds.contains(as.getEntityId()))as.remove();
                    continue;
                }

                String raw=pdc.get(instanceKey,PersistentDataType.STRING);
                if(raw==null)continue;
                try{
                    UUID id=UUID.fromString(raw);
                    ArmorStand registered=entities.get(id);
                    boolean expected=expectedInstanceIds.contains(id);
                    if(!expected && (registered==null||registered.isDead()||registered!=as)){
                        // Старый/осиротевший EventHead не присутствует в ожидаемом active.yml/runtime.
                        as.remove();
                    }
                }catch(Exception ex){
                    as.remove();
                }
            }
        }
    }


    /**
     * 3.5.0: удаляет сохранившиеся collected=true и один раз сбрасывает старый runtime
     * от старых 3.4.8/3.4.9 тестовых сборок, оставляя события/точки/шаблоны/настройки нетронутыми.
     */
    public void cleanupLegacyRuntimeFor350Once(){
        java.io.File marker=new java.io.File(plugin.getDataFolder(),"data/active/.runtime-cleaned-3.5.0-r1");
        if(marker.exists()) return;

        Map<UUID,EventInstance> stored=plugin.data().loadInstances();
        int removed=0;
        for(EventInstance old:stored.values()){
            if(old==null||old.instanceId==null) continue;
            removed++;
            if(old.location!=null&&old.location.getWorld()!=null){
                final UUID id=old.instanceId;
                final Location location=old.location.clone();
                SchedulerUtil.runRegion(plugin,location,()->removePhysicalInstanceAt(id,location));
            }
        }

        expectedInstanceIds.clear();
        instances.clear();
        entities.clear();
        animationEntities.clear();
        animationEntityIds.clear();
        decoys.clear();
        decoyEntityIds.clear();
        plugin.data().clearAllInstances();

        try{
            java.io.File parent=marker.getParentFile();
            if(parent!=null) parent.mkdirs();
            marker.createNewFile();
        }catch(java.io.IOException ex){
            plugin.getLogger().warning("Не удалось создать marker очистки старого runtime: "+ex.getMessage());
        }

        if(plugin.getConfig().getBoolean("debug",false)){
            plugin.getLogger().info("[3.5.0] Удалён старый runtime EventHeads: "+removed+".");
        }
    }

    private NamespacedKey animationKey(){
        return new NamespacedKey(plugin,"eventheads_animation");
    }

    public void trackAnimationEntity(UUID instanceId,Entity entity){
        if(instanceId!=null&&entity!=null){
            animationEntities.put(instanceId,entity);
            animationEntityIds.add(entity.getUniqueId());
        }
    }

    public void untrackAnimationEntity(UUID instanceId){
        if(instanceId!=null){
            Entity entity=animationEntities.remove(instanceId);
            if(entity!=null) animationEntityIds.remove(entity.getUniqueId());
        }
    }

    /**
     * Применяет новый диапазон времени жизни к уже существующим экземплярам события.
     * По умолчанию изменение min/max lifetime влияет на новые спавны; этот метод используется
     * отдельной кнопкой «Применить к активным», чтобы администратор сам решал, менять ли уже
     * существующие предметы.
     */
    public void applyLifetimeToActive(String eventId,Consumer<Integer> callback){
        EventDefinition e=plugin.events().get(eventId);
        if(e==null){if(callback!=null)callback.accept(0);return;}
        List<EventInstance> targets=new ArrayList<>();
        for(EventInstance i:instances.values()) if(i.eventId.equals(eventId)) targets.add(i);
        if(targets.isEmpty()){if(callback!=null)callback.accept(0);return;}
        java.util.concurrent.atomic.AtomicInteger changed=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger remaining=new java.util.concurrent.atomic.AtomicInteger(targets.size());
        for(EventInstance i:targets){
            if(i.location==null||i.location.getWorld()==null){if(remaining.decrementAndGet()==0 && callback!=null)callback.accept(changed.get());continue;}
            SchedulerUtil.runRegion(plugin,i.location,()->{
                synchronized(i){
                    i.expiresAt=System.currentTimeMillis()+randomLifetime(e)*1000L;
                    plugin.data().saveInstance(i);
                    changed.incrementAndGet();
                }
                if(remaining.decrementAndGet()==0 && callback!=null) SchedulerUtil.runGlobal(plugin,()->callback.accept(changed.get()));
            });
        }
    }
    public void restore(){
        long now=System.currentTimeMillis();
        for(EventInstance old:plugin.data().loadInstances().values()){
            // Старые версии могли оставить collected=true в active.yml, если награда/анимация
            // завершилась нештатно. Такой экземпляр больше нельзя восстанавливать в мир.
            if(old.collected){
                expectedInstanceIds.remove(old.instanceId);
                if(old.location!=null && old.location.getWorld()!=null){
                    final UUID staleId=old.instanceId;
                    final Location staleLocation=old.location.clone();
                    SchedulerUtil.runRegion(plugin,staleLocation,()->removePhysicalInstanceAt(staleId,staleLocation));
                }
                SchedulerUtil.runGlobal(plugin,()->plugin.data().removeInstance(old.instanceId));
                continue;
            }
            EventDefinition e=plugin.events().get(old.eventId);
            if(e==null||old.expiresAt<=now||!e.currentlyScheduled()){
                expectedInstanceIds.remove(old.instanceId);
                SchedulerUtil.runGlobal(plugin,()->plugin.data().removeInstance(old.instanceId));
                continue;
            }
            if(old.location==null||old.location.getWorld()==null){
                expectedInstanceIds.remove(old.instanceId);
                SchedulerUtil.runGlobal(plugin,()->plugin.data().removeInstance(old.instanceId));
                continue;
            }
            SchedulerUtil.runRegion(plugin,old.location,()->{
                if(!isValidSavedLocation(e,old.location)){
                    expectedInstanceIds.remove(old.instanceId);
                    SchedulerUtil.runGlobal(plugin,()->plugin.data().removeInstance(old.instanceId));
                    schedule(old.eventId);
                    return;
                }
                spawnAtRaw(e,old.location,old.yaw,old.instanceId,old.spawnedAt,old.expiresAt,false);
            });
        }
    }
    /**
     * Анти-ESP приманки: случайные невидимые ArmorStand вокруг настоящего EventHead.
     * Новый набор появляется после случайной задержки, живёт ограниченное время и
     * после исчезновения получает новые случайные позиции. Это намеренно не EventHead:
     * у приманки нет награды, она не сохраняется как активный экземпляр и не содержит
     * предметной логики настоящего события.
     */
    private void refreshDecoys(){
        long now=System.currentTimeMillis();
        for(EventInstance inst:new ArrayList<>(instances.values())){
            if(inst.location==null||inst.location.getWorld()==null)continue;
            SchedulerUtil.runRegion(plugin,inst.location,()->maintainDecoysAtRegion(inst,now));
        }
        for(UUID id:new ArrayList<>(decoys.keySet()))if(!instances.containsKey(id))removeDecoys(id);
    }

    private void maintainDecoysAtRegion(EventInstance inst,long now){
        List<ArmorStand> list=decoys.computeIfAbsent(inst.instanceId,k->new java.util.concurrent.CopyOnWriteArrayList<>());
        list.removeIf(a->a==null||a.isDead()||!a.isValid());
        if(!list.isEmpty())return;
        long next=decoyNextSpawn.getOrDefault(inst.instanceId,0L);if(now<next)return;
        int desiredMin=Math.max(0,plugin.getConfig().getInt("security.anti-esp-decoys-min",2));
        int desiredMax=Math.max(desiredMin,plugin.getConfig().getInt("security.anti-esp-decoys-max",4));
        int target=desiredMin==desiredMax?desiredMin:ThreadLocalRandom.current().nextInt(desiredMin,desiredMax+1);
        for(int i=0;i<target;i++)spawnDecoy(inst,list);
        decoyNextSpawn.put(inst.instanceId,now+randomDecoyDelay()*1000L);
    }

    private long randomDecoyDelay(){
        long min=Math.max(0,plugin.getConfig().getLong("security.anti-esp-decoys-delay-min-seconds",10));
        long max=Math.max(min,plugin.getConfig().getLong("security.anti-esp-decoys-delay-max-seconds",30));
        return min==max?min:ThreadLocalRandom.current().nextLong(min,max+1);
    }

    private void spawnDecoy(EventInstance inst,List<ArmorStand> list){
        if(inst.location==null||inst.location.getWorld()==null)return;
        double minR=Math.max(1.0,plugin.getConfig().getDouble("security.anti-esp-decoys-radius-min",3.5));
        double maxR=Math.max(minR,plugin.getConfig().getDouble("security.anti-esp-decoys-radius-max",10.0));
        for(int attempt=0;attempt<16;attempt++){
            double angle=ThreadLocalRandom.current().nextDouble(Math.PI*2);
            double radius=minR==maxR?minR:ThreadLocalRandom.current().nextDouble(minR,maxR);
            Location d=inst.location.clone().add(Math.cos(angle)*radius,0,Math.sin(angle)*radius);
            d.setX(Math.floor(d.getX())+0.5); d.setZ(Math.floor(d.getZ())+0.5);
            if(!d.getChunk().isLoaded())d.getChunk().load();
            String tier=pickDecoyTier();
            d.setY(inst.location.getBlockY()+decoyYOffset(tier));
            if(d.distanceSquared(inst.location)<minR*minR) continue;
            boolean nearOther=list.stream().filter(Objects::nonNull).anyMatch(a->!a.isDead()&&a.getLocation().distanceSquared(d)<2.25);
            if(nearOther) continue;
            ArmorStand as=inst.location.getWorld().spawn(d,ArmorStand.class,a->{
                a.setInvisible(true);
                a.setGravity(false);
                a.setInvulnerable(true);
                a.setBasePlate(false);
                a.setArms(false);
                a.setCollidable(false);
                a.setSilent(true);
                a.setPersistent(false);
                a.setCanPickupItems(false);
                a.setRotation(ThreadLocalRandom.current().nextFloat()*360f,0f);
                // Ложная стойка имеет обычный прямой наклон головы: 0 градусов.
                a.setHeadPose(new org.bukkit.util.EulerAngle(0,0,0));
                // Для приманки используется тот же визуальный предмет/голова, что и у настоящего EventHead.
                // Обычный игрок стойку не видит, а клиент с ESP получает максимально правдоподобную цель.
                ItemStack decoyVisual=plugin.items().createInstance(inst.eventId,plugin.events().get(inst.eventId)==null?null:plugin.events().get(inst.eventId).visual);
                if(a.getEquipment()!=null) a.getEquipment().setHelmet(decoyVisual);
                a.getPersistentDataContainer().set(decoyKey,PersistentDataType.STRING,inst.instanceId.toString());
                a.getPersistentDataContainer().set(decoyEventKey,PersistentDataType.STRING,inst.eventId);
                a.getPersistentDataContainer().set(decoyTierKey,PersistentDataType.STRING,tier);
                a.setDisabledSlots(org.bukkit.inventory.EquipmentSlot.HEAD,org.bukkit.inventory.EquipmentSlot.CHEST,org.bukkit.inventory.EquipmentSlot.LEGS,org.bukkit.inventory.EquipmentSlot.FEET,org.bukkit.inventory.EquipmentSlot.HAND,org.bukkit.inventory.EquipmentSlot.OFF_HAND);
            });
            list.add(as);
            decoyEntityIds.add(as.getEntityId());
            long minLife=Math.max(1,plugin.getConfig().getLong("security.anti-esp-decoys-lifetime-min-seconds",15));
            long maxLife=Math.max(minLife,plugin.getConfig().getLong("security.anti-esp-decoys-lifetime-max-seconds",30));
            long life=minLife==maxLife?minLife:ThreadLocalRandom.current().nextLong(minLife,maxLife+1);
            SchedulerUtil.runEntityLater(plugin,as,()->{if(!as.isDead())as.remove();list.remove(as);decoyEntityIds.remove(as.getEntityId());},life*20L);
            if(plugin.getConfig().getBoolean("security.anti-esp-decoys-particles",false)) d.getWorld().spawnParticle(Particle.END_ROD,d.clone().add(0,1.2,0),2,0.05,0.08,0.05,0);
            return;
        }
    }

    private void removeDecoys(UUID instanceId){
        decoyNextSpawn.remove(instanceId);List<ArmorStand> list=decoys.remove(instanceId);
        if(list!=null)for(ArmorStand as:list)if(as!=null){SchedulerUtil.runEntity(plugin,as,()->{decoyEntityIds.remove(as.getEntityId());as.remove();});}
    }

    /** Select buried/ground/air placement according to configured shares. */
    private String pickDecoyTier(){
        double surface=Math.max(0.0D,plugin.getConfig().getDouble("security.anti-esp-decoys-surface-share",0.25D));
        double air=Math.max(0.0D,plugin.getConfig().getDouble("security.anti-esp-decoys-air-share",0.15D));
        if(surface+air>1.0D){double k=1.0D/(surface+air);surface*=k;air*=k;}
        double r=ThreadLocalRandom.current().nextDouble();
        if(r<surface)return "ground";
        if(r<surface+air)return "air";
        return "buried";
    }

    private double decoyYOffset(String tier){
        String base="security.anti-esp-decoys-y-"+tier+"-";
        double min,max;
        switch(tier){
            case "ground" -> {min=plugin.getConfig().getDouble(base+"min",-0.1D);max=plugin.getConfig().getDouble(base+"max",0.2D);}
            case "air" -> {min=plugin.getConfig().getDouble(base+"min",1.5D);max=plugin.getConfig().getDouble(base+"max",3.0D);}
            default -> {min=plugin.getConfig().getDouble(base+"min",-2.0D);max=plugin.getConfig().getDouble(base+"max",-1.0D);}
        }
        if(max<min){double t=min;min=max;max=t;}
        return min==max?min:ThreadLocalRandom.current().nextDouble(min,max);
    }

    /** Remove every EventHeads Anti-ESP decoy immediately, including orphaned ones. */
    public int removeAllDecoys(){
        java.util.concurrent.atomic.AtomicInteger removed=new java.util.concurrent.atomic.AtomicInteger();
        for(UUID id:new ArrayList<>(decoys.keySet())){
            List<ArmorStand> list=decoys.remove(id); decoyNextSpawn.remove(id);
            if(list!=null)for(ArmorStand as:list)if(as!=null){removed.incrementAndGet();SchedulerUtil.runEntity(plugin,as,()->{decoyEntityIds.remove(as.getEntityId());if(!as.isDead())as.remove();});}
        }
        return removed.get();
    }

    public void tick(){
        long now=System.currentTimeMillis();
        if(plugin.getConfig().getBoolean("security.anti-esp-decoys",true))refreshDecoys();
        else if(!decoys.isEmpty())removeAllDecoys();
        for(EventInstance i:new ArrayList<>(instances.values())){
            if(i.collected) continue;
            EventDefinition d=plugin.events().get(i.eventId);
            if(i.expiresAt<=now||d==null||!d.currentlyScheduled()){remove(i.instanceId);schedule(i.eventId);continue;}
            if(i.location!=null)SchedulerUtil.runRegion(plugin,i.location,()->maintainInstance(i.instanceId));
        }
        for(EventDefinition e:plugin.events().values()){
            if(!e.currentlyScheduled()||count(e.id)>=e.maxActive)continue;
            long next=nextAttempt.getOrDefault(e.id,0L);if(now<next)continue;
            if(e.spawnChance<100&&ThreadLocalRandom.current().nextDouble(100)>=e.spawnChance){schedule(e.id);continue;}
            SpawnHint hint=chooseSpawnHint(e);
            if(hint==null){nextAttempt.put(e.id,now+Math.max(1,plugin.getConfig().getLong("spawn.failed-attempt-delay-seconds",5))*1000L);continue;}
            nextAttempt.put(e.id,now+Math.max(1,plugin.getConfig().getLong("spawn.failed-attempt-delay-seconds",5))*1000L);
            SchedulerUtil.runRegion(plugin,hint.location,()->{if(spawnOneAtHint(e,hint))schedule(e.id);});
        }
    }

    private void maintainInstance(UUID id){
        EventInstance i=instances.get(id);if(i==null)return;
        ArmorStand visual=entities.get(id);
        if(visual==null||visual.isDead()||!visual.isValid()){remove(id);schedule(i.eventId);}
    }
    public void schedule(String id){EventDefinition e=plugin.events().get(id);if(e==null)return;long min=Math.max(0,e.minDelaySeconds),max=Math.max(min,e.maxDelaySeconds);long d=min==max?min:ThreadLocalRandom.current().nextLong(min,max+1);nextAttempt.put(e.id,System.currentTimeMillis()+d*1000L);}

    public void testSpawn(Player p,String id,Consumer<Boolean> callback){
        if(p==null){if(callback!=null)callback.accept(false);return;}
        SchedulerUtil.runEntity(plugin,p,()->{
            EventDefinition e=plugin.events().get(id);if(e==null){if(callback!=null)callback.accept(false);return;}
            SpawnHint hint=chooseSpawnHint(e);if(hint==null){if(callback!=null)callback.accept(false);return;}
            SchedulerUtil.runRegion(plugin,hint.location,()->{
                Location candidate=resolveCandidateLocation(e,hint);
                boolean ok=candidate!=null && canSpawn(e,candidate,true) && spawnAtRaw(e,candidate,e.randomRotation?randomYaw():candidate.getYaw(),UUID.randomUUID(),System.currentTimeMillis(),System.currentTimeMillis()+randomLifetime(e)*1000L,true);
                SchedulerUtil.runEntity(plugin,p,()->{if(callback!=null)callback.accept(ok);});
            });
        });
    }
    private boolean spawnOneAtHint(EventDefinition e,SpawnHint hint){
        Location l=resolveCandidateLocation(e,hint);if(l==null)return false;
        return canSpawn(e,l,false) && spawnAtRaw(e,l,e.randomRotation?randomYaw():l.getYaw(),UUID.randomUUID(),System.currentTimeMillis(),System.currentTimeMillis()+randomLifetime(e)*1000L,false);
    }
    private int regionCount(EventDefinition e){
        int n=0;for(EventInstance i:instances.values()){
            EventDefinition other=plugin.events().get(i.eventId);if(other==null)continue;
            if(e.worldGuardRegions.stream().anyMatch(r->other.worldGuardRegions.stream().anyMatch(x->x.equalsIgnoreCase(r))))n++;
        }return n;
    }
    private float randomYaw(){return ThreadLocalRandom.current().nextInt(24)*15f;}
    private long randomLifetime(EventDefinition e){long min=Math.max(1,e.minLifetimeSeconds),max=Math.max(min,e.maxLifetimeSeconds);return min==max?min:ThreadLocalRandom.current().nextLong(min,max+1);}

    private record SpawnHint(Location location,boolean exactPoint,String regionName){}

    private SpawnHint chooseSpawnHint(EventDefinition e){
        if(e.spawnMode==EventDefinition.SpawnMode.POINTS||e.spawnMode==EventDefinition.SpawnMode.MIXED){
            List<SpawnPoint> pts=new ArrayList<>(e.points);Collections.shuffle(pts);
            for(SpawnPoint point:pts)if(point.enabled&&(point.chance>=100||ThreadLocalRandom.current().nextDouble(100)<point.chance))return new SpawnHint(point.location.clone(),true,null);
            if(e.spawnMode==EventDefinition.SpawnMode.POINTS)return null;
        }
        if(!e.worldGuardRegions.isEmpty()&&plugin.worldGuard().available()){
            List<String> regions=new ArrayList<>(e.worldGuardRegions);Collections.shuffle(regions);
            World w=e.worldGuardWorld==null?null:Bukkit.getWorld(e.worldGuardWorld);if(w==null)return null;
            // Do not query WorldGuard region data from the global scheduler. The region task will
            // select the actual coordinate from the region bounds before touching terrain.
            String region=regions.get(ThreadLocalRandom.current().nextInt(regions.size()));
            return new SpawnHint(new Location(w,0,w.getMinHeight(),0),false,region);
        }
        if(e.hasArea){
            World w=e.worldGuardWorld==null?null:Bukkit.getWorld(e.worldGuardWorld);if(w==null)return null;
            int minX=Math.min(e.areaMinX,e.areaMaxX),maxX=Math.max(e.areaMinX,e.areaMaxX),minY=Math.min(e.areaMinY,e.areaMaxY),maxY=Math.max(e.areaMinY,e.areaMaxY),minZ=Math.min(e.areaMinZ,e.areaMaxZ),maxZ=Math.max(e.areaMinZ,e.areaMaxZ);
            int attempts=Math.max(1,plugin.getConfig().getInt("spawn.max-attempts",100));
            return new SpawnHint(new Location(w,ThreadLocalRandom.current().nextInt(minX,maxX+1),ThreadLocalRandom.current().nextInt(minY,maxY+1),ThreadLocalRandom.current().nextInt(minZ,maxZ+1)),false,null);
        }
        List<Location> players=new ArrayList<>(playerLocations.values());if(players.isEmpty())return null;Collections.shuffle(players);
        int radius=Math.max(4,plugin.getConfig().getInt("spawn.random-radius",48));
        Location base=players.get(0);World w=base.getWorld();if(w==null)return null;
        int x=base.getBlockX()+ThreadLocalRandom.current().nextInt(-radius,radius+1);int z=base.getBlockZ()+ThreadLocalRandom.current().nextInt(-radius,radius+1);
        return new SpawnHint(new Location(w,x,w.getMinHeight(),z),false,null);
    }

    private Location resolveCandidateLocation(EventDefinition e,SpawnHint hint){
        Location l=hint.location==null?null:hint.location.clone();if(l==null||l.getWorld()==null)return null;
        if(hint.regionName!=null&&!hint.regionName.isBlank()){
            l=plugin.worldGuard().randomCandidate(hint.regionName,l.getWorld());
            if(l==null)return null;
        }
        if(!hint.exactPoint){
            int y=l.getWorld().getHighestBlockYAt(l.getBlockX(),l.getBlockZ())+1;l.setY(y);
        }
        if(l.getBlockY()<e.minY||l.getBlockY()>e.maxY)return null;
        if(e.hasArea&&!insideArea(e,l))return null;
        if(!e.worldGuardRegions.isEmpty()){
            if(!plugin.worldGuard().available())return null;
            // Java требует, чтобы переменная, захватываемая lambda,
            // была final или effectively final. Выше l может изменяться,
            // поэтому фиксируем итоговую координату перед stream().
            final Location checkLocation=l;
            boolean inside=e.worldGuardRegions.stream().anyMatch(r->plugin.worldGuard().contains(checkLocation,r)||plugin.worldGuard().contains(checkLocation.clone().add(0,-1,0),r)||plugin.worldGuard().contains(checkLocation.clone().add(0,1,0),r));
            if(!inside)return null;
        }
        return l;
    }

    private Location findLocation(EventDefinition e){SpawnHint hint=chooseSpawnHint(e);return hint==null?null:resolveCandidateLocation(e,hint);}
    public boolean validatePoint(EventDefinition e,Location l){ return canSpawn(e,l,true); }
    public String validationReason(EventDefinition e,Location l){return validationReason(e,l,false,true);}
    /** Validation used while creating/editing a saved spawn point. Player-distance protection is only for actual spawns. */
    public String validationReasonForPoint(EventDefinition e,Location l){return validationReason(e,l,false,false);}
    /** Validation variant used by BlockPlaceEvent (placing a point with the head item). Player distance is NOT checked: it applies only to actual spawns. */
    public String validationReason(EventDefinition e,Location l,boolean allowConfiguredHeadOccupancy){return validationReason(e,l,allowConfiguredHeadOccupancy,false);}
    private String validationReason(EventDefinition e,Location l,boolean allowConfiguredHeadOccupancy,boolean checkPlayerDistance){
        if(l==null||l.getWorld()==null)return "неизвестный мир";
        if(plugin.data().isGlobalBlocked(l))return "это место находится в общем чёрном списке";
        if(e.blockedLocations.stream().anyMatch(x->sameBlock(x,l)))return "это место заблокировано для данного ивента";
        if(l.getBlockY()<e.minY||l.getBlockY()>e.maxY)return "высота должна быть от "+e.minY+" до "+e.maxY;
        Block b=l.getBlock(),below=b.getRelative(BlockFace.DOWN);
        if(!allowConfiguredHeadOccupancy && !b.isEmpty())return "место спавна занято блоком "+b.getType();
        if(!e.allowFloating&&e.requireSolidGround&&!below.getType().isSolid())return "снизу требуется твёрдый блок";
        if(!e.allowWater&&(b.isLiquid()||below.isLiquid()))return "вода запрещена настройками";
        if(!e.allowLava&&(b.getType()==Material.LAVA||below.getType()==Material.LAVA))return "лава запрещена настройками";
        if(!e.worldGuardRegions.isEmpty()){
            if(!plugin.worldGuard().available())return "WorldGuard нужен для заданного региона";
            // Точка события стоит над опорным блоком. Для низких/плоских регионов
            // WorldGuard граница часто заканчивается на опоре, поэтому принимаем обе клетки.
            Location support=l.clone().add(0,-1,0);
            boolean inside=e.worldGuardRegions.stream().anyMatch(r->plugin.worldGuard().contains(l,r)||plugin.worldGuard().contains(support,r)||plugin.worldGuard().contains(l.clone().add(0,1,0),r));
            if(!inside)return "место находится вне разрешённого WorldGuard-региона";
        }
        if(e.hasArea&&!insideArea(e,l))return "место находится вне заданной области";
        double minSq=e.minDistance*e.minDistance;
        for(EventInstance other:instances.values())if(other.location.getWorld()==l.getWorld()&&other.location.distanceSquared(l)<minSq)return "слишком близко к другому ивентовому предмету";
        if(checkPlayerDistance&&e.minPlayerDistance>0){
            double playerSq=e.minPlayerDistance*e.minPlayerDistance;
            for(Location pl:new ArrayList<>(playerLocations.values())){
                if(pl==null||pl.getWorld()!=l.getWorld())continue;
                double dx=pl.getX()-l.getX(),dz=pl.getZ()-l.getZ();
                if(dx*dx+dz*dz<playerSq)return "слишком близко к игроку";
            }
        }
        if(e.regionMaxActive>0&&regionActiveAt(e,l)>=e.regionMaxActive)return "достигнут лимит предметов в регионе: "+e.regionMaxActive;
        return null;
    }
    private boolean canSpawn(EventDefinition e,Location l,boolean test){
        return validationReason(e,l)==null;
    }
    private int regionActiveAt(EventDefinition e, Location l){
        int count=0;
        for(EventInstance other:instances.values()) {
            if(other.location.getWorld()!=l.getWorld()) continue;
            if(e.worldGuardRegions.isEmpty()) {
                if(e.hasArea && insideArea(e,other.location)) count++;
            } else if(e.worldGuardRegions.stream().anyMatch(r->plugin.worldGuard().available() && plugin.worldGuard().contains(other.location,r))) count++;
        }
        return count;
    }
    private boolean sameBlock(Location a,Location b){return a!=null&&b!=null&&a.getWorld()!=null&&b.getWorld()!=null&&a.getWorld().getUID().equals(b.getWorld().getUID())&&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();}
    private boolean insideArea(EventDefinition e,Location l){return l.getBlockX()>=Math.min(e.areaMinX,e.areaMaxX)&&l.getBlockX()<=Math.max(e.areaMinX,e.areaMaxX)&&l.getBlockY()>=Math.min(e.areaMinY,e.areaMaxY)&&l.getBlockY()<=Math.max(e.areaMinY,e.areaMaxY)&&l.getBlockZ()>=Math.min(e.areaMinZ,e.areaMaxZ)&&l.getBlockZ()<=Math.max(e.areaMinZ,e.areaMaxZ);}
    private boolean isValidSavedLocation(EventDefinition e,Location l){return canSpawn(e,l,true);}
    private boolean spawnAtRaw(EventDefinition e,Location l,float yaw,UUID id,long spawned,long expires){
        return spawnAtRaw(e,l,yaw,id,spawned,expires,true);
    }
    private boolean spawnAtRaw(EventDefinition e,Location l,float yaw,UUID id,long spawned,long expires,boolean recordLifecycle){
        if(l==null||l.getWorld()==null)return false;
        EventInstance inst=new EventInstance(id,e.id,l.clone(),yaw,spawned,expires);
        double entityY=Math.max(l.getWorld().getMinHeight()+0.1D,l.getY()+plugin.eventHeadEntityYOffset(e));
        Location entityLocation=l.clone();
        entityLocation.setX(l.getBlockX()+0.5D);
        entityLocation.setY(entityY);
        entityLocation.setZ(l.getBlockZ()+0.5D);
        ArmorStand as=l.getWorld().spawn(entityLocation,ArmorStand.class,a->{
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setMarker(false);
            a.setBasePlate(false);
            a.setArms(false);
            a.setCollidable(false);
            a.setRotation(yaw,0);
            a.setCanMove(false);
            // Как в 3.4.8: стойка не сохраняется в чанке, при старте её пересоздаёт restore().
            a.setPersistent(false);
            // Слоты заблокированы и через Paper API, и через обычное событие манипуляции.
            // Это исключает надевание брони или подмену головы игроками.
            a.setDisabledSlots(
                    org.bukkit.inventory.EquipmentSlot.HEAD,
                    org.bukkit.inventory.EquipmentSlot.CHEST,
                    org.bukkit.inventory.EquipmentSlot.LEGS,
                    org.bukkit.inventory.EquipmentSlot.FEET,
                    org.bukkit.inventory.EquipmentSlot.HAND,
                    org.bukkit.inventory.EquipmentSlot.OFF_HAND
            );
            a.getPersistentDataContainer().set(new NamespacedKey(plugin,"instance"),PersistentDataType.STRING,id.toString());
            ItemStack visual=plugin.items().createInstance(e.id,e.visual);
            if(a.getEquipment()!=null)a.getEquipment().setHelmet(visual);
        });
        instances.put(id,inst);
        entities.put(id,as);
        expectedInstanceIds.add(id);
        final boolean lifecycle=recordLifecycle;
        if(lifecycle)lifecycleTracked.add(id);
        SchedulerUtil.runGlobal(plugin,()->{
            plugin.data().saveInstance(inst);
            if(lifecycle)plugin.data().recordEventStart(e.id,spawned);
        });
        if(plugin.getConfig().getBoolean("security.anti-esp-decoys",true)) { List<ArmorStand> list=decoys.computeIfAbsent(id,k->new java.util.concurrent.CopyOnWriteArrayList<>()); int n=Math.max(0,plugin.getConfig().getInt("security.anti-esp-decoys-min",2)); for(int j=0;j<n;j++)spawnDecoy(inst,list); }
        return true;
    }
    public boolean collect(UUID id,PlayerLike player){
        EventInstance i=instances.get(id);if(i==null)return false;
        synchronized(i){if(i.collected)return false;i.collected=true;}
        // Фиксируем сбор сразу. Если сервер перезапустится до завершения reward future,
        // старый физический EventHead всё равно не будет восстановлен.
        SchedulerUtil.runGlobal(plugin,()->{plugin.data().saveInstance(i);plugin.data().autosave();});
        EventDefinition e=plugin.events().get(i.eventId);if(e==null){i.collected=false;SchedulerUtil.runGlobal(plugin,()->{plugin.data().saveInstance(i);plugin.data().autosave();});return false;}
        Player p=player.player();
        ConditionService.Result condition=plugin.conditions().check(p,e);
        if(!condition.allowed()){
            i.collected=false;
            SchedulerUtil.runGlobal(plugin,()->{plugin.data().saveInstance(i);plugin.data().autosave();});
            if(condition.penalty()>0)plugin.economy().takeAsync(p,condition.penalty(),e.id).whenComplete((result,error)->{
                if(error!=null || result==null || !result.success())
                    plugin.getLogger().fine("Не удалось снять штраф за невыполненное условие события "+e.id+" с "+p.getName());
            });
            if(condition.message()!=null&&!condition.message().isBlank())plugin.lang().send(p,condition.message());
            return false;
        }

        // RU: Награда может быть асинхронной. После её завершения возвращаемся в регион EventHead,
        // удаляем реальную сущность и запускаем визуальную анимацию уже в том же регионе.
        plugin.reward().grantAsync(p,e).whenComplete((result,error)->{
            SchedulerUtil.runRegion(plugin,i.location,()->{
                if(!instances.containsKey(id))return;

                if(error!=null || result==null || !result.success()){
                    i.collected=false;
                    SchedulerUtil.runGlobal(plugin,()->{plugin.data().saveInstance(i);plugin.data().autosave();});
                    boolean mayCharge=e.rewardMin<0||e.rewardMax<0;
                    if(mayCharge){
                        SchedulerUtil.runEntity(plugin,p,()->plugin.lang().send(p,plugin.lang().tr(p,"economy-failed")));
                        return;
                    }
                    if(!plugin.getConfig().getBoolean("economy.collect-on-reward-failure",true))return;
                    SchedulerUtil.runEntity(plugin,p,()->plugin.reward().announceFailure(p));
                    plugin.animation().play(i,e.visual,p);
                    schedule(e.id);
                    return;
                }

                int actual=result.actualAmount();
                SchedulerUtil.runGlobal(plugin,()->plugin.data().incrementStats(p.getUniqueId(),p.getName(),e.id,actual));
                plugin.animation().play(i,e.visual,p);
                schedule(e.id);
            });
        });
        return true;
    }
    public boolean breakByModerator(UUID id){EventInstance i=instances.get(id);if(i==null)return false;remove(id);schedule(i.eventId);return true;}

    /**
     * RU: Завершает состояние collected у экземпляра после того, как награда подтверждена.
     * Вызывается из региона EventHead и удаляет только runtime/data-state, не управляя
     * анимационной сущностью напрямую.
     */
    public void finishCollected(UUID id){
        if(id==null)return;
        removeDecoys(id);
        Entity animation=animationEntities.get(id);
        if(animation!=null){
            // The animation entity is controlled by its own EntityScheduler.
        }
        EventInstance i=instances.remove(id);
        entities.remove(id);
        expectedInstanceIds.remove(id);
        if(i==null)return;
        final EventInstance removedInstance=i;
        final boolean wasLifecycleTracked=lifecycleTracked.remove(id);
        SchedulerUtil.runGlobal(plugin,()->{
            if(wasLifecycleTracked) plugin.data().recordEventEnd(
                    removedInstance.eventId,
                    removedInstance.spawnedAt,
                    System.currentTimeMillis(),
                    "собран"
            );
            plugin.data().removeInstance(removedInstance.instanceId);
            plugin.data().autosave();
        });
    }

    /** Removes a stale physical EventHead at a known saved location. Runs in that region. */
    private void removePhysicalInstanceAt(UUID id, Location location){
        if(id==null || location==null || location.getWorld()==null)return;
        NamespacedKey key=instanceKey;
        for(ArmorStand candidate:location.getWorld().getNearbyEntitiesByType(
                ArmorStand.class, location, 3.0, 3.5, 3.0)){
            String raw=candidate.getPersistentDataContainer().get(key,PersistentDataType.STRING);
            if(id.toString().equals(raw) && !candidate.getPersistentDataContainer().has(animationKey(),PersistentDataType.BYTE)){
                candidate.remove();
            }
        }
    }

    /** RU: Удаляет активный EventHead, когда код уже выполняется в его регионе. */
    private void removeNowInRegion(UUID id,EventInstance known){
        removeDecoys(id);
        ArmorStand as=entities.remove(id);
        expectedInstanceIds.remove(id);
        if(as!=null && !as.isDead())as.remove();
        Entity animation=animationEntities.remove(id);
        if(animation!=null)SchedulerUtil.runEntity(plugin,animation,()->{if(!animation.isDead())animation.remove();});

        EventInstance i=instances.remove(id);
        if(i==null)i=known;
        if(i==null)return;

        if(i.location!=null&&i.location.getWorld()!=null){
            NamespacedKey key=instanceKey;
            for(ArmorStand candidate:i.location.getWorld().getNearbyEntitiesByType(ArmorStand.class,i.location,2.75,3.0,2.75)){
                String raw=candidate.getPersistentDataContainer().get(key,PersistentDataType.STRING);
                if(id.toString().equals(raw))candidate.remove();
            }
        }
        final EventInstance removedInstance=i;
        final boolean wasLifecycleTracked=lifecycleTracked.remove(id);
        SchedulerUtil.runGlobal(plugin,()->{
            if(wasLifecycleTracked)plugin.data().recordEventEnd(removedInstance.eventId,removedInstance.spawnedAt,System.currentTimeMillis(),"завершён");
            plugin.data().removeInstance(removedInstance.instanceId);
        });
    }

    public void remove(UUID id){
        EventInstance i=instances.get(id);
        if(i==null)return;
        if(i.location!=null&&i.location.getWorld()!=null){
            final EventInstance target=i;
            SchedulerUtil.runRegion(plugin,target.location,()->removeNowInRegion(id,target));
        }else{
            removeNowInRegion(id,i);
        }
    }
    public void removeByEvent(String eventId){for(UUID id:new ArrayList<>(instances.keySet())){EventInstance i=instances.get(id);if(i!=null&&i.eventId.equals(eventId))remove(id);}}
}
