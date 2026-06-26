package com.colorpop.rush;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure-Java model of the Color Pop Rush board.
 *
 * The grid stores a colour index for every cell ({@link #EMPTY} when no bubble
 * is present) plus a parallel "type" grid for special power tiles. Row 0 is the
 * top row; gravity pulls bubbles towards the bottom (highest row index). The
 * class deliberately has no Android dependencies so the game rules can be
 * unit-tested on a plain JVM.
 */
public class Board {

    /** Marker for an empty cell. */
    public static final int EMPTY = -1;

    // Power-tile types (parallel to colour).
    public static final int T_NORMAL = 0;
    public static final int T_ROCKET_H = 1; // clears its row
    public static final int T_ROCKET_V = 2; // clears its column
    public static final int T_BOMB = 3;     // clears a 5x5 area
    public static final int T_RAINBOW = 4;  // clears all bubbles of its colour

    public final int cols;
    public final int rows;

    private final int[][] cells;   // [row][col] -> colour index or EMPTY
    private final int[][] type;    // [row][col] -> T_* power type
    private final int[][] srcRow;  // [row][col] -> row a cell animated *from* after the last collapse
    private int numColors;
    private final Random rng;

    public Board(int cols, int rows, int numColors, long seed) {
        this.cols = cols;
        this.rows = rows;
        this.numColors = Math.max(2, numColors);
        this.cells = new int[rows][cols];
        this.type = new int[rows][cols];
        this.srcRow = new int[rows][cols];
        this.rng = new Random(seed);
        fill();
    }

    /**
     * Re-randomise every cell. Anti-frustration: guarantees at least one legal
     * move, several distinct moves, and that no single group dominates the board
     * (which would make the opening trivial).
     */
    public final void fill() {
        int attempts = 0;
        do {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    cells[r][c] = rng.nextInt(numColors);
                    type[r][c] = T_NORMAL;
                    srcRow[r][c] = r;
                }
            }
            attempts++;
        } while ((!hasMove() || countMoves() < 3 || maxGroupSize() > (cols * rows * 2) / 5)
                && attempts < 200);
        if (!hasMove()) {
            shuffle();
        }
    }

    public int colorAt(int r, int c) {
        return cells[r][c];
    }

    public int typeAt(int r, int c) {
        return inBounds(r, c) ? type[r][c] : T_NORMAL;
    }

    /** Mark a cell as a power tile (keeps its colour). */
    public void setType(int r, int c, int t) {
        if (inBounds(r, c) && cells[r][c] != EMPTY) {
            type[r][c] = t;
        }
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
     * bubbles that contains (r,c). Returns the cells as {row,col} pairs.
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

    /** The poppable group at (r,c), or an empty list if tapping there pops nothing. */
    public List<int[]> previewGroup(int r, int c) {
        List<int[]> g = group(r, c);
        return g.size() >= 2 ? g : new ArrayList<int[]>();
    }

    /** True if tapping (r,c) would pop something (group of two or more). */
    public boolean isPoppable(int r, int c) {
        return group(r, c).size() >= 2;
    }

    /** Clear every cell in the supplied list. */
    public void clear(List<int[]> group) {
        for (int[] cell : group) {
            cells[cell[0]][cell[1]] = EMPTY;
            type[cell[0]][cell[1]] = T_NORMAL;
        }
    }

    /** Clear a single cell (used by the Hammer booster). */
    public void clearCell(int r, int c) {
        if (inBounds(r, c)) {
            cells[r][c] = EMPTY;
            type[r][c] = T_NORMAL;
        }
    }

    /** Collect every cell currently holding the given colour (Rainbow booster/tile). */
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

    /** Collect cells in a square radius around (r,c) (Bomb booster/tile). */
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

    /** The cells a power tile at (r,c) clears, by its type. */
    public List<int[]> cellsForPower(int r, int c) {
        List<int[]> out = new ArrayList<int[]>();
        if (!inBounds(r, c)) {
            return out;
        }
        int t = type[r][c];
        if (t == T_ROCKET_H) {
            for (int cc = 0; cc < cols; cc++) {
                if (cells[r][cc] != EMPTY) {
                    out.add(new int[]{r, cc});
                }
            }
        } else if (t == T_ROCKET_V) {
            for (int rr = 0; rr < rows; rr++) {
                if (cells[rr][c] != EMPTY) {
                    out.add(new int[]{rr, c});
                }
            }
        } else if (t == T_BOMB) {
            return cellsInBlast(r, c, 2);
        } else if (t == T_RAINBOW) {
            if (cells[r][c] != EMPTY) {
                return cellsOfColor(cells[r][c]);
            }
        }
        return out;
    }

    /**
     * Pick the most useful poppable group for an idle hint: biggest group, with
     * a bonus for matching the level's goal colour. Returns the group cells, or
     * an empty list if (somehow) no move exists.
     */
    public List<int[]> bestHintGroup(int goalColor) {
        boolean[][] seen = new boolean[rows][cols];
        List<int[]> best = new ArrayList<int[]>();
        int bestScore = -1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (seen[r][c] || cells[r][c] == EMPTY) {
                    continue;
                }
                List<int[]> g = group(r, c);
                int color = cells[r][c];
                for (int[] cell : g) {
                    seen[cell[0]][cell[1]] = true;
                }
                if (g.size() >= 2) {
                    int sc = g.size() * 10 + (color == goalColor ? 5 : 0)
                            + (g.size() >= 5 ? (g.size() - 4) * 4 : 0);
                    if (sc > bestScore) {
                        bestScore = sc;
                        best = g;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Apply gravity then refill empty cells from the top with new random
     * bubbles. Power-tile types travel with their bubble; new bubbles are normal.
     */
    public void collapse() {
        int[][] next = new int[rows][cols];
        int[][] nextType = new int[rows][cols];
        int[][] src = new int[rows][cols];
        for (int c = 0; c < cols; c++) {
            int writeRow = rows - 1;
            for (int r = rows - 1; r >= 0; r--) {
                if (cells[r][c] != EMPTY) {
                    next[writeRow][c] = cells[r][c];
                    nextType[writeRow][c] = type[r][c];
                    src[writeRow][c] = r;
                    writeRow--;
                }
            }
            int needed = writeRow + 1;
            for (int r = writeRow; r >= 0; r--) {
                next[r][c] = rng.nextInt(numColors);
                nextType[r][c] = T_NORMAL;
                src[r][c] = r - needed; // negative => above the visible top
            }
        }
        for (int r = 0; r < rows; r++) {
            System.arraycopy(next[r], 0, cells[r], 0, cols);
            System.arraycopy(nextType[r], 0, type[r], 0, cols);
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

    /** Number of distinct poppable groups (size >= 2). */
    public int countMoves() {
        boolean[][] seen = new boolean[rows][cols];
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (seen[r][c] || cells[r][c] == EMPTY) {
                    continue;
                }
                List<int[]> g = group(r, c);
                for (int[] cell : g) {
                    seen[cell[0]][cell[1]] = true;
                }
                if (g.size() >= 2) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Size of the largest connected same-colour group. */
    public int maxGroupSize() {
        boolean[][] seen = new boolean[rows][cols];
        int m = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (seen[r][c] || cells[r][c] == EMPTY) {
                    continue;
                }
                List<int[]> g = group(r, c);
                for (int[] cell : g) {
                    seen[cell[0]][cell[1]] = true;
                }
                if (g.size() > m) {
                    m = g.size();
                }
            }
        }
        return m;
    }

    /**
     * Randomly permute all bubbles (keeping their power types attached to their
     * colour) until a legal move exists. Used by the Shuffle booster and as a
     * safety net when a refill leaves no groups.
     */
    public void shuffle() {
        List<int[]> bag = new ArrayList<int[]>(); // {color,type}
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (cells[r][c] != EMPTY) {
                    bag.add(new int[]{cells[r][c], type[r][c]});
                }
            }
        }
        int attempts = 0;
        do {
            for (int i = bag.size() - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int[] tmp = bag.get(i);
                bag.set(i, bag.get(j));
                bag.set(j, tmp);
            }
            int k = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (cells[r][c] != EMPTY) {
                        cells[r][c] = bag.get(k)[0];
                        type[r][c] = bag.get(k)[1];
                        srcRow[r][c] = r;
                        k++;
                    }
                }
            }
            attempts++;
        } while (!hasMove() && attempts < 40);
    }
}
