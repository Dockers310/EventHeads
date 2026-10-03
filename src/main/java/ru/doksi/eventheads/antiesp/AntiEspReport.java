package ru.doksi.eventheads.antiesp;

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.roles.Perm;
import org.bukkit.Bukkit;
import ru.doksi.eventheads.util.SchedulerUtil;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;import net.kyori.adventure.text.event.ClickEvent;import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Records Anti-ESP decoy interactions without trying to detect a cheat directly. */
public final class AntiEspReport {
    public static final class Entry {
        public final UUID uuid;
        public final String name;
        public final int total, buried, surface;
        public final long last;
        Entry(UUID uuid, String name, int total, int buried, int surface, long last) {
            this.uuid=uuid; this.name=name; this.total=total; this.buried=buried; this.surface=surface; this.last=last;
        }
    }

    private final EventHeadsPlugin plugin;
    private final File file;
    private YamlConfiguration data;
    private boolean dirty;
    private final Map<UUID, Long> cooldown=new ConcurrentHashMap<>();

    public AntiEspReport(EventHeadsPlugin plugin) {
        this.plugin=plugin;
        this.file=new File(new File(new File(plugin.getDataFolder(),"data"),"statistics"),"anti-esp.yml");
        load();
    }

    private void load() {
        try {
            if(!file.exists()) {
                File parent=file.getParentFile();
                if(parent!=null) parent.mkdirs();
                new YamlConfiguration().save(file);
            }
        } catch(IOException ex) {
            plugin.getLogger().warning("anti-esp.yml: "+ex.getMessage());
        }
        data=YamlConfiguration.loadConfiguration(file);
    }

    public synchronized void flush() {
        if(!dirty) return;
        try { data.save(file); dirty=false; }
        catch(IOException ex) { plugin.getLogger().warning("Не удалось сохранить anti-esp.yml: "+ex.getMessage()); }
    }

    public synchronized void trigger(Player player, Location where, String how, boolean buried) {
        if(!plugin.getConfig().getBoolean("security.anti-esp-report",true)) return;
        if(player.hasPermission(plugin.getConfig().getString("security.anti-esp-show-head-permission","eventheads.anti-esp.view"))) return;
        long now=System.currentTimeMillis();
        long cd=Math.max(0,plugin.getConfig().getLong("security.anti-esp-report-cooldown-ms",1500));
        Long prev=cooldown.get(player.getUniqueId());
        if(prev!=null && now-prev<cd) return;
        cooldown.put(player.getUniqueId(),now);
        cooldown.entrySet().removeIf(e->now-e.getValue()>60_000L);

        String base="players."+player.getUniqueId()+".";
        int total=data.getInt(base+"total",0)+1;
        int nBuried=data.getInt(base+"buried",0)+(buried?1:0);
        int nSurface=data.getInt(base+"surface",0)+(buried?0:1);
        String pos=where.getBlockX()+" "+where.getBlockY()+" "+where.getBlockZ();
        String world=where.getWorld()==null?"?":where.getWorld().getName();

        data.set(base+"name",player.getName());
        data.set(base+"total",total);
        data.set(base+"buried",nBuried);
        data.set(base+"surface",nSurface);
        if(data.getLong(base+"first",0L)==0L) data.set(base+"first",now);
        data.set(base+"last",now);
        data.set(base+"lastWorld",world);
        data.set(base+"lastPos",pos);
        dirty=true;

        String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(now))+
                " | "+player.getName()+" | "+player.getUniqueId()+" | "+how+
                " | "+(buried?"buried":"surface")+" | "+world+" "+pos+" | total="+total;
        writeLog(line);

        int need=buried
                ? Math.max(1,plugin.getConfig().getInt("security.anti-esp-report-threshold-buried",1))
                : Math.max(1,plugin.getConfig().getInt("security.anti-esp-report-threshold-surface",4));
        int have=buried?nBuried:nSurface;
        if(have<need || have%need!=0) return;

