
package ru.doksi.eventheads.data;

// RU: YAML-хранилище событий, активных экземпляров, статистики и журналов; база данных не используется.
// EN: YAML storage for events, active instances, statistics and logs; no database is required.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.catalog.ParticleSettings;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.events.EventInstance;
import ru.doksi.eventheads.events.SpawnPoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.Base64;

public final class DataStore {
    private final EventHeadsPlugin plugin;
    private final File dir;
    private final File dataDir, eventsDir, pointsDir, usersDir, activeDir, statsDir, logsDir, backupDir;
    private final File eventsFile,pointsFile,accessFile,instancesFile,statsFile,logFile;
    private YamlConfiguration events=new YamlConfiguration(),access=new YamlConfiguration(),instances=new YamlConfiguration(),stats=new YamlConfiguration(),logs=new YamlConfiguration();
    private boolean instancesDirty, statsDirty, logsDirty;
    private long lastBackupAt=0L;
    private long logSequence=0L;
    private final Set<String> globalBlockedIndex=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String,Integer> lastPointSizeSeen=new java.util.concurrent.ConcurrentHashMap<>();
    private boolean pointFormatRepairNeeded=false;
    /** RU: ключи, порождённые сломанным форматом 3.4.0—3.4.2: "0id", "12chance", "3enabled", "7location". */
    private static final java.util.regex.Pattern MALFORMED_POINT_KEY=java.util.regex.Pattern.compile("^\\d+(id|chance|enabled|location)$");
    public DataStore(EventHeadsPlugin p){
        plugin=p;
        dir=p.getDataFolder();
        dataDir=new File(dir,"data");
        eventsDir=new File(dataDir,"events");
        pointsDir=new File(dataDir,"points");
        usersDir=new File(dataDir,"users");
        activeDir=new File(dataDir,"active");
        statsDir=new File(dataDir,"statistics");
        logsDir=new File(dataDir,"logs");
        backupDir=new File(dataDir,"backups");
        eventsFile=new File(eventsDir,"events.yml");
        pointsFile=new File(pointsDir,"points.yml");
        accessFile=new File(usersDir,"users.yml");
        instancesFile=new File(activeDir,"active.yml");
        statsFile=new File(statsDir,"statistics.yml");
        logFile=new File(logsDir,"logs.yml");
    }
    public void load(){
        createDataStructure();
        migrateLegacy("events.yml", eventsFile);
        migrateLegacy("points.yml", pointsFile);
        migrateLegacy("access.yml", accessFile);
        migrateLegacy("active.yml", instancesFile);
        migrateLegacy("statistics.yml", statsFile);
        migrateLegacy("logs.yml", logFile);
        events=load(eventsFile);
        access=load(accessFile);
        instances=load(instancesFile);
        stats=load(statsFile);
        logs=load(logFile);
        rebuildGlobalBlockedIndex();
        if(!access.contains("players")){access.createSection("players");saveAccess();}
    }
    private void createDataStructure(){dataDir.mkdirs();eventsDir.mkdirs();pointsDir.mkdirs();usersDir.mkdirs();activeDir.mkdirs();statsDir.mkdirs();logsDir.mkdirs();backupDir.mkdirs();}
    private void migrateLegacy(String legacyName,File target){
        if(target.exists())return;
        File legacy=new File(dir,legacyName);
        try{
            if(legacy.exists()){Files.copy(legacy.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);plugin.getLogger().info("Перенесены данные "+legacyName+" -> "+dir.toPath().relativize(target.toPath()));return;}
        }catch(Exception ex){plugin.getLogger().warning("Не удалось перенести "+legacyName+": "+ex.getMessage());}
    }
    private YamlConfiguration load(File f){
        try{
            if(!f.exists()){f.getParentFile().mkdirs();YamlConfiguration empty=new YamlConfiguration();
                if(f.equals(accessFile))empty.createSection("players");
                empty.save(f);
            }
        }catch(Exception ex){plugin.getLogger().warning("Не удалось создать "+f.getPath()+": "+ex.getMessage());}
        return YamlConfiguration.loadConfiguration(f);
    }
    public YamlConfiguration events(){return events;} public YamlConfiguration access(){return access;} public YamlConfiguration instances(){return instances;} public YamlConfiguration stats(){return stats;}
    public List<Location> globalBlockedLocations(){
        List<Location> out=new ArrayList<>();
        ConfigurationSection sec=events.getConfigurationSection("globalBlockedLocations");
        if(sec!=null) for(String k:sec.getKeys(false)){
            Location l=readLocation(events,"globalBlockedLocations."+k);
            if(l!=null)out.add(l);
        }
        return out;
    }

    public void addGlobalBlocked(Location l){
        if(l==null||l.getWorld()==null)return;
        String key=locationKey(l);
        if(globalBlockedIndex.contains(key)||isGlobalBlocked(l))return;
        String base="globalBlockedLocations."+UUID.randomUUID();
        writeLocation(events,base,l);
        globalBlockedIndex.add(key);
        saveEvents();
    }

    public boolean isGlobalBlocked(Location l){
        return l!=null&&l.getWorld()!=null&&globalBlockedIndex.contains(locationKey(l));
    }

    public boolean removeGlobalBlocked(Location l){
        ConfigurationSection sec=events.getConfigurationSection("globalBlockedLocations");
        if(sec==null||l==null)return false;
        for(String k:new ArrayList<>(sec.getKeys(false))){
            Location x=readLocation(events,"globalBlockedLocations."+k);
            if(sameBlock(x,l)){
                events.set("globalBlockedLocations."+k,null);
                globalBlockedIndex.remove(locationKey(l));
                saveEvents();
                return true;
            }
        }
        return false;
    }

    private String locationKey(Location l){
        return l.getWorld().getUID()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ();
    }

