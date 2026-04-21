package com.mymod.formation;

import java.io.Serializable;

/**
 * One of 9 wing formations. Holds a 7×7 grid of SlotData.
 * Row 3, Col 3 is the commander/anchor position.
 * Row 0 = Front, Row 6 = Rear. Col 0 = Port, Col 6 = Starboard.
 * Center is row 3, col 3. Row 0 = far front, row 6 = far rear. Col 0 = far port, col 6 = far starboard.
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
