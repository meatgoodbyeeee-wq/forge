package forge.ai.combatdrill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exhaustive combat search ported from combat-drill (src/engine.js, the "basic" engine: board only, no hands).
 * <p>
 * Side 0 is the player to decide (the AI), side 1 the opponent. Each ply is one attack: the side to move picks a
 * subset of its creatures, the defender picks every legal block assignment (menace, flying/reach), the attacker
 * picks a damage assignment, then the turn passes. Creatures that attacked stay tapped (unless they have vigilance)
 * and cannot block on the opponent's turn. Minimax with alpha-beta pruning and a transposition table; values are
 * from side 0's point of view: win = {@link #WIN} - ply, loss = -WIN + ply, otherwise a heuristic on life and
 * creature values clamped to +/-400.
 * <p>
 * Pure Java on purpose (no Forge types) so it can be cross-checked against the JavaScript engine.
 */
public final class CombatDrillSolver {
    public static final int WIN = 1000;
    private static final int INF = 1_000_000;
    /** Bitmask representation: at most this many creatures per side. */
    public static final int MAX_PER_SIDE = 12;
    /** Life totals are packed into the transposition key; above this the board is rejected. */
    public static final int MAX_LIFE = 1000;

    /** A creature as the drill models it. */
    public static final class Creature {
        public int p, t;
        public boolean fly, reach, fs, ds, dt, tr, me, vg, ll, ind, df, hx;
        /** Ward cost (generic mana); only used in the evaluation. */
        public int wd;

        public Creature() { }

        public Creature(int p, int t) {
            this.p = p;
            this.t = t;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder().append(p).append('/').append(t);
            if (fly) sb.append(" fly");
            if (reach) sb.append(" reach");
            if (fs) sb.append(" fs");
            if (ds) sb.append(" ds");
            if (dt) sb.append(" dt");
            if (tr) sb.append(" tr");
            if (me) sb.append(" me");
            if (vg) sb.append(" vg");
            if (ll) sb.append(" ll");
            if (ind) sb.append(" ind");
            if (df) sb.append(" df");
            if (hx) sb.append(" hx");
            if (wd > 0) sb.append(" wd").append(wd);
            return sb.toString();
        }
    }

    /** Search position. {@code tapped} marks creatures of the side NOT to move that cannot block (they attacked). */
    public static final class State {
        public final int[] alive;
        public final int[] life;
        public final int turn;
        public final int tapped;

        public State(int alive0, int alive1, int life0, int life1, int turn, int tapped) {
            this(new int[] {alive0, alive1}, new int[] {life0, life1}, turn, tapped);
        }

        State(int[] alive, int[] life, int turn, int tapped) {
            this.alive = alive;
            this.life = life;
            this.turn = turn;
            this.tapped = tapped;
        }
    }

    /** Thrown when the time budget runs out. */
    public static final class TimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TimeoutException() {
            super(null, null, false, false);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Combat damage (ccCombat / ccStep / ccAssign / ccDeals / ccNeed in engine.js)
    // ---------------------------------------------------------------------------------------------------------

    /** A combatant inside one combat. {@code target} is the attacker index a blocker blocks. */
    public static final class Fighter {
        public int P, T, dmg, target;
        public boolean dt, tr, ll, ind, prev, fs, ds, alive = true, blocked;
    }

    /** One possible result of a combat (one damage assignment choice). */
    public static final class Outcome {
        public int[] aDmg, bDmg;
        public boolean[] aDT, bDT, aAlive, bAlive;
        public int toPlayer, gainA, gainD;
    }

    /** A damage assignment of one attacker: pairs of (blocker index, damage), plus damage to the player. */
    static final class Assign {
        final int[][] to;
        final int player;

        Assign(int[][] to, int player) {
            this.to = to;
            this.player = player;
        }
    }

    /** step: 0 = single damage step, 1 = first strike step, 2 = regular step after first strike. */
    static boolean deals(Fighter c, int step) {
        return step == 0 || (step == 1 ? (c.fs || c.ds) : (!c.fs || c.ds));
    }

    /** Lethal damage for assignment purposes (indestructible / prevention not considered). */
    static int need(Fighter b, int dmg, boolean dt) {
        return dt ? 1 : Math.max(1, b.T - dmg);
    }

