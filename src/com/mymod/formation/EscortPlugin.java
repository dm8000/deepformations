package com.mymod.formation;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Escort behavior using the same HOLD/LOCK patterns as FormationPlugin.
 *
 * GUARD:   Rear/flank slot. HOLD-style when enemies nearby.
 * SUPPORT: Rear slot by default (HOLD). When principal targets enemy:
 *          - If target already within 120% weapon range → stay, fire missiles
 *          - Otherwise advance by orbiting principal's side until in range
 *          - Withdraw when missiles dry, high flux, or target cleared
 * VANILLA_ESCORT: Issues vanilla light escort assignment, no override.
 */
public class EscortPlugin implements EveryFrameCombatPlugin {

    // Leash sizes — same values as FormationPlugin HOLD
    private static final float OUTER  = 250f;
    private static final float INNER  =  80f;

    // Support advance: how far from principal the ship orbits to
    // Uses same size-aware formula as getSlotPosition
    private static final float BACK_THRESHOLD = 120f;
    private static final float CORRECT_SPEED  = 0.9f;

    private CombatEngineAPI engine;
    private boolean initialized = false;

    private enum SupportState { HOLD, ADVANCE, FIRE, WITHDRAW }

    private static class ActiveEscort {
        ShipAPI      escort;
        ShipAPI      principal;
        EscortRole   role;
        int          roleIndex;
        int          roleTotal;
        boolean      inLeash      = true;
        SupportState supportState = SupportState.HOLD;
    }

