package ru.doksi.eventheads.roles;

// RU: Роли, веса, индивидуальные права и супер-администратор.
// EN: Roles, role weights, per-player overrides and super-admin logic.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.events.SpawnPoint;
import ru.doksi.eventheads.util.SchedulerUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;

import static ru.doksi.eventheads.roles.Perm.POINTS;
import static ru.doksi.eventheads.roles.Perm.POINTS_ALL;

public final class AccessManager {
    private final EventHeadsPlugin plugin;
    private final Map<UUID, String> roles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, String> names = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, CustomRole> customRoles = new java.util.concurrent.ConcurrentHashMap<>();
    // RU: Дополнительные настройки точек для встроенных ролей и собственного центра кастомных ролей.
    // EN: Extra point settings for built-in roles and optional custom-role center overrides.
    private final Map<String, PointRoleSettings> rolePointSettings = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Boolean>> overrides = new java.util.concurrent.ConcurrentHashMap<>();

    public AccessManager(EventHeadsPlugin plugin){ this.plugin = plugin; }

    public void load(){
        roles.clear(); names.clear(); customRoles.clear(); rolePointSettings.clear(); overrides.clear();
        var c=plugin.data().access();
        var rs=c.getConfigurationSection("roles");
        if(rs!=null) for(String id:rs.getKeys(false)){
            String name=c.getString("roles."+id+".name",id);
            int weight=c.getInt("roles."+id+".weight",1);
            Set<String> perms=new LinkedHashSet<>(c.getStringList("roles."+id+".permissions"));
            customRoles.put(id.toLowerCase(Locale.ROOT),new CustomRole(id,name,weight,perms,                    c.getString("roles."+id+".icon","WRITABLE_BOOK"),                    c.getInt("roles."+id+".maxPoints",-1),                    c.getDouble("roles."+id+".pointRadius",-1.0D)));
        }
        var rps=c.getConfigurationSection("role-point-settings");
        if(rps!=null) for(String id:rps.getKeys(false)){
            String path="role-point-settings."+id;
            int maxPoints=c.getInt(path+".maxPoints",-1);
            double pointRadius=c.getDouble(path+".pointRadius",-1.0D);
            double centerX=c.contains(path+".center.x")?c.getDouble(path+".center.x"):Double.NaN;
            double centerZ=c.contains(path+".center.z")?c.getDouble(path+".center.z"):Double.NaN;
            rolePointSettings.put(id.toLowerCase(Locale.ROOT),new PointRoleSettings(maxPoints,pointRadius,centerX,centerZ));
        }
        var os=c.getConfigurationSection("overrides");
        if(os!=null) for(String u:os.getKeys(false)){ try{UUID uuid=UUID.fromString(u); for(String perm:os.getConfigurationSection(u).getKeys(false)) overrides.computeIfAbsent(uuid,k->new java.util.concurrent.ConcurrentHashMap<>()).put(perm.trim().toLowerCase(Locale.ROOT),c.getBoolean("overrides."+u+"."+perm));}catch(Exception ignored){} }
        var section=c.getConfigurationSection("players");
        if(section!=null) for(String k:section.getKeys(false)) try{
            UUID u=UUID.fromString(k);
            roles.put(u,c.getString("players."+k+".role",c.getString("players."+k,"NONE")));
            String n=c.getString("players."+k+".name",null);
            if(n!=null&&!n.isBlank()) names.put(u,n);
        }catch(Exception ignored){}
    }
    public synchronized void save(){
        var c=plugin.data().access(); c.set("players",null); c.set("roles",null); c.set("role-point-settings",null); c.set("overrides",null);
        for(var e:roles.entrySet()){ c.set("players."+e.getKey()+".role",e.getValue()); String n=names.get(e.getKey()); if(n!=null)c.set("players."+e.getKey()+".name",n); }
        for(var e:rolePointSettings.entrySet()){
            String id=e.getKey(); PointRoleSettings ps=e.getValue();
            c.set("role-point-settings."+id+".maxPoints",ps.maxPoints);
            c.set("role-point-settings."+id+".pointRadius",ps.pointRadius);
            if(ps.hasCenter()){
                c.set("role-point-settings."+id+".center.x",ps.centerX);
                c.set("role-point-settings."+id+".center.z",ps.centerZ);
            }
        }
        for(var e:overrides.entrySet()) for(var o:e.getValue().entrySet()) c.set("overrides."+e.getKey()+"."+o.getKey(),o.getValue());
        for(var r:customRoles.values()){
            c.set("roles."+r.id()+".name",r.displayName());
            c.set("roles."+r.id()+".weight",r.weight());
            c.set("roles."+r.id()+".permissions",new ArrayList<>(r.permissions())); c.set("roles."+r.id()+".icon",r.icon());
            c.set("roles."+r.id()+".maxPoints",r.maxPoints());
            c.set("roles."+r.id()+".pointRadius",r.pointRadius());
        }
        plugin.data().saveAccess();
    }
    public Role get(UUID uuid){
        String id=roleId(uuid); try{return Role.valueOf(id.toUpperCase(Locale.ROOT));}catch(Exception ignored){return Role.NONE;}
    }
    public String roleId(UUID uuid){
        if(plugin.isSuperAdmin(uuid)) return "ADMIN";
        Player online=Bukkit.getPlayer(uuid);
        if(online!=null && plugin.luckPerms()!=null){
            String mapped=plugin.luckPerms().mappedRole(online);
            String internal=roles.getOrDefault(uuid,"NONE");
            if(mapped!=null && weight(mapped)>weight(internal)) return mapped;
        }
        return roles.getOrDefault(uuid,"NONE");
    }
    public String roleDisplay(UUID uuid){
        String id=roleId(uuid); CustomRole cr=customRoles.get(id.toLowerCase(Locale.ROOT)); if(cr!=null)return cr.displayName();
        try{return Role.valueOf(id.toUpperCase(Locale.ROOT)).name();}catch(Exception ignored){return id;}
    }
    public boolean allows(UUID uuid,String permission){
        if(plugin.isSuperAdmin(uuid)) return true;
        String normalized=permission==null?"":permission.trim().toLowerCase(Locale.ROOT);
        String id=roleId(uuid);
        Map<String,Boolean> ov=overrides.get(uuid); if(ov!=null&&ov.containsKey(normalized)) return Boolean.TRUE.equals(ov.get(normalized));
        return roleAllows(uuid,normalized);
    }

