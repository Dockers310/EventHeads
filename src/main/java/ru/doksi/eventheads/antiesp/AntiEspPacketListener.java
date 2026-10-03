package ru.doksi.eventheads.antiesp;

import ru.doksi.eventheads.EventHeadsPlugin;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player filtering for Anti-ESP decoys.
 *
 * The real ArmorStand remains an invisible server entity, so a client-side ESP can still
 * detect the entity itself. Ordinary players, however, must not receive the head item.
 * The actual Equipment packet is therefore rewritten only for players without the view permission.
 */
public final class AntiEspPacketListener extends PacketListenerAbstract {
    private final EventHeadsPlugin plugin;
    private String showPermission;
    private boolean packetErrorLogged;

    public AntiEspPacketListener(EventHeadsPlugin plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        reload();
    }

    public void reload(){
        this.showPermission=plugin.getConfig().getString("security.anti-esp-show-head-permission","eventheads.anti-esp.view");
        this.packetErrorLogged=false;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!plugin.getConfig().getBoolean("security.anti-esp-hide-head-item", true)) return;
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_EQUIPMENT) return;

        final Player viewer;
        try {
            viewer = event.getPlayer();
        } catch (Throwable ignored) {
            return;
        }
        if (viewer == null) return; if (!plugin.antiEspHidden(viewer) && plugin.antiEspViewPermission(viewer.getUniqueId())) return;

        try {
            WrapperPlayServerEntityEquipment packet = new WrapperPlayServerEntityEquipment(event);
            int entityId = packet.getEntityId();
            if (!plugin.spawner().isAntiEspDecoyEntity(entityId)) return;

            List<Equipment> original = packet.getEquipment();
            if (original == null || original.isEmpty()) return;

            boolean changed = false;
            List<Equipment> filtered = new ArrayList<>(original.size());
            for (Equipment equipment : original) {
                if (equipment != null && equipment.getSlot() == EquipmentSlot.HELMET) {
                    filtered.add(new Equipment(EquipmentSlot.HELMET, ItemStack.EMPTY));
                    changed = true;
                } else {
                    filtered.add(equipment);
                }
            }
            if (changed) { packet.setEquipment(filtered); event.markForReEncode(true); }
        } catch (Throwable t) {
            // Never break the outgoing packet pipeline. Log the first failure so compatibility
            // problems are visible instead of silently disabling Anti-ESP filtering.
            if(!packetErrorLogged){packetErrorLogged=true;plugin.getLogger().warning("Anti-ESP packet filtering error: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));}
        }
    }
}