        String teleportCommand=coTeleportCommand(where);
        plugin.getLogger().warning("Anti-ESP trigger: "+player.getName()+" total="+total+" "+world+" "+pos);
        Component click=Component.text("[EventHeads]", NamedTextColor.DARK_PURPLE)
                .append(Component.text(" Anti-ESP: ", NamedTextColor.RED))
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text(" срабатываний ", NamedTextColor.GRAY))
                .append(Component.text(Integer.toString(total), NamedTextColor.WHITE))
                .append(Component.text(" "+world+" "+pos, NamedTextColor.GRAY))
                .clickEvent(ClickEvent.runCommand(teleportCommand))
                .hoverEvent(HoverEvent.showText(Component.text("Телепорт к точке срабатывания\n"+teleportCommand, NamedTextColor.GRAY)));
        for(Player staff:Bukkit.getOnlinePlayers()) {
            SchedulerUtil.runEntity(plugin,staff,()->{if(plugin.access().has(staff,Perm.ANTI_ESP))staff.sendMessage(click);});
        }
        for(String command:plugin.getConfig().getStringList("security.anti-esp-report-commands")) {
            String cmd=command.replace("%player%", sanitize(player.getName())).replace("%uuid%", player.getUniqueId().toString()).replace("%count%", Integer.toString(total)).replace("%buried%", Integer.toString(nBuried));
            SchedulerUtil.runGlobal(plugin,()->Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd));
        }
    }

    /**
     * RU: CoreProtect принимает "/co teleport <world> <x> <y> <z>" с реальным именем мира,
     * а не условным идентификатором. До 3.4.3 здесь подставлялось "wid:1/2/3", которое
     * CoreProtect не распознаёт (см. подсказку "Используйте /co teleport <world> <x> <y> <z>").
     * Координаты округляются до одного знака после точки: CoreProtect не принимает
     * "сырые" double-координаты вроде 65.12459560937967 (Location.getY() на неровных
     * поверхностях), но нормально работает с 65.1 — телепорту рядом с точкой этого достаточно.
     */
    private String coTeleportCommand(Location where){
        String world=where.getWorld()!=null?where.getWorld().getName():"world";
        return "/co teleport "+world+" "+round1(where.getX())+" "+round1(where.getY())+" "+round1(where.getZ());
    }
    private String round1(double v){ return String.format(java.util.Locale.ROOT,"%.1f",v); }

    private String sanitize(String raw) {
        return raw==null?"":raw.replaceAll("[;&|\\n\\r]","");
    }

    public synchronized List<Entry> top(int limit) {
        List<Entry> out=new ArrayList<>();
        ConfigurationSection sec=data.getConfigurationSection("players");
        if(sec!=null) for(String key:sec.getKeys(false)) {
            try {
                UUID uuid=UUID.fromString(key);
                out.add(new Entry(uuid,data.getString("players."+key+".name","?"),
                        data.getInt("players."+key+".total",0),
                        data.getInt("players."+key+".buried",0),
                        data.getInt("players."+key+".surface",0),
                        data.getLong("players."+key+".last",0L)));
            } catch(IllegalArgumentException ignored) {}
        }
        out.sort(Comparator.comparingInt((Entry e)->e.total).reversed()
                .thenComparing(Comparator.comparingLong((Entry e)->e.last).reversed())
                .thenComparing(e->e.name, String.CASE_INSENSITIVE_ORDER));
        return out.size()>limit?new ArrayList<>(out.subList(0,limit)):out;
    }

    public synchronized int reset(UUID player) {
        if(player!=null) {
            if(data.getConfigurationSection("players."+player)==null) return 0;
            data.set("players."+player,null); dirty=true; flush(); return 1;
        }
        ConfigurationSection sec=data.getConfigurationSection("players");
        int n=sec==null?0:sec.getKeys(false).size();
        data.set("players",null); dirty=true; flush(); return n;
    }

    private void writeLog(String line) {
        try {
            Path log=plugin.getDataFolder().toPath().resolve("data").resolve("logs").resolve("anti-esp.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log,line+System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(IOException ex) {
            plugin.getLogger().warning("Не удалось записать anti-esp.log: "+ex.getMessage());
        }
    }
}