    /** Permission supplied by the assigned EventHeads role, without per-player overrides. */
    public boolean roleAllows(UUID uuid,String permission){
        if(plugin.isSuperAdmin(uuid)) return true;
        String normalized=permission==null?"":permission.trim().toLowerCase(Locale.ROOT);
        String id=roleId(uuid);
        CustomRole cr=customRoles.get(id.toLowerCase(Locale.ROOT));
        if(cr!=null)return cr.allows(normalized);
        try{return Role.valueOf(id.toUpperCase(Locale.ROOT)).allows(normalized);}catch(Exception ignored){return false;}
    }
    public boolean has(Player p,String permission){
        if(plugin.isSuperAdmin(p.getUniqueId())) return true;
        if(plugin.luckPerms()!=null && plugin.luckPerms().available()) {
            if(plugin.luckPerms().has(p,"eventheads.admin") || plugin.luckPerms().has(p,"eventheads."+permission)) return true;
        }
        if(p.hasPermission("eventheads.admin") || p.hasPermission("eventheads."+permission) || allows(p.getUniqueId(),permission)) return true;
        // points-all is a superset of ordinary point access.
        return POINTS.equals(permission) && allows(p.getUniqueId(),POINTS_ALL);
    }
    public int weight(String roleId){
        CustomRole cr=customRoles.get(roleId.toLowerCase(Locale.ROOT)); if(cr!=null)return cr.weight();
        try{return Role.valueOf(roleId.toUpperCase(Locale.ROOT)).weight();}catch(Exception ignored){return 0;}
    }
    public boolean canGrant(Player actor,String targetRole){
        if(plugin.isSuperAdmin(actor.getUniqueId()) || isAdmin(actor.getUniqueId())) return true;
        if(!has(actor,"access"))return false;
        String actorRole=roleId(actor.getUniqueId());
        // RU: Эти два флага позволяют серверному владельцу явно настроить повышение ролей.
        // EN: These two flags let the server owner explicitly configure role escalation.
        if(plugin.getConfig().getBoolean("access.allow-role-escalation",false)) return true;
        if(actorRole.equalsIgnoreCase("ADMIN") && targetRole.equalsIgnoreCase("ADMIN")
                && plugin.getConfig().getBoolean("access.allow-admin-to-grant-admin",true)) return true;
        return weight(targetRole)<weight(actorRole);
    }
    /** Returns whether the UUID belongs to the protected EventHeads super-administrator. */
    public boolean isSuperAdmin(UUID uuid){ return uuid != null && plugin.isSuperAdmin(uuid); }

