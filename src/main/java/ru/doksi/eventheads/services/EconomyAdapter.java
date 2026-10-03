package ru.doksi.eventheads.services;

import ru.doksi.eventheads.EventHeadsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.doksi.eventheads.util.SchedulerUtil;
import java.util.concurrent.CompletableFuture;

import java.lang.reflect.Method;
import java.util.Locale;

/** Economy adapter for Vault/CMI/console commands with transaction verification. */
public final class EconomyAdapter {
    private final EventHeadsPlugin plugin;
    private boolean vaultErrorLogged;

    public EconomyAdapter(EventHeadsPlugin plugin){ this.plugin=plugin; }

    public CompletableFuture<Result> giveAsync(Player player,double amount,String eventId){CompletableFuture<Result> f=new CompletableFuture<>();SchedulerUtil.runGlobal(plugin,()->{try{f.complete(giveNow(player,amount,eventId));}catch(Throwable t){f.completeExceptionally(t);}});return f;}

    public CompletableFuture<Result> takeAsync(Player player,double amount,String eventId){CompletableFuture<Result> f=new CompletableFuture<>();SchedulerUtil.runGlobal(plugin,()->{try{f.complete(takeNow(player,amount,eventId));}catch(Throwable t){f.completeExceptionally(t);}});return f;}

    private Result giveNow(Player player,double amount,String eventId){
        if(amount<0) return takeNow(player,-amount,eventId);
        if(amount<=0) return new Result(true,0,"none");
        String mode=plugin.getConfig().getString("economy.provider","AUTO").toUpperCase(Locale.ROOT);

        // CMI-first when CMI is installed. This avoids false Vault reflection errors on
        // servers where CMI bundles/provides a different Vault Economy interface version.
        if((mode.equals("AUTO")||mode.equals("CMI")) && Bukkit.getPluginManager().isPluginEnabled("CMI")){
            Result r=dispatch(plugin.getConfig().getString("economy.cmi-command","cmi money give {player} {amount}"),player,amount,eventId,"CMI");
            if(r.success() || mode.equals("CMI")) return r;
        }

        if(mode.equals("AUTO")||mode.equals("VAULT")){
            Result vault=tryVault(player,amount);
            if(vault.success() || mode.equals("VAULT")) return vault;
        }

        if(mode.equals("AUTO")||mode.equals("COMMAND")){
            Result r=dispatch(plugin.getConfig().getString("economy.command",""),player,amount,eventId,"command");
            if(r.success()) return r;
        }

        if(plugin.getConfig().getBoolean("economy.crazy-vouchers.enabled",false)
                && Bukkit.getPluginManager().isPluginEnabled("CrazyVouchers")){
            Result r=dispatch(plugin.getConfig().getString("economy.crazy-vouchers.command",""),player,amount,eventId,"CrazyVouchers");
            if(r.success()) return r;
        }
        return new Result(false,0,"none");
    }

    private Result takeNow(Player player,double amount,String eventId){
        if(amount<=0) return new Result(true,0,"none");
        String mode=plugin.getConfig().getString("economy.provider","AUTO").toUpperCase(Locale.ROOT);

        if((mode.equals("AUTO")||mode.equals("CMI")) && Bukkit.getPluginManager().isPluginEnabled("CMI")){
            String template=plugin.getConfig().getString("economy.withdraw-command","cmi money take {player} {amount}");
            Result r=dispatchWithdraw(template,player,amount,eventId,"CMI");
            if(r.success() || mode.equals("CMI")) return r;
        }

        if(mode.equals("AUTO")||mode.equals("VAULT")){
            Result vault=tryVaultWithdraw(player,amount);
            if(vault.success() || mode.equals("VAULT")) return vault;
        }

        if(mode.equals("COMMAND")){
            Result r=dispatchWithdraw(plugin.getConfig().getString("economy.withdraw-command",""),player,amount,eventId,"command");
            if(r.success()) return r;
        }
        return new Result(false,0,"none");
    }