    private void rebuildGlobalBlockedIndex(){
        globalBlockedIndex.clear();
        ConfigurationSection sec=events.getConfigurationSection("globalBlockedLocations");
        int unresolved=0;
        if(sec!=null) for(String k:sec.getKeys(false)){
            Location l=readLocation(events,"globalBlockedLocations."+k);
            if(l!=null&&l.getWorld()!=null)globalBlockedIndex.add(locationKey(l)); else unresolved++;
        }
        if(unresolved>0)plugin.getLogger().warning("Не удалось разрешить "+unresolved+" globalBlockedLocations: мир не загружен; записи сохранены и не будут автоматически удалены.");
    }

    private boolean sameBlock(Location a,Location b){
        return a!=null&&b!=null&&a.getWorld()!=null&&b.getWorld()!=null&&a.getWorld().getUID().equals(b.getWorld().getUID())&&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();
    }

    public synchronized void saveAll(){
        backupFiles();
        save(events,eventsFile);
        saveAllPoints(plugin.events().values());
        save(access,accessFile);
        flushDirty();
    }

    /**
     * Folia-safe periodic persistence. Mutable event/point objects are owned by player/region
     * contexts, so periodic global persistence only flushes data that is explicitly marked dirty.
     * Full event/point persistence remains available via saveAll() for disable/reload.
     */
    public synchronized void autosave(){
        flushDirty();
    }

    private void backupFiles(){
        int count=Math.max(0,plugin.getConfig().getInt("storage.backup-count",3));
        if(count<=0)return;
        long interval=Math.max(0L,plugin.getConfig().getLong("storage.backup-interval-minutes",60L))*60_000L;
        long now=System.currentTimeMillis();
        if(lastBackupAt!=0L&&interval>0L&&now-lastBackupAt<interval)return;
        lastBackupAt=now;
        File[] files={eventsFile,pointsFile,accessFile,instancesFile,statsFile,logFile};
        for(File f:files){
            if(!f.exists())continue;
            try{
                for(int i=count;i>=2;i--){
                    File src=new File(backupDir,f.getName()+".bak."+(i-1));
                    File dst=new File(backupDir,f.getName()+".bak."+i);
                    if(src.exists())Files.move(src.toPath(),dst.toPath(),StandardCopyOption.REPLACE_EXISTING);
                }
                Files.copy(f.toPath(),new File(backupDir,f.getName()+".bak.1").toPath(),StandardCopyOption.REPLACE_EXISTING);
            }catch(Exception ex){plugin.getLogger().fine("Backup failed for "+f.getName()+": "+ex.getMessage());}
        }
    }

    public synchronized void saveEvents(){save(events,eventsFile);}
    public synchronized void saveAccess(){save(access,accessFile);}

