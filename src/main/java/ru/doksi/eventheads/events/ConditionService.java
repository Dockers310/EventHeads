package ru.doksi.eventheads.events;

import ru.doksi.eventheads.EventHeadsPlugin;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** Checks the optional collection conditions without changing the event's existing spawn rules. */
public final class ConditionService {
    private final EventHeadsPlugin plugin;
    public ConditionService(EventHeadsPlugin plugin){this.plugin=plugin;}

    public Result check(Player p, EventDefinition e){
        if(e==null||e.conditions==null||!e.conditions.enabled) return Result.ok();
        var c=e.conditions;
        World w=p.getWorld();
        if(c.world!=null&&!c.world.isBlank()&&!w.getName().equalsIgnoreCase(c.world)) return fail(c,"§cСобытие доступно только в мире §f"+c.world+"§c.");
        String weather=c.weather==null?"ANY":c.weather.toUpperCase(Locale.ROOT);
        if(weather.equals("CLEAR") && (w.hasStorm()||w.isThundering())) return fail(c,"§cСобрать сейчас нельзя: нужна ясная погода.");
        if(weather.equals("RAIN") && (!w.hasStorm()||w.isThundering())) return fail(c,"§cСобрать сейчас нельзя: нужен дождь.");
        if(weather.equals("THUNDER") && !w.isThundering()) return fail(c,"§cСобрать сейчас нельзя: нужна гроза.");
        long t=w.getTime()%24000L; int timeMin=Math.max(0,Math.min(23999,c.timeMin)); int timeMax=Math.max(0,Math.min(24000,c.timeMax));
        boolean timeOk=timeMin<=timeMax?(t>=timeMin&&t<=timeMax):(t>=timeMin||t<=timeMax);
        if(c.dayNight!=null&&!c.dayNight.equalsIgnoreCase("ANY")){
            boolean day=t>=0&&t<13000L; if(c.dayNight.equalsIgnoreCase("DAY")&&!day) return fail(c,"§cСобрать сейчас нельзя: сейчас нужна дневная часть суток."); if(c.dayNight.equalsIgnoreCase("NIGHT")&&day) return fail(c,"§cСобрать сейчас нельзя: сейчас нужна ночь.");
        }
        String dayNightValue=c.dayNight==null?"ANY":c.dayNight;
        if(!(timeMin==0&&timeMax==24000)||dayNightValue.equalsIgnoreCase("DAY")||dayNightValue.equalsIgnoreCase("NIGHT")) if(!timeOk) return fail(c,"§cСобрать сейчас нельзя: сейчас неподходящее время.");
        if(c.requiredHelmetMaterial!=null&&!c.requiredHelmetMaterial.isBlank()){
            Material m=parseMaterial(c.requiredHelmetMaterial);
            if(m==null) return fail(c,"§cВы не можете собрать: §fтребование к шлему настроено на неизвестный предмет§c.");
            ItemStack helmet=p.getInventory().getHelmet();
            if(helmet==null||helmet.getType()!=m)return fail(c,"§cВы не можете собрать: §fвы не надели "+nice(m)+"§c.");
        }
        if(c.requiredInventoryMaterial!=null&&!c.requiredInventoryMaterial.isBlank()){
            Material m=parseMaterial(c.requiredInventoryMaterial);
            if(m==null) return fail(c,"§cВы не можете собрать: §fтребуемый предмет в инвентаре настроен неверно§c.");
            if(!p.getInventory().contains(m))return fail(c,"§cВы не можете собрать: §fнужного предмета нет в инвентаре§c.");
        }
        return Result.ok();
    }
    private Result fail(EventConditions c,String msg){String custom=c.failMessage==null?"":c.failMessage.trim();return new Result(false,custom.isBlank()?msg:custom,c.failPenalty);}
    private Material parseMaterial(String raw){
        if(raw==null||raw.isBlank())return null;
        String normalized=raw.trim().toUpperCase(Locale.ROOT);
        Material m=Material.matchMaterial(normalized);
        if(m==null){
            try { m=Material.valueOf(normalized); } catch(Exception ignored) {}
        }
        return m;
    }
    private String nice(Material m){return switch(m){case PUMPKIN->"тыкву";case CARVED_PUMPKIN->"резную тыкву";case TURTLE_HELMET->"черепаший панцирь";case NETHERITE_HELMET->"незеритовый шлем";case DIAMOND_HELMET->"алмазный шлем";case GOLDEN_HELMET->"золотой шлем";case IRON_HELMET->"железный шлем";case CHAINMAIL_HELMET->"кольчужный шлем";case LEATHER_HELMET->"кожаную шапку";default->{String s=m.name().toLowerCase(Locale.ROOT).replace('_',' ');yield Character.toUpperCase(s.charAt(0))+s.substring(1);}};}
    public record Result(boolean allowed,String message,int penalty){public static Result ok(){return new Result(true,"",0);}}
}
