
package ru.doksi.eventheads;

// RU: Главный класс плагина: загрузка сервисов, слушателей, планировщиков и интеграций.
// EN: Main plugin bootstrap: services, listeners, schedulers and integrations.

import ru.doksi.eventheads.antiesp.AntiEspPacketListener;
import ru.doksi.eventheads.antiesp.AntiEspReport;
import ru.doksi.eventheads.commands.Commands;
import ru.doksi.eventheads.data.DataStore;
import ru.doksi.eventheads.events.ConditionService;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.events.EventTemplateService;
import ru.doksi.eventheads.events.RewardService;
import ru.doksi.eventheads.events.SpawnManager;
import ru.doksi.eventheads.gui.GuiManager;
import ru.doksi.eventheads.integrations.WorldEditHook;
import ru.doksi.eventheads.integrations.WorldGuardHook;
import ru.doksi.eventheads.listeners.AccessListener;
import ru.doksi.eventheads.listeners.GuiListener;
import ru.doksi.eventheads.listeners.InputManager;
import ru.doksi.eventheads.listeners.InteractionListener;
import ru.doksi.eventheads.listeners.ProtectionListener;
import ru.doksi.eventheads.listeners.SecurityScanner;
import ru.doksi.eventheads.localization.LocaleManager;
import ru.doksi.eventheads.roles.AccessManager;
import ru.doksi.eventheads.roles.Perm;
import ru.doksi.eventheads.roles.LuckPermsHook;
import ru.doksi.eventheads.services.AnimationService;
import ru.doksi.eventheads.services.EconomyAdapter;
import ru.doksi.eventheads.services.ItemService;
import ru.doksi.eventheads.util.SchedulerUtil;
import org.bukkit.Bukkit;import org.bukkit.entity.Player;import io.papermc.paper.ServerBuildInfo;import net.kyori.adventure.key.Key;import org.bukkit.event.EventHandler;import org.bukkit.event.Listener;import org.bukkit.event.world.ChunkLoadEvent;import org.bukkit.event.world.EntitiesLoadEvent;import org.bukkit.event.world.WorldLoadEvent;import org.bukkit.plugin.java.JavaPlugin;import java.time.Instant;import java.util.*;

