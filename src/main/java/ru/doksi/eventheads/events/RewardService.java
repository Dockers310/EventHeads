package ru.doksi.eventheads.events;

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.services.EconomyAdapter;
import org.bukkit.entity.Player;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;

/** Reward generation and explicit localized economy notifications. */
public final class RewardService {
    private final EventHeadsPlugin plugin;
    public RewardService(EventHeadsPlugin plugin){this.plugin=plugin;}

    public CompletableFuture<EconomyAdapter.Result> grantAsync(Player p,EventDefinition e){
        int amount=e.randomReward(new java.util.Random(ThreadLocalRandom.current().nextLong()));
        if(amount<0)return plugin.economy().takeAsync(p,-(double)amount,e.id);
        return plugin.economy().giveAsync(p,amount,e.id);
    }

    public CompletableFuture<EconomyAdapter.Result> grant(Player p,EventDefinition e){return grantAsync(p,e);}

    public void announceFailure(Player p){
        plugin.lang().send(p, plugin.lang().tr(p,"economy-failed"));
    }

}
