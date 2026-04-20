TLDR:

This is a Starsector mod to enable ship formations, especialized escorting and increased control over friendly ships. 


# Deep Battle Formations — Technical Documentation

## Overview

Deep Battle Formations is a Starsector 0.98a-RC8 mod that gives the player precise control over fleet positioning and escort behaviors. It consists of two independent systems:

- **Formation System** (`FormationPlugin.java`) — assigns ships to slots in a 7×7 grid relative to a commander ship
- **Escort System** (`EscortPlugin.java`) — assigns ships to behavioral roles relative to a principal ship

These systems are mutually exclusive: a ship can be in a formation slot OR an escort group, but not both.

---

## Formation System

### Architecture

The formation grid is a 7×7 matrix of slots (`WingFormation.java`). Each slot stores:
- `shipId` — the fleet member assigned to this slot
- `tightness` — DEFEND (1), HOLD (2), or LOCK (3)
- `isAdmiral` — flags a slot as the formation anchor override

The player can define up to 9 wings (W1–W9) and switch between them in combat using Numpad 1–9.

### Slot World Position

Each slot's position in the world is computed by `WingGroup.getSlotWorldPosition()`:

```
forwardDist  = (3.0 - row) * rowSpacing
starboardDist = (col - 3.0) * colSpacing
```

Row 3 and col 3 are the center (anchor level). Positive `forwardDist` means ahead of the commander, negative means behind. The result is then rotated by the commander's current facing angle and offset from the commander's position.

### Tightness Modes

| Mode | Outer Leash | Inner Leash | Behavior |
|------|-------------|-------------|----------|
| DEFEND | 500px | 150px | Loose — ship roams freely within a large radius |
| HOLD | 250px | 80px | Medium — holds position, allows some combat maneuvering |
| LOCK | 120px | 40px | Tight — strongly enforces slot position at all times |

### Leash Hysteresis

The leash uses hysteresis to prevent oscillation:

```
if (inLeash  && dist > outerLeash) → inLeash = false  (start correcting)
if (!inLeash && dist < innerLeash) → inLeash = true   (stop correcting)
```

When inside the leash the ship is left alone (only facing is adjusted). When outside it the full movement correction activates. The gap between outer and inner leash prevents the ship from flickering between states.

### Separation Forces

Before computing movement, the target slot position is adjusted away from nearby friendlies to prevent ships from stacking:

```
For each nearby friendly within sepRadius:
  minClearance = (r1 + r2) * 1.5
  weight = max(0, (minClearance - distance) / minClearance)²
  weight *= 3 if other ship is the commander
  push slot away from other ship by weight
```

The quadratic weight means the force grows sharply as ships get close, fading smoothly to zero at the clearance boundary.

### Reciprocal Velocity Obstacles (RVO)

After separation, an RVO pass further adjusts the target slot to avoid predicted collisions:

```
For each nearby friendly:
  Compute relative position (rp) and relative velocity (rv)
  t = -(rp · rv) / (rv · rv)   ← time to closest approach
  if t in [0, 4 seconds]:
    cx, cy = position at closest approach
    if closestDist < combinedRadius:
      urgency = (1 - t/4)²
      deflect slot perpendicular to collision axis, scaled by urgency
```

The direction of deflection is chosen to move away from the other ship (dot product check). This runs in continuous space with no grid, handling mixed ship sizes naturally through `combinedRadius = (r1 + r2) * 1.2`.

### Movement Commands

The slot position (after separation and RVO) drives movement commands. The logic differs based on whether enemies are nearby:

**No enemies (full control):**
- Compute angle from ship to slot
- If slot is more than 120° behind → back up (`ACCELERATE_BACKWARDS`) rather than turning around
- Otherwise → turn toward slot, then accelerate
- Deceleration curve: if `v²/(2a) >= dist - innerLeash`, start decelerating now

