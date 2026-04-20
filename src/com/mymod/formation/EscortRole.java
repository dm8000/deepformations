package com.mymod.formation;

public enum EscortRole {
    GUARD,          // dynamic rear/flank slots, fights threats to principal
    SUPPORT,        // fixed rear slot, mirrors principal's target
    VANILLA_ESCORT  // uses vanilla escort assignment, no position override
}
