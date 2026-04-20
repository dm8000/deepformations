package com.mymod.formation;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.awt.Color;

public class FormationIntelPlugin extends BaseIntelPlugin {

    private transient int selectedRow      = -1;
    private transient int selectedCol      = -1;
    private transient String activeTab     = "formations"; // "formations" or "escorts"
    private transient String selectedPrincipalId = null;
    private transient int    selectedEscortSlot  = -1;

    private static final float BH      = 22f;
    private static final float BW      = 110f;
    private static final float CELL_H  = 28f;  // height of each slot button
    private static final float CELL_W  = 90f;  // width of each slot button
    private static final float COL_GAP = 4f;   // gap between columns

    @Override public String getName() { return "Battle Formations"; }
    @Override public String getIcon() {
        return Global.getSettings().getSpriteName("intel", "fleet_log");
    }
    @Override public boolean isEnded()             { return false; }
    @Override public boolean isDone()               { return false; }
    @Override public boolean hasLargeDescription() { return true;  }
    @Override public boolean hasSmallDescription() { return true;  }

    @Override
    public Set<String> getIntelTags(com.fs.starfarer.api.ui.SectorMapAPI map) {
        Set<String> tags = new HashSet<String>();
        tags.add("Personal");
        return tags;
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        FormationConfig config = FormationConfig.getOrCreate();
        info.addPara("Wing " + (config.getActiveWing() + 1)
                + " - " + config.getActiveFormation().getAssignedCount()
                + " ships assigned.", 0f);
        info.addPara("Grid spacing: " + config.getSpacing() + "px per slot. "
                + "Adjust in LunaLib settings.", 3f);
        info.addPara("Click 'Show Details' to edit formation.", 3f);
    }

    @Override
    public void createLargeDescription(CustomPanelAPI panel, float width, float height) {
        FormationConfig config = FormationConfig.getOrCreate();
        WingFormation   wing   = config.getActiveFormation();

        // ── Tab selector ─────────────────────────────────────────────────────
        TooltipMakerAPI tabs = panel.createUIElement(width, 30f, false);
        tabs.addButton((activeTab.equals("formations") ? "> " : "  ") + "Formations",
                "tab_formations", 130f, 24f, 0f);
        tabs.addButton((activeTab.equals("escorts") ? "> " : "  ") + "Smart Escorts",
                "tab_escorts", 130f, 24f, 0f);
        panel.addUIElement(tabs).inTL(0f, 0f);

        if (activeTab.equals("escorts")) {
            renderEscortTab(panel, config, width, height, 35f);
            return;
        }

        float topH     = 35f;  // height of tab bar
        float gridTop  = 0f;
        float gridH    = WingFormation.ROWS * (CELL_H + COL_GAP);
        float gridW    = WingFormation.COLS * (CELL_W + COL_GAP);
        float rightX   = gridW + 20f;
        float rightW   = 280f;  // fixed narrow right panel



        // ── Grid: 6 columns, each a scrollable TooltipMakerAPI ────
        // Column header row
        String[] colNames = {"Port", "C1", "C2", "Mid", "C4", "C5", "Stbd"};
        String[] rowNames = {"Front","R1","R2","Mid","R4","R5","Rear"};

        for (int c = 0; c < WingFormation.COLS; c++) {
            float colX = 5f + c * (CELL_W + COL_GAP);
            float colTotalH = gridH + 20f; // +20 for header

            TooltipMakerAPI col = panel.createUIElement(CELL_W, colTotalH, false);
            // Column header
            col.addPara(colNames[c], Color.GRAY, 0f);
            // 6 slot buttons in this column
            for (int r = 0; r < WingFormation.ROWS; r++) {
                SlotData slot  = wing.getSlot(r, c);
                boolean  isSel = (r == selectedRow && c == selectedCol);
                String   lbl   = rowNames[r] + "\n" + slotShort(slot);
                if (isSel) lbl = "*" + lbl;
                col.addButton(lbl, "slot_"+r+"_"+c, CELL_W, CELL_H, COL_GAP);
            }
            panel.addUIElement(col).inTL(colX, topH + gridTop);
        }

        // ── Detail panel (right of grid) ──────────────────────────
        float detailTop = gridTop;
        if (selectedRow >= 0 && selectedCol >= 0) {
            SlotData sel = wing.getSlot(selectedRow, selectedCol);
            TooltipMakerAPI detail = panel.createUIElement(rightW, height - detailTop, true);

            // Wing selector at top of right panel
            detail.addPara("Wing:", 0f);
            for (int i = 0; i < FormationConfig.WING_COUNT; i++) {
                String wlbl = (i == config.getActiveWing()) ? "[W"+(i+1)+"]" : "W"+(i+1);
                detail.addButton(wlbl, "wing_"+i, 36f, 20f, 2f);
            }
            detail.addSectionHeading(
                    rowNames[selectedRow] + " / " + colNames[selectedCol],
                    Alignment.LMID, 6f);

            detail.addPara("Behavior:", 4f);
            detail.addButton((sel.tightness == SlotData.DEFEND ? "> " : "  ") + "Defend",
                    "tight_" + SlotData.DEFEND, BW, BH, 2f);
            detail.addButton((sel.tightness == SlotData.HOLD   ? "> " : "  ") + "Hold",
                    "tight_" + SlotData.HOLD,   BW, BH, 2f);
            detail.addButton((sel.tightness == SlotData.LOCK   ? "> " : "  ") + "Lock",
                    "tight_" + SlotData.LOCK,   BW, BH, 2f);
            detail.addButton("Clear", "clear_slot", 60f, BH, 10f);
            // Admiral button - only shown if slot has a specific ship assigned
            if (sel.shipId != null) {
                String admLabel = sel.isAdmiral ? "> Admiral <" : "Set Admiral";
                detail.addButton(admLabel, "set_admiral", BW + 20f, BH, 4f);
            }

            detail.addSectionHeading("Hull Class", Alignment.LMID, 8f);
            String[] cls  = {"CAPITAL_SHIP","CRUISER","DESTROYER","FRIGATE"};
            String[] clsL = {"Any Capital","Any Cruiser","Any Destroyer","Any Frigate"};
            for (int i = 0; i < cls.length; i++) {
                detail.addButton((cls[i].equals(sel.shipClass)?"> ":"")+clsL[i],
                        "cls_"+cls[i], BW+20f, BH, 2f);
            }

            detail.addSectionHeading("Specific Ship", Alignment.LMID, 8f);
            List<FleetMemberAPI> members =
                    Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy();
            for (FleetMemberAPI m : members) {
                String lbl = (m.getId().equals(sel.shipId)?"> ":"")
                        + m.getShipName()+" ("+m.getHullSpec().getHullName()+")";
                detail.addButton(lbl, "ship_"+m.getId(), rightW - 10f, BH, 2f);
            }
            panel.addUIElement(detail).inTL(rightX, topH + detailTop);
        } else {
            TooltipMakerAPI hint = panel.createUIElement(rightW, 80f, false);
            hint.addPara("Wing:", 0f);
            for (int i = 0; i < FormationConfig.WING_COUNT; i++) {
                String wlbl = (i == config.getActiveWing()) ? "[W"+(i+1)+"]" : "W"+(i+1);
                hint.addButton(wlbl, "wing_"+i, 36f, 20f, 2f);
            }
            hint.addPara("Select a slot to assign ships.", Color.GRAY, 8f);
            panel.addUIElement(hint).inTL(rightX, topH + detailTop);
        }
    }