**Enemies nearby (vanilla AI owns facing):**
- Use local-space strafe and forward commands only
- `localFwd = dx*cos(facing) + dy*sin(facing)` — component toward slot along ship's forward axis
- `localRight = dx*sin(facing) - dy*cos(facing)` — component to the right
- Issue STRAFE_RIGHT/LEFT and ACCELERATE/ACCELERATE_BACKWARDS to correct position without fighting the AI's facing decisions

### HOLD/LOCK Braking (`applyLockBrake`)

Called from `processInputPreCoreControls` for last-word advantage over the vanilla AI:

**HOLD (blockAll=false):** Only blocks movement *away* from slot. Ship can still maneuver toward threats within a combat bubble (`collisionRadius * 3`).

**LOCK (blockAll=true):** Blocks all opposing commands and issues corrections:
- If moving away → block ACCELERATE, issue DECELERATE
- If drifting right when slot is left → block STRAFE_LEFT, issue STRAFE_RIGHT
- If drifting forward when slot is behind → block ACCELERATE_BACKWARDS, issue ACCELERATE

The `processInputPreCoreControls` hook fires *after* the vanilla AI's `advance()`, giving our commands the final word each frame.

### Wing Switch Stagger

When switching wings, ships don't all move at once. A priority-based delay system:

1. Sort ships by distance to their new slot (closest move first)
2. For each pair of ships whose paths intersect (segment intersection test):
   - The lower-priority ship gets `departDelay = travelTime * 0.4`
3. Ships wait out their delay before starting to move

---

## Escort System

### Roles

| Role | Description |
|------|-------------|
| GUARD | Holds a rear-flank slot. HOLD-style brake when enemies nearby |
| SUPPORT | Fire-and-withdraw state machine. Fires missiles at principal's target, returns to rear |
| VANILLA_ESCORT | Issues vanilla `LIGHT_ESCORT` assignment at battle start, no override |

### Escort Groups

Each group has one **principal** (the ship being escorted) and up to 6 **escort slots**. Ships are mutually exclusive between formation grid and escort groups.

### Slot Positioning

All slot positions are computed relative to the principal's current facing and collision radius:

```
distance = principal.collisionRadius * 2 + escort.collisionRadius + 60
angle_in_principal_space → worldAngle = principal.facing - angle
slotX = principal.x + cos(worldAngle) * distance
slotY = principal.y + sin(worldAngle) * distance
```

**SUPPORT:** 180° (pure rear). Multiple support ships spread ±20° around 180°.

**GUARD:** Rear-quarter flanks. First pair at 135°/225°, second pair at 115°/245°, third at 100°/260°. Always between 90° and 270° — never forward of the principal's beam.

This size-aware formula ensures ships of different sizes maintain proper clearance regardless of hull class.

### Flank Position

When a SUPPORT ship advances, it moves to a **flank position** beside the principal rather than flying straight at the target:

```
Principal → Target direction vector: (ptdx, ptdy)
Perpendicular: perpX = -ptdy/len, perpY = ptdx/len

Side = sign of (escortRelativePos · perpendicular)  ← picks the side escort is already on

flankPos = principal.pos + perp * side * (r_principal + r_escort + 100)
```

This keeps the escort beside the principal, within missile range of the target, without going in front of or through the principal.

---

## Support State Machine

The SUPPORT role implements a fire-and-withdraw cycle controlled by a state machine:

```
HOLD ──(target set, already in range)──────────────────────► FIRE
HOLD ──(target set, out of range)──────────────────────────► ADVANCE
ADVANCE ──(within 120% maxRange)───────────────────────────► FIRE
ADVANCE ──(target cleared)─────────────────────────────────► WITHDRAW
FIRE ──(target cleared | flux > 65% | range > 150% | dry)──► WITHDRAW
WITHDRAW ──(back at rear slot)─────────────────────────────► HOLD
WITHDRAW ──(target reappears, flux < 30%)──────────────────► ADVANCE or FIRE
```

