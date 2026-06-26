import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Faults;
import android.graphics.RectF;
import android.view.MotionEvent;

import com.colorpop.rush.Board;
import com.colorpop.rush.GameView;
import com.colorpop.rush.Level;
import com.colorpop.rush.Storage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * JVM execution proof. Drives the REAL Color Pop Rush game code (GameView + the
 * whole engine) against a hand-built Android shim through the exact same entry
 * points a device uses — layout/attach/onTouchEvent/doFrame/draw/onBack — across
 * every screen and a full set of playthroughs (locks/BREAK, power tiles, fusion,
 * all six boosters, undo, swap, pause, settings, daily, stats, moves-sweep, win,
 * fail+continue). GameView swallows exceptions via safeRecover(); the patched
 * copy + the shim record EVERY crash-class condition into Faults.LOG, so this
 * harness asserts the game never faulted anywhere.
 */
public class TestRun {

    static GameView v;
    static final Canvas canvas = new Canvas();
    static long nanos = 0;
    static final int W = 1080, H = 1920;
    static int frameCount = 0;
    static int faultsBefore = 0;

    // ---- reflection helpers -------------------------------------------------
    static Field fld(String n) {
        try { Field f = GameView.class.getDeclaredField(n); f.setAccessible(true); return f; }
        catch (Exception e) { throw new RuntimeException("no field " + n, e); }
    }
    static Object get(String n) { try { return fld(n).get(v); } catch (Exception e) { throw new RuntimeException(e); } }
    static int gi(String n)   { try { return fld(n).getInt(v); } catch (Exception e) { throw new RuntimeException(e); } }
    static float gf(String n) { try { return fld(n).getFloat(v); } catch (Exception e) { throw new RuntimeException(e); } }
    static void si(String n, int val) { try { fld(n).setInt(v, val); } catch (Exception e) { throw new RuntimeException(e); } }
    static String state() { return ((Enum<?>) get("state")).name(); }
    static Board board()  { return (Board) get("board"); }
    static Storage store(){ return (Storage) get("store"); }

    static Object invoke(String name, Class<?>[] sig, Object... args) {
        try { Method m = GameView.class.getDeclaredMethod(name, sig); m.setAccessible(true); return m.invoke(v, args); }
        catch (Exception e) { throw new RuntimeException("invoke " + name + " failed", e); }
    }
    static RectF rect(String name) { return (RectF) invoke(name, new Class<?>[]{}); }
    static RectF rect(String name, int i) { return (RectF) invoke(name, new Class<?>[]{int.class}, i); }

    // ---- driving the real entry points -------------------------------------
    static void frame() {
        nanos += 16_000_000L;
        v.doFrame(nanos);   // real frame callback: update() + invalidate()
        v.draw(canvas);     // real render: onDraw()
        frameCount++;
    }
    static void frames(int n) { for (int i = 0; i < n; i++) frame(); }

    /** Run frames until the level animation / sweep finishes (capped). */
    static void settle() {
        for (int i = 0; i < 600 && (state().equals("ANIM") || state().equals("SWEEP")); i++) frame();
        frame();
    }

