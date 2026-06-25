package com.colorpop.rush;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure-Java model of the Color Pop Rush board.
 *
 * The grid stores a colour index for every cell ({@link #EMPTY} when no bubble
 * is present). Row 0 is the top row; gravity pulls bubbles towards the bottom
 * (highest row index). The class deliberately has no Android dependencies so
 * the game rules can be unit-tested on a plain JVM.
 */
public class Board {

    /** Marker for an empty cell. */
    public static final int EMPTY = -1;

    public final int cols;
    public final int rows;

    private final int[][] cells;   // [row][col] -> colour index or EMPTY
    private final int[][] srcRow;  // [row][col] -> row a cell animated *from* after the last collapse
    private int numColors;
    private final Random rng;

    public Board(int cols, int rows, int numColors, long seed) {
        this.cols = cols;
        this.rows = rows;
        this.numColors = Math.max(2, numColors);
        this.cells = new int[rows][cols];
        this.srcRow = new int[rows][cols];
        this.rng = new Random(seed);
        fill();
    }

    /** Re-randomise every cell, guaranteeing at least one legal move exists. */
    public final void fill() {
        do {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    cells[r][c] = rng.nextInt(numColors);
                    srcRow[r][c] = r;
                }
            }
        } while (!hasMove());
    }

    public int colorAt(int r, int c) {
        return cells[r][c];
    }

    /** Source row (for fall animation) the cell at (r,c) came from after collapse. */
    public int srcRowOf(int r, int c) {
        return srcRow[r][c];
    }

    public int getNumColors() {
        return numColors;
    }

    private boolean inBounds(int r, int c) {
        return r >= 0 && r < rows && c >= 0 && c < cols;
    }

    /**
     * Flood-fill the maximal connected (4-directional) group of same-coloured
     * bubbles that contains (r,c). Returns the cells as {row,col} pairs. A
     * single isolated bubble yields a list of size 1.
     */
    public List<int[]> group(int r, int c) {
        List<int[]> out = new ArrayList<int[]>();
        if (!inBounds(r, c) || cells[r][c] == EMPTY) {
            return out;
        }
        int color = cells[r][c];
        boolean[][] seen = new boolean[rows][cols];
        ArrayDeque<int[]> stack = new ArrayDeque<int[]>();
        stack.push(new int[]{r, c});
        seen[r][c] = true;
        final int[] dr = {-1, 1, 0, 0};
        final int[] dc = {0, 0, -1, 1};
        while (!stack.isEmpty()) {
            int[] cur = stack.pop();
            out.add(cur);
            for (int i = 0; i < 4; i++) {
                int nr = cur[0] + dr[i];
                int nc = cur[1] + dc[i];
                if (inBounds(nr, nc) && !seen[nr][nc] && cells[nr][nc] == color) {
                    seen[nr][nc] = true;
                    stack.push(new int[]{nr, nc});
                }
            }
        }
        return out;
    }

    /** True if tapping (r,c) would pop something (group of two or more). */
    public boolean isPoppable(int r, int c) {
        return group(r, c).size() >= 2;
    }

    /** Clear every cell in the supplied list. */
    public void clear(List<int[]> group) {
        for (int[] cell : group) {
            cells[cell[0]][cell[1]] = EMPTY;
        }
    }

    /** Clear a single cell (used by the Hammer booster). */
    public void clearCell(int r, int c) {
        if (inBounds(r, c)) {
            cells[r][c] = EMPTY;
        }
    }

    /** Collect every cell currently holding the given colour (Rainbow booster). */
    public List<int[]> cellsOfColor(int color) {
        List<int[]> out = new ArrayList<int[]>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (cells[r][c] == color) {
                    out.add(new int[]{r, c});
                }
            }
        }
        return out;
    }

    /** Collect cells in a square radius around (r,c) (Bomb booster). */
    public List<int[]> cellsInBlast(int r, int c, int radius) {
        List<int[]> out = new ArrayList<int[]>();
        for (int rr = r - radius; rr <= r + radius; rr++) {
            for (int cc = c - radius; cc <= c + radius; cc++) {
                if (inBounds(rr, cc) && cells[rr][cc] != EMPTY) {
                    out.add(new int[]{rr, cc});
                }
            }
        }
        return out;
    }

    /**
     * Apply gravity then refill empty cells from the top with new random
     * bubbles, so the board is always full. Records, for every resulting cell,
     * the row it should visually fall from (negative rows start above the
     * board) in {@link #srcRow} for the renderer to animate.
     */
    public void collapse() {
        int[][] next = new int[rows][cols];
        int[][] src = new int[rows][cols];
        for (int c = 0; c < cols; c++) {
            int writeRow = rows - 1;
            // Survivors fall straight down, preserving order.
            for (int r = rows - 1; r >= 0; r--) {
                if (cells[r][c] != EMPTY) {
                    next[writeRow][c] = cells[r][c];
                    src[writeRow][c] = r;
                    writeRow--;
                }
            }
            // Remaining top rows (0..writeRow) get fresh bubbles entering from above.
            int needed = writeRow + 1;
            for (int r = writeRow; r >= 0; r--) {
                next[r][c] = rng.nextInt(numColors);
                src[r][c] = r - needed; // negative => above the visible top
            }
        }
        for (int r = 0; r < rows; r++) {
            System.arraycopy(next[r], 0, cells[r], 0, cols);
            System.arraycopy(src[r], 0, srcRow[r], 0, cols);
        }
        if (!hasMove()) {
            shuffle();
        }
    }

    /** True if at least one group of two or more same-coloured bubbles exists. */
    public boolean hasMove() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = cells[r][c];
                if (v == EMPTY) {
                    continue;
                }
                if (c + 1 < cols && cells[r][c + 1] == v) {
                    return true;
                }
                if (r + 1 < rows && cells[r + 1][c] == v) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Randomly permute all bubbles until a legal move exists. Used as a safety
     * net on the rare occasion a refill leaves the board with no groups.
     */
    public void shuffle() {
        List<Integer> bag = new ArrayList<Integer>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (cells[r][c] != EMPTY) {
                    bag.add(cells[r][c]);
                }
            }
        }
        int attempts = 0;
        do {
            for (int i = bag.size() - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = bag.get(i);
                bag.set(i, bag.get(j));
                bag.set(j, tmp);
            }
            int k = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (cells[r][c] != EMPTY) {
                        cells[r][c] = bag.get(k++);
                    }
                }
            }
            attempts++;
        } while (!hasMove() && attempts < 40);
    }
}