**Range thresholds use 120%/150% of nominal weapon range** because missiles travel beyond the displayed range indicator. 120% is the fire/advance threshold; 150% is the withdraw threshold during FIRE, giving the ship time to fire before retreating.

### State Behaviors

**HOLD:** Standard nudge toward rear slot. HOLD-style brake applied in `processInputPreCoreControls` when enemies are present. Missiles suppressed.

**ADVANCE:** Navigate to flank position using `navigateTo()`. Face target with `faceShip()`. Missiles suppressed until within range.

**FIRE:** LOCK-style brake holds flank position. Ship faces target. Missiles free to fire.

**WITHDRAW:** Full movement correction toward rear slot using FormationPlugin's local-coordinate pattern:
- No enemies: turn toward slot and accelerate (can use ACCELERATE_BACKWARDS for efficiency)
- Enemies nearby: use local strafe/forward commands — vanilla AI owns facing, we own translation
- HOLD-style blocking prevents commands moving away from the slot

### Missile Suppression

Non-PD missiles are suppressed (`setForceNoFireOneFrame(true)`) unless:
- Ship is in FIRE state, OR
- The designated target is already within `weapon.getRange() * 1.2` of any missile launcher

PD-flagged weapons (Paladin, Vulcan, etc.) are always free to intercept missiles and fighters regardless of state.

Withdrawal is triggered when `allMissilesExpended()` returns true: all non-PD missile weapons either have 0 ammo or cooldown > 5 seconds (reloading). Regular guns never trigger withdrawal — only finite-ammo weapons.

---

## Carrier Behavior

Carriers (ships with non-empty `getAllWings()`) in the SUPPORT role never leave their rear slot. Instead:

- When principal has a target (R pressed): `setShipTarget()` on the carrier AND on every individual fighter in every wing, called every frame from both `advance()` and `processInputPreCoreControls()`
- When principal has no target: `wing.orderReturn(fighter)` on every wing member — actively recalls fighters rather than just clearing their target

Carrier weapons (including missiles) are never suppressed — they fire freely at whatever the vanilla carrier AI decides.

---

## Command Injection Architecture

Both FormationPlugin and EscortPlugin use the same two-hook pattern:

**`advance()`** — runs first. Issues movement commands based on current state.

**`processInputPreCoreControls()`** — runs after vanilla AI's `advance()`. Issues brake/correction commands that override whatever vanilla AI decided. This is the "last word" hook.

`blockCommandForOneFrame()` prevents a specific command from taking effect this frame, regardless of who issues it. Critically, we never block a command and then immediately issue the same command — the block would cancel our own order.

---

## Key Constants Reference

| Constant | Value | Purpose |
|----------|-------|---------|
| DEFEND_OUTER | 500px | DEFEND leash activation distance |
| HOLD_OUTER | 250px | HOLD leash activation distance |
| LOCK_OUTER | 120px | LOCK leash activation distance |
| CORRECT_SPEED_FRAC | 0.6 | Formation speed multiplier (60% of max) |
| BACK_THRESHOLD_DEG | 120° | Angle beyond which ship backs up instead of turning |
| RVO TIME_HORIZON | 4s | How far ahead RVO predicts collisions |
| SUPPORT OUTER | 250px | Support escort leash size |
| FIRE_RANGE_THRESHOLD | 1.2× | Fire when within 120% of weapon range |
| WITHDRAW_RANGE | 1.5× | Withdraw when beyond 150% of weapon range |

---

## Files Reference

