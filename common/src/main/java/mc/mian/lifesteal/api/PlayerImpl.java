package mc.mian.lifesteal.api;

import net.minecraft.world.entity.LivingEntity;

public interface PlayerImpl {
    void setRevived(boolean bool);
    boolean getRevived();
    void playerDeathTransfer(LivingEntity killerEntityOverride,  boolean notify);
    void sendScreenMessage(String message);
    boolean hasHearts(int heartCount);
    void loseHearts(int heartCount);
    void gainHearts(int heartCount);
    int suggestWager();
    void setWager(int heartCount);
    int getWager();
    boolean setJonger(int heartCount);
    void resetJonger();
    void giveFragments(int count);
}
