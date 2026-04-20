package com.mymod.formation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * One principal ship and its escorts.
 * Max 6 escort slots — enough for a full escort screen.
 */
public class EscortGroup implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int MAX_ESCORTS = 6;

    public String            principalId; // fleet member ID of the escorted ship
    public List<EscortSlot>  slots        = new ArrayList<EscortSlot>();

    public EscortGroup() {
        for (int i = 0; i < MAX_ESCORTS; i++) slots.add(new EscortSlot());
    }

    public EscortGroup(String principalId) {
        this();
        this.principalId = principalId;
    }

    /** Returns slot index this shipId is assigned to, or -1. */
    public int findSlotForShip(String shipId) {
        for (int i = 0; i < slots.size(); i++) {
            if (shipId.equals(slots.get(i).shipId)) return i;
        }
        return -1;
    }

    /** Returns first empty slot index, or -1 if full. */
    public int firstEmptySlot() {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) return i;
        }
        return -1;
    }

    public int getAssignedCount() {
        int n = 0;
        for (EscortSlot s : slots) if (!s.isEmpty()) n++;
        return n;
    }
}
