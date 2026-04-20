package com.mymod.formation;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;

import java.util.ArrayList;
import java.util.List;

public class FormationModPlugin extends BaseModPlugin {

    @Override
    public void onGameLoad(boolean newGame) {
        FormationConfig.getOrCreate();

        // Copy to new list before iterating to avoid ConcurrentModificationException
        List<IntelInfoPlugin> existing =
                Global.getSector().getIntelManager().getIntel(FormationIntelPlugin.class);
        if (existing != null && !existing.isEmpty()) {
            List<IntelInfoPlugin> toRemove = new ArrayList<IntelInfoPlugin>(existing);
            for (IntelInfoPlugin old : toRemove) {
                Global.getSector().getIntelManager().removeIntel(old);
            }
        }

        Global.getSector().getIntelManager().addIntel(new FormationIntelPlugin(), false);
    }
}