    private final List<ActiveEscort> escorts = new ArrayList<ActiveEscort>();

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        initialized = false;
        escorts.clear();
    }

    private void deferredInit() {
        initialized = true;
        EscortConfig config = EscortConfig.getOrCreate();
        if (config.groups.isEmpty()) return;

        int playerOwner = engine.getPlayerShip() != null ? engine.getPlayerShip().getOwner() : 0;
        List<ShipAPI> allShips = new ArrayList<ShipAPI>();
        for (ShipAPI s : engine.getShips())
            if (s.getOwner() == playerOwner && !s.isFighter() && s.getFleetMember() != null)
                allShips.add(s);

        CombatFleetManagerAPI fleetMgr  = engine.getFleetManager(playerOwner);
        CombatTaskManagerAPI  taskMgr   = fleetMgr.getTaskManager(false);

        for (EscortGroup group : config.groups) {
            ShipAPI principal = findShipById(allShips, group.principalId);
            if (principal == null) continue;

            DeployedFleetMemberAPI dPrincipal = fleetMgr.getDeployedFleetMember(principal);

            List<EscortSlot> liveSlots = new ArrayList<EscortSlot>();
            List<ShipAPI>    liveShips = new ArrayList<ShipAPI>();
            for (EscortSlot slot : group.slots) {
                if (slot.isEmpty()) continue;
                ShipAPI e = findShipById(allShips, slot.shipId);
                if (e == null || e == principal) continue;
                liveSlots.add(slot); liveShips.add(e);
            }

            int gTotal = 0, sTotal = 0;
            for (EscortSlot s : liveSlots) {
                if (s.role == EscortRole.GUARD)   gTotal++;
                if (s.role == EscortRole.SUPPORT) sTotal++;
            }
            int gIdx = 0, sIdx = 0;

            for (int i = 0; i < liveShips.size(); i++) {
                EscortRole role = liveSlots.get(i).role;
                ShipAPI    ship = liveShips.get(i);

                if (role == EscortRole.VANILLA_ESCORT) {
                    if (dPrincipal != null) {
                        try {
                            DeployedFleetMemberAPI de = fleetMgr.getDeployedFleetMember(ship);
                            if (de != null) {
                                CombatFleetManagerAPI.AssignmentInfo info =
                                    taskMgr.createAssignment(CombatAssignmentType.LIGHT_ESCORT, dPrincipal, false);
                                if (info != null) taskMgr.giveAssignment(de, info, false);
                            }
                        } catch (Exception ignored) {}
                    }
                    continue;
                }

                ActiveEscort ae = new ActiveEscort();
                ae.escort = ship; ae.principal = principal; ae.role = role;
                if (role == EscortRole.GUARD)   { ae.roleIndex = gIdx++; ae.roleTotal = gTotal; }
                else                             { ae.roleIndex = sIdx++; ae.roleTotal = sTotal; }
                escorts.add(ae);
            }
        }
        Global.getLogger(EscortPlugin.class).info("EscortPlugin: " + escorts.size() + " escorts");
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused()) return;
        if (!initialized) { deferredInit(); return; }
        for (ActiveEscort ae : escorts) {
            if (!ae.escort.isAlive() || !ae.principal.isAlive()) continue;
            if (ae.role == EscortRole.SUPPORT) {
                tickSupport(ae);
            } else if (ae.role == EscortRole.GUARD) {
                tickGuard(ae);
            }
            nudge(ae);
        }
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused()) return;
        for (ActiveEscort ae : escorts) {
            if (!ae.escort.isAlive() || !ae.principal.isAlive()) continue;
            nudge(ae);
            if (ae.role == EscortRole.SUPPORT) applyHoldOrAdvanceBrake(ae);
            if (ae.role == EscortRole.GUARD) applyGuardBrake(ae);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Support state machine — transitions only
    // ─────────────────────────────────────────────────────────────────────────

    private void tickSupport(ActiveEscort ae) {
        if (ae.role != EscortRole.SUPPORT) return;

        ShipAPI escort = ae.escort;
        ShipAPI target = ae.principal.getShipTarget(); // explicit R-press only
        boolean hasT = target != null && target.isAlive();

        // Carrier support in the original logic does NOT use the support
        // state machine. It stays in HOLD, mirrors the target to the hull and
        // all living fighter members, and recalls wings when no target exists.
        if (isCarrier(escort)) {
            ae.supportState = SupportState.HOLD;
            if (hasT) {
                escort.setShipTarget(target);
                directFighters(escort, target);
            } else {
                escort.setShipTarget(null);
                returnFighters(escort);
            }
            return;
        }

        float   maxRange = getMaxWeaponRange(escort);
        float   flux     = escort.getFluxLevel();
        float   tDist    = hasT ? dist(escort, target) : Float.MAX_VALUE;

        // Set/clear escort target
        if (hasT) {
            escort.setShipTarget(target);
        } else {
            // Clear target so vanilla AI stops chasing old enemy
            escort.setShipTarget(null);
        }

        // Suppress missiles — only free in FIRE state or when target already in range
        suppressMissiles(escort, hasT ? target : null,
            ae.supportState == SupportState.FIRE);

        // Transitions
        switch (ae.supportState) {
            case HOLD:
                if (!hasT) break; // no target → stay HOLD
                if (tDist <= maxRange * 1.2f) ae.supportState = SupportState.FIRE;
                else                           ae.supportState = SupportState.ADVANCE;
                break;
            case ADVANCE:
                if (!hasT)                      { ae.supportState = SupportState.WITHDRAW; break; }
                if (tDist <= maxRange * 1.2f)     ae.supportState = SupportState.FIRE;
                break;
            case FIRE:
                if (!hasT)                        { ae.supportState = SupportState.WITHDRAW; break; }
                if (flux > 0.65f)                 { ae.supportState = SupportState.WITHDRAW; break; }
                if (tDist > maxRange * 1.5f)      { ae.supportState = SupportState.WITHDRAW; break; }
                if (allMissilesExpended(escort)) ae.supportState = SupportState.WITHDRAW;
                break;
            case WITHDRAW:
                Vector2f slot = rearSlot(ae);
                float sd = dist(escort, slot);
                if (sd < INNER) { ae.supportState = SupportState.HOLD; break; }
                if (hasT && flux < 0.3f) {
                    if (tDist <= maxRange * 1.2f) ae.supportState = SupportState.FIRE;
                    else if (tDist > maxRange * 1.2f) ae.supportState = SupportState.ADVANCE;
                }
                break;
        }
    }

    private void tickGuard(ActiveEscort ae) {
        // Guard escorts stay on the rear/flank slot and do not use the support
        // fire/withdraw state machine. This keeps them stable under pressure.
        if (ae.escort.getShipTarget() != null && !ae.escort.getShipTarget().isAlive()) {
            ae.escort.setShipTarget(null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Movement — reuses FormationPlugin nudge pattern
    // ─────────────────────────────────────────────────────────────────────────

    private void nudge(ActiveEscort ae) {
        ShipAPI escort    = ae.escort;
        ShipAPI principal = ae.principal;

        // SUPPORT ADVANCE: orbit to flank position within range
        if (ae.role == EscortRole.SUPPORT && ae.supportState == SupportState.ADVANCE) {
            Vector2f flankPos = flankSlot(ae);
            navigateTo(escort, flankPos);
            if (ae.escort.getShipTarget() != null)
                faceShip(escort, ae.escort.getShipTarget());
            return;
        }

        // SUPPORT WITHDRAW: return to rear slot using FormationPlugin movement pattern
        if (ae.role == EscortRole.SUPPORT && ae.supportState == SupportState.WITHDRAW) {
            Vector2f rear  = rearSlot(ae);
            float dx       = rear.x - escort.getLocation().x;
            float dy       = rear.y - escort.getLocation().y;
            float dist2    = len(dx, dy);
            if (dist2 < 1f) return;

            float theta   = (float) Math.toRadians(escort.getFacing());
            float cosT    = (float) Math.cos(theta);
            float sinT    = (float) Math.sin(theta);
            float localFwd   = dx*cosT + dy*sinT;
            float localRight = dx*sinT - dy*cosT;
            Vector2f vel  = escort.getVelocity();
            float velFwd  = vel.x*cosT + vel.y*sinT;
            float velRight = vel.x*sinT - vel.y*cosT;
            float maxSpd  = escort.getMaxSpeed() * CORRECT_SPEED;
            float speed   = len(vel.x, vel.y);
            float decel   = escort.getDeceleration();
            float stop    = decel > 0 ? speed*speed/(2f*decel) : 0f;
            boolean shouldDecel = stop >= (dist2 - INNER) && speed > 15f;

            if (!escort.areAnyEnemiesInRange()) {
                // No enemies: full control — turn and accelerate
                float angleToSlot = (float) Math.toDegrees(Math.atan2(dy, dx));
                float angleDiff   = normalizeAngle(angleToSlot - escort.getFacing());
                boolean behind    = Math.abs(angleDiff) > BACK_THRESHOLD;
                if (behind) {
                    float sd = normalizeAngle(angleToSlot - (escort.getFacing()+180f));
                    if      (sd >  15f) escort.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
                    else if (sd < -15f) escort.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
                    escort.giveCommand(shouldDecel ? ShipCommand.DECELERATE : ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                } else {
                    if      (angleDiff >  8f) escort.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
                    else if (angleDiff < -8f) escort.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
                    if (shouldDecel) escort.giveCommand(ShipCommand.DECELERATE, null, 0);
                    else if (Math.abs(angleDiff) < 30f && velFwd < maxSpd) escort.giveCommand(ShipCommand.ACCELERATE, null, 0);
                }
            } else {
                // Enemies nearby: vanilla AI owns facing — use local strafe/fwd like FormationPlugin
                if (shouldDecel) {
                    escort.giveCommand(ShipCommand.DECELERATE, null, 0);
                } else {
                    // Lateral correction
                    if (Math.abs(localRight) > 20f) {
                        if (localRight > 0 && velRight <  maxSpd) escort.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                        if (localRight < 0 && velRight > -maxSpd) escort.giveCommand(ShipCommand.STRAFE_LEFT,  null, 0);
                    } else {
                        if (velRight >  15f) escort.giveCommand(ShipCommand.STRAFE_LEFT,  null, 0);
                        if (velRight < -15f) escort.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                    }
                    // Forward/back correction
                    if (Math.abs(localFwd) > 20f) {
                        if (localFwd > 0 && velFwd <  maxSpd) escort.giveCommand(ShipCommand.ACCELERATE, null, 0);
                        if (localFwd < 0 && velFwd > -maxSpd) escort.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                    } else {
                        if (velFwd >  15f) escort.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                        if (velFwd < -15f) escort.giveCommand(ShipCommand.ACCELERATE,            null, 0);
                    }
                }
            }
            // Block commands moving away from slot (HOLD-style)
            float approachRate = (dx*vel.x + dy*vel.y) / dist2;
            if (approachRate < 0f) {
                escort.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                escort.giveCommand(ShipCommand.DECELERATE, null, 0);
            }
            if (localRight > 20f)  escort.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
            if (localRight < -20f) escort.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
            if (localFwd   > 20f)  escort.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
            return;
        }

        // SUPPORT FIRE: hold flank position, face target
        if (ae.role == EscortRole.SUPPORT && ae.supportState == SupportState.FIRE) {
            Vector2f flankPos = flankSlot(ae);
            // Hold: apply LOCK-style brake around flank position
            applyBrake(escort, flankPos, true);
            if (ae.escort.getShipTarget() != null)
                faceShip(escort, ae.escort.getShipTarget());
            return;
        }

        // GUARD + SUPPORT HOLD: standard nudge toward rear/flank slot
        // Same logic as FormationPlugin nudgeShip — separation + RVO + leash
        Vector2f slotPos = getSlotPosition(escort, principal, ae.roleIndex, ae.roleTotal, ae.role);

        // Separation (same as FormationPlugin)
        float sepX = 0f, sepY = 0f;
        for (ShipAPI other : engine.getShips()) {
            if (other == escort || other.getOwner() != escort.getOwner()) continue;
            if (other.isFighter() || other.isHulk()) continue;
            float ox = slotPos.x - other.getLocation().x;
            float oy = slotPos.y - other.getLocation().y;
            float od = len(ox, oy);
            if (od < 1f || od > 800f) continue;
            float minC  = (escort.getCollisionRadius() + other.getCollisionRadius()) * 1.5f;
            float bonus = (other == principal) ? 3f : 1f;
            float w = Math.max(0f, (minC - od) / minC); w = w*w*bonus;
            if (w < 0.01f) continue;
            sepX += (ox/od)*w; sepY += (oy/od)*w;
        }
        float scale = escort.getCollisionRadius() * 1.5f;
        Vector2f adjusted = new Vector2f(slotPos.x + sepX*scale, slotPos.y + sepY*scale);

        // RVO (same as FormationPlugin)
        float dx0 = adjusted.x - escort.getLocation().x;
        float dy0 = adjusted.y - escort.getLocation().y;
        float d0  = len(dx0, dy0);
        float dVx = d0 > 0 ? (dx0/d0)*escort.getMaxSpeed()*CORRECT_SPEED : 0f;
        float dVy = d0 > 0 ? (dy0/d0)*escort.getMaxSpeed()*CORRECT_SPEED : 0f;
        float rvoX = 0f, rvoY = 0f;
        for (ShipAPI other : engine.getShips()) {
            if (other == escort || other.getOwner() != escort.getOwner()) continue;
            if (other.isFighter() || other.isHulk()) continue;
            float rpx = other.getLocation().x - escort.getLocation().x;
            float rpy = other.getLocation().y - escort.getLocation().y;
            float rvx = dVx - other.getVelocity().x;
            float rvy = dVy - other.getVelocity().y;
            float cr  = (escort.getCollisionRadius() + other.getCollisionRadius()) * 1.2f;
            float rv2 = rvx*rvx + rvy*rvy;
            if (rv2 < 0.001f) continue;
            float t = -(rpx*rvx + rpy*rvy) / rv2;
            if (t < 0f || t > 4f) continue;
            float cx = rpx + rvx*t, cy = rpy + rvy*t;
            float cd = len(cx, cy);
            if (cd >= cr || cd < 0.001f) continue;
            float urg = 1f - (t/4f); urg = urg*urg;
            float px = -cy/cd, py = cx/cd;
            if (px*(-rpx)+py*(-rpy) < 0) { px=-px; py=-py; }
            rvoX += px*urg*0.7f*cr; rvoY += py*urg*0.7f*cr;
        }
        if (rvoX*rvoX + rvoY*rvoY > 0.01f) { adjusted.x += rvoX; adjusted.y += rvoY; }

        float dx   = adjusted.x - escort.getLocation().x;
        float dy   = adjusted.y - escort.getLocation().y;
        float dist = len(dx, dy);

        if (ae.inLeash  && dist > OUTER) ae.inLeash = false;
        if (!ae.inLeash && dist < INNER) ae.inLeash = true;

        if (ae.inLeash) {
            // Inside leash: match principal facing when no enemies
            if (!escort.areAnyEnemiesInRange()) {
                float diff = normalizeAngle(principal.getFacing() - escort.getFacing());
                if      (diff >  8f) escort.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
                else if (diff < -8f) escort.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
            }
            return;
        }

        // Outside leash: navigate toward slot (same pattern as FormationPlugin)
        float theta  = (float) Math.toRadians(escort.getFacing());
        float cosT   = (float) Math.cos(theta);
        float sinT   = (float) Math.sin(theta);
        float lFwd   = dx*cosT + dy*sinT;
        float lRight = dx*sinT - dy*cosT;
        Vector2f vel = escort.getVelocity();
        float velFwd = vel.x*cosT + vel.y*sinT;
        float velRt  = vel.x*sinT - vel.y*cosT;
        float maxSpd = escort.getMaxSpeed() * (dist/OUTER > 1.5f ? 1f : CORRECT_SPEED);
        float speed  = len(vel.x, vel.y);
        float decel  = escort.getDeceleration();
        float stop   = decel > 0 ? speed*speed/(2f*decel) : 0f;
        boolean shouldDecel = stop >= (dist - INNER) && speed > 15f;

        if (!escort.areAnyEnemiesInRange()) {
            float angleToSlot = (float) Math.toDegrees(Math.atan2(dy, dx));
            float angleDiff   = normalizeAngle(angleToSlot - escort.getFacing());
            boolean behind    = Math.abs(angleDiff) > BACK_THRESHOLD;
            if (behind) {
                float sd = normalizeAngle(angleToSlot - (escort.getFacing()+180f));
                if      (sd >  15f) escort.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
                else if (sd < -15f) escort.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
                escort.giveCommand(shouldDecel ? ShipCommand.DECELERATE : ShipCommand.ACCELERATE_BACKWARDS, null, 0);
            } else {
                if      (angleDiff >  8f) escort.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
                else if (angleDiff < -8f) escort.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
                if (shouldDecel) escort.giveCommand(ShipCommand.DECELERATE, null, 0);
                else if (Math.abs(angleDiff) < 30f && velFwd < maxSpd) escort.giveCommand(ShipCommand.ACCELERATE, null, 0);
                else if (speed > 20f) escort.giveCommand(ShipCommand.DECELERATE, null, 0);
            }
            if (Math.abs(lRight) > 30f)
                escort.giveCommand(lRight > 0 ? ShipCommand.STRAFE_RIGHT : ShipCommand.STRAFE_LEFT, null, 0);
        } else {
            if (shouldDecel) {
                escort.giveCommand(ShipCommand.DECELERATE, null, 0);
            } else {
                if (Math.abs(lRight) > 20f) {
                    if (lRight > 0 && velRt <  maxSpd) escort.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                    if (lRight < 0 && velRt > -maxSpd) escort.giveCommand(ShipCommand.STRAFE_LEFT,  null, 0);
                } else {
                    if (velRt >  15f) escort.giveCommand(ShipCommand.STRAFE_LEFT,  null, 0);
                    if (velRt < -15f) escort.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                }
                if (Math.abs(lFwd) > 20f) {
                    if (lFwd > 0 && velFwd <  maxSpd) escort.giveCommand(ShipCommand.ACCELERATE, null, 0);
                    if (lFwd < 0 && velFwd > -maxSpd) escort.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                } else {
                    if (velFwd >  15f) escort.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                    if (velFwd < -15f) escort.giveCommand(ShipCommand.ACCELERATE, null, 0);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // applyHoldOrAdvanceBrake — called from processInputPreCoreControls
    // Mirrors applyLockBrake from FormationPlugin
    // ─────────────────────────────────────────────────────────────────────────

    private void applyHoldOrAdvanceBrake(ActiveEscort ae) {
        ShipAPI escort = ae.escort;

        // HOLD: HOLD-style brake when enemies nearby
        if (ae.supportState == SupportState.HOLD && escort.areSignificantEnemiesInRange()) {
            applyBrake(escort, rearSlot(ae), false);
        }

        // FIRE: LOCK-style brake — hold flank position
        if (ae.supportState == SupportState.FIRE) {
            applyBrake(escort, flankSlot(ae), true);
        }


    }

    private void applyGuardBrake(ActiveEscort ae) {
        ShipAPI escort = ae.escort;
        if (escort.areSignificantEnemiesInRange()) {
            applyBrake(escort, rearSlot(ae), false);
        }
    }

    /**
     * Port of FormationPlugin.applyLockBrake.
     * blockAll=true  → LOCK: block opposing commands, issue corrections
     * blockAll=false → HOLD: only block movement away from slot
     */
    private void applyBrake(ShipAPI ship, Vector2f slotPos, boolean blockAll) {
        float dx = slotPos.x - ship.getLocation().x;
        float dy = slotPos.y - ship.getLocation().y;
        float dist = len(dx, dy);
        if (dist < 1f) return;

        float theta    = (float) Math.toRadians(ship.getFacing());
        float cosT     = (float) Math.cos(theta);
        float sinT     = (float) Math.sin(theta);
        float localFwd   = dx*cosT + dy*sinT;
        float localRight = dx*sinT - dy*cosT;
        Vector2f vel     = ship.getVelocity();
        float approachRate = (dx*vel.x + dy*vel.y) / dist;

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
            if (localFwd   > 20f)  ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Slot positions
    // ─────────────────────────────────────────────────────────────────────────

    /** Rear slot: 180° behind principal, size-aware distance. */
    private Vector2f rearSlot(ActiveEscort ae) {
        return getSlotPosition(ae.escort, ae.principal, ae.roleIndex, ae.roleTotal, ae.role);
    }

    /**
     * Flank slot: perpendicular to principal->target axis, at collision-clearance distance.
     * Picks the side the escort is already on.
     */
    private Vector2f flankSlot(ActiveEscort ae) {
        ShipAPI escort    = ae.escort;
        ShipAPI principal = ae.principal;
        ShipAPI target    = escort.getShipTarget();
        if (target == null || !target.isAlive()) return rearSlot(ae);

        float ptdx = target.getLocation().x - principal.getLocation().x;
        float ptdy = target.getLocation().y - principal.getLocation().y;
        float ptLen = len(ptdx, ptdy);
        float perpX = ptLen > 1f ? -ptdy/ptLen : 1f;
        float perpY = ptLen > 1f ?  ptdx/ptLen : 0f;

        float escRelX = escort.getLocation().x - principal.getLocation().x;
        float escRelY = escort.getLocation().y - principal.getLocation().y;
        float side = (escRelX*perpX + escRelY*perpY) >= 0 ? 1f : -1f;

        float d = principal.getCollisionRadius() + escort.getCollisionRadius() + 100f;
        return new Vector2f(
            principal.getLocation().x + perpX*side*d,
            principal.getLocation().y + perpY*side*d);
    }

    /** Navigate toward a target position using FormationPlugin's travel pattern. */
    private void navigateTo(ShipAPI ship, Vector2f target) {
        float dx   = target.x - ship.getLocation().x;
        float dy   = target.y - ship.getLocation().y;
        float dist = len(dx, dy);
        if (dist < 20f) {
            float speed = len(ship.getVelocity().x, ship.getVelocity().y);
            if (speed > 15f) ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            return;
        }
        float theta     = (float) Math.toRadians(ship.getFacing());
        float cosT      = (float) Math.cos(theta);
        float sinT      = (float) Math.sin(theta);
        float lRight    = dx*sinT - dy*cosT;
        Vector2f vel    = ship.getVelocity();
        float velFwd    = vel.x*cosT + vel.y*sinT;
        float speed     = len(vel.x, vel.y);
        float decel     = ship.getDeceleration();
        float stop      = decel > 0 ? speed*speed/(2f*decel) : 0f;

        float angle  = (float) Math.toDegrees(Math.atan2(dy, dx));
        float diff   = normalizeAngle(angle - ship.getFacing());

        if      (diff >  8f) ship.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
        else if (diff < -8f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);

        if (stop >= dist && speed > 15f) {
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        } else if (Math.abs(diff) < 40f && velFwd < ship.getMaxSpeed()*CORRECT_SPEED) {
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
            ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
        }
        if (lRight >  30f) { ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0); }
        if (lRight < -30f) { ship.giveCommand(ShipCommand.STRAFE_LEFT,  null, 0); }
    }

    private void faceShip(ShipAPI ship, ShipAPI target) {
        float dx   = target.getLocation().x - ship.getLocation().x;
        float dy   = target.getLocation().y - ship.getLocation().y;
        float diff = normalizeAngle((float)Math.toDegrees(Math.atan2(dy,dx)) - ship.getFacing());
        if      (diff >  8f) ship.giveCommand(ShipCommand.TURN_LEFT,  null, 0);
        else if (diff < -8f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Carrier helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void directFighters(ShipAPI carrier, ShipAPI target) {
        try {
            for (FighterWingAPI wing : carrier.getAllWings()) {
                if (wing == null || wing.isDestroyed()) continue;
                for (ShipAPI f : wing.getWingMembers())
                    if (f != null && f.isAlive()) f.setShipTarget(target);
            }
        } catch (Exception ignored) {}
    }

    private void returnFighters(ShipAPI carrier) {
        try {
            for (FighterWingAPI wing : carrier.getAllWings()) {
                if (wing == null || wing.isDestroyed()) continue;
                try {
                    for (ShipAPI fighter : wing.getWingMembers()) {
                        if (fighter != null && fighter.isAlive()) {
                            wing.orderReturn(fighter);
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private boolean isCarrier(ShipAPI ship) {
        try { return ship.getAllWings() != null && !ship.getAllWings().isEmpty(); }
        catch (Exception ignored) { return false; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Weapon helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void suppressMissiles(ShipAPI ship, ShipAPI target, boolean inFireState) {
        if (inFireState) return;
        // Allow if target already in range
        if (target != null && target.isAlive()) {
            float td = dist(ship.getLocation(), target.getLocation());
            try {
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (!w.isDecorative() && w.getType() == WeaponAPI.WeaponType.MISSILE)
                        if (td <= w.getRange() * 1.2f) return;
                }
            } catch (Exception ignored) {}
        }
        try {
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.isDecorative() || w.isDisabled()) continue;
                if (w.getType() != WeaponAPI.WeaponType.MISSILE) continue;
                try { if (w.hasAIHint(WeaponAPI.AIHints.PD) || w.hasAIHint(WeaponAPI.AIHints.PD_ALSO)) continue; }
                catch (Exception ignored) {}
                w.setForceNoFireOneFrame(true);
            }
        } catch (Exception ignored) {}
    }

    private boolean allMissilesExpended(ShipAPI ship) {
        try {
            int total = 0, done = 0;
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.isDecorative() || w.getType() != WeaponAPI.WeaponType.MISSILE) continue;
                try { if (w.hasAIHint(WeaponAPI.AIHints.PD) || w.hasAIHint(WeaponAPI.AIHints.PD_ALSO)) continue; }
                catch (Exception ignored) {}
                total++;
                if (w.usesAmmo() && w.getAmmo() == 0) { done++; continue; }
                if (w.getCooldownRemaining() > 5f) done++;
            }
            return total > 0 && done >= total;
        } catch (Exception ignored) { return false; }
    }

    private float getMaxWeaponRange(ShipAPI ship) {
        float max = 0f;
        try { for (WeaponAPI w : ship.getAllWeapons()) if (!w.isDecorative() && w.getRange() > max) max = w.getRange(); }
        catch (Exception ignored) {}
        return max > 0 ? max : 800f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Slot position — same size-aware formula used throughout
    // ─────────────────────────────────────────────────────────────────────────

    private Vector2f getSlotPosition(ShipAPI escort, ShipAPI principal,
                                      int slotIndex, int roleTotal, EscortRole role) {
        float angle;
        if (role == EscortRole.SUPPORT) {
            angle = roleTotal == 1 ? 180f : 160f + 40f/(roleTotal-1)*slotIndex;
        } else {
            int pair = slotIndex/2;
            angle = (slotIndex%2==0) ? 135f - Math.min(pair,2)*20f : 225f + Math.min(pair,2)*20f;
        }
        angle = Math.max(90f, Math.min(270f, angle));
        float d  = principal.getCollisionRadius()*2f + escort.getCollisionRadius() + 60f;
        float wa = (float) Math.toRadians(principal.getFacing() - angle);
        return new Vector2f(
            principal.getLocation().x + (float)Math.cos(wa)*d,
            principal.getLocation().y + (float)Math.sin(wa)*d);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private float dist(ShipAPI a, ShipAPI b) {
        float dx = b.getLocation().x - a.getLocation().x;
        float dy = b.getLocation().y - a.getLocation().y;
        return len(dx, dy);
    }
    private float dist(ShipAPI a, Vector2f b) {
        float dx = b.x - a.getLocation().x;
        float dy = b.y - a.getLocation().y;
        return len(dx, dy);
    }
    private float dist(Vector2f a, Vector2f b) {
        return len(b.x-a.x, b.y-a.y);
    }
    private static float len(float x, float y) { return (float) Math.sqrt(x*x + y*y); }
    private static float len(Vector2f v) { return len(v.x, v.y); }

    private ShipAPI findShipById(List<ShipAPI> ships, String id) {
        for (ShipAPI s : ships) {
            FleetMemberAPI m = s.getFleetMember();
            if (m != null && id.equals(m.getId())) return s;
        }
        return null;
    }

    private static float normalizeAngle(float d) {
        while (d >  180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    @Override public void renderInWorldCoords(ViewportAPI viewport) {}
    @Override public void renderInUICoords(ViewportAPI viewport) {}
}
