package com.mymod.formation;

import com.fs.starfarer.api.Global;
import java.util.Map;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent escort configuration stored in campaign save.
 */
public class EscortConfig {

    private static final String KEY = "deep_formations_escort_config";

    public List<EscortGroup> groups = new ArrayList<EscortGroup>();

    // ── Persistence ───────────────────────────────────────────────────────────

    public static EscortConfig getOrCreate() {
        Map<String, Object> pd = Global.getSector().getPersistentData();
        Object obj = pd.get(KEY);
        if (obj instanceof EscortConfig) return (EscortConfig) obj;
        EscortConfig cfg = new EscortConfig();
        Global.getSector().getPersistentData().put(KEY, cfg);
        return cfg;
    }

    public void save() {
        Global.getSector().getPersistentData().put(KEY, this);
    }

    // ── Group management ─────────────────────────────────────────────────────

    /** Get or create group for this principal. */
    public EscortGroup getOrCreateGroup(String principalId) {
        for (EscortGroup g : groups) {
            if (principalId.equals(g.principalId)) return g;
        }
        EscortGroup g = new EscortGroup(principalId);
        groups.add(g);
        return g;
    }

    /** Remove group for this principal entirely. */
    public void removeGroup(String principalId) {
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (principalId.equals(groups.get(i).principalId)) groups.remove(i);
        }
    }

    /** Find which group this ship is an escort in, or null. */
    public EscortGroup findGroupForEscort(String shipId) {
        for (EscortGroup g : groups) {
            if (g.findSlotForShip(shipId) >= 0) return g;
        }
        return null;
    }

    /** Find which group this ship is the principal of, or null. */
    public EscortGroup findGroupForPrincipal(String shipId) {
        for (EscortGroup g : groups) {
            if (shipId.equals(g.principalId)) return g;
        }
        return null;
    }

    /**
     * Enforce mutual exclusivity: remove shipId from ALL escort roles
     * (both as principal and as escort) across all groups.
     */
    public void removeShipFromAllGroups(String shipId) {
        removeGroup(shipId); // remove as principal
        for (EscortGroup g : groups) { // remove as escort
            int idx = g.findSlotForShip(shipId);
            if (idx >= 0) g.slots.get(idx).clear();
        }
        // Clean up groups with no principal
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (groups.get(i).principalId == null) groups.remove(i);
        }
    }
}
