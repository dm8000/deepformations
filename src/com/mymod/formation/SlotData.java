package com.mymod.formation;

import java.io.Serializable;

public class SlotData implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int DEFEND = 1;  // loose - fights freely, nudged back when done
    public static final int HOLD   = 2;  // medium - holds under fire, blocked when enemies near
    public static final int LOCK   = 3;  // strict - all movement blocked when outside leash

    public String  shipId;
    public String  shipClass;
    public int     tightness = DEFEND;
    public boolean isAdmiral = false;

    public boolean isEmpty() { return shipId == null && shipClass == null; }

    public void clear() {
        shipId    = null;
        shipClass = null;
        tightness = DEFEND;
        isAdmiral = false;
    }

    public void assignShip(String id)  { this.shipId = id;    this.shipClass = null; }
    public void assignClass(String cls){ this.shipClass = cls; this.shipId    = null; }

    public float getArrivalRadius() { return 150f; }
    public boolean isLockMode()     { return tightness == LOCK; }
}
