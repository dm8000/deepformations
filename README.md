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
