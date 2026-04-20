package com.mymod.formation;

import java.io.Serializable;

/**
 * One of 9 wing formations. Holds a 6x6 grid of SlotData.
 *
 * Row 0 = front (closest to enemies), Row 5 = rear.
 * Col 0 = port  (left when facing forward), Col 5 = starboard.
 */
public class WingFormation implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int ROWS = 7;
    public static final int COLS = 7;

    private final SlotData[][] grid = new SlotData[ROWS][COLS];

    public WingFormation() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                grid[r][c] = new SlotData();
            }
        }
    }

    public SlotData getSlot(int row, int col) {
        return grid[row][col];
    }

    /** Direct access to underlying grid for serialisation. */
    public SlotData[][] getGrid() {
        return grid;
    }

    /** Number of non-empty slots. */
    public int getAssignedCount() {
        int count = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (!grid[r][c].isEmpty()) count++;
            }
        }
        return count;
    }
}
