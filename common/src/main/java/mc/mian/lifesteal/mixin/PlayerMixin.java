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
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(value = Player.class, priority = 1)
public abstract class PlayerMixin extends LivingEntity implements PlayerImpl {
    @Shadow public abstract boolean killedEntity(ServerLevel level, LivingEntity entity);

    private boolean revived;
    protected PlayerMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
        LSData.get(this).ifPresent(iLifestealData -> iLifestealData.refreshHealth(false));
    }

    // INTERNAL heart increase method
    // Gift hearts to a specified target player
    private void giftHearts(LivingEntity targetPlayer, int heartAmount) {
        LSData.get(targetPlayer).ifPresent(lifestealData -> {
            final int maximumAllowedHP = LifeSteal.config.maximumHealthGainable.get();
            final boolean dropAtMaximumHP = LifeSteal.config.playerDropsHeartCrystalWhenKillerHasMax.get();
            final boolean dropCrystals = LifeSteal.config.playerDropsHeartCrystalWhenKilled.get();
            final int proposedHP = 20 + (int) lifestealData.getValue(LSConstants.HEALTH_DIFFERENCE) + heartAmount * 2;

            if ((maximumAllowedHP == -1) || (proposedHP <= maximumAllowedHP)) {
                lifestealData.setValue(LSConstants.HEALTH_DIFFERENCE, proposedHP-20);
                lifestealData.refreshHealth(false);
                if (heartAmount==1) {sendScreenMessage("You gained a heart!", targetPlayer);}
                else                {sendScreenMessage("You gained " + heartAmount + " hearts!", targetPlayer);}
            }
            else if (dropCrystals && dropAtMaximumHP) {
                LSUtil.ripHeartCrystalFromPlayer(targetPlayer, heartAmount);
            }
        });
    }

    @Override
    public void sendScreenMessage(String message){
        sendScreenMessage(message, this);
    }

    // Send centered large red text message on screen
    public void sendScreenMessage(String message, LivingEntity targetPlayer) {
        ServerPlayer player = (ServerPlayer)(LivingEntity)targetPlayer;
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

    // API end point
    // Check if player can afford to lose <heartCount> hearts
    @Override
    public boolean hasHearts(int heartCount) {
        LivingEntity playerEntity = this;
        Optional<LSData> optionalLSData = LSData.get(playerEntity);
        return optionalLSData.map(lifestealData -> {
            int currentHealthPoints = 20 + (int) lifestealData.getValue(LSConstants.HEALTH_DIFFERENCE);
            int currentHearts = currentHealthPoints / 2; // Assuming 1 heart = 2 HP
            int proposedHearts = currentHearts - heartCount;
            return (proposedHearts >= 1);
        }).orElse(false);
    }

    // API end point
    // Remove <heartCount> hearts from this player
    @Override
    public void loseHearts(int heartCount) {
        LivingEntity playerEntity = this;
        LSData.get(playerEntity).ifPresent(lifestealData -> {
            int HeartDifference = lifestealData.getValue(LSConstants.HEALTH_DIFFERENCE);
            int proposedHealth = 20 + HeartDifference - heartCount*2;

            // Validate Decrease...
            if (proposedHealth < 0){
                proposedHealth = 0; // THIS SHOULD NEVER HAPPEN...but kill them
            }
            // Lose Hearts
            lifestealData.setValue(LSConstants.HEALTH_DIFFERENCE, proposedHealth-20);
            lifestealData.refreshHealth(false);
            // Confirmation Message
            if (heartCount==1) {sendScreenMessage("You lost a heart...");}
            else               {sendScreenMessage("You lost " + heartCount + " hearts...");}
        });
    }

    // API end point
    // Gift <heartCount> hearts to this player
    @Override
    public void gainHearts(int heartCount) {
        final int maximumAllowedHP = LifeSteal.config.maximumHealthGainable.get();
        LivingEntity playerEntity = this;

        LSData.get(playerEntity).ifPresent(lifestealData -> {
            int HeartDifference = lifestealData.getValue(LSConstants.HEALTH_DIFFERENCE);
            int proposedHealth = 20 + HeartDifference + heartCount*2;
            // Validate Increase
            if ((maximumAllowedHP != -1) && (proposedHealth > maximumAllowedHP)){
                LSUtil.ripHeartCrystalFromPlayer(playerEntity, heartCount);
                return;
            }
            // Gain Hearts
            lifestealData.setValue(LSConstants.HEALTH_DIFFERENCE, proposedHealth-20);
            lifestealData.refreshHealth(false);

            // Confirmation Message
            if (heartCount==1) {sendScreenMessage("You gained a heart!");}
            else               {sendScreenMessage("You gained " + heartCount + " hearts!");}
        });
    }


    @Inject(method = "dropEquipment", at = @At("HEAD"))
    private void onDeath(final CallbackInfo info) {
        playerDeathTransfer(null, false);
    }


    // have this player die
    // (transfer or drop hearts, use context)
    // (calculate heart drop count, use context)
    // CAN OVERWRITE killerEntity (for 3rd party mods to simulate kill...)
    @Override
    public void playerDeathTransfer(LivingEntity killerEntityOverride,  boolean notify){
        final int maximumHealthLoseable = LifeSteal.config.maximumHealthLoseable.get();
        final int startingHitPointDifference = LifeSteal.config.startingHealthDifference.get();
        // NOT USING THIS (dynamic system)
        final int amountOfHealthLostUponLossConfig = LifeSteal.config.amountOfHealthLostUponLoss.get();
        final int extraHeartDropPercentConfig = LifeSteal.config.extraHeartDropPercent.get();
        final boolean playersGainHeartsifKillednoHeart = LifeSteal.config.playersGainHeartsifKillednoHeart.get();
        final boolean disableLifesteal = LifeSteal.config.disableLifesteal.get();
        final boolean loseHeartsWhenKilledByPlayer = LifeSteal.config.loseHeartsWhenKilledByPlayer.get();
        final boolean loseHeartsWhenKilledByMob = LifeSteal.config.loseHeartsWhenKilledByMob.get();
        final boolean loseHeartsWhenKilledByEnvironment = LifeSteal.config.loseHeartsWhenKilledByEnvironment.get();
        final boolean heartCrystalCanDrop = LifeSteal.config.playerDropsHeartCrystalWhenKilled.get();
        final int safeRadius = LifeSteal.config.safeZoneRadius.get();

        final int weakPlayerThreshold = LifeSteal.config.weakPlayerThreshold.get();

        LivingEntity killedEntity = this;
        LSData.get(killedEntity).ifPresent(lifestealData -> {
            // GATE KEEP
            if (!(killedEntity instanceof ServerPlayer)) {return;}
            if (killedEntity.isAlive()) {return;}

            // INITIALIZE VARIABLES
            int HeartDifference = lifestealData.getValue(LSConstants.HEALTH_DIFFERENCE);
            LivingEntity killerEntity = killedEntity.getLastHurtByMob();
            if (killerEntityOverride!=null) {killerEntity = killerEntityOverride;}
            boolean killerIsPlayer = killerEntity instanceof ServerPlayer;
            boolean killerIsSelf = (killerEntity == killedEntity);
            boolean killerIsMob = (!killerIsPlayer && (killerEntity !=null));
            ServerPlayer killerPlayer = killerIsPlayer ? (ServerPlayer) killerEntity : null;
            Vec3 pos = killedEntity.position();

            // CALCULATE HEARTS DROPPED (if the person who died has lots of MAXHP, they will drop more hearts)
            // (drop 1 extra heart for every 10 max HP --> 2 health == 1 heart)
            int healthDrop = Math.round((HeartDifference+20f)/2f*extraHeartDropPercentConfig/100f);
            healthDrop *= 2;
            healthDrop += amountOfHealthLostUponLossConfig;
            if ((maximumHealthLoseable!= -1) && (maximumHealthLoseable > healthDrop)) {
                healthDrop = maximumHealthLoseable;
            }
            int heartsDropped = healthDrop / 2;

            // no heart loss if player is weakling
            if (weakPlayerThreshold >= HeartDifference) {
                lifestealData.setValue(LSConstants.HEARTS_DROPPED, 0);
                return;
            }
            // no heart loss in save zone (above ground 50+)
            else if ((pos.x <= safeRadius) && (pos.z <= safeRadius) && (pos.y > 50)) {
                lifestealData.setValue(LSConstants.HEARTS_DROPPED, 0);
                return;
            }
            // DECREMENT HEALTH  / RECORD DROPPED HEARTS
            else{
                // record new HP value for user
                lifestealData.setValue(LSConstants.HEALTH_DIFFERENCE, HeartDifference - healthDrop);
                // Refresh HP
                lifestealData.refreshHealth(false);
                // record heart drop (respawn message or immediate)
                if (!notify) {lifestealData.setValue(LSConstants.HEARTS_DROPPED, heartsDropped);}
                if (notify && (heartsDropped==1)) {sendScreenMessage("You lost a heart...");}
                else if (notify) {sendScreenMessage("You lost " + heartsDropped + " hearts...");}
            }

            // TRANSFER HEARTS
            if (disableLifesteal) {
                return;
            }
            // Give Hearts to KILLER (player kill)
            else if (loseHeartsWhenKilledByPlayer && killerIsPlayer && !killerIsSelf) {
                giftHearts(killerEntity, heartsDropped);
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
