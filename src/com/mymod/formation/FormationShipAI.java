package com.mymod.formation;

import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import org.lwjgl.util.vector.Vector2f;

/**
 * Formation position controller.
 *
 * KEY DESIGN: we NEVER issue TURN_LEFT or TURN_RIGHT.
 * Vanilla AI keeps full control of facing, shields, weapons, and targeting.
 * We only issue translation commands (strafe/accelerate) to push the ship's
 * body toward its grid slot. The ship can face enemies while crabbing sideways.
 *
 * Control loop each frame:
 *   1. Compute slot world position from anchor ship
 *   2. Convert error vector to ship's LOCAL frame
 *      - local X = dot(error, ship_right)  -> STRAFE
 *      - local Y = dot(error, ship_forward) -> ACCELERATE/BACKWARDS
 *   3. If outside leash: issue strafe/accelerate proportional to error
 *   4. If inside leash: do nothing (dead zone prevents jitter)
 *
 * Leash sizes:
 *   DEFEND: 200px outer, 80px inner dead zone
 *   RALLY:  400px outer, 150px inner dead zone
 */
public class FormationShipAI implements ShipAIPlugin {

    // Leash radii per mode
    private static final float DEFEND_OUTER = 200f;
    private static final float DEFEND_INNER =  80f;
    private static final float RALLY_OUTER  = 400f;
    private static final float RALLY_INNER  = 150f;

    // Speed cap as fraction of max speed when correcting
    private static final float CORRECT_SPEED_FRAC = 0.7f;

    private ShipAPI             commander;
    private final ShipAPI       ship;
    private final WingFormation formation;
    private final int           row, col;
    private final float         colSpacing, rowSpacing;
    private final ShipwideAIFlags flags = new ShipwideAIFlags();

    private boolean active = true;
    private boolean inLeash = true; // start assuming we're in position

    public void setActive(boolean v)           { active = v; }
    public void updateCommander(ShipAPI c)     { commander = c; }

    // originalAI accepted but NOT stored - cannot call BasicShipAI from wrapper
    public FormationShipAI(ShipAPI ship, ShipAPI commander, WingFormation formation,
                           int row, int col, float colSpacing, float rowSpacing, ShipAIPlugin originalAI) {
        this.ship      = ship;
        this.commander = commander;
        this.formation = formation;
        this.row       = row;
        this.col       = col;
        this.colSpacing = colSpacing;
        this.rowSpacing = rowSpacing;
    }

    @Override
    public void advance(float amount) {
        if (!active) return;
        if (ship == null || !ship.isAlive() || ship.isHulk()) return;
        if (commander == null || !commander.isAlive()) return;

        SlotData slot = formation.getSlot(row, col);

        // Compute world-space slot position
        Vector2f slotPos = WingGroup.getSlotWorldPosition(commander, row, col, colSpacing, rowSpacing);
        float dx = slotPos.x - ship.getLocation().x;
        float dy = slotPos.y - ship.getLocation().y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        float outerLeash = 150f; // FormationShipAI leash - not used in new mode system
        float innerLeash =  50f;

        // Hysteresis: start correcting when outside outer, stop when inside inner
        if (inLeash && dist > outerLeash) inLeash = false;
        if (!inLeash && dist < innerLeash) inLeash = true;

        if (inLeash) return; // inside dead zone - vanilla AI has full control

        // ── Convert error to ship's LOCAL frame ───────────────────────────────
        // Ship facing theta: 0=right, 90=up (CCW positive, standard Starsector)
        float theta   = (float) Math.toRadians(ship.getFacing());
        float cosT    = (float) Math.cos(theta);
        float sinT    = (float) Math.sin(theta);

        // Forward unit vector: (cosT, sinT)
        // Right unit vector:   (sinT, -cosT)
        float localForward = dx * cosT  + dy * sinT;   // dot(error, forward)
        float localRight   = dx * sinT  + dy * (-cosT); // dot(error, right)

        // Speed cap: slow down proportionally as we approach inner leash
        float speedFrac = Math.min(1f, (dist - innerLeash) / (outerLeash - innerLeash));
        float maxCorrectSpeed = ship.getMaxSpeed() * CORRECT_SPEED_FRAC * speedFrac;
        maxCorrectSpeed = Math.max(maxCorrectSpeed, 20f);

        // Current local velocity components
        Vector2f vel = ship.getVelocity();
        float velForward = vel.x * cosT  + vel.y * sinT;
        float velRight   = vel.x * sinT  + vel.y * (-cosT);

        // ── Issue STRAFE commands for lateral error ───────────────────────────
        float absRight = Math.abs(localRight);
        if (absRight > 15f) {
            // Only strafe if not already moving fast enough in the right direction
            if (localRight > 0 && velRight < maxCorrectSpeed) {
                ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            } else if (localRight < 0 && velRight > -maxCorrectSpeed) {
                ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            }
        } else {
            // Near lateral target - bleed off lateral velocity
            if (velRight >  20f) ship.giveCommand(ShipCommand.STRAFE_LEFT,  null, 0);
            if (velRight < -20f) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
        }

        // ── Issue ACCELERATE commands for forward/back error ─────────────────
        float absForward = Math.abs(localForward);
        if (absForward > 15f) {
            if (localForward > 0 && velForward < maxCorrectSpeed) {
                ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
            } else if (localForward < 0 && velForward > -maxCorrectSpeed) {
                ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
            }
        } else {
            // Near forward target - bleed off forward velocity
            if (velForward >  20f) ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
            if (velForward < -20f) ship.giveCommand(ShipCommand.ACCELERATE,            null, 0);
        }
    }

    private static float len(Vector2f v) {
        return (float) Math.sqrt(v.x * v.x + v.y * v.y);
    }

    @Override public void cancelCurrentManeuver() {}
    @Override public void forceCircumstanceEvaluation() {}
    @Override public void setDoNotFireDelay(float amount) {}
    @Override public ShipwideAIFlags getAIFlags() { return flags; }
    @Override public ShipAIConfig getConfig() { return null; }
    public boolean needsRefit() { return false; }
}