    public void set(OfflinePlayer p,Role role){setRole(p,role.name());}
    public void setRole(OfflinePlayer p,String role){
        if(p==null||plugin.isSuperAdmin(p.getUniqueId())) return;
        roles.put(p.getUniqueId(),role); if(p.getName()!=null)names.put(p.getUniqueId(),p.getName()); save();if(p.isOnline())SchedulerUtil.runEntity(plugin,(Player)p,()->syncUsePermission((Player)p));
    }
    public void remove(OfflinePlayer p){
        if(p==null||plugin.isSuperAdmin(p.getUniqueId())) return;
        roles.remove(p.getUniqueId()); names.remove(p.getUniqueId()); save();if(p.isOnline())SchedulerUtil.runEntity(plugin,(Player)p,()->syncUsePermission((Player)p));
    }
    public Map<UUID,String> snapshot(){return Map.copyOf(roles);}
    public String playerName(UUID uuid){String n=names.get(uuid); if(n!=null)return n; OfflinePlayer p=Bukkit.getOfflinePlayer(uuid); return p.getName()!=null?p.getName():uuid.toString();}
    public Map<String,CustomRole> customRoles(){return Map.copyOf(customRoles);}
    public List<String> roleIds(){List<String> out=new ArrayList<>(); for(Role r:Role.values()) if(r!=Role.NONE) out.add(r.name()); out.addAll(customRoles.keySet()); return out;}
    public boolean createRole(String id,String name,int weight,Collection<String> permissions){ return createRole(id,name,weight,permissions,"WRITABLE_BOOK",-1,-1.0D); }
    public boolean createRole(String id,String name,int weight,Collection<String> permissions,String icon){ return createRole(id,name,weight,permissions,icon,-1,-1.0D); }
    public boolean createRole(String id,String name,int weight,Collection<String> permissions,String icon,int maxPoints,double pointRadius){
        if(!id.matches("[A-Za-z0-9_-]{1,32}")||customRoles.containsKey(id.toLowerCase(Locale.ROOT)))return false;
        customRoles.put(id.toLowerCase(Locale.ROOT),new CustomRole(id,name,weight,new LinkedHashSet<>(permissions),icon,maxPoints,pointRadius));save();return true;
    }
    public boolean updateRole(String id,String name,int weight,Collection<String> permissions){ CustomRole old=customRoles.get(id.toLowerCase(Locale.ROOT)); return old!=null && updateRole(id,name,weight,permissions,old.icon(),old.maxPoints(),old.pointRadius()); }
    public boolean updateRole(String id,String name,int weight,Collection<String> permissions,String icon){ CustomRole old=customRoles.get(id.toLowerCase(Locale.ROOT)); return old!=null && updateRole(id,name,weight,permissions,icon,old.maxPoints(),old.pointRadius()); }
    public boolean updateRole(String id,String name,int weight,Collection<String> permissions,String icon,int maxPoints,double pointRadius){
        CustomRole old=customRoles.get(id.toLowerCase(Locale.ROOT)); if(old==null)return false;
        customRoles.put(id.toLowerCase(Locale.ROOT),new CustomRole(old.id(),name,weight,new LinkedHashSet<>(permissions),icon,maxPoints,pointRadius)); save(); return true;
    }
    public boolean deleteRole(String id){
        if(RoleNames.isBuiltin(id))return false;
        String key=id.toLowerCase(Locale.ROOT);
        if(customRoles.remove(key)==null)return false;
        rolePointSettings.remove(key);
        roles.replaceAll((u,r)->r.equalsIgnoreCase(id)?"NONE":r);save();return true;
    }

