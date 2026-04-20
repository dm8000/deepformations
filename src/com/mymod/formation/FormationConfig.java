package com.mymod.formation;

import com.fs.starfarer.api.Global;

import java.io.Serializable;
import java.util.Map;

/**
 * Root configuration for the Deep Battle Formations mod.
 * Stored in Global.getSector().getPersistentData() under PERSISTENT_KEY.
 * Call save() after any mutation; call getOrCreate() to fetch/initialise.
 */
public class FormationConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String PERSISTENT_KEY = "deep_formations_config";
    public static final int    WING_COUNT     = 9;

    private final WingFormation[] wings = new WingFormation[WING_COUNT];
    private int   activeWing = 0;
    private float spacing    = 1500f;

    public FormationConfig() {
        for (int i = 0; i < WING_COUNT; i++) {
            wings[i] = new WingFormation();
        }
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public WingFormation getWing(int index) {
        if (index < 0 || index >= WING_COUNT) throw new IndexOutOfBoundsException("Wing index: " + index);
        return wings[index];
    }

    public int getActiveWing() {
        return activeWing;
    }

    public float getSpacing() { return spacing; }
    public void setSpacing(float s) { spacing = s; }

    /** Remove shipId from all slots across all wings — for mutual exclusivity with escorts. */
    public void removeShipFromAllSlots(String shipId) {
        for (WingFormation wing : wings) {
            for (int r = 0; r < WingFormation.ROWS; r++) {
                for (int c = 0; c < WingFormation.COLS; c++) {
                    SlotData slot = wing.getSlot(r, c);
                    if (shipId.equals(slot.shipId)) slot.shipId = null;
                }
            }
        }
    }

    public void setActiveWing(int index) {
        if (index >= 0 && index < WING_COUNT) {
            activeWing = index;
        }
    }

    public WingFormation getActiveFormation() {
        return wings[activeWing];
    }

    // -----------------------------------------------------------------------
    // Persistence helpers
    // -----------------------------------------------------------------------

    /**
     * Fetches the config from PersistentData, creating and storing a new
     * instance if one has not yet been saved.
     */
    @SuppressWarnings("unchecked")
    public static FormationConfig getOrCreate() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        FormationConfig config = (FormationConfig) data.get(PERSISTENT_KEY);
        if (config == null) {
            config = new FormationConfig();
            data.put(PERSISTENT_KEY, config);
        }
        return config;
    }

    /** Persists current state back into PersistentData. */
    public void save() {
        Global.getSector().getPersistentData().put(PERSISTENT_KEY, this);
    }
}