public final class EventHeadsPlugin extends JavaPlugin {
    private DataStore data; private EconomyAdapter economy; private SecurityScanner securityScanner; private LocaleManager lang; private AccessManager access; private ItemService items; private SpawnManager spawner; private RewardService reward; private AnimationService animation; private GuiManager gui; private InputManager input; private WorldGuardHook worldGuard; private WorldEditHook worldEdit; private LuckPermsHook luckPerms; private ConditionService conditions; private EventTemplateService templates; private InteractionListener pointListener; private AntiEspPacketListener antiEspPacketListener; private AntiEspReport antiEspReport; private final Set<java.util.UUID> antiEspHiddenViewers=java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Only simple booleans/UUIDs are read from PacketEvents network threads. */
    private final Map<java.util.UUID,Boolean> antiEspViewPermissionCache=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,EventDefinition> events=new java.util.concurrent.ConcurrentHashMap<>();
    public boolean isSuperAdmin(java.util.UUID uuid){
        if(uuid==null) return false;
        var names=getConfig().getStringList("access.super-admin-names");
        Player online=Bukkit.getPlayer(uuid);
        String name=online!=null?online.getName():Bukkit.getOfflinePlayer(uuid).getName();
        return name!=null && names.stream().anyMatch(n->n.equalsIgnoreCase(name));
    }
    @Override public void onEnable(){
        // RU: Инициализация выполняется в одном месте, чтобы порядок зависимостей был предсказуемым.
        // EN: Initialization is kept in one place so service/dependency order remains deterministic.
        saveDefaultConfig();getDataFolder().mkdirs();data=new DataStore(this);data.load();antiEspHiddenViewers.addAll(data.loadAntiEspHidden());lang=new LocaleManager(this);luckPerms=new LuckPermsHook(this);luckPerms.reload();access=new AccessManager(this);access.load();items=new ItemService(this);worldGuard=new WorldGuardHook(this);worldGuard.reload();worldEdit=new WorldEditHook(this);economy=new EconomyAdapter(this);reward=new RewardService(this);conditions=new ConditionService(this);templates=new EventTemplateService(this);animation=new AnimationService(this);spawner=new SpawnManager(this);events.putAll(data.loadEvents());spawner.prepareExpectedInstances();data.ensurePointsBackup(events);gui=new GuiManager(this);input=new InputManager(this);pointListener=new InteractionListener(this);securityScanner=new SecurityScanner(this);getServer().getPluginManager().registerEvents(pointListener,this);getServer().getPluginManager().registerEvents(input,this);getServer().getPluginManager().registerEvents(securityScanner,this);getServer().getPluginManager().registerEvents(new ProtectionListener(this),this);getServer().getPluginManager().registerEvents(new GuiListener(this),this);getServer().getPluginManager().registerEvents(new AccessListener(this),this);
        getServer().getPluginManager().registerEvents(new Listener(){
            @EventHandler public void onChunkLoad(ChunkLoadEvent event){ spawner.cleanupChunk(event.getChunk()); securityScanner.trackChunk(event.getChunk()); }
            @EventHandler public void onEntitiesLoad(EntitiesLoadEvent event){ spawner.cleanupEntities(event.getEntities()); }
            @EventHandler public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent event){ securityScanner.untrackChunk(event.getChunk()); }
            @EventHandler public void onWorldLoad(WorldLoadEvent event){ SchedulerUtil.runGlobalLater(EventHeadsPlugin.this,()->data.retryIncompletePoints(),1L); }
            @EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent event){
                Player player=event.getPlayer();
                spawner.trackPlayer(player); spawner.cleanupAroundPlayer(player,8); securityScanner.startPlayerScan(player); gui.startRefresh(player);
                SchedulerUtil.runEntity(EventHeadsPlugin.this,player,()->{
                    access.syncUsePermission(player);
                    applyAntiEspStaffDefault(player);
                });
            }
            @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event){
                Player player=event.getPlayer();
                gui.stopRefresh(player); securityScanner.stopPlayerScan(player); spawner.untrackPlayer(player); clearAntiEspViewPermission(player.getUniqueId());
            }
            @EventHandler public void onMove(org.bukkit.event.player.PlayerMoveEvent event){
                Player player=event.getPlayer();
                if(event.getFrom().getWorld()!=event.getTo().getWorld() || event.getFrom().getBlockX()!=event.getTo().getBlockX() || event.getFrom().getBlockZ()!=event.getTo().getBlockZ()) spawner.trackPlayer(player);
            }
            @EventHandler public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event){ spawner.trackPlayer(event.getPlayer()); }
            @EventHandler public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event){ SchedulerUtil.runEntity(EventHeadsPlugin.this,event.getPlayer(),()->spawner.trackPlayer(event.getPlayer())); }
        },this);
        antiEspReport=new AntiEspReport(this);registerAntiEspPacketListener();validateConfig();Objects.requireNonNull(getCommand("eventheads")).setExecutor(new Commands(this));Objects.requireNonNull(getCommand("eventheads")).setTabCompleter(new Commands(this));for(Player player:getServer().getOnlinePlayers()){
            spawner.trackPlayer(player);
            spawner.cleanupAroundPlayer(player,8);
            securityScanner.startPlayerScan(player);
            gui.startRefresh(player);
            SchedulerUtil.runEntity(this,player,()->{
                access.syncUsePermission(player);
                applyAntiEspStaffDefault(player);
            });
        }
        // RU: 3.5.0 один раз очищает старый runtime от прошлых тестовых сборок,
        // затем восстанавливает только новый runtime. Физические сироты дополнительно
        // удаляются при загрузке чанков и вокруг игроков.
        SchedulerUtil.runGlobalLater(this, () -> {
            spawner.cleanupLegacyRuntimeFor350Once();
            spawner.prepareExpectedInstances();
            SchedulerUtil.runGlobalLater(this, () -> {
                spawner.restore();
                SchedulerUtil.runGlobalLater(this, () -> {
                    for(Player player:getServer().getOnlinePlayers()) spawner.cleanupAroundPlayer(player,4);
                }, 2L);
            }, 2L);
        }, 1L);
        // Retry unresolved points because custom worlds can be attached by another plugin after enable.
        SchedulerUtil.runGlobalRepeating(this,()->data.retryIncompletePoints(),100L,200L);
        long period=getConfig().getLong("spawn.scheduler-period-ticks",20);
        SchedulerUtil.runGlobalRepeating(this,spawner::tick,20L,Math.max(1L,period));
        SchedulerUtil.runGlobalRepeating(this,()->{ for(Player player:getServer().getOnlinePlayers()) spawner.cleanupAroundPlayer(player,4); },200L,200L);
        long containerScanSeconds=Math.max(30L,getConfig().getLong("security.full-container-scan-seconds",120L));
        SchedulerUtil.runGlobalRepeating(this,securityScanner::scan,containerScanSeconds*20L,containerScanSeconds*20L);
        long autosaveTicks=Math.max(20L,getConfig().getLong("storage.autosave-seconds",30)*20L);
        SchedulerUtil.runGlobalRepeating(this,()->{data.autosave();if(antiEspReport!=null)antiEspReport.flush();},autosaveTicks,autosaveTicks);