    public String roleIcon(String roleId){
        CustomRole cr=customRoles.get(roleId.toLowerCase(Locale.ROOT));
        return cr!=null?cr.icon():switch(roleId.toUpperCase(Locale.ROOT)){case "ADMIN"->"NETHER_STAR";case "MANAGER"->"COMMAND_BLOCK";case "SENIOR_MODERATOR","MODERATOR"->"SHIELD";case "SENIOR_HELPER","HELPER"->"NAME_TAG";case "VIEWER"->"BOOK";default->"PAPER";};
    }
    public String luckPermsGroup(UUID uuid){
        Player online=Bukkit.getPlayer(uuid);
        if(online==null || plugin.luckPerms()==null) return null;
        return plugin.luckPerms().primaryGroup(online);
    }
    public boolean isBuiltinRole(String id){ return RoleNames.isBuiltin(id); }

    /** Максимум новых точек на одного игрока, суммарно по всем событиям. -1 = без ограничения. */
    public int maxPointsFor(UUID uuid){
        if(plugin.isSuperAdmin(uuid)) return -1;
        return roleMaxPoints(roleId(uuid));
    }

    /** Горизонтальный радиус точки от центра постановки. -1 = без ограничения. */
    public double pointRadiusFor(UUID uuid){
        if(plugin.isSuperAdmin(uuid)) return -1.0D;
        return rolePointRadius(roleId(uuid));
    }

    /** Эффективный лимит точек конкретной роли. */
    public int roleMaxPoints(String roleId){
        if(roleId==null)return -1;
        CustomRole custom=customRoles.get(roleId.toLowerCase(Locale.ROOT));
        if(custom!=null)return custom.maxPoints();
        PointRoleSettings ps=rolePointSettings.get(roleId.toLowerCase(Locale.ROOT));
        return ps==null?-1:ps.maxPoints;
    }

    /** Эффективный радиус конкретной роли. */
    public double rolePointRadius(String roleId){
        if(roleId==null)return -1.0D;
        CustomRole custom=customRoles.get(roleId.toLowerCase(Locale.ROOT));
        if(custom!=null)return custom.pointRadius();
        PointRoleSettings ps=rolePointSettings.get(roleId.toLowerCase(Locale.ROOT));
        return ps==null?-1.0D:ps.pointRadius;
    }

    /** Глобальный центр остаётся запасным центром для ролей без собственного центра. */
    public double pointCenterX(){
        return plugin.getConfig().getDouble("points.placement-center.x",0.0D);
    }

    public double pointCenterZ(){
        return plugin.getConfig().getDouble("points.placement-center.z",0.0D);
    }

    public double rolePointCenterX(String roleId){
        PointRoleSettings ps=roleId==null?null:rolePointSettings.get(roleId.toLowerCase(Locale.ROOT));
        return ps!=null && ps.hasCenter()?ps.centerX:pointCenterX();
    }

    public double rolePointCenterZ(String roleId){
        PointRoleSettings ps=roleId==null?null:rolePointSettings.get(roleId.toLowerCase(Locale.ROOT));
        return ps!=null && ps.hasCenter()?ps.centerZ:pointCenterZ();
    }

    public String pointCenterText(){
        return "X="+formatRadius(pointCenterX())+", Z="+formatRadius(pointCenterZ());
    }

    public String rolePointCenterText(String roleId){
        return "X="+formatRadius(rolePointCenterX(roleId))+", Z="+formatRadius(rolePointCenterZ(roleId));
    }

    public void setPointCenter(double x,double z){
        plugin.getConfig().set("points.placement-center.x",x);
        plugin.getConfig().set("points.placement-center.z",z);
        plugin.saveConfig();
    }