    /** Every possible result of the combat, one per damage assignment choice of the attackers. */
    public static List<Outcome> combat(Fighter[] att, Fighter[] blk) {
        boolean anyFS = false;
        for (Fighter a : att) if (a.alive && (a.fs || a.ds)) anyFS = true;
        for (Fighter b : blk) if (b.alive && (b.fs || b.ds)) anyFS = true;
        Outcome init = new Outcome();
        init.aDmg = new int[att.length];
        init.bDmg = new int[blk.length];
        init.aDT = new boolean[att.length];
        init.bDT = new boolean[blk.length];
        init.aAlive = new boolean[att.length];
        init.bAlive = new boolean[blk.length];
        for (int i = 0; i < att.length; i++) { init.aDmg[i] = att[i].dmg; init.aAlive[i] = att[i].alive; }
        for (int j = 0; j < blk.length; j++) { init.bDmg[j] = blk[j].dmg; init.bAlive[j] = blk[j].alive; }
        List<Outcome> states = new ArrayList<>();
        states.add(init);
        int[] steps = anyFS ? new int[] {1, 2} : new int[] {0};
        for (int step : steps) {
            List<Outcome> next = new ArrayList<>();
            for (Outcome st : states) next.addAll(step(att, blk, st, step));
            states = next;
        }
        return states;
    }

    private static List<Outcome> step(Fighter[] att, Fighter[] blk, Outcome st, int step) {
        final int nA = att.length;
        int[] aAdd = new int[nA];
        boolean[] aDTn = new boolean[nA];
        int gainA = 0, gainD = 0, toP = 0;
        for (int j = 0; j < blk.length; j++) { // blockers -> attackers
            Fighter b = blk[j];
            if (!st.bAlive[j] || !deals(b, step) || b.P <= 0) continue;
            int i = b.target;
            if (!st.aAlive[i] || att[i].prev) continue;
            aAdd[i] += b.P;
            if (b.dt) aDTn[i] = true;
            if (b.ll) gainD += b.P;
        }
        List<Integer> choiceIdx = new ArrayList<>();
        List<List<Assign>> choiceOpts = new ArrayList<>();
        for (int i = 0; i < nA; i++) { // attackers -> blockers / player
            Fighter a = att[i];
            if (!st.aAlive[i] || !deals(a, step) || a.P <= 0) continue;
            if (!a.blocked) {
                toP += a.P;
                if (a.ll) gainA += a.P;
                continue;
            }
            int cnt = 0;
            for (int j = 0; j < blk.length; j++) if (blk[j].target == i && st.bAlive[j]) cnt++;
            if (cnt == 0) { // every blocker died: trample sends everything through
                if (a.tr) {
                    toP += a.P;
                    if (a.ll) gainA += a.P;
                }
                continue;
            }
            int[] L = new int[cnt];
            cnt = 0;
            for (int j = 0; j < blk.length; j++) if (blk[j].target == i && st.bAlive[j]) L[cnt++] = j;
            choiceIdx.add(i);
            choiceOpts.add(assign(a, L, blk, st));
        }
        List<Outcome> res = new ArrayList<>();
        Assign[] combo = new Assign[choiceIdx.size()];
        expand(att, blk, st, aAdd, aDTn, toP, gainA, gainD, choiceIdx, choiceOpts, combo, 0, res);
        return res;
    }

