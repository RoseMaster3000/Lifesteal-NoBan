package mc.mian.lifesteal.api;

import net.minecraft.world.entity.LivingEntity;

public interface PlayerImpl {
    void setRevived(boolean bool);
    boolean getRevived();
    public void playerDeathTransfer(LivingEntity killerEntityOverride);
}