| File | Purpose |
|------|---------|
| `FormationPlugin.java` | Main combat plugin — formation nudge, wing switching, hotkeys |
| `EscortPlugin.java` | Escort combat plugin — GUARD/SUPPORT/VANILLA_ESCORT behaviors |
| `FormationIntelPlugin.java` | Intel tab UI — 7×7 grid editor, wing tabs, escort assignment panel |
| `WingFormation.java` | Data model — 7×7 grid of SlotData per wing |
| `WingGroup.java` | Slot world position math, ship-to-slot assignment resolution |
| `SlotData.java` | Per-slot data: shipId, tightness mode, admiral flag |
| `FormationConfig.java` | Persistent storage for formation data across battles |
| `EscortConfig.java` | Persistent storage for escort group data |
| `EscortGroup.java` | Principal + up to 6 escort slots |
| `EscortSlot.java` | shipId, ship class, role per escort |
| `EscortRole.java` | Enum: GUARD, SUPPORT, VANILLA_ESCORT |
| `FormationModPlugin.java` | BaseModPlugin — re-registers intel on game load |


# Deep Battle Formations — Developer Notes

## File Map

```
deep_formations/
├── mod_info.json
├── build.gradle
├── data/
│   └── config/
│       ├── settings.json        ← combat plugin registration
│       └── LunaSettings.csv     ← LunaLib settings panel
└── src/com/mymod/formation/
    ├── SlotData.java            ← per-cell config (shipId / shipClass / tightness)
    ├── WingFormation.java       ← 6×6 grid of SlotData
    ├── FormationConfig.java     ← 9 wings + active wing; PersistentData host
    ├── WingGroup.java           ← world-space slot math + ship→slot resolution
    ├── FormationShipAI.java     ← ShipAIPlugin: proportional nav controller
    ├── FormationPlugin.java     ← EveryFrameCombatPlugin: init/advance/Z-toggle
    ├── FormationIntelPlugin.java← BaseIntelPlugin: 6×6 grid editor in Intel tab
    └── FormationModPlugin.java  ← MagicPlugin: onGameLoad registration
```

---

## API Verification Checklist
(Things to confirm against your actual jar set before the first compile.)

### MagicPlugin
```java
import org.magiclib.plugins.MagicPlugin;
```
Check the MagicLib jar for the exact package. May be:
  - `org.magiclib.plugins.MagicPlugin`
  - `org.magiclib.campaign.MagicPlugin`
  - `data.scripts.util.MagicPlugin` (older versions)
Browse `MagicLib.jar!/` or https://magiclibstarsector.github.io/MagicLib/

### LunaSettings import
```java
import lunalib.lunaSettings.LunaSettings;
```
Verify in LunaLib.jar. Methods used:
```java
LunaSettings.getBoolean(String modId, String fieldId) → Boolean
LunaSettings.getInt(String modId, String fieldId)     → Integer
```

### ShipAIPlugin interface methods
Used in FormationShipAI.java:
```java
void advance(float amount)
ShipwideAIFlags getAIFlags()
boolean needsRefit()
void forceCircumstancesReevaluation()
void cancelCurrentManeuver()
```
If `ShipwideAIFlags` has changed constructor or if `cancelCurrentManeuver` doesn't exist
in your version, adjust accordingly. Check `starfarer.api.jar!/com/fs/starfarer/api/combat/`.

### ShipAPI methods used
```java
ship.getLocation()          → Vector2f        ✓ stable
ship.getVelocity()          → Vector2f        ✓ stable
ship.getFacing()            → float (degrees) ✓ stable
ship.isAlive()              → boolean         ✓ stable
ship.isHulk()               → boolean         ✓ stable
ship.isFighter()            → boolean         ✓ stable
ship.getOwner()             → int (0=player)  ✓ stable
ship.getMaxSpeed()          → float           — verify exists; fallback: remove decel heuristic
ship.getFleetMember()       → FleetMemberAPI  ✓ stable
ship.getAI()                → ShipAIPlugin    ✓ stable
ship.setShipAI(ShipAIPlugin)                  ✓ stable
ship.giveCommand(ShipCommand, Object, int)     ✓ stable
```