    private Result tryVaultWithdraw(Player player,double amount){
        try{
            Class<?> ecoClass=Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration=Bukkit.getServicesManager().getRegistration(ecoClass);
            if(registration==null) return new Result(false,0,"none");
            Object provider=registration.getClass().getMethod("getProvider").invoke(registration);
            if(provider==null) return new Result(false,0,"none");
            Object response=invokeEconomyMethod(provider,"withdrawPlayer",player,amount,ecoClass);
            if(response==null) return new Result(false,0,"none");
            return economyResponse(response,providerName(provider));
        }catch(Throwable ex){
            logVaultError(ex);
            return new Result(false,0,"none");
        }
    }

    private Result tryVault(Player player,double amount){
        try{
            Class<?> ecoClass=Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration=Bukkit.getServicesManager().getRegistration(ecoClass);
            if(registration==null) return new Result(false,0,"none");
            Object provider=registration.getClass().getMethod("getProvider").invoke(registration);
            if(provider==null) return new Result(false,0,"none");
            Object response=invokeEconomyMethod(provider,"depositPlayer",player,amount,ecoClass);
            if(response==null) return new Result(false,0,"none");
            return economyResponse(response,providerName(provider));
        }catch(Throwable ex){
            logVaultError(ex);
            return new Result(false,0,"none");
        }
    }

    private Object invokeEconomyMethod(Object provider,String method,Player player,double amount,Class<?> ecoClass) throws Exception{
        Class<?> providerClass=provider.getClass();
        try{return providerClass.getMethod(method,org.bukkit.OfflinePlayer.class,double.class).invoke(provider,player,amount);}catch(NoSuchMethodException ignored){}
        try{return providerClass.getMethod(method,Player.class,double.class).invoke(provider,player,amount);}catch(NoSuchMethodException ignored){}
        try{return providerClass.getMethod(method,String.class,double.class).invoke(provider,player.getName(),amount);}catch(NoSuchMethodException ignored){}
        try{return ecoClass.getMethod(method,org.bukkit.OfflinePlayer.class,double.class).invoke(provider,player,amount);}catch(NoSuchMethodException ignored){}
        try{return ecoClass.getMethod(method,String.class,double.class).invoke(provider,player.getName(),amount);}catch(NoSuchMethodException ignored){}
        throw new NoSuchMethodException(ecoClass.getName()+"."+method+" for provider "+providerClass.getName());
    }

    private Result economyResponse(Object response,String provider){
        try{
            boolean success=(boolean)response.getClass().getMethod("transactionSuccess").invoke(response);
            double actual=((Number)response.getClass().getField("amount").get(response)).doubleValue();
            return new Result(success,(int)Math.max(0,Math.round(actual)),provider);
        }catch(Throwable ex){
            return new Result(false,0,provider);
        }
    }