    /** RU: персональная видимость приманок Anti-ESP (/eh antiesp hide|show) должна переживать рестарт. */
    public java.util.Set<UUID> loadAntiEspHidden(){
        java.util.Set<UUID> out=new HashSet<>();
        for(String s:access.getStringList("antiEspHiddenViewers")){
            try{out.add(UUID.fromString(s));}catch(Exception ignored){}
        }
        return out;
    }
    public synchronized void saveAntiEspHidden(java.util.Set<UUID> hidden){
        List<String> list=new ArrayList<>();
        for(UUID u:hidden) list.add(u.toString());
        access.set("antiEspHiddenViewers",list);
        saveAccess();
    }
    public synchronized void saveInstances(){instancesDirty=true;}
    public synchronized void saveStats(){statsDirty=true;}
    private void flushDirty(){
        if(instancesDirty){save(instances,instancesFile);instancesDirty=false;}
        if(statsDirty){save(stats,statsFile);statsDirty=false;}
        if(logsDirty){save(logs,logFile);logsDirty=false;}
    }
    private void save(YamlConfiguration c,File f){try{File tmp=new File(f.getParentFile(),f.getName()+".tmp"); c.save(tmp); Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception ex){try{c.save(f);}catch(Exception second){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Failed to save "+f.getName(),second);}}}

    public synchronized void log(String action,String actor,String details){
        long now=System.currentTimeMillis();
        long unique=Math.max(now,logSequence+1); logSequence=unique;
        String id=Long.toString(unique);
        logs.set("entries."+id+".time",now); logs.set("entries."+id+".actor",actor); logs.set("entries."+id+".action",action); logs.set("entries."+id+".details",details); logsDirty=true;
    }
    public org.bukkit.configuration.ConfigurationSection auditEntries(){ return logs.getConfigurationSection("entries"); }

    /** Separate points snapshot. Never overwrites points.yml with incomplete data. */
    public synchronized void savePoints(EventDefinition e){
        if(e==null)return;
        YamlConfiguration p=load(pointsFile);
        String root="events."+e.id+".points";
        p.set(root,null);
        writePointsForEvent(p,e,root);
        save(p,pointsFile);
    }

    private void writePointsForEvent(YamlConfiguration p,EventDefinition e,String root){
        LinkedHashMap<String,SpawnPoint> byId=new LinkedHashMap<>();
        int duplicateIds=0;
        for(SpawnPoint sp:e.points){
            String id=(sp.id==null||sp.id.isBlank())?UUID.randomUUID().toString():sp.id;
            if(byId.containsKey(id)) duplicateIds++;
            byId.put(id,sp);
        }
        LinkedHashMap<String,SpawnPoint> unique=new LinkedHashMap<>();
        Set<String> seenLocations=new HashSet<>();
        int duplicateLocations=0;
        for(SpawnPoint sp:byId.values()){
            String locKey=pointBlockKey(sp);
            if(!locKey.isBlank() && !seenLocations.add(locKey)){
                duplicateLocations++;
                continue;
            }
            unique.put((sp.id==null||sp.id.isBlank())?UUID.randomUUID().toString():sp.id,sp);
        }
        if(duplicateIds>0 || duplicateLocations>0){
            plugin.getLogger().warning("[points-guard] "+e.id+": перед сохранением удалено дублей: по UUID="+duplicateIds+", по блоку="+duplicateLocations+". Было="+e.points.size()+", станет="+unique.size());
            e.points.clear();
            e.points.addAll(unique.values());
            e.pointsIncomplete=e.points.stream().anyMatch(sp->!sp.resolved());
        }
        int i=0;
        for(SpawnPoint sp:unique.values()){
            String b=root+"."+(i++)+".";
            p.set(b+"id",sp.id); p.set(b+"chance",sp.chance); p.set(b+"enabled",sp.enabled); p.set(b+"addedBy",sp.addedBy==null?null:sp.addedBy.toString());
            p.set(b+"location.world",sp.location.getWorld()!=null?sp.location.getWorld().getName():sp.worldName);
            p.set(b+"location.worldUuid",sp.location.getWorld()!=null?sp.location.getWorld().getUID().toString():(sp.worldUuid==null?null:sp.worldUuid.toString()));
            p.set(b+"location.x",sp.location.getX()); p.set(b+"location.y",sp.location.getY()); p.set(b+"location.z",sp.location.getZ());
            p.set(b+"location.yaw",(double)sp.location.getYaw()); p.set(b+"location.pitch",(double)sp.location.getPitch());
        }
    }

    private String pointBlockKey(SpawnPoint sp){
        if(sp==null||sp.location==null)return "";
        String world=sp.location.getWorld()!=null?sp.location.getWorld().getUID().toString():(sp.worldUuid==null?(sp.worldName==null?"":sp.worldName.toLowerCase(Locale.ROOT)):sp.worldUuid.toString());
        return world+":"+sp.location.getBlockX()+":"+sp.location.getBlockY()+":"+sp.location.getBlockZ();
    }

    private void deduplicateLoadedPoints(EventDefinition e){
        LinkedHashMap<String,SpawnPoint> byId=new LinkedHashMap<>();
        int duplicateIds=0;
        for(SpawnPoint sp:e.points){
            String id=(sp.id==null||sp.id.isBlank())?UUID.randomUUID().toString():sp.id;
            if(byId.containsKey(id)) duplicateIds++;
            byId.put(id,sp);
        }
        LinkedHashMap<String,SpawnPoint> byLocation=new LinkedHashMap<>();
        Map<String,String> locationToId=new HashMap<>();
        int duplicateLocations=0;
        for(SpawnPoint sp:byId.values()){
            String id=(sp.id==null||sp.id.isBlank())?UUID.randomUUID().toString():sp.id;
            String locKey=pointBlockKey(sp);
            if(!locKey.isBlank()){
                String oldId=locationToId.put(locKey,id);
                if(oldId!=null){byLocation.remove(oldId);duplicateLocations++;}
            }
            byLocation.put(id,sp);
        }
        if(duplicateIds>0 || duplicateLocations>0){
            int before=e.points.size(); e.points.clear(); e.points.addAll(byLocation.values()); e.pointsIncomplete=e.points.stream().anyMatch(sp->!sp.resolved());
            plugin.getLogger().warning("[points-guard] "+e.id+": при загрузке удалено дублей: UUID="+duplicateIds+", по блоку="+duplicateLocations+". Было="+before+", осталось="+e.points.size());
        }
    }

    public synchronized void saveAllPoints(Collection<EventDefinition> all){
        YamlConfiguration p=load(pointsFile);
        for(EventDefinition e:all){
            String root="events."+e.id+".points";
            p.set(root,null);
            writePointsForEvent(p,e,root);
        }
        save(p,pointsFile);
    }

    private void loadPointsInto(EventDefinition e){ loadPointsInto(e,false); }

    /**
     * Loads the separate points snapshot without ever discarding usable in-memory data.
     * World resolution may happen later; raw world name/UUID and coordinates remain stored.
     */
    private boolean loadPointsInto(EventDefinition e, boolean eventPointsPresent){
        YamlConfiguration p=load(pointsFile);
        ConfigurationSection sec=p.getConfigurationSection("events."+e.id+".points");
        if(sec==null||sec.getKeys(false).isEmpty()){
            e.pointsIncomplete=false;
            return !eventPointsPresent || !e.points.isEmpty();
        }
        List<SpawnPoint> loaded=new ArrayList<>(); int unresolved=0;
        for(String k:sec.getKeys(false)){
            SpawnPoint sp=readSpawnPoint(p,"events."+e.id+".points."+k,k);
            if(sp==null){ unresolved++; continue; }
            loaded.add(sp);
            if(!sp.resolved()) unresolved++;
        }

        if(eventPointsPresent){
            // events.yml is the authoritative editable snapshot. The separate points.yml
            // mirror is not allowed to resurrect deleted or edited points.
            e.pointsIncomplete=e.points.stream().anyMatch(sp->!sp.resolved());
        }else{
            e.pointsIncomplete=unresolved>0;
            e.points.clear();
            e.points.addAll(loaded);
        }
        if(unresolved>0) logUnresolvedPoints(e, loaded);
        return unresolved==0;
    }

    private void logUnresolvedPoints(EventDefinition e,List<SpawnPoint> loaded){
        Map<String,Integer> byWorld=new LinkedHashMap<>();
        for(SpawnPoint sp:loaded) if(!sp.resolved()) byWorld.merge(sp.worldName==null?"<unknown>":sp.worldName,1,Integer::sum);
        plugin.getLogger().warning("Событие '"+e.id+"': не удалось разрешить часть точек. Сохранены raw world/UUID/координаты; ожидание загрузки мира. "+byWorld);
    }

    public int retryIncompletePoints(){
        int recovered=0;
        for(EventDefinition e:plugin.events().values()){
            boolean changed=false;
            for(int i=0;i<e.points.size();i++){
                SpawnPoint sp=e.points.get(i);
                if(sp.resolved()) continue;
                World w=resolveWorld(sp.worldUuid,sp.worldName);
                if(w==null) continue;
                Location l=sp.location.clone(); l.setWorld(w);
                e.points.set(i,sp.resolve(l)); changed=true;
            }
            if(changed){
                boolean unresolved=e.points.stream().anyMatch(sp->!sp.resolved());
                if(!unresolved) e.pointsIncomplete=false;
                recovered++;
                saveEvent(e);
                if(!unresolved){
                    Integer last=lastPointSizeSeen.get(e.id);
                    if(last==null||last.intValue()!=e.points.size()){ plugin.loggerInfoPointRecovery(e,0); lastPointSizeSeen.put(e.id,e.points.size()); }
                }
            }
        }
        return recovered;
    }

    private World resolveWorld(UUID uuid,String name){
        if(uuid!=null){
            World w=Bukkit.getWorld(uuid);
            if(w!=null)return w;
        }
        if(name!=null&&!name.isBlank()){
            World w=Bukkit.getWorld(name);
            if(w!=null)return w;
            String n=name.startsWith("minecraft:")?name.substring("minecraft:".length()):name;
            for(World x:Bukkit.getWorlds()) if(x.getName().equalsIgnoreCase(n))return x;
            if(n.equalsIgnoreCase("overworld")&&Bukkit.getWorlds().stream().anyMatch(x->x.getEnvironment()==World.Environment.NORMAL))
                return Bukkit.getWorlds().stream().filter(x->x.getEnvironment()==World.Environment.NORMAL).findFirst().orElse(null);
            if(n.equalsIgnoreCase("the_nether")&&Bukkit.getWorlds().stream().anyMatch(x->x.getEnvironment()==World.Environment.NETHER))
                return Bukkit.getWorlds().stream().filter(x->x.getEnvironment()==World.Environment.NETHER).findFirst().orElse(null);
            if(n.equalsIgnoreCase("the_end")&&Bukkit.getWorlds().stream().anyMatch(x->x.getEnvironment()==World.Environment.THE_END))
                return Bukkit.getWorlds().stream().filter(x->x.getEnvironment()==World.Environment.THE_END).findFirst().orElse(null);
        }
        return null;
    }

    private SpawnPoint readSpawnPoint(YamlConfiguration c,String base,String fallbackId){
        String worldName=c.getString(base+".location.world",c.getString(base+".world",null));
        String uuidText=c.getString(base+".location.worldUuid",c.getString(base+".worldUuid",null));
        UUID worldUuid=null; try{if(uuidText!=null)worldUuid=UUID.fromString(uuidText);}catch(Exception ignored){}
        Location legacy=c.getLocation(base+".location");
        double x=c.getDouble(base+".location.x",c.getDouble(base+".x",legacy==null?0:legacy.getX()));
        double y=c.getDouble(base+".location.y",c.getDouble(base+".y",legacy==null?0:legacy.getY()));
        double z=c.getDouble(base+".location.z",c.getDouble(base+".z",legacy==null?0:legacy.getZ()));
        float yaw=(float)c.getDouble(base+".location.yaw",c.getDouble(base+".yaw",legacy==null?0:legacy.getYaw()));
        float pitch=(float)c.getDouble(base+".location.pitch",c.getDouble(base+".pitch",legacy==null?0:legacy.getPitch()));
        // 3.4.3: записи без какой-либо информации о мире — это мусор,
        // оставшийся от сломанного формата 3.4.0—3.4.2 (ключи вида "0id", "0chance").
        // Такие записи не являются точками и не должны попадать в список.
        if(worldName==null&&worldUuid==null&&legacy==null) return null;
        World world=resolveWorld(worldUuid,worldName);
        Location l=new Location(world,x,y,z,yaw,pitch);
        String id=c.getString(base+".id",null);
        if(id==null||id.isBlank()||MALFORMED_POINT_KEY.matcher(id).matches()) id=(fallbackId==null||fallbackId.isBlank()||MALFORMED_POINT_KEY.matcher(fallbackId).matches())?UUID.randomUUID().toString():fallbackId;
        UUID addedBy=null;
        String addedByText=c.getString(base+".addedBy",null);
        try{if(addedByText!=null)addedBy=UUID.fromString(addedByText);}catch(Exception ignored){}
        return new SpawnPoint(id,l,c.getDouble(base+".chance",100),c.getBoolean(base+".enabled",true),worldName,worldUuid,addedBy);
    }

    private Location readPointLocation(YamlConfiguration p,String base){
        String worldName=p.getString(base+".world",null); String worldUuid=p.getString(base+".worldUuid",null);
        World world=null;
        try{if(worldUuid!=null)world=Bukkit.getWorld(UUID.fromString(worldUuid));}catch(Exception ignored){}
        if(world==null&&worldName!=null)world=Bukkit.getWorld(worldName);
        if(world==null){Location legacy=p.getLocation(base+".location");if(legacy!=null)return legacy;return null;}
        return new Location(world,p.getDouble(base+".x"),p.getDouble(base+".y"),p.getDouble(base+".z"),(float)p.getDouble(base+".yaw",0),(float)p.getDouble(base+".pitch",0));
    }

    private void writeLocation(YamlConfiguration c,String base,Location l){
        if(l==null){c.set(base,null);return;}
        c.set(base+".world",l.getWorld()==null?null:l.getWorld().getName());
        c.set(base+".worldUuid",l.getWorld()==null?null:l.getWorld().getUID().toString());
        c.set(base+".x",l.getX()); c.set(base+".y",l.getY()); c.set(base+".z",l.getZ());
        c.set(base+".yaw",(double)l.getYaw()); c.set(base+".pitch",(double)l.getPitch());
    }

    private Location readLocation(YamlConfiguration c,String base){
        String worldName=c.getString(base+".world",null); String worldUuid=c.getString(base+".worldUuid",null); World world=null;
        try{if(worldUuid!=null)world=Bukkit.getWorld(UUID.fromString(worldUuid));}catch(Exception ignored){}
        if(world==null&&worldName!=null)world=Bukkit.getWorld(worldName);
        if(world!=null)return new Location(world,c.getDouble(base+".x"),c.getDouble(base+".y"),c.getDouble(base+".z"),(float)c.getDouble(base+".yaw",0),(float)c.getDouble(base+".pitch",0));
        return c.getLocation(base);
    }

    public synchronized void saveEvent(EventDefinition e){
        deduplicateLoadedPoints(e);
        Integer previousSize=lastPointSizeSeen.put(e.id,e.points.size());
        if(plugin.getConfig().getBoolean("debug",false) && (previousSize==null || previousSize.intValue()!=e.points.size())){
            plugin.getLogger().info("[DEBUG points] "+e.id+": список точек изменён: "+(previousSize==null?"?":previousSize)+" -> "+e.points.size());
            StackTraceElement[] trace=Thread.currentThread().getStackTrace();
            int end=Math.min(trace.length,8);
            StringBuilder sb=new StringBuilder();
            for(int i=3;i<end;i++){ if(sb.length()>0)sb.append(" <- "); sb.append(trace[i].toString()); }
            plugin.getLogger().info("[DEBUG points] источник: "+sb);
        }
        String b="events."+e.id+".";
        events.set(b+"adminName",e.adminName); events.set(b+"playerName",e.playerName); events.set(b+"description",e.description);
        events.set(b+"authorUuid",e.authorUuid); events.set(b+"authorName",e.authorName); events.set(b+"createdAt",e.createdAt);
        events.set(b+"rewardMin",e.rewardMin); events.set(b+"rewardMax",e.rewardMax); events.set(b+"spawnMode",e.spawnMode.name());
        events.set(b+"maxActive",e.maxActive); events.set(b+"regionMaxActive",e.regionMaxActive); events.set(b+"minDistance",e.minDistance); events.set(b+"minPlayerDistance",e.minPlayerDistance);
        events.set(b+"minY",e.minY); events.set(b+"maxY",e.maxY); events.set(b+"requireSolidGround",e.requireSolidGround); events.set(b+"allowFloating",e.allowFloating); events.set(b+"allowWater",e.allowWater); events.set(b+"allowLava",e.allowLava);
        events.set(b+"minDelaySeconds",e.minDelaySeconds); events.set(b+"maxDelaySeconds",e.maxDelaySeconds); events.set(b+"minLifetimeSeconds",e.minLifetimeSeconds); events.set(b+"maxLifetimeSeconds",e.maxLifetimeSeconds);
        events.set(b+"spawnChance",e.spawnChance); events.set(b+"enabled",e.enabled); events.set(b+"startAt",e.startAt==null?null:e.startAt.toEpochMilli()); events.set(b+"endAt",e.endAt==null?null:e.endAt.toEpochMilli());
        events.set(b+"weeklyScheduleEnabled",e.weeklyScheduleEnabled); events.set(b+"weeklyDayOfWeek",e.weeklyDayOfWeek); events.set(b+"weeklyTimeMinutes",e.weeklyTimeMinutes); events.set(b+"weeklyDurationSeconds",e.weeklyDurationSeconds); events.set(b+"dailyScheduleEnabled",e.dailyScheduleEnabled); events.set(b+"dailyTimeMinutes",e.dailyTimeMinutes); events.set(b+"dailyDurationSeconds",e.dailyDurationSeconds);
        events.set(b+"worldGuardRegion",e.worldGuardRegion); events.set(b+"worldGuardWorld",e.worldGuardWorld); events.set(b+"worldGuardRegions",e.worldGuardRegions);
        events.set(b+"hasArea",e.hasArea); events.set(b+"areaMinX",e.areaMinX); events.set(b+"areaMinY",e.areaMinY); events.set(b+"areaMinZ",e.areaMinZ); events.set(b+"areaMaxX",e.areaMaxX); events.set(b+"areaMaxY",e.areaMaxY); events.set(b+"areaMaxZ",e.areaMaxZ);
        events.set(b+"randomRotation",e.randomRotation); events.set(b+"animationSmall",e.animationSmall); events.set(b+"visualOffsetY",e.visualOffsetY); events.set(b+"animationSpeed",e.animationSpeed);
        events.set(b+"collectParticleCount",e.collectParticleCount); events.set(b+"collectParticleColor",e.collectParticleColor); events.set(b+"collectParticleColors",e.collectParticleColors); events.set(b+"collectParticle",e.collectParticle); events.set(b+"collectParticles",e.collectParticles); events.set(b+"visual",e.visual);
        events.set(b+"sound",e.sound); events.set(b+"sounds",e.sounds); events.set(b+"soundEnabled",e.soundEnabled); events.set(b+"soundVolume",e.soundVolume); events.set(b+"soundPitch",e.soundPitch);
        events.set(b+"conditions.enabled",e.conditions.enabled); events.set(b+"conditions.world",e.conditions.world); events.set(b+"conditions.weather",e.conditions.weather); events.set(b+"conditions.dayNight",e.conditions.dayNight); events.set(b+"conditions.timeMin",e.conditions.timeMin); events.set(b+"conditions.timeMax",e.conditions.timeMax); events.set(b+"conditions.requiredInventoryMaterial",e.conditions.requiredInventoryMaterial); events.set(b+"conditions.requiredHelmetMaterial",e.conditions.requiredHelmetMaterial); events.set(b+"conditions.failMessage",e.conditions.failMessage); events.set(b+"conditions.failPenalty",e.conditions.failPenalty);
        events.set(b+"particleSettings",null); for(var pe:e.particleSettings.entrySet()){String q=b+"particleSettings."+pe.getKey()+"."; ParticleSettings ps=pe.getValue(); events.set(q+"radius",ps.radius); events.set(q+"durationSeconds",ps.durationSeconds); events.set(q+"count",ps.count); events.set(q+"speed",ps.speed); events.set(q+"size",ps.size); events.set(q+"color",ps.color); events.set(q+"note",ps.note); events.set(q+"notes",ps.notes);}
        // Never destroy the last known point snapshot while worlds are temporarily unavailable.
        // If pointsIncomplete=true, the existing events.yml points section is kept verbatim.
        if(!e.pointsIncomplete || !e.points.isEmpty()){
            events.set(b+"points",null); int i=0;
            for(SpawnPoint point:e.points){String q=b+"points."+(i++)+"."; events.set(q+"id",point.id); events.set(q+"chance",point.chance); events.set(q+"enabled",point.enabled); events.set(q+"addedBy",point.addedBy==null?null:point.addedBy.toString()); events.set(q+"location.world",point.location.getWorld()!=null?point.location.getWorld().getName():point.worldName); events.set(q+"location.worldUuid",point.location.getWorld()!=null?point.location.getWorld().getUID().toString():(point.worldUuid==null?null:point.worldUuid.toString())); events.set(q+"location.x",point.location.getX()); events.set(q+"location.y",point.location.getY()); events.set(q+"location.z",point.location.getZ()); events.set(q+"location.yaw",(double)point.location.getYaw()); events.set(q+"location.pitch",(double)point.location.getPitch());}
        }
        events.set(b+"blockedLocations",null); int bi=0;
        for(Location blocked:e.blockedLocations)writeLocation(events,b+"blockedLocations."+(bi++),blocked);
        saveEvents(); savePoints(e);
    }
    public boolean removeEvent(String id){
        if(id==null||id.isBlank()||!events.isConfigurationSection("events."+id))return false;
        events.set("events."+id,null);
        saveEvents();
        YamlConfiguration pts=load(pointsFile);
        pts.set("events."+id,null);
        save(pts,pointsFile);
        YamlConfiguration active=load(instancesFile);
        ConfigurationSection inst=active.getConfigurationSection("instances");
        if(inst!=null){for(String key:new ArrayList<>(inst.getKeys(false))){if(id.equalsIgnoreCase(active.getString("instances."+key+".eventId")))active.set("instances."+key,null);}}
        save(active,instancesFile);
        ConfigurationSection players=stats.getConfigurationSection("players");
        if(players!=null){for(String player:new ArrayList<>(players.getKeys(false))){stats.set("players."+player+".items."+id,null);stats.set("players."+player+".rewards."+id,null);}}
        saveStats();
        return true;
    }
    public EventDefinition getEvent(String id){String b="events."+id+"."; if(!events.isConfigurationSection("events."+id))return null; EventDefinition e=new EventDefinition(id);e.adminName=events.getString(b+"adminName",id);e.playerName=events.getString(b+"playerName","Ивентовый предмет");e.description=events.getString(b+"description","");e.authorUuid=events.getString(b+"authorUuid",null);e.authorName=events.getString(b+"authorName",null);e.createdAt=events.getLong(b+"createdAt",System.currentTimeMillis());e.rewardMin=events.getInt(b+"rewardMin",1);e.rewardMax=events.getInt(b+"rewardMax",5);try{e.spawnMode=EventDefinition.SpawnMode.valueOf(events.getString(b+"spawnMode","RANDOM"));}catch(Exception ignored){}e.maxActive=events.getInt(b+"maxActive",30);e.regionMaxActive=events.getInt(b+"regionMaxActive",30);e.minDistance=events.getDouble(b+"minDistance",8);e.minPlayerDistance=events.getDouble(b+"minPlayerDistance",0.0D);e.minY=events.getInt(b+"minY",-64);e.maxY=events.getInt(b+"maxY",320);e.requireSolidGround=events.getBoolean(b+"requireSolidGround",true);e.allowFloating=events.getBoolean(b+"allowFloating",false);e.allowWater=events.getBoolean(b+"allowWater",false);e.allowLava=events.getBoolean(b+"allowLava",false);e.minDelaySeconds=events.getLong(b+"minDelaySeconds",15);e.maxDelaySeconds=events.getLong(b+"maxDelaySeconds",60);e.minLifetimeSeconds=events.getLong(b+"minLifetimeSeconds",30);e.maxLifetimeSeconds=events.getLong(b+"maxLifetimeSeconds",120);e.spawnChance=events.getDouble(b+"spawnChance",100);e.enabled=events.getBoolean(b+"enabled",true); long s=events.getLong(b+"startAt",0); if(s>0)e.startAt=Instant.ofEpochMilli(s); long en=events.getLong(b+"endAt",0); if(en>0)e.endAt=Instant.ofEpochMilli(en); e.weeklyScheduleEnabled=events.getBoolean(b+"weeklyScheduleEnabled",false); e.weeklyDayOfWeek=events.getInt(b+"weeklyDayOfWeek",6); e.weeklyTimeMinutes=events.getInt(b+"weeklyTimeMinutes",1200); e.weeklyDurationSeconds=events.getLong(b+"weeklyDurationSeconds",3600); e.dailyScheduleEnabled=events.getBoolean(b+"dailyScheduleEnabled",false); e.dailyTimeMinutes=events.getInt(b+"dailyTimeMinutes",1200); e.dailyDurationSeconds=events.getLong(b+"dailyDurationSeconds",3600); e.worldGuardRegion=events.getString(b+"worldGuardRegion",null);e.worldGuardWorld=events.getString(b+"worldGuardWorld",null);e.worldGuardRegions.addAll(events.getStringList(b+"worldGuardRegions"));if(e.worldGuardRegion!=null&&e.worldGuardRegions.stream().noneMatch(x->x.equalsIgnoreCase(e.worldGuardRegion)))e.worldGuardRegions.add(e.worldGuardRegion);e.hasArea=events.getBoolean(b+"hasArea",false);e.areaMinX=events.getInt(b+"areaMinX",0);e.areaMinY=events.getInt(b+"areaMinY",0);e.areaMinZ=events.getInt(b+"areaMinZ",0);e.areaMaxX=events.getInt(b+"areaMaxX",0);e.areaMaxY=events.getInt(b+"areaMaxY",0);e.areaMaxZ=events.getInt(b+"areaMaxZ",0);e.randomRotation=events.getBoolean(b+"randomRotation",true);e.animationSmall=events.getBoolean(b+"animationSmall",false);e.visualOffsetY=events.getDouble(b+"visualOffsetY",0.30);e.animationSpeed=Math.max(0.05,events.getDouble(b+"animationSpeed",1.0));e.collectParticleCount=events.getInt(b+"collectParticleCount",-1);e.collectParticleColor=events.getString(b+"collectParticleColor","#FFFFFF");e.collectParticleColors.addAll(events.getStringList(b+"collectParticleColors"));e.collectParticle=events.getString(b+"collectParticle","");e.collectParticles.addAll(events.getStringList(b+"collectParticles"));if(e.collectParticles.isEmpty() && !e.collectParticle.isBlank()) e.collectParticles.add(e.collectParticle);if(e.collectParticleColors.isEmpty() && e.collectParticleColor!=null && !e.collectParticleColor.isBlank())e.collectParticleColors.add(e.collectParticleColor); e.sound=events.getString(b+"sound",""); e.sounds.addAll(events.getStringList(b+"sounds")); if(e.sounds.isEmpty()&&!e.sound.isBlank()) e.sounds.add(e.sound); e.soundEnabled=events.getBoolean(b+"soundEnabled",true); e.soundVolume=(float)events.getDouble(b+"soundVolume",1.0D); e.soundPitch=(float)events.getDouble(b+"soundPitch",1.0D); e.conditions.enabled=events.getBoolean(b+"conditions.enabled",false); e.conditions.world=events.getString(b+"conditions.world",""); e.conditions.weather=events.getString(b+"conditions.weather","ANY"); e.conditions.dayNight=events.getString(b+"conditions.dayNight","ANY"); e.conditions.timeMin=events.getInt(b+"conditions.timeMin",0); e.conditions.timeMax=events.getInt(b+"conditions.timeMax",24000); e.conditions.requiredInventoryMaterial=events.getString(b+"conditions.requiredInventoryMaterial",""); e.conditions.requiredHelmetMaterial=events.getString(b+"conditions.requiredHelmetMaterial",""); e.conditions.failMessage=events.getString(b+"conditions.failMessage","§cВы не можете собрать этот ивент: выполните требования."); e.conditions.failPenalty=events.getInt(b+"conditions.failPenalty",0); org.bukkit.configuration.ConfigurationSection pss=events.getConfigurationSection(b+"particleSettings"); if(pss!=null) for(String pk:pss.getKeys(false)){ParticleSettings ps=new ParticleSettings(); String q=b+"particleSettings."+pk+"."; ps.radius=events.getDouble(q+"radius",0.15D); ps.durationSeconds=events.getLong(q+"durationSeconds",-1L); ps.count=events.getInt(q+"count",-1); ps.speed=events.getDouble(q+"speed",0.01D); ps.size=(float)events.getDouble(q+"size",1.0D); String loadedColor=events.getString(q+"color",""); ps.color=("#FFFFFF".equalsIgnoreCase(loadedColor) && e.collectParticleColors.stream().anyMatch(c->c!=null && !c.equalsIgnoreCase("#FFFFFF")))?"":loadedColor; ps.note=events.getInt(q+"note",-1); for(Integer note:events.getIntegerList(q+"notes")) if(note!=null && note>=0 && note<=24 && !ps.notes.contains(note)) ps.notes.add(note); if(ps.notes.isEmpty() && events.contains(q+"note") && ps.note>=0 && ps.note<=24) ps.notes.add(ps.note); e.particleSettings.put(pk,ps);} e.visual=events.getItemStack(b+"visual",null);ConfigurationSection ps=events.getConfigurationSection(b+"points");int malformed=0;if(ps!=null){for(String k:ps.getKeys(false)){SpawnPoint sp=readSpawnPoint(events,b+"points."+k,k);if(sp!=null)e.points.add(sp);else if(MALFORMED_POINT_KEY.matcher(k).matches())malformed++;}}if(malformed>0){pointFormatRepairNeeded=true;plugin.getLogger().warning("[points-guard] "+id+": отброшено "+malformed+" битых записей точек из events.yml (сломанный формат 3.4.0—3.4.2). Файл будет перезаписан в корректном виде.");} deduplicateLoadedPoints(e); loadPointsInto(e,!e.points.isEmpty()); deduplicateLoadedPoints(e); ConfigurationSection bs=events.getConfigurationSection(b+"blockedLocations");if(bs!=null)for(String k:bs.getKeys(false)){Location l=readLocation(events,b+"blockedLocations."+k);if(l!=null)e.blockedLocations.add(l);} return e;}
    public Map<String,EventDefinition> loadEvents(){Map<String,EventDefinition> out=new LinkedHashMap<>();pointFormatRepairNeeded=false;ConfigurationSection sec=events.getConfigurationSection("events");if(sec!=null)for(String id:sec.getKeys(false)){EventDefinition e=getEvent(id);if(e!=null)out.put(id,e);}if(pointFormatRepairNeeded){pointFormatRepairNeeded=false;for(EventDefinition e:out.values())saveEvent(e);plugin.getLogger().info("[points-guard] events.yml и points.yml перезаписаны в корректном формате 3.4.4.");}return out;}
    public void ensurePointsBackup(Map<String,EventDefinition> loaded){for(EventDefinition e:loaded.values())savePoints(e);}
    public synchronized void saveInstance(EventInstance i){String b="instances."+i.instanceId+".";instances.set(b+"eventId",i.eventId);writeLocation(instances,b+"location",i.location);instances.set(b+"yaw",i.yaw);instances.set(b+"spawnedAt",i.spawnedAt);instances.set(b+"expiresAt",i.expiresAt);instances.set(b+"collected",i.collected);saveInstances();}
    public synchronized void removeInstance(UUID id){instances.set("instances."+id,null);saveInstances();}
    /** Clears only runtime active instances; event definitions are not touched. */
    public synchronized int clearAllInstances(){
        ConfigurationSection sec = instances.getConfigurationSection("instances");
        int count = sec == null ? 0 : sec.getKeys(false).size();
        instances.set("instances", null);
        saveInstances();
        return count;
    }
    public Map<UUID,EventInstance> loadInstances(){Map<UUID,EventInstance> out=new LinkedHashMap<>();ConfigurationSection sec=instances.getConfigurationSection("instances");if(sec!=null)for(String k:sec.getKeys(false)){try{UUID id=UUID.fromString(k);Location l=readLocation(instances,"instances."+k+".location");if(l==null)continue;String event=instances.getString("instances."+k+".eventId");if(event==null)continue;EventInstance restored=new EventInstance(id,event,l,(float)instances.getDouble("instances."+k+".yaw",l.getYaw()),instances.getLong("instances."+k+".spawnedAt"),instances.getLong("instances."+k+".expiresAt"));
                    restored.collected=instances.getBoolean("instances."+k+".collected",false);
                    out.put(id,restored);}catch(Exception ex){plugin.getLogger().warning("Invalid active instance: "+k);}}return out;}
    public synchronized void incrementStats(UUID player,String name,String eventId,int reward){String b="players."+player+".";stats.set(b+"name",name);stats.set(b+"items."+eventId,stats.getInt(b+"items."+eventId,0)+1);stats.set(b+"rewards."+eventId,stats.getInt(b+"rewards."+eventId,0)+reward);stats.set(b+"total",stats.getInt(b+"total",0)+1);saveStats();}
    public int getCount(UUID player,String event){return stats.getInt("players."+player+".items."+event,0);}
    public int getReward(UUID player,String event){return stats.getInt("players."+player+".rewards."+event,0);} public int getTotal(String event){int t=0; ConfigurationSection sec=stats.getConfigurationSection("players"); if(sec!=null) for(String u:sec.getKeys(false)) try{t+=getCount(UUID.fromString(u),event);}catch(Exception ignored){} return t;}    
    public synchronized void recordEventStart(String eventId,long when){
        if(eventId==null)return;
        String b="events."+eventId+".";
        int starts=stats.getInt(b+"starts",0)+1;
        stats.set(b+"starts",starts);
        stats.set(b+"lastStart",when);
        String h=b+"history."+when+".";
        stats.set(h+"start",when);
        stats.set(h+"end",0L);
        stats.set(h+"reason","started");
        saveStats();
    }
    public synchronized void recordEventEnd(String eventId,long startedAt,long endedAt,String reason){
        if(eventId==null)return;
        String b="events."+eventId+".";
        stats.set(b+"lastEnd",endedAt);
        String h=b+"history."+startedAt+".";
        stats.set(h+"start",startedAt);
        stats.set(h+"end",endedAt);
        stats.set(h+"reason",reason==null?"ended":reason);
        saveStats();
    }
    public int getEventStarts(String eventId){return stats.getInt("events."+eventId+".starts",0);}
    public long getEventLastStart(String eventId){return stats.getLong("events."+eventId+".lastStart",0L);}
    public long getEventLastEnd(String eventId){return stats.getLong("events."+eventId+".lastEnd",0L);}
    public ConfigurationSection getEventHistory(String eventId){return stats.getConfigurationSection("events."+eventId+".history");}

    public String storageReport(){
        File[] files={eventsFile,pointsFile,accessFile,instancesFile,statsFile,logFile};
        String[] labels={"События","Точки","Пользователи и роли","Активные экземпляры","Статистика","Журнал"};
        StringBuilder b=new StringBuilder("§6§lEventHeads — хранилище данных§r\n");
        b.append("§7Папка: §f").append(dir.getAbsolutePath()).append("\n");
        for(int i=0;i<files.length;i++){File f=files[i];b.append(f.exists()?"§a✔ ":"§c✘ ").append("§f").append(labels[i]).append("§7: ").append(f.exists()?"создан":"ОТСУТСТВУЕТ").append(" §8(").append(f.getPath()).append(")");if(f.exists())b.append(" §8— ").append(f.length()).append(" Б");b.append("\n");}
        b.append("§7Резервные копии: §f").append(backupDir.getAbsolutePath());
        return b.toString();
    }

    public boolean exportEvent(String id, java.io.File file){
        try{
            org.bukkit.configuration.ConfigurationSection src=events.getConfigurationSection("events."+id);
            if(src==null)return false;
            org.bukkit.configuration.file.YamlConfiguration out=new org.bukkit.configuration.file.YamlConfiguration();
            for(String key:src.getKeys(true)){ if(src.isConfigurationSection(key)) continue; out.set("event."+key,src.get(key)); }
            file.getParentFile().mkdirs(); out.save(file); return true;
        }catch(Exception ex){ plugin.getLogger().warning("Export failed: "+ex.getMessage()); return false; }
    }
    public boolean importEvent(String id, java.io.File file){
        try{
            org.bukkit.configuration.file.YamlConfiguration in=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            org.bukkit.configuration.ConfigurationSection src=in.getConfigurationSection("event");
            if(src==null)return false;
            events.set("events."+id,null);
            for(String key:src.getKeys(true)){ if(src.isConfigurationSection(key)) continue; events.set("events."+id+"."+key,src.get(key)); }
            events.save(eventsFile); return true;
        }catch(Exception ex){ plugin.getLogger().warning("Import failed: "+ex.getMessage()); return false; }
    }

}
