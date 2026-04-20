package com.mymod.formation;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless helper + mutable assignment cache for one WingFormation during a battle.
 *
 * Responsibilities:
 *   1. Compute world-space slot positions given the commander ship each frame.
 *   2. Map fleet member IDs to (row, col) slots (resolving role-fill assignments
 *      against the live fleet list at battle start).
 */
public class WingGroup {

    /**
     * Computes the world-space centre position of a formation slot.
     *
     * Coordinate system:
     *   - Ship facing 0° = +X (right), 90° = +Y (up), CCW-positive.
     *   - Forward unit vector  = (cos θ, sin θ)
     *   - Starboard unit vector = (sin θ, −cos θ)   [90° clockwise from forward]
     *
     * Grid layout:
     *   Row 0 = front (most forward), Row 5 = rear.
     *   Col 0 = port  (left),         Col 5 = starboard (right).
     *
     * @param commander The player flagship whose position/facing anchors the grid.
     * @param row       0–5, 0 = front.
     * @param col       0–5, 0 = port.
     * @param spacing   World pixels between adjacent slot centres.
     * @return New Vector2f containing the world-space position.
     */
    /** Overload with separate row/col spacing. */
    public static Vector2f getSlotWorldPosition(ShipAPI commander, int row, int col,
                                                float colSpacing, float rowSpacing) {
        float theta = (float) Math.toRadians(commander.getFacing());
        float cosT  = (float) Math.cos(theta);
        float sinT  = (float) Math.sin(theta);

        // Row 3 = exact anchor level (0 ahead), row 0 = 3*spacing ahead, row 6 = 3*spacing behind
        // Col 3 = exact anchor level (0 lateral), col 0 = 3*spacing port, col 6 = 3*spacing stbd
        float forwardDist   = (3.0f - row) * rowSpacing;
        float starboardDist = (col - 3.0f) * colSpacing;

        float worldX = commander.getLocation().x
                + forwardDist   * cosT
                + starboardDist * sinT;
        float worldY = commander.getLocation().y
                + forwardDist   * sinT
                + starboardDist * (-cosT);

        return new Vector2f(worldX, worldY);
    }

    /** Legacy single-spacing overload. */
    public static Vector2f getSlotWorldPosition(ShipAPI commander, int row, int col, float spacing) {
        return getSlotWorldPosition(commander, row, col, spacing, spacing);
    }

    // -----------------------------------------------------------------------
    // Instance state: resolved ship → slot mapping for a single battle
    // -----------------------------------------------------------------------

    /** Maps FleetMemberAPI.getId() → int[]{row, col}. Built once at battle start. */
    private final Map<String, int[]> shipToSlot  = new HashMap<String, int[]>();
    /** Reverse: int[] key by "row,col" string → shipId. */
    private final Map<String, String> slotToShip = new HashMap<String, String>();

    public WingGroup() {}

    /**
     * Resolves all assignments in the given formation against the live ship list,
     * populating the shipToSlot / slotToShip maps.
     * Call once per battle during FormationPlugin.init().
     *
     * Role-fill (shipClass) assignments are satisfied in declaration order; a ship
     * matched to an earlier slot is not re-used for a later one.
     *
     * @param formation    The formation config to resolve.
     * @param battleShips  All ShipAPI instances on the player side (non-fighter).
     */
    public void resolveAssignments(WingFormation formation, List<ShipAPI> battleShips) {
        shipToSlot.clear();
        slotToShip.clear();

        // Build ID → ShipAPI map for quick lookup.
        Map<String, ShipAPI> idMap = new HashMap<String, ShipAPI>();
        for (ShipAPI ship : battleShips) {
            FleetMemberAPI member = ship.getFleetMember();
            if (member != null) {
                idMap.put(member.getId(), ship);
            }
        }

        // Pass 1: Specific-ship assignments (shipId set).
        for (int r = 0; r < WingFormation.ROWS; r++) {
            for (int c = 0; c < WingFormation.COLS; c++) {
                SlotData slot = formation.getSlot(r, c);
                if (slot.shipId != null && idMap.containsKey(slot.shipId)) {
                    bindSlot(r, c, slot.shipId);
                }
            }
        }

        // Pass 2: Role-fill (shipClass) assignments.
        for (int r = 0; r < WingFormation.ROWS; r++) {
            for (int c = 0; c < WingFormation.COLS; c++) {
                SlotData slot = formation.getSlot(r, c);
                if (slot.shipClass == null) continue;

                // Find first unassigned ship of matching hull class.
                for (ShipAPI ship : battleShips) {
                    FleetMemberAPI member = ship.getFleetMember();
                    if (member == null) continue;
                    String memberId = member.getId();
                    if (shipToSlot.containsKey(memberId)) continue; // already assigned

                    String hullSize = member.getHullSpec().getHullSize().name(); // e.g. "CAPITAL_SHIP"
                    if (hullSize.equals(slot.shipClass)) {
                        bindSlot(r, c, memberId);
                        break;
                    }
                }
            }
        }
    }

    private void bindSlot(int row, int col, String shipId) {
        shipToSlot.put(shipId, new int[]{row, col});
        slotToShip.put(row + "," + col, shipId);
    }

    /** Returns the [row, col] pair for the given fleet member ID, or null. */
    public int[] getSlotFor(String memberId) {
        return shipToSlot.get(memberId);
    }

    /** True if this fleet member has a slot assignment. */
    public boolean isAssigned(String memberId) {
        return shipToSlot.containsKey(memberId);
    }

    /** Iterate all resolved assignments: key = memberId, value = int[]{row,col}. */
    public Map<String, int[]> getAllAssignments() {
        return shipToSlot;
    }
}