    /**
     * RU: Право менять ограничения точек роли.
     * ADMIN/супер-админ могут менять любую роль. Остальные сотрудники с ACCESS
     * могут менять только роли ниже своей и не могут менять настройки собственной роли.
     * Одного POINTS для этого недостаточно.
     */
    public boolean canEditRolePointSettings(Player actor,String targetRole){
        if(actor==null || targetRole==null || targetRole.isBlank())return false;
        if(!RoleNames.isBuiltin(targetRole) && !customRoles.containsKey(targetRole.toLowerCase(Locale.ROOT)))return false;
        if(plugin.isSuperAdmin(actor.getUniqueId()) || isAdmin(actor.getUniqueId()))return true;
        if(!has(actor,Perm.ACCESS))return false;
        String actorRole=roleId(actor.getUniqueId());
        return weight(targetRole)<weight(actorRole);
    }

    public boolean setRoleMaxPoints(Player actor,String roleId,int maxPoints){
        if(!canEditRolePointSettings(actor,roleId) || maxPoints<-1 || maxPoints>1_000_000)return false;
        String id=roleId.toLowerCase(Locale.ROOT);
        CustomRole custom=customRoles.get(id);
        if(custom!=null){
            customRoles.put(id,new CustomRole(custom.id(),custom.displayName(),custom.weight(),new LinkedHashSet<>(custom.permissions()),custom.icon(),maxPoints,custom.pointRadius()));
        } else {
            PointRoleSettings ps=rolePointSettings.get(id);
            double cx=ps!=null&&ps.hasCenter()?ps.centerX:Double.NaN;
            double cz=ps!=null&&ps.hasCenter()?ps.centerZ:Double.NaN;
            putRolePointSettings(id,maxPoints,rolePointRadius(id),cx,cz);
        }
        save(); return true;
    }

    public boolean setRolePointRadius(Player actor,String roleId,double radius){
        if(!canEditRolePointSettings(actor,roleId) || !Double.isFinite(radius) || radius<-1.0D || radius>1_000_000.0D)return false;
        String id=roleId.toLowerCase(Locale.ROOT);
        CustomRole custom=customRoles.get(id);
        if(custom!=null){
            customRoles.put(id,new CustomRole(custom.id(),custom.displayName(),custom.weight(),new LinkedHashSet<>(custom.permissions()),custom.icon(),custom.maxPoints(),radius));
        } else {
            PointRoleSettings ps=rolePointSettings.get(id);
            double cx=ps!=null&&ps.hasCenter()?ps.centerX:Double.NaN;
            double cz=ps!=null&&ps.hasCenter()?ps.centerZ:Double.NaN;
            putRolePointSettings(id,roleMaxPoints(id),radius,cx,cz);
        }
        save(); return true;
    }

