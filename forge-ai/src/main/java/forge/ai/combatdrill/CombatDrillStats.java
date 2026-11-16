package forge.ai.combatdrill;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Counters for the combat-drill attack, printed at the end of a sim run. */
public final class CombatDrillStats {
    static final AtomicLong calls = new AtomicLong();
    static final AtomicLong eligible = new AtomicLong();
    static final AtomicLong searched = new AtomicLong();
    static final AtomicLong timeouts = new AtomicLong();
    static final AtomicLong overrides = new AtomicLong();
    static final AtomicLong foundWins = new AtomicLong();
    static final AtomicLong avoidedLosses = new AtomicLong();
    static final AtomicLong nanos = new AtomicLong();
    private static final Map<String, AtomicLong> SKIPS = new ConcurrentHashMap<>();
    private static final Map<Integer, AtomicLong> PLY = new ConcurrentHashMap<>();

    private CombatDrillStats() { }

    static void skip(String why) {
        SKIPS.computeIfAbsent(why, k -> new AtomicLong()).incrementAndGet();
    }

    static void plyHist(int ply) {
        PLY.computeIfAbsent(ply, k -> new AtomicLong()).incrementAndGet();
    }

    public static boolean isUsed() {
        return calls.get() > 0;
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder("COMBAT-DRILL calls=").append(calls).append(" eligible=").append(eligible)
                .append(" searched=").append(searched).append(" timeouts=").append(timeouts)
                .append(" overrides=").append(overrides).append(" (wins found=").append(foundWins)
                .append(", losses avoided=").append(avoidedLosses).append(')');
        long n = eligible.get();
        if (n > 0) {
            sb.append(String.format(" avgMs=%.1f", nanos.get() / 1e6 / n));
        }
        sb.append("\n  depth=").append(new TreeMap<>(PLY)).append(" skipped=").append(new TreeMap<>(SKIPS));
        return sb.toString();
    }
}
