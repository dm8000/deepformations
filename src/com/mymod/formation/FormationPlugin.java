package com.mymod.formation;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import lunalib.lunaSettings.LunaSettings;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class FormationPlugin implements EveryFrameCombatPlugin {

    public static final String MOD_ID = "deep_formations";

    private static final int[] NUMPAD_KEYS = {
        Keyboard.KEY_NUMPAD1, Keyboard.KEY_NUMPAD2, Keyboard.KEY_NUMPAD3,
        Keyboard.KEY_NUMPAD4, Keyboard.KEY_NUMPAD5, Keyboard.KEY_NUMPAD6,
        Keyboard.KEY_NUMPAD7, Keyboard.KEY_NUMPAD8, Keyboard.KEY_NUMPAD9,
    };

    // Leash radii
    private static final float DEFEND_OUTER = 500f;
    private static final float DEFEND_INNER = 150f;
    private static final float HOLD_OUTER   = 250f;
    private static final float HOLD_INNER   =  80f;
    private static final float LOCK_OUTER   = 120f;
    private static final float LOCK_INNER   =  40f;

    private static final float CORRECT_SPEED_FRAC = 0.6f;

    // Backing-up angle threshold: if slot is more than this degrees behind, use ACCELERATE_BACKWARDS
    private static final float BACK_THRESHOLD_DEG = 120f;

    private CombatEngineAPI engine;
    private final EscortPlugin escortPlugin = new EscortPlugin();
    private WingFormation   activeFormation;
    private float           colSpacing;
    private float           rowSpacing;
    private ShipAPI         commander;

    private boolean formationActive = true;
    private boolean initialized     = false;
    private boolean prevYDown       = false;
    private final boolean[] prevWingKeys = new boolean[9];

    private static class ManagedShip {
        ShipAPI ship;
        int     row, col;
        boolean inLeash    = true;
        float   departDelay = 0f; // seconds to wait before moving to new slot (wing switch stagger)
    }
    private final List<ManagedShip> managed = new ArrayList<ManagedShip>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        initialized = false;
        managed.clear();
        escortPlugin.init(engine);
        try {
            Boolean enabled = LunaSettings.getBoolean(MOD_ID, "formation_enabled");
            if (Boolean.FALSE.equals(enabled)) { formationActive = false; initialized = true; return; }
            Integer colSp = LunaSettings.getInt(MOD_ID, "formation_col_spacing");
            Integer rowSp = LunaSettings.getInt(MOD_ID, "formation_row_spacing");
            colSpacing = (colSp != null) ? colSp.floatValue() : 1500f;
            rowSpacing = (rowSp != null) ? rowSp.floatValue() : 1500f;
        } catch (Exception e) { colSpacing = 1500f; rowSpacing = 1500f; }
    }

    private void deferredInit() {
        initialized = true;
        FormationConfig config = FormationConfig.getOrCreate();
        activeFormation = config.getActiveFormation();

        ShipAPI playerShip = engine.getPlayerShip();
        commander = (playerShip != null && playerShip.isAlive()) ? playerShip : null;

        int side = (commander != null) ? commander.getOwner() : 0;
        List<ShipAPI> candidates = buildCandidates(side);

        if (commander == null) {
            String admId = findAdmiralId(activeFormation);
            if (admId != null) commander = findShipById(candidates, admId);
        }
        if (commander == null) return;

        side       = commander.getOwner();
        candidates = buildCandidates(side);

        WingGroup wingGroup = new WingGroup();
        wingGroup.resolveAssignments(activeFormation, candidates);

        for (Map.Entry<String, int[]> e : wingGroup.getAllAssignments().entrySet()) {
            String  id   = e.getKey();
            int[]   slot = e.getValue();
            ShipAPI ship = findShipById(candidates, id);
            if (ship == null || ship == commander) continue;
            if (activeFormation.getSlot(slot[0], slot[1]).isAdmiral) continue;

            ManagedShip ms = new ManagedShip();
            ms.ship = ship;
            ms.row  = slot[0];
            ms.col  = slot[1];
            managed.add(ms);
        }

        engine.addFloatingText(commander.getLocation(),
                managed.size() > 0 ? "Formation: " + managed.size() + " ships" : "Formation: 0 matched",
                20f, managed.size() > 0 ? Color.GREEN : Color.ORANGE, commander, 2f, 1f);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused()) return;
        if (!initialized) { deferredInit(); return; }
        if (commander == null) return;

        boolean yDown = Keyboard.isKeyDown(Keyboard.KEY_Y);
        if (yDown && !prevYDown) {
            formationActive = !formationActive;
            engine.addFloatingText(commander.getLocation(),
                    formationActive ? "Formation ON" : "Formation OFF",
                    24f, formationActive ? Color.GREEN : Color.ORANGE, commander, 1f, 0.5f);
        }
        prevYDown = yDown;

        if (!formationActive) return;

        for (int i = 0; i < 9; i++) {
            boolean keyDown = Keyboard.isKeyDown(NUMPAD_KEYS[i]);
            if (keyDown && !prevWingKeys[i]) switchToWing(i);
            prevWingKeys[i] = keyDown;
        }

        ShipAPI player = engine.getPlayerShip();
        if (player != null && player.isAlive() && player != commander) commander = player;

        escortPlugin.advance(amount, events);

        // Tick depart delays
        for (ManagedShip ms : managed) {
            if (ms.departDelay > 0f) ms.departDelay -= amount;
        }

        for (ManagedShip ms : managed) {
            if (!ms.ship.isAlive()) continue;
            nudgeShip(ms);
        }
    }

    private void nudgeShip(ManagedShip ms) {
        ShipAPI ship  = ms.ship;
        SlotData slot = activeFormation.getSlot(ms.row, ms.col);

        // Waiting for staggered departure
        if (ms.departDelay > 0f) return;

        Vector2f slotPos = WingGroup.getSlotWorldPosition(commander, ms.row, ms.col, colSpacing, rowSpacing);

        // Separation: adjust target away from nearby friendlies
        float sepX = 0f, sepY = 0f;
        float sepRadius = Math.max(colSpacing, rowSpacing) * 0.8f;
        for (ShipAPI other : engine.getShips()) {
            if (other == ship) continue;
            if (other.getOwner() != ship.getOwner()) continue;
            if (other.isFighter() || other.isHulk()) continue;
            float ox = slotPos.x - other.getLocation().x;
            float oy = slotPos.y - other.getLocation().y;
            float od = (float) Math.sqrt(ox * ox + oy * oy);
            if (od < 1f || od > sepRadius) continue;
            float minClearance = (ship.getCollisionRadius() + other.getCollisionRadius()) * 1.5f;
            float commanderBonus = (other == commander) ? 3f : 1f;
            float weight = Math.max(0f, (minClearance - od) / minClearance);
            weight = weight * weight * commanderBonus;
            if (weight < 0.01f) continue;
            sepX += (ox / od) * weight;
            sepY += (oy / od) * weight;
        }
        Vector2f adjustedSlot = new Vector2f(
            slotPos.x + sepX * ship.getCollisionRadius() * 1.5f,
            slotPos.y + sepY * ship.getCollisionRadius() * 1.5f
        );

        float dx   = adjustedSlot.x - ship.getLocation().x;
        float dy   = adjustedSlot.y - ship.getLocation().y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        // ── Simplified RVO velocity deflection ───────────────────────────────
        // For each nearby friendly, if our desired velocity toward the slot
        // would cause a collision within TIME_HORIZON seconds, deflect it
        // perpendicular to the collision axis weighted by urgency.
        // This runs in continuous space - no grid needed.
        final float TIME_HORIZON   = 4f;   // seconds to look ahead
        final float RVO_WEIGHT     = 0.7f; // how strongly to deflect
        float rvoX = 0f, rvoY = 0f;

        // Desired velocity = direction to slot * maxSpd
        float desiredSpd = Math.min(dist > 0 ? dist * 0.35f : 0f,
                ship.getMaxSpeed() * CORRECT_SPEED_FRAC);
        float desiredVx  = dist > 0 ? (dx / dist) * desiredSpd : 0f;
        float desiredVy  = dist > 0 ? (dy / dist) * desiredSpd : 0f;

        for (ShipAPI other : engine.getShips()) {
            if (other == ship) continue;
            if (other.getOwner() != ship.getOwner()) continue;
            if (other.isFighter() || other.isHulk()) continue;

            // Relative position and velocity
            float rpx = other.getLocation().x - ship.getLocation().x;
            float rpy = other.getLocation().y - ship.getLocation().y;
            float rvx = desiredVx - other.getVelocity().x;
            float rvy = desiredVy - other.getVelocity().y;

            // Combined radius - scale by both ship sizes
            float combinedR = (ship.getCollisionRadius() + other.getCollisionRadius()) * 1.2f;

            // Time to closest approach: t = -(rp·rv) / (rv·rv)
            float rvDotRv = rvx * rvx + rvy * rvy;
            if (rvDotRv < 0.001f) continue; // not approaching

            float t = -(rpx * rvx + rpy * rvy) / rvDotRv;
            if (t < 0f || t > TIME_HORIZON) continue; // not within horizon

            // Position at closest approach
            float cx = rpx + rvx * t;
            float cy = rpy + rvy * t;
            float closestDist = (float) Math.sqrt(cx * cx + cy * cy);

            if (closestDist >= combinedR) continue; // no collision predicted

            // Urgency: stronger deflection for imminent collisions
            float urgency = 1f - (t / TIME_HORIZON);
            urgency = urgency * urgency; // square for sharper response

            // Deflect perpendicular to collision axis
            // Perpendicular to (cx,cy) is (-cy, cx)
            float perpLen = closestDist > 0.001f ? closestDist : 1f;
            float perpX   = -cy / perpLen;
            float perpY   =  cx / perpLen;

            // Choose deflection direction away from the other ship
            float dotAway = perpX * (-rpx) + perpY * (-rpy);
            if (dotAway < 0) { perpX = -perpX; perpY = -perpY; }

            rvoX += perpX * urgency * RVO_WEIGHT * combinedR;
            rvoY += perpY * urgency * RVO_WEIGHT * combinedR;
        }

        // Apply RVO deflection to the target position
        if (rvoX * rvoX + rvoY * rvoY > 0.01f) {
            adjustedSlot.x += rvoX;
            adjustedSlot.y += rvoY;
            // Recompute dx/dy/dist with deflected target
            dx   = adjustedSlot.x - ship.getLocation().x;
            dy   = adjustedSlot.y - ship.getLocation().y;
            dist = (float) Math.sqrt(dx * dx + dy * dy);
        }

        float outerLeash, innerLeash;
        if (slot.tightness == SlotData.LOCK) {
            outerLeash = LOCK_OUTER;   innerLeash = LOCK_INNER;
        } else if (slot.tightness == SlotData.HOLD) {
            outerLeash = HOLD_OUTER;   innerLeash = HOLD_INNER;
        } else {
            outerLeash = DEFEND_OUTER; innerLeash = DEFEND_INNER;
        }

        // HOLD combat bubble: when enemies nearby, let ship maneuver within collision radius * 3
        if (slot.tightness == SlotData.HOLD && ship.areSignificantEnemiesInRange()) {
            float bubble = ship.getCollisionRadius() * 3f;
            innerLeash = Math.max(innerLeash, bubble);
        }

        if (ms.inLeash  && dist > outerLeash) ms.inLeash = false;
        if (!ms.inLeash && dist < innerLeash) ms.inLeash = true;

        if (ms.inLeash) {
            // Inside leash: match commander facing when no enemies
            if (!ship.areAnyEnemiesInRange() && commander != null && commander.isAlive()) {
                float diff = normalizeAngle(commander.getFacing() - ship.getFacing());
                if      (diff >  8f) ship.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
                else if (diff < -8f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
            }
            return;
        }

        float theta    = (float) Math.toRadians(ship.getFacing());
        float cosT     = (float) Math.cos(theta);
        float sinT     = (float) Math.sin(theta);
        float localFwd   = dx * cosT + dy * sinT;
        float localRight = dx * sinT - dy * cosT;
        Vector2f vel   = ship.getVelocity();
        float velFwd   = vel.x * cosT + vel.y * sinT;
        float velRight = vel.x * sinT - vel.y * cosT;

        float maxSpd = Math.max(20f, ship.getMaxSpeed() * CORRECT_SPEED_FRAC);
        boolean enemiesNear = ship.areAnyEnemiesInRange();

        // ── Deceleration curve ────────────────────────────────────────────────
        // Compute estimated stop distance: v²/(2*decel). Start decelerating when
        // the ship would overshoot the inner leash boundary at current speed.
        float speed      = len(vel);
        float decel      = ship.getDeceleration();
        float stopDist   = (decel > 0f) ? (speed * speed) / (2f * decel) : 0f;
        boolean shouldDecel = stopDist >= (dist - innerLeash) && speed > 15f;

        if (!enemiesNear) {
            // ── No enemies: full control of movement AND facing ───────────────

            // Smarter direction: if slot is mostly behind, back up instead of turning
            float angleToSlot = (float) Math.toDegrees(Math.atan2(dy, dx));
            float angleDiff   = normalizeAngle(angleToSlot - ship.getFacing());
            boolean slotIsBehind = Math.abs(angleDiff) > BACK_THRESHOLD_DEG;

            if (slotIsBehind) {
                // Slot is behind: back up without turning
                // Optionally nudge turn to align stern toward slot
                float sternAngleDiff = normalizeAngle(angleToSlot - (ship.getFacing() + 180f));
                if      (sternAngleDiff >  15f) ship.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
                else if (sternAngleDiff < -15f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);

                if (shouldDecel) {
                    ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                } else {
                    ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                }
            } else {
                // Slot is ahead: turn toward it and accelerate
                if      (angleDiff >  8f) ship.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
                else if (angleDiff < -8f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);

                if (shouldDecel) {
                    ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                } else if (Math.abs(angleDiff) < 30f && velFwd < maxSpd) {
                    ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
                } else if (speed > 20f && Math.abs(angleDiff) >= 30f) {
                    ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                }
            }

            // Lateral correction always
            if (Math.abs(localRight) > 30f) {
                if (localRight > 0) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                else                ship.giveCommand(ShipCommand.STRAFE_LEFT,  null, 0);
            }

        } else {
            // ── Enemies nearby: vanilla AI owns facing ────────────────────────

            // Deceleration curve applies here too
            if (shouldDecel) {
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            } else {
                if (Math.abs(localRight) > 20f) {
                    if (localRight > 0 && velRight <  maxSpd) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                    if (localRight < 0 && velRight > -maxSpd) ship.giveCommand(ShipCommand.STRAFE_LEFT,  null, 0);
                } else {
                    if (velRight >  15f) ship.giveCommand(ShipCommand.STRAFE_LEFT,  null, 0);
                    if (velRight < -15f) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                }
                if (Math.abs(localFwd) > 20f) {
                    if (localFwd > 0 && velFwd <  maxSpd) ship.giveCommand(ShipCommand.ACCELERATE,           null, 0);
                    if (localFwd < 0 && velFwd > -maxSpd) ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                } else {
                    if (velFwd >  15f) ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                    if (velFwd < -15f) ship.giveCommand(ShipCommand.ACCELERATE,            null, 0);
                }
            }
        }
    }

    private void switchToWing(int wingIndex) {
        FormationConfig config = FormationConfig.getOrCreate();
        if (wingIndex == config.getActiveWing()) return;

        config.setActiveWing(wingIndex);
        config.save();
        activeFormation = config.getActiveFormation();

        managed.clear();
        int side = commander.getOwner();
        List<ShipAPI> candidates = buildCandidates(side);

        WingGroup wingGroup = new WingGroup();
        wingGroup.resolveAssignments(activeFormation, candidates);

        final List<ManagedShip> newManaged = new ArrayList<ManagedShip>();
        for (Map.Entry<String, int[]> e : wingGroup.getAllAssignments().entrySet()) {
            String  id   = e.getKey();
            int[]   slot = e.getValue();
            ShipAPI ship = findShipById(candidates, id);
            if (ship == null || ship == commander) continue;
            if (activeFormation.getSlot(slot[0], slot[1]).isAdmiral) continue;

            ManagedShip ms = new ManagedShip();
            ms.ship    = ship;
            ms.row     = slot[0];
            ms.col     = slot[1];
            ms.inLeash = false;
            newManaged.add(ms);
        }

        // ── Priority-based stagger ────────────────────────────────────────────
        // Sort by distance to new slot: closest ship moves first (no delay),
        // ships whose straight-line paths conflict with higher-priority ships
        // get a small delay proportional to their travel distance.
        Collections.sort(newManaged, new Comparator<ManagedShip>() {
            public int compare(ManagedShip a, ManagedShip b) {
                float da = distToSlot(a);
                float db = distToSlot(b);
                return Float.compare(da, db);
            }
        });

        // Check path intersections: for each lower-priority ship, if its path
        // crosses a higher-priority ship's path, add a departure delay.
        for (int i = 1; i < newManaged.size(); i++) {
            ManagedShip ms = newManaged.get(i);
            Vector2f destI = WingGroup.getSlotWorldPosition(
                    commander, ms.row, ms.col, colSpacing, rowSpacing);
            for (int j = 0; j < i; j++) {
                ManagedShip higher = newManaged.get(j);
                Vector2f destJ = WingGroup.getSlotWorldPosition(
                        commander, higher.row, higher.col, colSpacing, rowSpacing);
                if (pathsIntersect(ms.ship.getLocation(), destI,
                                   higher.ship.getLocation(), destJ)) {
                    // Delay proportional to how far the higher-priority ship needs to travel
                    float travelTime = distToSlot(higher) / Math.max(1f, higher.ship.getMaxSpeed());
                    ms.departDelay = Math.max(ms.departDelay, travelTime * 0.4f);
                }
            }
        }

        managed.addAll(newManaged);

        engine.addFloatingText(commander.getLocation(),
                "Wing " + (wingIndex + 1) + " - " + activeFormation.getAssignedCount() + " ships",
                24f, Color.CYAN, commander, 2f, 1f);
    }

    /** Returns true if segment AB and segment CD intersect in 2D. */
    private static boolean pathsIntersect(Vector2f a, Vector2f b, Vector2f c, Vector2f d) {
        float abx = b.x - a.x, aby = b.y - a.y;
        float cdx = d.x - c.x, cdy = d.y - c.y;
        float denom = abx * cdy - aby * cdx;
        if (Math.abs(denom) < 0.001f) return false; // parallel
        float acx = c.x - a.x, acy = c.y - a.y;
        float t = (acx * cdy - acy * cdx) / denom;
        float u = (acx * aby - acy * abx) / denom;
        return (t >= 0f && t <= 1f && u >= 0f && u <= 1f);
    }

    private float distToSlot(ManagedShip ms) {
        Vector2f dest = WingGroup.getSlotWorldPosition(
                commander, ms.row, ms.col, colSpacing, rowSpacing);
        float dx = dest.x - ms.ship.getLocation().x;
        float dy = dest.y - ms.ship.getLocation().y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void applyLockBrake(ManagedShip ms, boolean blockAll) {
        ShipAPI ship = ms.ship;
        Vector2f slotPos = WingGroup.getSlotWorldPosition(
                commander, ms.row, ms.col, colSpacing, rowSpacing);
        float dx = slotPos.x - ship.getLocation().x;
        float dy = slotPos.y - ship.getLocation().y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        float theta    = (float) Math.toRadians(ship.getFacing());
        float cosT     = (float) Math.cos(theta);
        float sinT     = (float) Math.sin(theta);
        float localFwd   = dx * cosT + dy * sinT;
        float localRight = dx * sinT - dy * cosT;
        Vector2f vel     = ship.getVelocity();
        float approachRate = (dx * vel.x + dy * vel.y) / dist;

        if (blockAll) {
            if (approachRate < -10f) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            }
            if (localRight > 20f) {
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            } else if (localRight < -20f) {
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            }
            if (localFwd > 20f) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
                ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
            } else if (localFwd < -20f) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
            }
        } else {
            if (approachRate < 0f) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            }
            if (localRight > 20f)  ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
            if (localRight < -20f) ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
            if (localFwd > 20f)    ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
        }
    }

    private static float normalizeAngle(float d) {
        while (d >  180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    private static float len(Vector2f v) {
        return (float) Math.sqrt(v.x * v.x + v.y * v.y);
    }

    private String findAdmiralId(WingFormation f) {
        for (int r = 0; r < WingFormation.ROWS; r++)
            for (int c = 0; c < WingFormation.COLS; c++) {
                SlotData s = f.getSlot(r, c);
                if (s.isAdmiral && s.shipId != null) return s.shipId;
            }
        return null;
    }

    private List<ShipAPI> buildCandidates(int owner) {
        List<ShipAPI> list = new ArrayList<ShipAPI>();
        ShipAPI player = engine.getPlayerShip();
        for (ShipAPI s : engine.getShips()) {
            if (s.getOwner() != owner || s == player) continue;
            if (s.isFighter() || s.getFleetMember() == null) continue;
            list.add(s);
        }
        return list;
    }

    private ShipAPI findShipById(List<ShipAPI> ships, String id) {
        for (ShipAPI s : ships) {
            FleetMemberAPI m = s.getFleetMember();
            if (m != null && id.equals(m.getId())) return s;
        }
        return null;
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        escortPlugin.processInputPreCoreControls(amount, events);
        if (engine == null || engine.isPaused() || !formationActive || commander == null) return;
        for (ManagedShip ms : managed) {
            if (!ms.ship.isAlive() || ms.departDelay > 0f) continue;
            nudgeShip(ms);
            if (!ms.inLeash) {
                SlotData slot = activeFormation.getSlot(ms.row, ms.col);
                if (slot.tightness == SlotData.LOCK) {
                    applyLockBrake(ms, true);
                } else if (slot.tightness == SlotData.HOLD
                        && ms.ship.areSignificantEnemiesInRange()) {
                    applyLockBrake(ms, false);
                }
            }
        }
    }

    @Override public void renderInWorldCoords(ViewportAPI viewport) {}
    @Override public void renderInUICoords(ViewportAPI viewport) {}
}