    private Result dispatchWithdraw(String template,Player player,double amount,String eventId,String provider){
        if(template==null||template.isBlank()) return new Result(false,0,"none");
        String cmd=template.replace("{player}",sanitize(player.getName()))
                .replace("{amount}",String.valueOf((int)Math.round(amount)))
                .replace("{event_id}",sanitize(eventId));
        Double before=provider.equalsIgnoreCase("CMI")?tryCmiBalance(player):balanceOf(player);
        boolean dispatched=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd);
        if(!dispatched) return new Result(false,0,"none");
        Double after=provider.equalsIgnoreCase("CMI")?tryCmiBalance(player):balanceOf(player);
        if(before!=null){
            if(before+0.000001D<amount) return new Result(false,0,provider+"_INSUFFICIENT");
            if(after!=null){
                double actual=Math.max(0,before-after);
                if(actual+0.000001D>=amount) return new Result(true,(int)Math.round(actual),provider);
                return new Result(false,0,provider+"_UNVERIFIED");
            }
        }
        boolean allow=plugin.getConfig().getBoolean("economy.allow-unverified-withdraw-success",false);
        return allow?new Result(true,(int)Math.round(amount),provider+"_UNVERIFIED"):new Result(false,0,provider+"_UNVERIFIED");
    }

    private Result dispatch(String template,Player player,double amount,String eventId,String provider){
        if(template==null||template.isBlank()) return new Result(false,0,"none");
        String cmd=template.replace("{player}",sanitize(player.getName()))
                .replace("{amount}",String.valueOf((int)Math.round(amount)))
                .replace("{event_id}",sanitize(eventId));
        Double before=provider.equalsIgnoreCase("CMI")?tryCmiBalance(player):balanceOf(player);
        boolean dispatched=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd);
        if(!dispatched) return new Result(false,0,"none");
        Double after=provider.equalsIgnoreCase("CMI")?tryCmiBalance(player):balanceOf(player);
        if(before!=null&&after!=null){
            double actual=after-before;
            if(actual+0.000001D>=amount) return new Result(true,(int)Math.round(actual),provider);
            return new Result(false,0,provider+"_UNVERIFIED");
        }
        boolean allow=provider.equalsIgnoreCase("CMI")
                || plugin.getConfig().getBoolean("economy.allow-unverified-command-success",true);
        return allow?new Result(true,(int)Math.round(amount),provider+"_UNVERIFIED"):new Result(false,0,provider+"_UNVERIFIED");
    }

    private Double tryCmiBalance(Player player){
        try{
            Class<?> eco=Class.forName("com.Zrips.CMI.Modules.Economy.Economy");
            for(Class<?> t:new Class<?>[]{org.bukkit.OfflinePlayer.class,String.class,Player.class}){
                try{
                    Method m=eco.getMethod("getBalance",t);
                    Object value=m.invoke(null,t==String.class?player.getName():player);
                    if(value instanceof Number n)return n.doubleValue();
                }catch(NoSuchMethodException ignored){}
            }
        }catch(Throwable ignored){}
        return null;
    }

    public Double balance(Player player){
        String mode=plugin.getConfig().getString("economy.provider","AUTO").toUpperCase(Locale.ROOT);
        if((mode.equals("AUTO")||mode.equals("CMI")) && Bukkit.getPluginManager().isPluginEnabled("CMI")){
            Double value=tryCmiBalance(player);
            if(value!=null)return value;
        }
        return balanceOf(player);
    }

    private Double balanceOf(Player player){
        try{
            Class<?> ecoClass=Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration=Bukkit.getServicesManager().getRegistration(ecoClass);
            if(registration==null)return null;
            Object provider=registration.getClass().getMethod("getProvider").invoke(registration);
            if(provider==null)return null;
            for(Class<?> t:new Class<?>[]{provider.getClass(),ecoClass}){
                try{
                    Object value=t.getMethod("getBalance",org.bukkit.OfflinePlayer.class).invoke(provider,player);
                    if(value instanceof Number n)return n.doubleValue();
                }catch(NoSuchMethodException ignored){}
                try{
                    Object value=t.getMethod("getBalance",String.class).invoke(provider,player.getName());
                    if(value instanceof Number n)return n.doubleValue();
                }catch(NoSuchMethodException ignored){}
            }
        }catch(Throwable ignored){}
        return null;
    }

    private void logVaultError(Throwable ex){
        if(!vaultErrorLogged){
            vaultErrorLogged=true;
            plugin.getLogger().warning("Vault economy integration could not be used: "+ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage()));
        }
    }

    private String sanitize(String raw){return raw==null?"":raw.replaceAll("[;&|\\n\\r]","");}
    private String providerName(Object provider){
        try{return String.valueOf(provider.getClass().getMethod("getName").invoke(provider));}
        catch(Throwable ignored){return provider.getClass().getSimpleName();}
    }

    public record Result(boolean success,int actualAmount,String provider){}

    public String providerName(){
        try{
            Class<?> ecoClass=Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration=Bukkit.getServicesManager().getRegistration(ecoClass);
            if(registration!=null){
                Object provider=registration.getClass().getMethod("getProvider").invoke(registration);
                if(provider!=null)return providerName(provider);
            }
        }catch(Throwable ignored){}
        if(Bukkit.getPluginManager().isPluginEnabled("CMI"))return "CMI";
        return null;
    }
}