    @Override
    public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        if (!(buttonId instanceof String)) return;
        String id = (String) buttonId;

        // Tab switching — must be first
        if (id.equals("tab_formations")) {
            activeTab = "formations";
            ui.updateUIForItem(this);
            return;
        }
        if (id.equals("tab_escorts")) {
            activeTab = "escorts";
            ui.updateUIForItem(this);
            return;
        }
        // Escort tab buttons
        if (id.startsWith("ep_")) {
            handleEscortButton(id, ui);
            return;
        }

        FormationConfig config = FormationConfig.getOrCreate();
        WingFormation   wing   = config.getActiveFormation();

        if (id.startsWith("wing_")) {
            config.setActiveWing(Integer.parseInt(id.substring(5)));
            selectedRow = -1; selectedCol = -1;
            config.save();
        } else if (id.startsWith("slot_")) {
            String[] p = id.split("_");
            selectedRow = Integer.parseInt(p[1]);
            selectedCol = Integer.parseInt(p[2]);
        } else if (id.startsWith("tight_")) {
            if (selectedRow >= 0) {
                wing.getSlot(selectedRow, selectedCol).tightness =
                        Integer.parseInt(id.substring(6));
                config.save();
            }
        } else if (id.equals("clear_slot")) {
            if (selectedRow >= 0) {
                wing.getSlot(selectedRow, selectedCol).clear();
                config.save();
            }
        } else if (id.equals("set_admiral")) {
            if (selectedRow >= 0) {
                // Clear admiral from all other slots first
                for (int r = 0; r < WingFormation.ROWS; r++) {
                    for (int c = 0; c < WingFormation.COLS; c++) {
                        wing.getSlot(r, c).isAdmiral = false;
                    }
                }
                // Toggle on this slot
                SlotData s = wing.getSlot(selectedRow, selectedCol);
                s.isAdmiral = true;
                config.save();
            }
        } else if (id.startsWith("ship_")) {
            if (selectedRow >= 0) {
                String memberId = id.substring(5);
                // Remove from any other slot first (unique assignment)
                for (int r = 0; r < WingFormation.ROWS; r++) {
                    for (int c = 0; c < WingFormation.COLS; c++) {
                        if (r == selectedRow && c == selectedCol) continue;
                        SlotData other = wing.getSlot(r, c);
                        if (memberId.equals(other.shipId)) other.shipId = null;
                    }
                }
                wing.getSlot(selectedRow, selectedCol).assignShip(memberId);
                // Mutual exclusivity: remove from ALL escort roles (as escort AND as principal)
                EscortConfig eConf = EscortConfig.getOrCreate();
                eConf.removeShipFromAllGroups(memberId);
                eConf.save();
                config.save();
            }
        } else if (id.startsWith("cls_")) {
            if (selectedRow >= 0) {
                wing.getSlot(selectedRow, selectedCol).assignClass(id.substring(4));
                config.save();
            }
        }
        ui.updateUIForItem(this);
    }

    private String slotShort(SlotData s) {
        String mode;
        if (s.tightness == SlotData.LOCK)        mode = "LCK";
        else if (s.tightness == SlotData.HOLD)   mode = "HLD";
        else                                      mode = "DEF";
        if (s.isEmpty())         return "---";           // empty = dashes
        if (s.isAdmiral)         return "ADM " + mode;   // admiral = ADM
        if (s.shipId    != null) return "SHP " + mode;   // ship assigned = SHP
        if (s.shipClass != null) return abbrev(s.shipClass) + " " + mode;
        return "?";
    }

    /** Returns [row,col] if this shipId is assigned to any slot, or null. */
    // ── Escort tab rendering ─────────────────────────────────────────────────

    private void renderEscortTab(CustomPanelAPI panel, FormationConfig fConfig,
                                  float width, float height, float yOff) {
        EscortConfig eConfig = EscortConfig.getOrCreate();
        List<FleetMemberAPI> members =
                Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy();

        float leftW  = 220f;
        float rightX = leftW + 15f;
        float rightW = width - rightX - 5f;

        // ── Left: principal list ──────────────────────────────────────────────
        TooltipMakerAPI left = panel.createUIElement(leftW, height - yOff, true);
        left.addSectionHeading("Principal Ships", Alignment.LMID, 0f);
        left.addPara("Pick a ship to escort:", 4f);

        for (FleetMemberAPI m : members) {
            String pid = m.getId();
            boolean isSel = pid.equals(selectedPrincipalId);
            boolean hasGroup = eConfig.findGroupForPrincipal(pid) != null;
            EscortGroup asEscort = eConfig.findGroupForEscort(pid);
            String prefix = isSel ? "> " : "  ";
            String suffix = hasGroup ? " [+" + eConfig.getOrCreateGroup(pid).getAssignedCount() + "]"
                           : asEscort != null ? " [ESC]" : "";
            left.addButton(prefix + m.getShipName() + " (" + m.getHullSpec().getHullName() + ")" + suffix,
                    "ep_principal_" + pid, leftW - 10f, BH, 2f);
        }
        panel.addUIElement(left).inTL(0f, yOff);

        // ── Right: escort slot editor for selected principal ──────────────────
        if (selectedPrincipalId == null) {
            TooltipMakerAPI hint = panel.createUIElement(rightW, 60f, false);
            hint.addPara("Select a ship on the left to assign escorts.", 4f);
            panel.addUIElement(hint).inTL(rightX, yOff);
            return;
        }

        EscortGroup group = eConfig.getOrCreateGroup(selectedPrincipalId);
        TooltipMakerAPI right = panel.createUIElement(rightW, height - yOff, true);

        // Find principal name
        String principalName = selectedPrincipalId;
        for (FleetMemberAPI m : members) {
            if (m.getId().equals(selectedPrincipalId)) {
                principalName = m.getShipName(); break;
            }
        }
        right.addSectionHeading("Escorts for " + principalName, Alignment.LMID, 0f);

        // Escort slots
        for (int i = 0; i < EscortGroup.MAX_ESCORTS; i++) {
            EscortSlot slot = group.slots.get(i);
            boolean isSel = (selectedEscortSlot == i);
            String slotLabel = slot.isEmpty() ? "Slot " + (i+1) + ": ---"
                    : "Slot " + (i+1) + ": " + escortShipName(slot, members)
                      + " [" + slot.role.name().substring(0,3) + "]";
            right.addButton((isSel ? "> " : "  ") + slotLabel,
                    "ep_slot_" + i, rightW - 10f, BH, 2f);
        }

        if (selectedEscortSlot >= 0) {
            EscortSlot sel = group.slots.get(selectedEscortSlot);
            right.addSectionHeading("Role", Alignment.LMID, 8f);
            String[] roleLabels = {"Guard", "Support", "Vanilla Escort"};
            EscortRole[] roles  = {EscortRole.GUARD, EscortRole.SUPPORT, EscortRole.VANILLA_ESCORT};
            String[] roleDescs  = {
                "Rear/flank positioning, holds position",
                "Rear slot, mirrors principal target",
                "Vanilla escort AI, no position override"
            };
            for (int ri = 0; ri < roles.length; ri++) {
                String prefix = (sel.role == roles[ri]) ? "> " : "  ";
                right.addButton(prefix + roleLabels[ri] + " - " + roleDescs[ri],
                        "ep_role_" + roles[ri].name(), rightW - 10f, BH, 2f);
            }
            right.addSectionHeading("Assign Ship", Alignment.LMID, 8f);
            for (FleetMemberAPI m : members) {
                if (m.getId().equals(selectedPrincipalId)) continue;
                boolean assigned = m.getId().equals(sel.shipId);
                EscortGroup otherGroup = eConfig.findGroupForEscort(m.getId());
                EscortGroup asPrincipal = eConfig.findGroupForPrincipal(m.getId());
                String loc = assigned ? "> "
                        : otherGroup != null ? "[ESC] "
                        : asPrincipal != null ? "[PRI] " : "  ";
                right.addButton(loc + m.getShipName() + " (" + m.getHullSpec().getHullName() + ")",
                        "ep_assign_" + m.getId(), rightW - 10f, BH, 2f);
            }
            if (!sel.isEmpty()) {
                right.addButton("Clear slot", "ep_clear", rightW - 10f, BH, 6f);
            }
        }

        panel.addUIElement(right).inTL(rightX, yOff);
    }

    private String escortShipName(EscortSlot slot, List<FleetMemberAPI> members) {
        if (slot.shipId != null) {
            for (FleetMemberAPI m : members) {
                if (m.getId().equals(slot.shipId)) return m.getShipName();
            }
            return "Unknown";
        }
        return slot.shipClass != null ? slot.shipClass : "?";
    }

    private void handleEscortButton(String id, IntelUIAPI ui) {
        EscortConfig eConfig   = EscortConfig.getOrCreate();
        FormationConfig fConfig = FormationConfig.getOrCreate();

        if (id.startsWith("ep_principal_")) {
            String pid = id.substring("ep_principal_".length());
            selectedPrincipalId = pid.equals(selectedPrincipalId) ? null : pid;
            selectedEscortSlot  = -1;
            ui.updateUIForItem(this);
            return;
        }
        if (id.startsWith("ep_slot_")) {
            int idx = Integer.parseInt(id.substring("ep_slot_".length()));
            selectedEscortSlot = (selectedEscortSlot == idx) ? -1 : idx;
            ui.updateUIForItem(this);
            return;
        }
        if (id.startsWith("ep_role_") && selectedPrincipalId != null
                && selectedEscortSlot >= 0) {
            EscortRole role = EscortRole.valueOf(id.substring("ep_role_".length()));
            eConfig.getOrCreateGroup(selectedPrincipalId)
                   .slots.get(selectedEscortSlot).role = role;
            eConfig.save();
            ui.updateUIForItem(this);
            return;
        }
        if (id.startsWith("ep_assign_") && selectedPrincipalId != null
                && selectedEscortSlot >= 0) {
            String shipId = id.substring("ep_assign_".length());

            // Mutual exclusivity: remove from formation slots AND other escort groups FIRST
            fConfig.removeShipFromAllSlots(shipId);
            eConfig.removeShipFromAllGroups(shipId);

            // Also remove principal from formation slots if it's there
            fConfig.removeShipFromAllSlots(selectedPrincipalId);

            // Get or create group AFTER removal (removal may have wiped it)
            EscortGroup group = eConfig.getOrCreateGroup(selectedPrincipalId);
            group.slots.get(selectedEscortSlot).shipId = shipId;
            eConfig.save();
            fConfig.save();
            ui.updateUIForItem(this);
            return;
        }
        if (id.equals("ep_clear") && selectedPrincipalId != null
                && selectedEscortSlot >= 0) {
            eConfig.getOrCreateGroup(selectedPrincipalId)
                   .slots.get(selectedEscortSlot).clear();
            eConfig.save();
            ui.updateUIForItem(this);
        }
    }

    private int[] findAssignedSlot(WingFormation wing, String shipId) {
        for (int r = 0; r < WingFormation.ROWS; r++) {
            for (int c = 0; c < WingFormation.COLS; c++) {
                if (shipId.equals(wing.getSlot(r, c).shipId)) return new int[]{r, c};
            }
        }
        return null;
    }

    private String abbrev(String c) {
        if ("CAPITAL_SHIP".equals(c)) return "CAP";
        if ("CRUISER".equals(c))      return "CRU";
        if ("DESTROYER".equals(c))    return "DES";
        if ("FRIGATE".equals(c))      return "FRI";
        return c;
    }
}