### FleetMemberAPI methods used
```java
member.getId()                            → String     ✓ per spec
member.getShipName()                      → String     ✓ stable
member.getHullSpec()                      → ShipHullSpecAPI
member.getHullSpec().getHullSize()        → HullSize (enum: CAPITAL_SHIP, CRUISER, DESTROYER, FRIGATE, FIGHTER)
member.getHullSpec().getHullNameWithDParentheses() → String   — verify; fallback: getHullSpec().getHullName()
```

### TooltipMakerAPI.addButton signature
FormationIntelPlugin calls:
```java
info.addButton(String text, Object id, Color base, Color highlight, float w, float h, float opad)
```
If this overload doesn't exist in your version, use the no-color overload:
```java
info.addButton(String text, Object id, float w, float h, float opad)
```
and style with `buttonAPI.getPosition()` or just omit color.

### IntelManagerAPI.addIntel signature
```java
intelManager.addIntel(IntelInfoPlugin plugin, boolean isNew)
```
`isNew = false` suppresses the "new intel" notification on game load.

### BaseIntelPlugin.buttonPressConfirmed
Override this (not `buttonPressed`) for the standard Starsector button callback:
```java
public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui)
```

### LWJGL Keyboard import
```java
import org.lwjgl.input.Keyboard;
// Key code: Keyboard.KEY_Z  (== 44 in LWJGL 2.x)
```
This is from `lwjgl.jar` on the compile classpath.

---

## Combat Plugin Registration
`data/config/settings.json` is **merged** by Starsector at load — add only the
`"plugins"` key; the rest of the file stays empty. The value must be a unique key
across all loaded mods to avoid collision:
```json
{
    "plugins": {
        "deep_formations_combat_plugin": "com.mymod.formation.FormationPlugin"
    }
}
```

---

## Formation Grid Coordinate System

```
         FRONT (toward enemies)
    Port ┌───┬───┬───┬───┬───┬───┐ Starboard
    Col0 │0,0│0,1│0,2│0,3│0,4│0,5│ Col5
    Row0 ├───┼───┼───┼───┼───┼───┤
    Row1 │1,0│   │   │   │   │1,5│
    Row2 │2,0│   │   │   │   │2,5│
    Row3 │3,0│   │   │   │   │3,5│
    Row4 │4,0│   │   │   │   │4,5│
    Row5 │5,0│5,1│5,2│5,3│5,4│5,5│
         └───┴───┴───┴───┴───┴───┘
         REAR (toward allies)
```

World-space position (WingGroup.getSlotWorldPosition):
```
forward_dist   = (2.5 - row) * spacing       // positive = ahead of commander
starboard_dist = (col - 2.5) * spacing       // positive = right of commander
world_x = cmd.x + fwd * cos(θ) + stb * sin(θ)
world_y = cmd.y + fwd * sin(θ) - stb * cos(θ)
```

---

## Tightness Levels

| Level | Name   | Arrival Radius | Behaviour                          |
|-------|--------|---------------:|------------------------------------|
| 1     | Loose  | 200 px         | Converge slowly; normal speed      |
| 2     | Medium |  80 px         | Standard formation hold            |
| 3     | Tight  |  30 px         | Close quarters; no slow-down tweak |
| 4     | Lock   |  10 px         | Velocity-matches commander exactly |

---

## Next Steps / Extension Points

1. **Rendering** — `FormationPlugin.renderInWorldCoords()` is stubbed. Add
   `GL11.glBegin(GL11.GL_LINES)` calls there for a live grid overlay.

2. **Multiple wings mid-battle** — add a hotkey (e.g. [1]–[9]) in
   `FormationPlugin.advance()` to switch `config.setActiveWing(n)` and
   re-inject AI for the new formation.

3. **Enemy threat reaction** — in `FormationShipAI.advance()` you can fall back
   to the original AI when an enemy is within weapons range, re-engaging
   formation nav when the threat clears.

4. **Debug markers** — uncomment `engine.addFloatingText(...)` lines in
   `FormationPlugin.advance()` for a quick visual sanity-check during development.
