package com.mymod.formation;

import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * A dynamic AssignmentTargetAPI that always returns the world-space position
 * of a formation slot relative to the commander ship.
 *
 * Because getLocation() is called by the engine each frame, the target
 * automatically tracks as the commander moves and turns — no per-frame
 * assignment updates needed.
 */
public class SlotTarget implements AssignmentTargetAPI {

    private final ShipAPI commander;
    private final int     row;
    private final int     col;
    private final float   spacing;

    public SlotTarget(ShipAPI commander, int row, int col, float spacing) {
        this.commander = commander;
        this.row       = row;
        this.col       = col;
        this.spacing   = spacing;
    }

    @Override
    public Vector2f getLocation() {
        if (commander == null || !commander.isAlive()) {
            // Return a zero vector if commander is gone; assignment will be cleared elsewhere.
            return new Vector2f(0f, 0f);
        }
        return WingGroup.getSlotWorldPosition(commander, row, col, spacing);
    }

    @Override
    public Vector2f getVelocity() {
        if (commander == null || !commander.isAlive()) return new Vector2f(0f, 0f);
        return commander.getVelocity();
    }

    @Override
    public int getOwner() {
        if (commander == null) return 0;
        return commander.getOwner();
    }
}