    /** Cartesian product of the assignment choices, in the same order as the JavaScript engine. */
    private static void expand(Fighter[] att, Fighter[] blk, Outcome st, int[] aAdd, boolean[] aDTn, int toP, int gainA,
            int gainD, List<Integer> choiceIdx, List<List<Assign>> choiceOpts, Assign[] combo, int d, List<Outcome> res) {
        if (d < combo.length) {
            for (Assign o : choiceOpts.get(d)) {
                combo[d] = o;
                expand(att, blk, st, aAdd, aDTn, toP, gainA, gainD, choiceIdx, choiceOpts, combo, d + 1, res);
            }
            return;
        }
        Outcome r = new Outcome();
        r.aDmg = st.aDmg.clone();
        r.bDmg = st.bDmg.clone();
        r.aDT = st.aDT.clone();
        r.bDT = st.bDT.clone();
        r.aAlive = st.aAlive.clone();
        r.bAlive = st.bAlive.clone();
        int tp = st.toPlayer + toP, gA = st.gainA + gainA, gD = st.gainD + gainD;
        for (int i = 0; i < att.length; i++) {
            r.aDmg[i] += aAdd[i];
            if (aDTn[i]) r.aDT[i] = true;
        }
        for (int c = 0; c < combo.length; c++) {
            Fighter a = att[choiceIdx.get(c)];
            Assign o = combo[c];
            for (int[] jn : o.to) {
                int j = jn[0], n = jn[1];
                if (n <= 0 || blk[j].prev) continue;
                r.bDmg[j] += n;
                if (a.dt) r.bDT[j] = true;
                if (a.ll) gA += n;
            }
            if (o.player != 0) {
                tp += o.player;
                if (a.ll) gA += o.player;
            }
        }
        for (int i = 0; i < att.length; i++) {
            if (r.aAlive[i] && !att[i].ind && (r.aDmg[i] >= att[i].T || r.aDT[i])) r.aAlive[i] = false;
        }
        for (int j = 0; j < blk.length; j++) {
            if (r.bAlive[j] && !blk[j].ind && (r.bDmg[j] >= blk[j].T || r.bDT[j])) r.bAlive[j] = false;
        }
        r.toPlayer = tp;
        r.gainA = gA;
        r.gainD = gD;
        res.add(r);
    }

    /**
     * Damage assignment candidates of an attacker blocked by L (current rules: the attacker divides freely).
     * Candidates are the maximal sets of blockers it can kill, with the remainder on one more blocker. Trample
     * assigns lethal to everyone and sends the rest to the player.
     */
    static List<Assign> assign(Fighter a, int[] L, Fighter[] blk, Outcome st) {
        final int P = a.P, n = L.length;
        int[] need = new int[n];
        int sumNeed = 0;
        for (int k = 0; k < n; k++) {
            need[k] = need(blk[L[k]], st.bDmg[L[k]], a.dt);
            sumNeed += need[k];
        }
        List<Assign> res = new ArrayList<>();
        if (a.tr && P >= sumNeed) {
            int[][] to = new int[n][];
            for (int k = 0; k < n; k++) to[k] = new int[] {L[k], need[k]};
            res.add(new Assign(to, P - sumNeed));
            return res;
        }
        if (n == 1) {
            res.add(new Assign(new int[][] {{L[0], P}}, 0));
            return res;
        }
        boolean[] kill = new boolean[n];
        for (int k = 0; k < n; k++) kill[k] = !blk[L[k]].ind && !blk[L[k]].prev;
        List<int[]> feas = new ArrayList<>(); // {mask, sum}
        for (int m = 1; m < (1 << n); m++) {
            int s = 0;
            boolean ok = true;
            for (int k = 0; k < n; k++) {
                if ((m >> k & 1) != 0) {
                    if (!kill[k]) {
                        ok = false;
                        break;
                    }
                    s += need[k];
                }
            }
            if (ok && s <= P) feas.add(new int[] {m, s});
        }
        for (int[] x : feas) {
            boolean maximal = true;
            for (int[] y : feas) {
                if (y[0] != x[0] && (y[0] & x[0]) == x[0]) {
                    maximal = false;
                    break;
                }
            }
            if (!maximal) continue;
            int m = x[0], s = x[1];
            List<int[]> base = new ArrayList<>();
            for (int k = 0; k < n; k++) if ((m >> k & 1) != 0) base.add(new int[] {L[k], need[k]});
            int left = P - s;
            List<Integer> others = new ArrayList<>();
            for (int k = 0; k < n; k++) if ((m >> k & 1) == 0 && kill[k]) others.add(k);
            if (left > 0 && !others.isEmpty()) {
                for (int k : others) {
                    List<int[]> to = new ArrayList<>(base);
                    to.add(new int[] {L[k], left});
                    res.add(new Assign(to.toArray(new int[0][]), 0));
                }
            } else {
                res.add(new Assign(base.toArray(new int[0][]), 0));
            }
        }
        if (res.isEmpty()) {
            int k = -1;
            for (int q = 0; q < n; q++) if (kill[q]) { k = q; break; }
            res.add(new Assign(new int[][] {{L[k >= 0 ? k : 0], P}}, 0));
        }
        return res;
    }