printStartupBanner(); getLogger().info("EventHeads "+getDescription().getVersion()+" enabled. Events: "+events.size());}
    @Override public void onDisable(){
        // RU: Сохраняем active.yml и удаляем все визуальные сущности/анимации.
        // EN: Persist active.yml while removing all visual EventHead/animation entities.
        data.saveAll();if(antiEspReport!=null)antiEspReport.flush();if(antiEspPacketListener!=null){try{com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().unregisterListener(antiEspPacketListener);}catch(Throwable ignored){}}spawner.despawnEntitiesOnly();}

    private void registerAntiEspPacketListener(){
        if(!getConfig().getBoolean("security.anti-esp-hide-head-item",true)) return;
        if(getServer().getPluginManager().getPlugin("packetevents")==null) return;
        try {
            antiEspPacketListener=new AntiEspPacketListener(this);
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(antiEspPacketListener);
            getLogger().info("Anti-ESP packet head filtering enabled via PacketEvents.");
        } catch(Throwable t) {
            antiEspPacketListener=null;
            getLogger().warning("PacketEvents found, but Anti-ESP head filtering could not be enabled: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));
        }
    }
    public void reloadAll(Runnable completion){
        SchedulerUtil.runGlobal(this,()->{
            reloadConfig();
            validateConfig();
            lang.reload();
            data.load();
            events.clear();
            events.putAll(data.loadEvents());
            data.ensurePointsBackup(events);
            access.load();
            worldGuard.reload();
            worldEdit.reload();
            luckPerms.reload();
            if(antiEspPacketListener!=null)antiEspPacketListener.reload();
            for(Player player:getServer().getOnlinePlayers()) SchedulerUtil.runEntity(this,player,()->access.syncUsePermission(player));
            if(completion!=null) SchedulerUtil.runGlobal(this,completion);
        });
    }
    public void loggerInfoPointRecovery(EventDefinition e,int before){
        getLogger().info("Точки события '"+e.id+"' восстановлены: "+e.points.size()+" шт."+(before>0?" (сохранилось уже загруженных: "+before+")":""));
    }
    /**
     * RU: Административный состав по умолчанию начинает с режима /eventheads antiesp hide.
     * Команда /eventheads antiesp show может временно вернуть приманки для текущей сессии.
     */
    private void applyAntiEspStaffDefault(Player player){
        if(player==null || !getConfig().getBoolean("security.anti-esp-admin-default-hidden",true)) return;
        if(access!=null && (access.isAdmin(player.getUniqueId()) || access.has(player, Perm.SETTINGS) || access.has(player, Perm.ANTI_ESP))) antiEspHiddenViewers.add(player.getUniqueId());
    }

    public AntiEspReport antiEspReport(){return antiEspReport;}
    public boolean antiEspHidden(org.bukkit.entity.Player p){return p!=null && antiEspHiddenViewers.contains(p.getUniqueId());}
    public void antiEspVisibility(org.bukkit.entity.Player p,boolean show){if(p==null)return;if(show)antiEspHiddenViewers.remove(p.getUniqueId());else antiEspHiddenViewers.add(p.getUniqueId());data.saveAntiEspHidden(antiEspHiddenViewers);}
    /** Called only from an entity-owned context. */
    public void updateAntiEspViewPermission(Player p){
        if(p==null)return;
        String perm=getConfig().getString("security.anti-esp-show-head-permission","eventheads.anti-esp.view");
        antiEspViewPermissionCache.put(p.getUniqueId(),p.hasPermission(perm));
    }
    /** Network-thread-safe read: no Bukkit Player API is touched here. */
    public boolean antiEspViewPermission(java.util.UUID uuid){return Boolean.TRUE.equals(antiEspViewPermissionCache.get(uuid));}
    public void clearAntiEspViewPermission(java.util.UUID uuid){if(uuid!=null)antiEspViewPermissionCache.remove(uuid);}
    public DataStore data(){return data;} public EconomyAdapter economy(){return economy;} public ConditionService conditions(){return conditions;} public EventTemplateService templates(){return templates;} public LocaleManager lang(){return lang;} public AccessManager access(){return access;} public ItemService items(){return items;} public SpawnManager spawner(){return spawner;} public RewardService reward(){return reward;} public AnimationService animation(){return animation;} public GuiManager gui(){return gui;} public InputManager input(){return input;} public WorldGuardHook worldGuard(){return worldGuard;} public WorldEditHook worldEdit(){return worldEdit;} public LuckPermsHook luckPerms(){return luckPerms;} public InteractionListener pointListener(){return pointListener;} public Map<String,EventDefinition> events(){return events;}
    /**
     * RU: разрешает ввод пользователя (ID в любом регистре, административное или игровое
     * название события) в реальный внутренний ID. Используется везде, где игрок вводит
     * событие текстом вручную — например, привязка мульти-инструмента точек.
     */
    public String resolveEventId(String input){
        if(input==null||input.isBlank())return "";
        String raw=input.trim();
        if(events.containsKey(raw))return raw;
        for(String key:events.keySet()) if(key.equalsIgnoreCase(raw)) return key;
        for(EventDefinition e:events.values()){
            if(e.adminName!=null&&e.adminName.equalsIgnoreCase(raw))return e.id;
            if(e.playerName!=null&&e.playerName.equalsIgnoreCase(raw))return e.id;
        }
        return raw;
    }
    /**
     * Формирует диагностику одним сообщением. Ширина рамки намеренно фиксирована,
     * чтобы строки с версией и заголовком имели одинаковую длину даже в консоли Paper.
     * Важно: консоль сама добавляет дату/INFO перед каждой физической строкой, поэтому
     * визуально это несколько строк лога, хотя Bukkit получает одну строковую отправку.
     */
    /**
     * Формирует диагностику одним сообщением. Консоль Paper добавляет свой префикс
     * к каждой физической строке, поэтому многострочный блок естественно выглядит
     * как несколько строк лога, хотя плагин отправляет одну строку с переводами строк.
     */
    private void validateConfig(){
        validateRange("security.anti-esp-decoys-min","security.anti-esp-decoys-max",0,1);
        validateRange("security.anti-esp-decoys-delay-min-seconds","security.anti-esp-decoys-delay-max-seconds",0,1);
        validateRange("security.anti-esp-decoys-radius-min","security.anti-esp-decoys-radius-max",0,0);
        validateRange("security.anti-esp-decoys-lifetime-min-seconds","security.anti-esp-decoys-lifetime-max-seconds",1,1);
        double surface=Math.max(0,getConfig().getDouble("security.anti-esp-decoys-surface-share",0.25D));
        double air=Math.max(0,getConfig().getDouble("security.anti-esp-decoys-air-share",0.15D));
        if(surface+air>1.0D)getLogger().warning("security.anti-esp-decoys-surface-share + air-share > 1.0; proportions will be normalized at runtime.");
    }
    public double eventHeadEntityYOffset(EventDefinition eventDefinition){
        double configured=eventDefinition!=null&&Double.isFinite(eventDefinition.visualOffsetY)
                ?eventDefinition.visualOffsetY
                :getConfig().getDouble("animation.visual-offset-y",0.10D);
        return -1.55D+(configured<0.0D?0.10D:configured);
    }

    private void validateRange(String minKey,String maxKey,double minFloor,double ignored){
        double min=getConfig().getDouble(minKey,minFloor); double max=getConfig().getDouble(maxKey,min);
        if(min>max)getLogger().warning(minKey+" ("+min+") is greater than "+maxKey+" ("+max+"); runtime will use a safe ordered range.");
        if(min<minFloor)getLogger().warning(minKey+" ("+min+") is below allowed minimum "+minFloor+".");
    }

    private String serverPlatform(){
        try{
            ServerBuildInfo info=ServerBuildInfo.buildInfo();
            // Сначала проверяем точный brandId, затем официальный compatibility-check PaperMC.
            // Folia использует brand id papermc:folia.
            Key brandId=info.brandId();
            if(brandId!=null && "papermc:folia".equalsIgnoreCase(brandId.asString())) return "Folia";
            if(info.isBrandCompatible(Key.key("papermc","folia"))) return "Folia";
            if(brandId!=null && "papermc:paper".equalsIgnoreCase(brandId.asString())) return "Paper";
            if(info.isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID)) return "Paper";
            String brand=info.brandName();
            if(brand!=null&&!brand.isBlank())return brand;
        }catch(Throwable ignored){}
        return "Unknown";
    }

    private String serverBrandId(){
        try{
            Key key=ServerBuildInfo.buildInfo().brandId();
            return key==null?"unknown":key.asString();
        }catch(Throwable ignored){
            return "unknown";
        }
    }

    private String serverVersion(){
        try{
            ServerBuildInfo info=ServerBuildInfo.buildInfo();
            String name=info.minecraftVersionName();
            if(name!=null&&!name.isBlank())return name;
        }catch(Throwable ignored){}
        String v=getServer().getBukkitVersion();
        if(v==null||v.isBlank())return "unknown";
        int dash=v.indexOf('-');
        return dash>0?v.substring(0,dash):v;
    }

    public String debugText(){
        // RU: раньше здесь была псевдографическая рамка (╔═╗) с ручным паддингом по числу
        // символов. В чате Minecraft шрифт не моноширинный, поэтому такая рамка визуально
        // "плывёт" — правый край не совпадает по вертикали ни с одной строкой. Заменено на
        // простой заголовок без рамки, который выглядит одинаково в любом клиенте.
        StringBuilder b=new StringBuilder();
        b.append("§5§l▪ §d§lEventHeads §7• диагностика\n");
        b.append("§7Версия: §f").append(getDescription().getVersion()).append(" §8| §7Ядро: §f").append(serverPlatform()).append(" §8| §7Minecraft: §f").append(serverVersion()).append("\n");
        b.append("§7Событий: §e").append(events.size()).append(" §8| §7Активных экземпляров: §e").append(spawner.instances().size()).append("\n");
        b.append("§6§lСостояние событий§r\n");
        for(EventDefinition e:events.values()){
            String status=scheduleDebugStatus(e);
            b.append(" §d▸ §f").append(e.playerName==null?e.id:e.playerName)
             .append(" §8• §7создал: §f").append(e.authorName==null?"неизвестно":e.authorName)
             .append(" §8• §7режим: §e").append(modeRu(e.spawnMode))
             .append(" §8• §7точек: §e").append(e.points.size())
             .append(" §8• §7чёрный список: §e").append(e.blockedLocations.size())
             .append(" §8• §7статус: ").append(status).append("\n");
        }
        return b.toString();
    }
    private String scheduleDebugStatus(EventDefinition e){Instant now=Instant.now();if(e.startAt!=null&&now.isBefore(e.startAt))return "§eНЕ НАЧАЛОСЬ";if(e.endAt!=null&&!now.isBefore(e.endAt))return "§cЗАВЕРШЕНО";return e.enabled?"§aАКТИВНО":"§7ВЫКЛЮЧЕНО";}
    private void printStartupBanner(){
        getLogger().info("        ███████╗██╗  ██╗");
        getLogger().info("        ██╔════╝██║  ██║");
        getLogger().info("        █████╗  ███████║");
        getLogger().info("        ██╔══╝  ██╔══██║");
        getLogger().info("        ███████╗██║  ██║");
        getLogger().info("        ╚══════╝╚═╝  ╚═╝  EventHeads v"+getDescription().getVersion()+" • ядро: "+serverPlatform()+" "+serverVersion());
    }
    private String modeRu(EventDefinition.SpawnMode mode){return switch(mode){case RANDOM->"СЛУЧАЙНЫЙ";case POINTS->"ТОЧКИ";case MIXED->"СМЕШАННЫЙ";};}

}
