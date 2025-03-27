package mc.mian.lifesteal.fabric.event;

import mc.mian.lifesteal.util.LSConstants;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import mc.mian.lifesteal.data.LSData;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;
import java.util.Optional;

public class LSEvents {
    public static void register() {
        LSConstants.LOGGER.debug("Registering ModEvents for " + LSConstants.MOD_ID);
        ServerPlayerEvents.COPY_FROM.register(((oldPlayer, newPlayer, alive) -> LSData.get(oldPlayer).ifPresent(oldData -> LSData.get(newPlayer).ifPresent(newData ->
        {
            Collection<ResourceLocation> keys = newData.getKeys();
            keys.forEach(key -> newData.setValue(key, oldData.getValue(key)));
            newData.refreshHealth(!alive);
        }))));

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            String message = "You died like a bitch...";
            Optional<LSData> newDataOptional = LSData.get(newPlayer);

            // fetch heart drop count
            if (newDataOptional.isPresent()) {
                LSData newData = newDataOptional.get();
                int heartsDropped = newData.getValue(LSConstants.HEARTS_DROPPED);
   
                // generate respawn message (how many hearts dropped)
                if (heartsDropped == 0) {
                    message = "You didn't drop any hearts!";}
                else if (heartsDropped == 1) {
                    message = "You dropped a heart.";}
                else if (heartsDropped > 1){
                    message = "You dropped " + heartsDropped + " hearts...";}
            } 
            else {
                message = alive ? "Respawned" : "Welcome to the PokeJong SMP!";
            }

            // newPlayer.sendSystemMessage(Component.literal(message));


            Component emptyTitle = Component.literal("");
            Component subtitleMessage = Component.literal(message)
                    .withStyle(style -> style.withBold(true).withItalic(true)
                            .withColor(net.minecraft.ChatFormatting.RED));

            // Send Message (as subtitle centered on screen)
            newPlayer.connection.send(
                    new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(subtitleMessage));
            newPlayer.connection.send(
                    new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(emptyTitle));

        });
    }

}