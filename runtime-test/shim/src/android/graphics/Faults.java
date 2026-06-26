package android.graphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test-only instrumentation. Real Android graphics primitives throw on illegal
 * arguments (negative shader radius, NaN draw coordinates, null Path/RectF/text).
 * GameView wraps onDraw/onTouchEvent/doFrame in try/catch -> safeRecover(), so on
 * a real device those throws are swallowed and the screen merely glitches back to
 * the menu. To surface them in the JVM execution harness we ALSO record each
 * crash-class condition here before throwing, so the harness can assert the real
 * game code never triggered one across any screen or a full playthrough.
 */
public final class Faults {
    public static final List<String> LOG = Collections.synchronizedList(new ArrayList<String>());

    private Faults() {}

    /** Records a crash-class condition, then the caller throws as Android would. */
    public static void record(String what) {
        LOG.add(what);
    }

    public static void reset() {
        LOG.clear();
    }

    public static int count() {
        return LOG.size();
    }
}