    public boolean setRolePointCenter(Player actor,String roleId,double x,double z){
        if(!canEditRolePointSettings(actor,roleId) || !Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x)>30_000_000 || Math.abs(z)>30_000_000)return false;
        putRolePointSettings(roleId,roleMaxPoints(roleId),rolePointRadius(roleId),x,z);
        save(); return true;
    }

    public boolean clearRolePointCenter(Player actor,String roleId){
        if(!canEditRolePointSettings(actor,roleId))return false;
        String id=roleId.toLowerCase(Locale.ROOT);
        PointRoleSettings ps=rolePointSettings.get(id);
        if(ps==null)return true;
        if(RoleNames.isBuiltin(roleId)){
            if(ps.maxPoints==-1 && ps.pointRadius<0.0D) rolePointSettings.remove(id);
            else rolePointSettings.put(id,new PointRoleSettings(ps.maxPoints,ps.pointRadius,Double.NaN,Double.NaN));
        } else {
            rolePointSettings.remove(id);
        }
        save(); return true;
    }

    public boolean resetRoleMaxPoints(Player actor,String roleId){return setRoleMaxPoints(actor,roleId,-1);}
    public boolean resetRolePointRadius(Player actor,String roleId){return setRolePointRadius(actor,roleId,-1.0D);}

    private void putRolePointSettings(String roleId,int maxPoints,double radius,double centerX,double centerZ){
        String id=roleId.toLowerCase(Locale.ROOT);
        boolean center=Double.isFinite(centerX)&&Double.isFinite(centerZ);
        if(!RoleNames.isBuiltin(roleId)){
            if(center)rolePointSettings.put(id,new PointRoleSettings(-1,-1.0D,centerX,centerZ));
            else rolePointSettings.remove(id);
            return;
        }
        if(maxPoints==-1 && radius<0.0D && !center){rolePointSettings.remove(id);return;}
        rolePointSettings.put(id,new PointRoleSettings(maxPoints,radius,centerX,centerZ));
    }

    public boolean pointOwnershipRestricted(UUID uuid){
        if(plugin.isSuperAdmin(uuid)) return false;
        String role=roleId(uuid);
        return roleMaxPoints(role)>=0 || rolePointRadius(role)>=0.0D;
    }

    public int countOwnedPoints(UUID uuid){
        int count=0;
        for(EventDefinition event:plugin.events().values()) for(SpawnPoint point:event.points) if(uuid.equals(point.addedBy)) count++;
        return count;
    }

    public boolean pointWithinRadius(UUID uuid, Location location){
        double radius=pointRadiusFor(uuid);
        if(radius<0.0D) return true;
        if(location==null || !Double.isFinite(location.getX()) || !Double.isFinite(location.getZ())) return false;
        String role=roleId(uuid);
        double dx=location.getX()-rolePointCenterX(role);
        double dz=location.getZ()-rolePointCenterZ(role);
        return dx*dx+dz*dz <= radius*radius + 1.0E-9D;
    }

    public String pointPlacementReason(UUID uuid, Location location){
        if(!pointWithinRadius(uuid,location)){
            return "точки здесь недоступны для вашей роли (радиус от центра)";
        }
        int max=maxPointsFor(uuid);
        if(max>=0 && countOwnedPoints(uuid)>=max){
            return "лимит ваших точек уже достигнут";
        }
        return null;
    }

    public String pointPlacementDenied(UUID uuid, Location location){
        String reason=pointPlacementReason(uuid,location);
        return reason==null?null:"§cНедоступно: §7"+reason+".";
    }

    public String pointEditDenied(){
        return "§cНедоступно: §7можно изменять только свои точки.";
    }

    public boolean canEditPoint(UUID uuid, SpawnPoint point){
        if(point==null) return false;
        if(plugin.isSuperAdmin(uuid) || isAdmin(uuid) || allows(uuid,POINTS_ALL)) return true;
        return uuid.equals(point.addedBy);
    }

    private String formatRadius(double radius){
        if(Math.rint(radius)==radius) return Long.toString(Math.round(radius));
        return java.math.BigDecimal.valueOf(radius).stripTrailingZeros().toPlainString();
    }

    public void setOverride(OfflinePlayer player,String permission,boolean value){ overrides.computeIfAbsent(player.getUniqueId(),k->new java.util.concurrent.ConcurrentHashMap<>()).put(permission,value); save(); if(player.isOnline())SchedulerUtil.runEntity(plugin,(Player)player,()->syncUsePermission((Player)player)); }
    public Boolean override(UUID uuid,String permission){Map<String,Boolean> m=overrides.get(uuid);return m==null?null:m.get(permission);}
    public boolean isAdmin(UUID u){
        if(plugin.isSuperAdmin(u) || roleId(u).equalsIgnoreCase("ADMIN")) return true;
        Player p=Bukkit.getPlayer(u);
        return p!=null && (p.hasPermission("eventheads.admin") || (plugin.luckPerms()!=null && plugin.luckPerms().available() && plugin.luckPerms().has(p,"eventheads.admin")));
    }
    public void syncUsePermission(Player p){p.addAttachment(plugin).setPermission("eventheads.use",has(p,Perm.VIEW));plugin.updateAntiEspViewPermission(p);}

    private static final class PointRoleSettings {
        private final int maxPoints;
        private final double pointRadius;
        private final double centerX;
        private final double centerZ;
        private PointRoleSettings(int maxPoints,double pointRadius,double centerX,double centerZ){this.maxPoints=maxPoints;this.pointRadius=pointRadius;this.centerX=centerX;this.centerZ=centerZ;}
        private boolean hasCenter(){return Double.isFinite(centerX)&&Double.isFinite(centerZ);}
    }

    public static final class RoleNames { private RoleNames(){} public static boolean isBuiltin(String s){try{Role.valueOf(s.toUpperCase(Locale.ROOT));return true;}catch(Exception e){return false;}} }
}