    /** Board value of a creature (evaluation function). */
    public static int creatureValue(Creature c) {
        return 6 * c.p + 4 * c.t + (c.fly ? 4 : 0) + (c.fs ? 3 : 0) + (c.ds ? 6 : 0) + (c.dt ? 4 : 0) + (c.tr ? 2 : 0)
                + (c.me ? 2 : 0) + (c.vg ? 2 : 0) + (c.ll ? 3 : 0) + (c.ind ? 5 : 0) + (c.hx ? 1 : 0) + c.wd
                - (c.df ? 6 : 0);
    }

    static boolean canBlock(Creature blk, Creature att) {
        return !att.fly || blk.fly || blk.reach;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Search (makeSolver in engine.js)
    // ---------------------------------------------------------------------------------------------------------

    private static final class Entry {
        final int v, f, best;

        Entry(int v, int f, int best) {
            this.v = v;
            this.f = f;
            this.best = best;
        }
    }

    private final Creature[][] sides;
    private final int maxPly;
    private final Map<Long, Entry> memo = new HashMap<>();
    private Map<Long, int[][]> combatCache = new HashMap<>();
    /** group[side][i]: index of the first creature identical to creature i (same stats and keywords). */
    private final int[][] group;
    /** blockOrder[def][b]: attacker creature indices blocker b of side def tries, best first; -1 = no block. */
    private int[][][] blockOrder;
    private boolean symmetry = true;
    private long nodes;
    private long leaves;
    private long deadlineNanos = Long.MAX_VALUE;
    /** Creatures of side 0 that cannot attack at the root (summoning sick, tapped...). Extension over engine.js. */
    private int rootNoAttack;
    /** Creatures of side 0 tapped at the root: they stay tapped through the opponent's next turn. Extension. */
    private int rootStayTapped;

    public CombatDrillSolver(Creature[] me, Creature[] op, int maxPly) {
        if (me.length > MAX_PER_SIDE || op.length > MAX_PER_SIDE) {
            throw new IllegalArgumentException("too many creatures");
        }
        this.sides = new Creature[][] {me, op};
        this.maxPly = maxPly;
        group = new int[2][];
        for (int side = 0; side < 2; side++) {
            Creature[] cs = sides[side];
            group[side] = new int[cs.length];
            for (int i = 0; i < cs.length; i++) {
                group[side][i] = i;
                for (int q = 0; q < i; q++) {
                    if (identical(cs[q], cs[i])) {
                        group[side][i] = group[side][q];
                        break;
                    }
                }
            }
        }
        setOrdering(true);
    }

    static boolean identical(Creature x, Creature y) {
        return x.p == y.p && x.t == y.t && x.fly == y.fly && x.reach == y.reach && x.fs == y.fs && x.ds == y.ds
                && x.dt == y.dt && x.tr == y.tr && x.me == y.me && x.vg == y.vg && x.ll == y.ll && x.ind == y.ind
                && x.df == y.df && x.hx == y.hx && x.wd == y.wd;
    }

    /** Skip attacks/blocks that only swap identical creatures (on by default; exact). */
    public void setSymmetry(boolean on) {
        this.symmetry = on;
    }

    /**
     * Try promising blocks first (on by default; changes only the search order, not the values). Off = the order
     * of engine.js (no block, then the attackers in order).
     */
    public void setOrdering(boolean on) {
        blockOrder = new int[2][][];
        for (int def = 0; def < 2; def++) {
            Creature[] dcs = sides[def], acs = sides[1 - def];
            blockOrder[def] = new int[dcs.length][];
            for (int bi = 0; bi < dcs.length; bi++) {
                Integer[] opts = new Integer[acs.length + 1];
                opts[0] = -1;
                for (int ai = 0; ai < acs.length; ai++) opts[ai + 1] = ai;
                if (on) {
                    final Creature bc = dcs[bi];
                    java.util.Arrays.sort(opts, (x, y) -> Integer.compare(blockScore(bc, y < 0 ? null : acs[y]),
                            blockScore(bc, x < 0 ? null : acs[x])));
                }
                blockOrder[def][bi] = new int[opts.length];
                for (int q = 0; q < opts.length; q++) blockOrder[def][bi][q] = opts[q];
            }
        }
    }

    /** Rough one-on-one value of blocking attacker a with b (higher = better for the defender); null = no block. */
    static int blockScore(Creature b, Creature a) {
        if (a == null) return 0;
        boolean aFirst = (a.fs || a.ds) && !(b.fs || b.ds);
        boolean bFirst = (b.fs || b.ds) && !(a.fs || a.ds);
        int aDmg = a.ds ? 2 * a.p : a.p, bDmg = b.ds ? 2 * b.p : b.p;
        boolean bKills = !a.ind && b.p > 0 && (b.dt || bDmg >= a.t);
        boolean aKills = !b.ind && a.p > 0 && (a.dt || aDmg >= b.t);
        if (aFirst && !b.ind && a.p > 0 && (a.dt || a.p >= b.t)) bKills = false;
        if (bFirst && !a.ind && b.p > 0 && (b.dt || b.p >= a.t)) aKills = false;
        return (bKills ? creatureValue(a) : 0) - (aKills ? creatureValue(b) : 0) + (a.tr ? 0 : 2 * Math.max(a.p, 0));
    }

    /** Reuse the combat results of another solver over the same creatures (e.g. a shallower iteration). */
    public void shareCombatCache(CombatDrillSolver other) {
        this.combatCache = other.combatCache;
    }

    public void setRootNoAttack(int mask) {
        this.rootNoAttack = mask;
    }

    public void setRootStayTapped(int mask) {
        this.rootStayTapped = mask;
    }

    /** Absolute System.nanoTime() deadline; the search throws {@link TimeoutException} after it. */
    public void setDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public long getNodes() {
        return nodes;
    }

    public int getMemoSize() {
        return memo.size();
    }

    private int heuristic(State st) {
        int v = 0;
        for (int side = 0; side < 2; side++) {
            Creature[] cs = sides[side];
            int sg = side == 0 ? 1 : -1;
            for (int i = 0; i < cs.length; i++) if ((st.alive[side] >> i & 1) != 0) v += sg * creatureValue(cs[i]);
        }
        v += 3 * (st.life[0] - st.life[1]);
        return Math.max(-400, Math.min(400, v));
    }

    private static long key(State st, int ply) {
        long k = st.alive[0];
        k = k << 12 | st.alive[1];
        k = k << 12 | st.tapped;
        k = k << 10 | Math.min(st.life[0], 1023);
        k = k << 10 | Math.min(st.life[1], 1023);
        k = k << 1 | st.turn;
        k = k << 6 | ply;
        return k;
    }

    /** Attack subsets (as creature bitmasks) of the side to move, in the order engine.js enumerates them. */
    public int[] attackSubsets(State st, int ply) {
        return attackSubsets(st, ply, false);
    }

    /**
     * Attack subsets of the side to move. With {@code canonical}, subsets that only differ by which of several
     * identical creatures attack are enumerated once (the first ones of each group attack).
     */
    int[] attackSubsets(State st, int ply, boolean canonical) {
        int att = st.turn;
        Creature[] cs = sides[att];
        boolean root = ply == 0 && att == 0;
        int[] elig = new int[cs.length];
        int n = 0;
        for (int i = 0; i < cs.length; i++) {
            if ((st.alive[att] >> i & 1) == 0 || cs[i].df) continue; // defender cannot attack
            if (root && (rootNoAttack >> i & 1) != 0) continue;
            elig[n++] = i;
        }
        // prevE[k] = earlier eligible position holding an identical creature (-1: none)
        int[] prevE = new int[n];
        int need = 0;
        for (int k = 0; k < n; k++) {
            prevE[k] = -1;
            if (!canonical) continue;
            for (int q = k - 1; q >= 0; q--) {
                if (prevSame(att, elig[q], elig[k]) && (!root || (rootStayTapped >> elig[q] & 1) == (rootStayTapped >> elig[k] & 1))) {
                    prevE[k] = q;
                    need |= 1 << k;
                    break;
                }
            }
        }
        int[] res = new int[1 << n];
        int cnt = 0;
        for (int m = 0; m < (1 << n); m++) {
            if (need != 0) {
                boolean ok = true;
                for (int k = 0; k < n && ok; k++) {
                    if ((m >> k & 1) != 0 && prevE[k] >= 0 && (m >> prevE[k] & 1) == 0) ok = false;
                }
                if (!ok) continue;
            }
            int mask = 0;
            for (int k = 0; k < n; k++) if ((m >> k & 1) != 0) mask |= 1 << elig[k];
            res[cnt++] = mask;
        }
        return cnt == res.length ? res : java.util.Arrays.copyOf(res, cnt);
    }

    /** Whether creature j of {@code side} is identical to the earlier creature i (same stats and keywords). */
    private boolean prevSame(int side, int i, int j) {
        return group[side][i] == group[side][j];
    }

    /** Minimax value with alpha-beta (fail-soft) and the transposition table. */
    public int value(State st, int ply, int alpha, int beta) {
        if (st.life[1] <= 0) return WIN - ply;
        if (st.life[0] <= 0) return -WIN + ply;
        if (ply >= maxPly) return heuristic(st);
        long k = key(st, ply);
        Entry e = memo.get(k);
        if (e != null) {
            if (e.f == 0) return e.v;
            if (e.f == 1 && e.v >= beta) return e.v;
            if (e.f == 2 && e.v <= alpha) return e.v;
        }
        nodes++;
        if ((nodes & 255) == 0 && System.nanoTime() > deadlineNanos) throw new TimeoutException();
        boolean maxer = st.turn == 0;
        int[] subs = attackSubsets(st, ply, symmetry);
        if (e != null) {
            for (int i = 0; i < subs.length; i++) {
                if (subs[i] == e.best) {
                    subs[i] = subs[0];
                    subs[0] = e.best;
                    break;
                }
            }
        }
        int a = alpha, b = beta, best = maxer ? -INF : INF, bestSub = subs[0];
        for (int s : subs) {
            int v = attackValue(st, ply, s, a, b);
            if (maxer) {
                if (v > best) { best = v; bestSub = s; }
                if (best > a) a = best;
            } else {
                if (v < best) { best = v; bestSub = s; }
                if (best < b) b = best;
            }
            if (a >= b) break;
        }
        int f = best <= alpha ? 2 : best >= beta ? 1 : 0;
        memo.put(k, new Entry(best, f, bestSub));
        return best;
    }

    /** Exact value of one attack at the root. */
    public int attackValue(State st, int attackerMask) {
        return attackValue(st, 0, attackerMask, -INF, INF);
    }

    /** Value of attacking with {@code attackerMask}: the defender minimizes over blocks, the attacker maximizes over damage assignments. */
    public int attackValue(State st, int ply, int attackerMask, int alpha, int beta) {
        BlockSearch bs = new BlockSearch(st, ply, attackerMask, alpha, beta);
        bs.rec(0);
        return bs.worst;
    }

    /** Enumerates the defender's block assignments (forEachBlock + the callback of attackValue in engine.js). */
    private final class BlockSearch {
        final State st;
        final int ply, def, attackerMask;
        final int[] attackers;
        final Creature[] attList;
        /** posOf[creature index of the attacking side] = position in {@code attackers}, or -1. */
        final int[] posOf;
        final int[] avail;
        /** Earlier avail position holding an identical blocker (-1: none). */
        final int[] prevB;
        final int[] assign;
        final boolean maxer;
        int a, b, worst;
        boolean stop;

        BlockSearch(State st, int ply, int attackerMask, int alpha, int beta) {
            this.st = st;
            this.ply = ply;
            this.attackerMask = attackerMask;
            int att = st.turn;
            def = 1 - att;
            Creature[] acs = sides[att], dcs = sides[def];
            attackers = new int[Integer.bitCount(attackerMask)];
            posOf = new int[acs.length];
            java.util.Arrays.fill(posOf, -1);
            int n = 0;
            for (int i = 0; i < acs.length; i++) {
                if ((attackerMask >> i & 1) != 0) {
                    posOf[i] = n;
                    attackers[n++] = i;
                }
            }
            attList = new Creature[attackers.length];
            for (int j = 0; j < attackers.length; j++) attList[j] = acs[attackers[j]];
            int[] tmp = new int[dcs.length];
            n = 0;
            for (int i = 0; i < dcs.length; i++) {
                if ((st.alive[def] >> i & 1) != 0 && (st.tapped >> i & 1) == 0) tmp[n++] = i;
            }
            avail = java.util.Arrays.copyOf(tmp, n);
            prevB = new int[n];
            for (int k = 0; k < n; k++) {
                prevB[k] = -1;
                if (!symmetry) continue;
                for (int q = k - 1; q >= 0; q--) {
                    if (prevSame(def, avail[q], avail[k])) {
                        prevB[k] = q;
                        break;
                    }
                }
            }
            assign = new int[n];
            java.util.Arrays.fill(assign, -1);
            maxer = st.turn == 0;
            a = alpha;
            b = beta;
            worst = maxer ? INF : -INF;
        }

        void rec(int k) {
            if (stop) return;
            if (k == avail.length) {
                if (!menaceOK()) return;
                leaf();
                return;
            }
            Creature blocker = sides[def][avail[k]];
            int floor = prevB[k] >= 0 ? assign[prevB[k]] : -1; // identical blockers: non-decreasing targets
            for (int ci : blockOrder[def][avail[k]]) {
                int j;
                if (ci < 0) {
                    j = -1;
                } else {
                    j = posOf[ci];
                    if (j < 0 || !canBlock(blocker, attList[j])) continue;
                }
                if (j < floor) continue;
                assign[k] = j;
                rec(k + 1);
                if (stop) return;
            }
            assign[k] = -1;
        }

        boolean menaceOK() {
            for (int j = 0; j < attList.length; j++) {
                if (!attList[j].me) continue;
                int n = 0;
                for (int x : assign) if (x == j) n++;
                if (n == 1) return false;
            }
            return true;
        }

        void leaf() {
            if ((++leaves & 255) == 0 && System.nanoTime() > deadlineNanos) throw new TimeoutException();
            List<State> outs = resolve(st, ply, attackerMask, attackers, avail, assign);
            int ov = maxer ? -INF : INF;
            int a2 = a, b2 = b;
            for (State o : outs) {
                int v = value(o, ply + 1, a2, b2);
                if (maxer) {
                    if (v > ov) ov = v;
                    if (ov > a2) a2 = ov;
                } else {
                    if (v < ov) ov = v;
                    if (ov < b2) b2 = ov;
                }
                if (a2 >= b2) break;
            }
            if (maxer) {
                if (ov < worst) worst = ov;
                if (worst < b) b = worst;
            } else {
                if (ov > worst) worst = ov;
                if (worst > a) a = worst;
            }
            if (a >= b) stop = true;
        }
    }

    private static Fighter fighter(Creature c) {
        Fighter f = new Fighter();
        f.P = c.p;
        f.T = c.t;
        f.dt = c.dt;
        f.tr = c.tr;
        f.ll = c.ll;
        f.ind = c.ind;
        f.fs = c.fs;
        f.ds = c.ds;
        return f;
    }

    /**
     * Distinct combat results {deadAtt, deadDef, toPlayer, gainAttacker, gainDefender} (creature bitmasks), in the
     * order of the damage assignment choices. Depends only on who attacks and who blocks whom, so it is cached.
     */
    private int[][] combatResults(int att, int attackerMask, int[] attackers, int[] avail, int[] assign) {
        long ck = att;
        ck = ck << 12 | attackerMask;
        int[] code = new int[MAX_PER_SIDE];
        for (int k = 0; k < avail.length; k++) if (assign[k] >= 0) code[avail[k]] = assign[k] + 1;
        for (int i = 0; i < MAX_PER_SIDE; i++) ck = ck << 4 | code[i];
        int[][] cached = combatCache.get(ck);
        if (cached != null) return cached;
        int def = 1 - att;
        Creature[] acs = sides[att], dcs = sides[def];
        Fighter[] A = new Fighter[attackers.length];
        for (int j = 0; j < attackers.length; j++) {
            A[j] = fighter(acs[attackers[j]]);
            for (int x : assign) if (x == j) { A[j].blocked = true; break; }
        }
        int nb = 0;
        for (int x : assign) if (x >= 0) nb++;
        Fighter[] B = new Fighter[nb];
        int[] bIdx = new int[nb];
        nb = 0;
        for (int k = 0; k < avail.length; k++) {
            if (assign[k] >= 0) {
                B[nb] = fighter(dcs[avail[k]]);
                B[nb].target = assign[k];
                bIdx[nb++] = avail[k];
            }
        }
        List<Outcome> outs = combat(A, B);
        List<int[]> rows = new ArrayList<>(outs.size());
        Set<List<Integer>> seen = new HashSet<>();
        for (Outcome o : outs) {
            int deadAtt = 0, deadDef = 0;
            for (int j = 0; j < attackers.length; j++) if (!o.aAlive[j]) deadAtt |= 1 << attackers[j];
            for (int j = 0; j < bIdx.length; j++) if (!o.bAlive[j]) deadDef |= 1 << bIdx[j];
            int[] row = {deadAtt, deadDef, o.toPlayer, o.gainA, o.gainD};
            if (seen.add(java.util.Arrays.asList(deadAtt, deadDef, o.toPlayer, o.gainA, o.gainD))) rows.add(row);
        }
        int[][] res = rows.toArray(new int[0][]);
        combatCache.put(ck, res);
        return res;
    }

    /** Resolves a combat into the distinct resulting positions (one per damage assignment choice). */
    private List<State> resolve(State st, int ply, int attackerMask, int[] attackers, int[] avail, int[] assign) {
        int att = st.turn, def = 1 - att;
        Creature[] acs = sides[att];
        int[][] rows = combatResults(att, attackerMask, attackers, avail, assign);
        int tappedAll = 0;
        for (int ai : attackers) if (!acs[ai].vg) tappedAll |= 1 << ai; // vigilance does not tap
        if (ply == 0 && att == 0) tappedAll |= rootStayTapped;
        List<State> res = new ArrayList<>(rows.length);
        long[] seen = new long[rows.length];
        int nSeen = 0;
        for (int[] o : rows) {
            int[] alive = {st.alive[0], st.alive[1]};
            alive[att] &= ~o[0];
            alive[def] &= ~o[1];
            int[] life = {st.life[0], st.life[1]};
            life[def] -= o[2];
            life[att] += o[3];
            life[def] += o[4];
            long sk = ((long) alive[0] << 12 | alive[1]) << 40 | (long) (life[0] + 100000) << 20 | (life[1] + 100000);
            boolean dup = false;
            for (int q = 0; q < nSeen; q++) if (seen[q] == sk) { dup = true; break; }
            if (dup) continue;
            seen[nSeen++] = sk;
            res.add(new State(alive, life, def, tappedAll & alive[att]));
        }
        return res;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Root helpers
    // ---------------------------------------------------------------------------------------------------------

    /** Exact value of every attack subset at the root (rootOptions in engine.js). */
    public int[][] rootOptions(State root) {
        int[] subs = attackSubsets(root, 0);
        int[][] res = new int[subs.length][];
        for (int i = 0; i < subs.length; i++) res[i] = new int[] {subs[i], attackValue(root, subs[i])};
        return res;
    }

    /** Result of {@link #choose}. */
    public static final class Choice {
        public final int mask, value, preferredValue;

        Choice(int mask, int value, int preferredValue) {
            this.mask = mask;
            this.value = value;
            this.preferredValue = preferredValue;
        }
    }

    /**
     * Best attack at the root. The preferred attack (e.g. what the original AI chose) is evaluated first and kept
     * unless another attack is strictly better.
     */
    public Choice choose(State root, int preferredMask) {
        int[] subs = attackSubsets(root, 0, symmetry);
        boolean legal = false;
        for (int s : attackSubsets(root, 0, false)) if (s == preferredMask) legal = true;
        int bestMask = legal ? preferredMask : subs[0];
        int best = attackValue(root, bestMask);
        int pref = legal ? best : Integer.MIN_VALUE;
        for (int s : subs) {
            if (s == bestMask) continue;
            int v = attackValue(root, 0, s, best, INF);
            if (v > best) {
                best = v;
                bestMask = s;
            }
        }
        return new Choice(bestMask, best, pref);
    }

    /** {cls, n}: cls 1 = forced win, -1 = forced loss, 0 = open; n = on which of our attacks it ends. */
    public static int[] classify(int v) {
        if (v >= 900) return new int[] {1, (999 - v) / 2 + 1};
        if (v <= -900) return new int[] {-1, (v + 999 + 1) / 2};
        return new int[] {0, 0};
    }
}
