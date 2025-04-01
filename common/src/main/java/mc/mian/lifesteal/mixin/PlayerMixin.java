package mc.mian.lifesteal.mixin;

import mc.mian.lifesteal.LifeSteal;
import mc.mian.lifesteal.api.PlayerImpl;
import mc.mian.lifesteal.data.LSData;
import mc.mian.lifesteal.util.LSConstants;
import mc.mian.lifesteal.util.LSUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Player.class, priority = 1)
public abstract class PlayerMixin extends LivingEntity implements PlayerImpl {
    @Shadow public abstract boolean killedEntity(ServerLevel level, LivingEntity entity);

    private boolean revived;
    protected PlayerMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
        LSData.get(this).ifPresent(iLifestealData -> iLifestealData.refreshHealth(false));
    }

    public void increaseHealth(LivingEntity killerPlayer, int hitpoint, LivingEntity killedPlayer) {
        final int maximumhitpointsGainable = LifeSteal.config.maximumHealthGainable.get();
        boolean alreadyGiven = false;

        LSData lsData = LSData.get(killerPlayer).orElseGet(null);

        if(lsData != null){
            if (maximumhitpointsGainable > -1 && LifeSteal.config.playerDropsHeartCrystalWhenKillerHasMax.get() && !LifeSteal.config.playerDropsHeartCrystalWhenKilled.get()) {
                if ((int)lsData.getValue(LSConstants.HEALTH_DIFFERENCE) + hitpoint > LifeSteal.config.startingHealthDifference.get() + maximumhitpointsGainable) {
                    LSUtil.ripHeartCrystalFromPlayer(killedPlayer);
                    alreadyGiven = true;
                }
            }

            if (!alreadyGiven) {
                if (!LifeSteal.config.playerDropsHeartCrystalWhenKilled.get()) {
                    LSUtil.gainHealth(killerPlayer, (int)lsData.getValue(LSConstants.HEALTH_DIFFERENCE) + hitpoint);
                }
            }
        }
    }

    private void sendScreenMessage(String message) {
        ServerPlayer player = (ServerPlayer)(LivingEntity)this;
        Component emptyTitle = Component.literal("");
        Component subtitleMessage = Component.literal(message)
                .withStyle(style -> style.withBold(true).withItalic(true)
                        .withColor(net.minecraft.ChatFormatting.RED));

        // Send Message (as subtitle centered on screen)
        player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(subtitleMessage));
        player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(emptyTitle));
    }


    @Inject(method = "dropEquipment", at = @At("HEAD"))
    private void onDeath(final CallbackInfo info) {

        final int maximumheartsLoseable = LifeSteal.config.maximumHealthLoseable.get();
        final int startingHitPointDifference = LifeSteal.config.startingHealthDifference.get();
        // NOT USING THIS (dyanmic system)
        final int amountOfHealthLostUponLossConfig = LifeSteal.config.amountOfHealthLostUponLoss.get();
        final int extraHeartDropPercentConfig = LifeSteal.config.extraHeartDropPercent.get();
        final boolean playersGainHeartsifKillednoHeart = LifeSteal.config.playersGainHeartsifKillednoHeart.get();
        final boolean disableLifesteal = LifeSteal.config.disableLifesteal.get();
        final boolean loseHeartsWhenKilledByPlayer = LifeSteal.config.loseHeartsWhenKilledByPlayer.get();
        final boolean loseHeartsWhenKilledByMob = LifeSteal.config.loseHeartsWhenKilledByMob.get();
        final boolean loseHeartsWhenKilledByEnvironment = LifeSteal.config.loseHeartsWhenKilledByEnvironment.get();
        final boolean heartCrystalCanDrop = LifeSteal.config.playerDropsHeartCrystalWhenKilled.get();

        final int weakPlayerThreshold = LifeSteal.config.weakPlayerThreshold.get();

        LivingEntity killedEntity = this;

        LSData.get(killedEntity).ifPresent(lifestealData -> {
            // GATE KEEP
            if (!(killedEntity instanceof ServerPlayer)) {return;}
            if (killedEntity.isAlive()) {return;}

            // INITIALIZE VARIABLES
            int HeartDifference = lifestealData.getValue(LSConstants.HEALTH_DIFFERENCE);
            LivingEntity killerEntity = killedEntity.getLastHurtByMob();
            boolean killerIsPlayer = killerEntity instanceof ServerPlayer;
            boolean killerIsSelf = (killerEntity == killedEntity);
            boolean killerIsMob = (!killerIsPlayer && (killerEntity !=null));
            ServerPlayer killerPlayer = killerIsPlayer ? (ServerPlayer) killerEntity : null;

            // CALCULATE HEARTS DROPPED (if the person who died has lots of MAXHP, they will drop more hearts)
            // (drop 1 extra heart for every 10 max HP --> 2 health == 1 heart)
            int healthDrop = Math.round((HeartDifference+20f)/2f*extraHeartDropPercentConfig/100f);
            healthDrop *= 2; 
            healthDrop += amountOfHealthLostUponLossConfig; 
            int heartsDropped = healthDrop / 2;

            // DECREMENT HEALTH  / RECORD DROPPED HEARTS
            if (weakPlayerThreshold >= HeartDifference) {
                lifestealData.setValue(LSConstants.HEARTS_DROPPED, 0);
                return;
            }
            else{
                // record heart drop
                lifestealData.setValue(LSConstants.HEARTS_DROPPED, heartsDropped);
                // record new HP value for user
                lifestealData.setValue(LSConstants.HEALTH_DIFFERENCE, HeartDifference - healthDrop);
                // Refresh HP
                lifestealData.refreshHealth(false);
            }

            // TRANSFER HEARTS
            if (disableLifesteal) {   
                return;
            }
            // Give Hearts to KILLER (player kill)
            else if (loseHeartsWhenKilledByPlayer && killerIsPlayer && !killerIsSelf) {                
                increaseHealth(killerEntity, healthDrop, killedEntity);
            }
            // Drop hearts in world (suicide)
            else if (loseHeartsWhenKilledByPlayer && killerIsSelf && heartCrystalCanDrop){
                LSUtil.ripHeartCrystalFromPlayer(killedEntity, heartsDropped);
            }
            // Drop Hearts in world (mob kill)
            else if (loseHeartsWhenKilledByMob && killerIsMob && heartCrystalCanDrop){
                LSUtil.ripHeartCrystalFromPlayer(killedEntity, heartsDropped);
            }
            // Drop Hearts in world (environment kill)
            else if (loseHeartsWhenKilledByEnvironment && heartCrystalCanDrop){
                LSUtil.ripHeartCrystalFromPlayer(killedEntity, heartsDropped);
            }
        });
    }

    @Inject(method = "addAdditionalSaveData", at = @At("HEAD"))
    private void addOurDataTooLol(CompoundTag compoundTag, final CallbackInfo info){
        compoundTag.putBoolean("Revived", this.getRevived());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void loadOurDataTooLol(CompoundTag compoundTag, final CallbackInfo info){
       this.setRevived(compoundTag.getBoolean("Revived"));
    }

    @Override
    public void setRevived(boolean bool) {
        this.revived = bool;
    }

    @Override
    public boolean getRevived() {
        return this.revived;
    }
}
