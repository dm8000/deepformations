package com.mymod.formation;

import java.io.Serializable;

public class EscortSlot implements Serializable {
    private static final long serialVersionUID = 1L;

    public String     shipId;    // fleet member ID, or null if role-fill
    public String     shipClass; // hull class for role-fill, or null
    public EscortRole role       = EscortRole.GUARD;

    public boolean isEmpty() { return shipId == null && shipClass == null; }

    public void clear() {
        shipId    = null;
        shipClass = null;
        role      = EscortRole.GUARD;
    }
}
