package com.colorpop.rush;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure-Java model of the Color Pop Rush board.
 *
 * Grids (all [row][col], row 0 = top, gravity pulls toward the bottom):
 *  - cells: colour index, or {@link #EMPTY}.
 *  - type:  power-tile kind (T_* constants), travels with its bubble.
 *  - lock:  obstacle "chain" level. A locked bubble (lock&gt;0) cannot be popped
 *           or matched; adjacent pops chip a lock level off it, and at 0 it
 *           becomes a normal poppable bubble. Locked bubbles fall with gravity.
 *
 * No Android dependencies — the rules are unit-tested on a plain JVM.
 */
public class Board {

    public static final int EMPTY = -1;

    public static final int T_NORMAL = 0;
    public static final int T_ROCKET_H = 1; // clears its row
    public static final int T_ROCKET_V = 2; // clears its column
    public static final int T_BOMB = 3;     // clears a 5x5 area
    public static final int T_RAINBOW = 4;  // clears all bubbles of its colour

    public final int cols;
    public final int rows;

    private final int[][] cells;
    private final int[][] type;
    private final int[][] lock;
    private final int[][] srcRow;
    private int numColors;
    private final Random rng;

    public Board(int cols, int rows, int numColors, long seed) {
        this.cols = cols;
        this.rows = rows;
        this.numColors = Math.max(2, numColors);
        this.cells = new int[rows][cols];
        this.type = new int[rows][cols];
        this.lock = new int[rows][cols];
        this.srcRow = new int[rows][cols];
        this.rng = new Random(seed);
        fill();
    }

    /** Randomise the board with anti-frustration guarantees (>=3 moves, no dominating group). */
    public final void fill() {
        int attempts = 0;
        do {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    cells[r][c] = rng.nextInt(numColors);
                    type[r][c] = T_NORMAL;
                    lock[r][c] = 0;
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

    // --- Accessors --------------------------------------------------------

    public int colorAt(int r, int c) {
        return cells[r][c];
    }

    public int typeAt(int r, int c) {
        return inBounds(r, c) ? type[r][c] : T_NORMAL;
    }

    public int lockAt(int r, int c) {
        return inBounds(r, c) ? lock[r][c] : 0;
    }

    public boolean isLocked(int r, int c) {
        return inBounds(r, c) && lock[r][c] > 0;
    }

    /** A bubble that can currently be tapped/matched (present and not locked). */
    public boolean isPlayable(int r, int c) {
        return inBounds(r, c) && cells[r][c] != EMPTY && lock[r][c] == 0;
    }

    public void setType(int r, int c, int t) {
        if (inBounds(r, c) && cells[r][c] != EMPTY) {
            type[r][c] = t;
        }
    }

    public void setLock(int r, int c, int n) {
        if (inBounds(r, c) && cells[r][c] != EMPTY) {
            lock[r][c] = Math.max(0, n);
        }
    }

    public int srcRowOf(int r, int c) {
        return srcRow[r][c];
    }

    public int getNumColors() {
        return numColors;
    }

    private boolean inBounds(int r, int c) {
        return r >= 0 && r < rows && c >= 0 && c < cols;
    }

    // --- Matching ---------------------------------------------------------

    /** Maximal connected group of same-coloured PLAYABLE bubbles containing (r,c). */
    public List<int[]> group(int r, int c) {
        List<int[]> out = new ArrayList<int[]>();
        if (!isPlayable(r, c)) {
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
                if (inBounds(nr, nc) && !seen[nr][nc] && isPlayable(nr, nc) && cells[nr][nc] == color) {
                    seen[nr][nc] = true;
                    stack.push(new int[]{nr, nc});
                }
            }
        }
        return out;
    }

    public List<int[]> previewGroup(int r, int c) {
        List<int[]> g = group(r, c);
        return g.size() >= 2 ? g : new ArrayList<int[]>();
    }

    public boolean isPoppable(int r, int c) {
        return group(r, c).size() >= 2;
    }

    public void clear(List<int[]> group) {
        for (int[] cell : group) {
            cells[cell[0]][cell[1]] = EMPTY;
            type[cell[0]][cell[1]] = T_NORMAL;
            lock[cell[0]][cell[1]] = 0;
        }
    }

    public void clearCell(int r, int c) {
        if (inBounds(r, c)) {
            cells[r][c] = EMPTY;
            type[r][c] = T_NORMAL;
            lock[r][c] = 0;
        }
    }

    /** Cells holding the given colour that are PLAYABLE (Rainbow). */
    public List<int[]> cellsOfColor(int color) {
        List<int[]> out = new ArrayList<int[]>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (cells[r][c] == color && lock[r][c] == 0) {
                    out.add(new int[]{r, c});
                }
            }
        }
        return out;
    }

    /** Playable cells in a square radius around (r,c) (Bomb). */
    public List<int[]> cellsInBlast(int r, int c, int radius) {
        List<int[]> out = new ArrayList<int[]>();
        for (int rr = r - radius; rr <= r + radius; rr++) {
            for (int cc = c - radius; cc <= c + radius; cc++) {
                if (isPlayable(rr, cc)) {
                    out.add(new int[]{rr, cc});
                }
            }
        }
        return out;
    }

    /** Cells a power tile at (r,c) clears, by its type (playable cells only). */
    public List<int[]> cellsForPower(int r, int c) {
        List<int[]> out = new ArrayList<int[]>();
        if (!inBounds(r, c)) {
            return out;
        }
        int t = type[r][c];
        if (t == T_ROCKET_H) {
            for (int cc = 0; cc < cols; cc++) {
                if (isPlayable(r, cc)) {
                    out.add(new int[]{r, cc});
                }
            }
        } else if (t == T_ROCKET_V) {
            for (int rr = 0; rr < rows; rr++) {
                if (isPlayable(rr, c)) {
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

    /** Combined effect when a power tile at (r1,c1) fuses into one at (r2,c2). Detonates at (r2,c2). */
    public List<int[]> cellsForFusion(int r1, int c1, int r2, int c2) {
        boolean[][] in = new boolean[rows][cols];
        List<int[]> out = new ArrayList<int[]>();
        int a = typeAt(r1, c1), b = typeAt(r2, c2);
        boolean rocketA = (a == T_ROCKET_H || a == T_ROCKET_V);
        boolean rocketB = (b == T_ROCKET_H || b == T_ROCKET_V);
        if (a == T_RAINBOW || b == T_RAINBOW) {
            int other = (a == T_RAINBOW) ? b : a;
            if (a == T_RAINBOW && b == T_RAINBOW) {
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        addUnique(r, c, in, out);
                    }
                }
            } else {
                // Rainbow + power: clear the partner's colour, turning each into the partner power.
                int col = cells[r2][c2] != EMPTY ? cells[r2][c2] : cells[r1][c1];
                for (int[] cell : cellsOfColor(col)) {
                    addUnique(cell[0], cell[1], in, out);
                }
                addBlastShape(r2, c2, other, in, out);
            }
            return out;
        }
        if (rocketA && rocketB) {
            for (int cc = 0; cc < cols; cc++) {
                addUnique(r2, cc, in, out);
            }
            for (int rr = 0; rr < rows; rr++) {
                addUnique(rr, c2, in, out);
            }
        } else if ((rocketA && b == T_BOMB) || (rocketB && a == T_BOMB)) {
            for (int rr = r2 - 1; rr <= r2 + 1; rr++) {
                for (int cc = 0; cc < cols; cc++) {
                    addUnique(rr, cc, in, out);
                }
            }
            for (int cc = c2 - 1; cc <= c2 + 1; cc++) {
                for (int rr = 0; rr < rows; rr++) {
                    addUnique(rr, cc, in, out);
                }
            }
        } else { // bomb + bomb
            for (int[] cell : cellsInBlast(r2, c2, 3)) {
                addUnique(cell[0], cell[1], in, out);
            }
        }
        return out;
    }

    private void addBlastShape(int r, int c, int t, boolean[][] in, List<int[]> out) {
        if (t == T_ROCKET_H) {
            for (int cc = 0; cc < cols; cc++) {
                addUnique(r, cc, in, out);
            }
        } else if (t == T_ROCKET_V) {
            for (int rr = 0; rr < rows; rr++) {
                addUnique(rr, c, in, out);
            }
        } else if (t == T_BOMB) {
            for (int[] cell : cellsInBlast(r, c, 2)) {
                addUnique(cell[0], cell[1], in, out);
            }
        }
    }

    private void addUnique(int r, int c, boolean[][] in, List<int[]> out) {
        if (isPlayable(r, c) && !in[r][c]) {
            in[r][c] = true;
            out.add(new int[]{r, c});
        }
    }

    /** Swap two adjacent playable bubbles (colour+power travel together). */
    public boolean swap(int r1, int c1, int r2, int c2) {
        if (!isPlayable(r1, c1) || !isPlayable(r2, c2)
                || Math.abs(r1 - r2) + Math.abs(c1 - c2) != 1) {
            return false;
        }
        int tc = cells[r1][c1];
        cells[r1][c1] = cells[r2][c2];
        cells[r2][c2] = tc;
        int tt = type[r1][c1];
        type[r1][c1] = type[r2][c2];
        type[r2][c2] = tt;
        srcRow[r1][c1] = r1;
        srcRow[r2][c2] = r2;
        return true;
    }

    // --- Obstacles --------------------------------------------------------

    /** Seed {@code count} locked bubbles (each lock level {@code hp}) on random playable cells. */
    public void seedLocks(int count, int hp, Random r) {
        if (count <= 0 || hp <= 0) {
            return;
        }
        List<int[]> spots = new ArrayList<int[]>();
        for (int rr = 0; rr < rows; rr++) {
            for (int cc = 0; cc < cols; cc++) {
                if (cells[rr][cc] != EMPTY) {
                    spots.add(new int[]{rr, cc});
                }
            }
        }
        // Fisher-Yates partial shuffle.
        for (int i = spots.size() - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int[] t = spots.get(i);
            spots.set(i, spots.get(j));
            spots.set(j, t);
        }
        int placed = 0;
        for (int[] s : spots) {
            if (placed >= count) {
                break;
            }
            lock[s[0]][s[1]] = hp;
            placed++;
            if (!hasMove()) { // never lock away the last move
                lock[s[0]][s[1]] = 0;
                placed--;
            }
        }
    }

    public int lockedRemaining() {
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (lock[r][c] > 0) {
                    n++;
                }
            }
        }
        return n;
    }

    public int lockLevelsRemaining() {
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                n += lock[r][c];
            }
        }
        return n;
    }

    /**
     * Chip one lock level off every locked bubble orthogonally adjacent to a
     * just-cleared cell. Returns cells whose lock reached 0 (newly freed).
     */
    public List<int[]> damageLocksAround(List<int[]> cleared) {
        boolean[][] clearedAt = new boolean[rows][cols];
        for (int[] cell : cleared) {
            if (inBounds(cell[0], cell[1])) {
                clearedAt[cell[0]][cell[1]] = true;
            }
        }
        List<int[]> freed = new ArrayList<int[]>();
        final int[] dr = {-1, 1, 0, 0};
        final int[] dc = {0, 0, -1, 1};
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (lock[r][c] <= 0) {
                    continue;
                }
                boolean adj = false;
                for (int i = 0; i < 4; i++) {
                    int nr = r + dr[i], nc = c + dc[i];
                    if (inBounds(nr, nc) && clearedAt[nr][nc]) {
                        adj = true;
                        break;
                    }
                }
                if (adj) {
                    lock[r][c]--;
                    if (lock[r][c] == 0) {
                        freed.add(new int[]{r, c});
                    }
                }
            }
        }
        return freed;
    }

    // --- Hints / metrics --------------------------------------------------

    public List<int[]> bestHintGroup(int goalColor) {
        boolean[][] seen = new boolean[rows][cols];
        List<int[]> best = new ArrayList<int[]>();
        int bestScore = -1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (seen[r][c] || !isPlayable(r, c)) {
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

    /** A random occupied, non-power, playable cell (end-of-level sweep target), or null. */
    public int[] randomDetonateTarget(Random r) {
        List<int[]> spots = new ArrayList<int[]>();
        for (int rr = 0; rr < rows; rr++) {
            for (int cc = 0; cc < cols; cc++) {
                if (isPlayable(rr, cc) && type[rr][cc] == T_NORMAL) {
                    spots.add(new int[]{rr, cc});
                }
            }
        }
        return spots.isEmpty() ? null : spots.get(r.nextInt(spots.size()));
    }

    public void collapse() {
        int[][] nC = new int[rows][cols];
        int[][] nT = new int[rows][cols];
        int[][] nL = new int[rows][cols];
        int[][] src = new int[rows][cols];
        for (int c = 0; c < cols; c++) {
            int writeRow = rows - 1;
            for (int r = rows - 1; r >= 0; r--) {
                if (cells[r][c] != EMPTY) {
                    nC[writeRow][c] = cells[r][c];
                    nT[writeRow][c] = type[r][c];
                    nL[writeRow][c] = lock[r][c];
                    src[writeRow][c] = r;
                    writeRow--;
                }
            }
            int needed = writeRow + 1;
            for (int r = writeRow; r >= 0; r--) {
                nC[r][c] = rng.nextInt(numColors);
                nT[r][c] = T_NORMAL;
                nL[r][c] = 0;
                src[r][c] = r - needed;
            }
        }
        for (int r = 0; r < rows; r++) {
            System.arraycopy(nC[r], 0, cells[r], 0, cols);
            System.arraycopy(nT[r], 0, type[r], 0, cols);
            System.arraycopy(nL[r], 0, lock[r], 0, cols);
            System.arraycopy(src[r], 0, srcRow[r], 0, cols);
        }
        if (!hasMove()) {
            shuffle();
        }
    }

    public boolean hasMove() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!isPlayable(r, c)) {
                    continue;
                }
                int v = cells[r][c];
                if (c + 1 < cols && isPlayable(r, c + 1) && cells[r][c + 1] == v) {
                    return true;
                }
                if (r + 1 < rows && isPlayable(r + 1, c) && cells[r + 1][c] == v) {
                    return true;
                }
            }
        }
        return false;
    }

    public int countMoves() {
        boolean[][] seen = new boolean[rows][cols];
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (seen[r][c] || !isPlayable(r, c)) {
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

    public int maxGroupSize() {
        boolean[][] seen = new boolean[rows][cols];
        int m = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (seen[r][c] || !isPlayable(r, c)) {
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

    public void shuffle() {
        List<int[]> bag = new ArrayList<int[]>(); // {color,type,lock}
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (cells[r][c] != EMPTY) {
                    bag.add(new int[]{cells[r][c], type[r][c], lock[r][c]});
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
                        lock[r][c] = bag.get(k)[2];
                        srcRow[r][c] = r;
                        k++;
                    }
                }
            }
            attempts++;
        } while (!hasMove() && attempts < 40);
    }

    // --- Snapshot / restore (Undo) ---------------------------------------

    public static final class Snapshot {
        private final int[][] cells, type, lock, srcRow;
        private final int rows, cols, numColors;

        Snapshot(int[][] cells, int[][] type, int[][] lock, int[][] srcRow, int numColors) {
            this.rows = cells.length;
            this.cols = cells[0].length;
            this.cells = cells;
            this.type = type;
            this.lock = lock;
            this.srcRow = srcRow;
            this.numColors = numColors;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(copy(cells), copy(type), copy(lock), copy(srcRow), numColors);
    }

    public void restore(Snapshot s) {
        if (s == null || s.rows != rows || s.cols != cols) {
            return;
        }
        for (int r = 0; r < rows; r++) {
            System.arraycopy(s.cells[r], 0, cells[r], 0, cols);
            System.arraycopy(s.type[r], 0, type[r], 0, cols);
            System.arraycopy(s.lock[r], 0, lock[r], 0, cols);
            System.arraycopy(s.srcRow[r], 0, srcRow[r], 0, cols);
        }
        numColors = s.numColors;
    }

    private static int[][] copy(int[][] g) {
        int[][] out = new int[g.length][];
        for (int i = 0; i < g.length; i++) {
            out[i] = g[i].clone();
        }
        return out;
    }
}