    static void down(float x, float y) { v.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)); }
    static void up(float x, float y)   { v.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x, y, 0)); }

    static void tap(float x, float y) {
        down(x, y);
        frame();        // render the touch-down preview state
        up(x, y);
        frame();        // render the post-tap state
    }
    static void tapRect(String name) { RectF r = rect(name); tap(r.centerX(), r.centerY()); }
    static void tapRect(String name, int i) { RectF r = rect(name, i); tap(r.centerX(), r.centerY()); }

    static float cellX(int c) { return gf("boardX") + (c + 0.5f) * gf("cell"); }
    static float cellY(int r) { return gf("boardY") + (r + 0.5f) * gf("cell"); }
    static void tapCell(int r, int c) { tap(cellX(c), cellY(r)); settle(); }

    /** Keep a level from winning/losing during coverage so every path runs in PLAYING. */
    static void lockLevelOpen() { si("target", 99_999_999); si("collected", 0); si("movesLeft", 999); }

    static boolean lastEnteredAnim;
    /** Tap a board cell and capture whether it actually triggered a clear/detonation. */
    static void tapBoard(int r, int c) {
        tap(cellX(c), cellY(r));
        lastEnteredAnim = state().equals("ANIM"); // startClear sets ANIM before settling
        settle();
    }
    /** Assert the previous board tap really detonated (guards against no-op taps). */
    static void mustHavePopped(String label) {
        if (!lastEnteredAnim) { System.out.println("\n*** " + label + " did NOT enter ANIM (no-op tap!)"); FAILED = true; }
    }

    // ---- assertions ---------------------------------------------------------
    static int step = 0;
    static void checkpoint(String label) {
        step++;
        int now = Faults.count();
        if (now != faultsBefore) {
            System.out.println("\n*** FAULT after step " + step + " [" + label + "] state=" + state());
            for (int i = faultsBefore; i < now; i++) {
                System.out.println("    -> " + Faults.LOG.get(i));
            }
            FAILED = true;
            faultsBefore = now;
        } else {
            System.out.println(String.format("  ok %-34s state=%-9s frames=%d draws=%d", label, state(), frameCount, canvas.draws));
        }
    }
    static boolean FAILED = false;

    // ---- scenario helpers ---------------------------------------------------
    static int firstPoppable() {
        Board b = board();
        if (b == null) return -1;
        for (int r = 0; r < b.rows; r++)
            for (int c = 0; c < b.cols; c++)
                if (b.isPoppable(r, c)) return r * 1000 + c;
        return -1;
    }

    static void topUpBoosters() {
        Storage st = store();
        for (int i = 0; i < 6; i++) st.addBooster(i, 9);
        st.addCoins(5000);
    }

    static int firstBreakLevel() {
        for (int n = 1; n <= 120; n++) if (new Level(n).goalType == Level.BREAK) return n;
        return 48;
    }

    /** Play normal pops until the level ends or we run out of safe iterations. */
    static void playToEnd(int maxMoves) {
        for (int i = 0; i < maxMoves; i++) {
            if (!state().equals("PLAYING")) return;
            int p = firstPoppable();
            if (p < 0) return;
            tapCell(p / 1000, p % 1000);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Color Pop Rush — JVM execution proof ===");
        Context ctx = new Context();
        v = new GameView(ctx);
        v.layout(0, 0, W, H);   // -> onSizeChanged -> layoutBoard
        v.attach();             // -> onAttachedToWindow -> resume()
        faultsBefore = Faults.count();

        // ---------- MENU + its frames ----------
        frames(5);
        checkpoint("MENU idle");

        // ---------- DAILY ----------
        tapRect("menuDaily"); checkpoint("open DAILY");
        frames(3);
        tapRect("dailyClaim"); checkpoint("DAILY claim");
        tapRect("backBtn"); checkpoint("DAILY back -> MENU");

        // ---------- STATS (force chest claimable) ----------
        for (int lvl = 1; lvl <= 6; lvl++) store().recordResult(lvl, 3); // 18 stars -> chest unlocked
        tapRect("menuStats"); checkpoint("open STATS");
        frames(3);
        if (store().claimableChest() >= 0) { tapRect("statsChest"); checkpoint("STATS claim chest"); }
        tapRect("statsReset"); checkpoint("STATS reset (arm)");   // first tap arms
        frames(2);
        tapRect("backBtn"); checkpoint("STATS back -> MENU");

        // ---------- SETTINGS from menu ----------
        tapRect("menuSound"); checkpoint("open SETTINGS");
        tapRect("settingsToggle", 0); checkpoint("toggle sound");
        tapRect("settingsToggle", 1); checkpoint("toggle haptics");
        tapRect("settingsToggle", 2); checkpoint("toggle symbols");
        frames(2);
        tapRect("backBtn"); checkpoint("SETTINGS back -> MENU");

        // ---------- MAP ----------
        tapRect("menuPlay"); checkpoint("open MAP");
        frames(3);
        // scroll the map (MOVE drag)
        down(W / 2f, H * 0.7f);
        for (int i = 0; i < 6; i++) { v.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, W / 2f, H * 0.7f - i * 40, 0)); frame(); }
        up(W / 2f, H * 0.4f);
        checkpoint("MAP scroll");
        // tap level-1 node
        float nx = (Float) invoke("nodeX", new Class<?>[]{int.class}, 0);
        float ny = (Float) invoke("nodeY", new Class<?>[]{int.class}, 0);
        tap(nx, ny); checkpoint("MAP tap node1 -> PREGAME");

        // ---------- PREGAME ----------
        frames(3);
        // buy a booster slot (we have coins)
        RectF[] slots = (RectF[]) invoke("pregameBoosterSlots", new Class<?>[]{});
        tap(slots[0].centerX(), slots[0].centerY()); checkpoint("PREGAME buy booster");
        tapRect("pregamePlay"); checkpoint("PREGAME play -> PLAYING");

        // ---------- PLAYING: booster + power + fusion coverage ----------
        // Lock the level open so a big clear can't win it before everything is exercised.
        topUpBoosters();
        lockLevelOpen();
        RectF[] bar = (RectF[]) invoke("boosterBar", new Class<?>[]{});

        // normal pop (also seeds an undo snapshot)
        si("armed", -1);
        { int p = firstPoppable(); tapBoard(p / 1000, p % 1000); }
        mustHavePopped("normal pop"); checkpoint("normal pop");
        if (get("undoSnap") != null) { tapRect("undoBtn"); checkpoint("UNDO restores board"); }
        else { System.out.println("*** no undo snapshot after pop"); FAILED = true; }

        // BOMB booster (arm then tap a board cell)
        lockLevelOpen();
        tap(bar[0].centerX(), bar[0].centerY()); checkpoint("arm BOMB");
        { int p = firstPoppable(); tapBoard(p / 1000, p % 1000); } mustHavePopped("BOMB"); checkpoint("use BOMB blast");

        // RAINBOW booster
        lockLevelOpen();
        tap(bar[1].centerX(), bar[1].centerY()); checkpoint("arm RAINBOW");
        { int p = firstPoppable(); tapBoard(p / 1000, p % 1000); } mustHavePopped("RAINBOW"); checkpoint("use RAINBOW");

        // HAMMER booster (on a normal cell -> single clear)
        lockLevelOpen();
        tap(bar[2].centerX(), bar[2].centerY()); checkpoint("arm HAMMER");
        { int p = firstPoppable(); tapBoard(p / 1000, p % 1000); } mustHavePopped("HAMMER"); checkpoint("use HAMMER");

        // +5 MOVES
        lockLevelOpen();
        int mBefore = gi("movesLeft");
        tap(bar[3].centerX(), bar[3].centerY());
        checkpoint("+5 MOVES (" + mBefore + "->" + gi("movesLeft") + ")");

        // MIX / shuffle
        tap(bar[4].centerX(), bar[4].centerY()); checkpoint("SHUFFLE");

        // SWAP (arm, pick two adjacent playable cells)
        tap(bar[5].centerX(), bar[5].centerY()); checkpoint("arm SWAP");
        {
            Board b = board();
            int sr = -1, sc = -1;
            for (int r = 0; r < b.rows && sr < 0; r++)
                for (int c = 0; c < b.cols - 1; c++)
                    if (b.isPlayable(r, c) && b.isPlayable(r, c + 1)) { sr = r; sc = c; break; }
            tap(cellX(sc), cellY(sr)); frame();
            tap(cellX(sc + 1), cellY(sr)); settle();
        }
        checkpoint("SWAP two cells");

        // ---------- power tiles + fusion (forced onto the board) ----------
        lockLevelOpen();
        { si("armed", -1); board().setType(2, 2, Board.T_ROCKET_H); tapBoard(2, 2); }
        mustHavePopped("ROCKET detonate"); checkpoint("ROCKET detonate");

        lockLevelOpen();
        { si("armed", -1); Board b = board(); b.setType(4, 3, Board.T_BOMB); b.setType(4, 4, Board.T_BOMB); tapBoard(4, 3); }
        mustHavePopped("BOMB+BOMB fusion"); checkpoint("BOMB+BOMB fusion");

        lockLevelOpen();
        { si("armed", -1); board().setType(5, 5, Board.T_RAINBOW); tapBoard(5, 5); }
        mustHavePopped("RAINBOW detonate"); checkpoint("RAINBOW detonate");

        // ---------- pause / settings round-trip ----------
        lockLevelOpen();
        si("armed", -1);
        tapRect("backBtn"); checkpoint("PLAYING -> PAUSED");
        tapRect("pauseBtn", 2); checkpoint("PAUSED -> SETTINGS");
        tapRect("backBtn"); checkpoint("SETTINGS -> back to PAUSED");
        tapRect("pauseBtn", 0); checkpoint("PAUSED resume -> PLAYING");

        // pause mid-animation, then resume
        lockLevelOpen();
        { int p = firstPoppable(); if (p >= 0) { tap(cellX(p % 1000), cellY(p / 1000)); } } // enter ANIM
        if (state().equals("ANIM")) {
            v.onBack(); checkpoint("onBack during ANIM -> PAUSED");
            tapRect("pauseBtn", 0); settle(); checkpoint("resume -> finish ANIM");
        } else { settle(); checkpoint("anim already settled"); }

        // ---------- play a fresh level to completion (sweep -> complete) ----------
        // Start clean so target/moves are the real level values (the prior level was locked open).
        invoke("startPregame", new Class<?>[]{int.class}, 2);
        tapRect("pregamePlay");
        checkpoint("fresh level 2 start");
        playToEnd(80);
        checkpoint("level2 played to end");
        // run extra frames to drive SWEEP -> COMPLETE
        settle(); frames(4);
        if (state().equals("COMPLETE")) {
            tapRect("completeNext"); checkpoint("COMPLETE -> next PREGAME");
            tapRect("backBtn"); checkpoint("back -> MAP");
        } else {
            checkpoint("post-play state " + state());
        }

        // ---------- BREAK level (locks) ----------
        int bl = firstBreakLevel();
        invoke("startPregame", new Class<?>[]{int.class}, bl);
        checkpoint("startPregame BREAK lvl " + bl);
        tapRect("pregamePlay");
        topUpBoosters();
        checkpoint("BREAK start, locks=" + (board() != null ? board().lockedRemaining() : -1));
        // pop normally a bunch to chip locks
        playToEnd(20);
        checkpoint("BREAK after normal play, state=" + state() + " locks=" + (board() != null ? board().lockedRemaining() : -1));
        // if still playing, hammer remaining locks to force the BREAK win path
        if (state().equals("PLAYING")) {
            RectF[] b2 = (RectF[]) invoke("boosterBar", new Class<?>[]{});
            for (int guard = 0; guard < 40 && state().equals("PLAYING"); guard++) {
                Board b = board();
                if (b == null || b.lockedRemaining() == 0) break;
                int lr = -1, lc = -1;
                for (int r = 0; r < b.rows && lr < 0; r++)
                    for (int c = 0; c < b.cols; c++)
                        if (b.isLocked(r, c)) { lr = r; lc = c; break; }
                if (lr < 0) break;
                tap(b2[2].centerX(), b2[2].centerY()); // arm hammer
                tap(cellX(lc), cellY(lr)); settle();    // hammer the lock
            }
            checkpoint("BREAK hammer locks, state=" + state());
        }

        // ---------- FAILED + continue/retry/home ----------
        invoke("startPregame", new Class<?>[]{int.class}, 1);
        tapRect("pregamePlay");
        topUpBoosters();
        forceLose();
        checkpoint("forced FAILED #1, state=" + state());
        if (state().equals("FAILED")) { tapRect("failedContinue"); checkpoint("FAILED continue -> PLAYING"); }
        forceLose();
        if (state().equals("FAILED")) { tapRect("failedRetry"); checkpoint("FAILED retry -> PLAYING"); }
        forceLose();
        if (state().equals("FAILED")) { tapRect("failedHome"); checkpoint("FAILED home -> MAP"); }

        // ---------- onBack walk from MENU ----------
        // get to MENU
        for (int i = 0; i < 8 && !state().equals("MENU"); i++) { v.onBack(); frame(); }
        checkpoint("onBack to MENU, state=" + state());

        // ---------- destroy ----------
        v.detach();         // -> onDetachedFromWindow -> pause()
        v.destroy();        // -> sound.release()
        checkpoint("detach + destroy");

        System.out.println("\nTotal frames driven: " + frameCount + ", canvas draws: " + canvas.draws
                + ", faults: " + Faults.count());
        if (FAILED || Faults.count() > 0) {
            System.out.println("RESULT: FAIL — " + Faults.count() + " fault(s) recorded");
            if (!Faults.LOG.isEmpty()) for (String s : Faults.LOG) System.out.println("   FAULT: " + s);
            System.exit(1);
        } else {
            System.out.println("RESULT: PASS — game ran cleanly across every screen and full playthroughs, zero faults.");
        }
    }

    /** Force a loss: huge goal + 1 move left, then make one pop. */
    static void forceLose() {
        if (!state().equals("PLAYING")) return;
        si("target", 99_999_999);
        si("collected", 0);
        si("score", 0);
        si("movesLeft", 1);
        int p = firstPoppable();
        if (p >= 0) tapCell(p / 1000, p % 1000);
        settle(); frames(2);
    }
}
