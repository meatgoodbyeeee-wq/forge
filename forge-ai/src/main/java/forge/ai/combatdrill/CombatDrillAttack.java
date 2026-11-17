package forge.ai.combatdrill;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import forge.ai.combatdrill.CombatDrillSolver.Choice;
import forge.ai.combatdrill.CombatDrillSolver.Creature;
import forge.ai.combatdrill.CombatDrillSolver.State;
import forge.game.CardTraitBase;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Replaces the AI's attack declaration with the result of the combat-drill search ({@link CombatDrillSolver}) when
 * the board is one the drill models: two players, every creature on the battlefield has only the keywords the drill
 * knows and no other abilities that matter in combat. Otherwise the original declaration is left alone.
 * <p>
 * Limits of the basic engine: hands, instants, mana and future draws are ignored (both sides only attack and block
 * with what is on the battlefield now).
 */
public final class CombatDrillAttack {

    /** Keywords the drill models (haste/flash only matter before the creature is on the battlefield). */
    private static final Set<Keyword> MODELED = EnumSet.of(Keyword.FLYING, Keyword.REACH, Keyword.FIRST_STRIKE,
            Keyword.DOUBLE_STRIKE, Keyword.DEATHTOUCH, Keyword.TRAMPLE, Keyword.MENACE, Keyword.VIGILANCE,
            Keyword.LIFELINK, Keyword.INDESTRUCTIBLE, Keyword.DEFENDER, Keyword.HEXPROOF, Keyword.WARD,
            Keyword.HASTE, Keyword.FLASH);

    public enum Mode {
        /** Use the search result whenever it is strictly better than the original attack. */
        ALWAYS,
        /** Only when the search proves a win the original attack misses, or the original attack loses by force. */
        DECISIVE
    }

    /** Settings (from the AI profile). */
    public static final class Config {
        public Mode mode = Mode.ALWAYS;
        public int minPly = 4;
        public int maxPly = 8;
        public long budgetMs = 2000;
        public int maxCreatures = 8;
        public boolean verbose;
        /**
         * When the opponent has an untapped land and a card in hand (possible instant: burn, trick), only make
         * decisive overrides; the basic engine cannot see instants.
         */
        public boolean cautious;
    }

    private CombatDrillAttack() { }

    /** Why a board was not searched; null when it can be. */
    static String unsupportedReason(Card c) {
        if (c.getDamage() > 0) {
            return "damaged";
        }
        for (KeywordInterface ki : c.getKeywords()) {
            if (ki.getKeyword() == null || !MODELED.contains(ki.getKeyword())) {
                return "keyword " + ki.getOriginal();
            }
        }
        for (Trigger t : c.getTriggers()) {
            if (fromModeledKeyword(t) || isOwnEtbTrigger(t)) {
                continue;
            }
            return "trigger";
        }
        for (StaticAbility st : c.getStaticAbilities()) {
            if (!fromModeledKeyword(st)) {
                return "static";
            }
        }
        for (ReplacementEffect re : c.getReplacementEffects()) {
            if (!fromModeledKeyword(re)) {
                return "replacement";
            }
        }
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell() || sa.isManaAbility() || fromModeledKeyword(sa)) {
                continue;
            }
            ZoneType z = sa.getRestrictions() == null ? null : sa.getRestrictions().getZone();
            if (z != null && z != ZoneType.Battlefield) {
                continue; // e.g. cycling: only from the hand
            }
            return "ability";
        }
        return null;
    }

    private static boolean fromModeledKeyword(CardTraitBase t) {
        KeywordInterface ki = t.getKeyword();
        return ki != null && ki.getKeyword() != null && MODELED.contains(ki.getKeyword());
    }

    /** "When this creature enters" does nothing once it is on the battlefield. */
    private static boolean isOwnEtbTrigger(Trigger t) {
        return t.getMode() == TriggerType.ChangesZone && "Battlefield".equals(t.getParam("Destination"))
                && "Card.Self".equals(t.getParam("ValidCard"));
    }

    static Creature toCreature(Card c) {
        Creature x = new Creature(c.getNetPower(), c.getNetToughness());
        x.fly = c.hasKeyword(Keyword.FLYING);
        x.reach = c.hasKeyword(Keyword.REACH);
        x.fs = c.hasKeyword(Keyword.FIRST_STRIKE);
        x.ds = c.hasKeyword(Keyword.DOUBLE_STRIKE);
        x.dt = c.hasKeyword(Keyword.DEATHTOUCH);
        x.tr = c.hasKeyword(Keyword.TRAMPLE);
        x.me = c.hasKeyword(Keyword.MENACE);
        x.vg = c.hasKeyword(Keyword.VIGILANCE);
        x.ll = c.hasKeyword(Keyword.LIFELINK);
        x.ind = c.hasKeyword(Keyword.INDESTRUCTIBLE);
        x.df = c.hasKeyword(Keyword.DEFENDER);
        x.hx = c.hasKeyword(Keyword.HEXPROOF);
        if (x.fs && x.ds) {
            x.fs = false;
        }
        for (KeywordInterface ki : c.getKeywords()) {
            if (ki.getKeyword() == Keyword.WARD) {
                String[] parts = ki.getOriginal().split(":");
                int cost = 1;
                if (parts.length > 1) {
                    try {
                        cost = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException e) {
                        cost = 1;
                    }
                }
                x.wd = Math.max(x.wd, cost);
            }
        }
        return x;
    }

    /**
     * Possibly replaces {@code attacker}'s attackers in {@code combat}. Returns true when the declaration changed.
     */
    public static boolean refine(final Player ai, final Combat combat, final Config cfg) {
        CombatDrillStats.calls.incrementAndGet();
        final long t0 = System.nanoTime();
        if (ai.getGame().getPlayers().size() != 2 || ai.getOpponents().size() != 1) {
            CombatDrillStats.skip("players");
            return false;
        }
        final Player opp = ai.getOpponents().getFirst();
        // only the opponent player as a defender: planeswalkers and battles are not modeled
        for (GameEntity d : combat.getDefenders()) {
            if (d != opp) {
                CombatDrillStats.skip("non-player defender");
                return false;
            }
        }
        for (Card c : combat.getAttackers()) {
            if (!ai.equals(c.getController()) || combat.getDefenderByAttacker(c) != opp) {
                CombatDrillStats.skip("foreign attacker");
                return false;
            }
        }
        final CardCollectionView mine = ai.getCreaturesInPlay();
        final CardCollectionView theirs = opp.getCreaturesInPlay();
        if (mine.isEmpty()) {
            CombatDrillStats.skip("no creatures");
            return false;
        }
        if (mine.size() > Math.min(cfg.maxCreatures, CombatDrillSolver.MAX_PER_SIDE)
                || theirs.size() > Math.min(cfg.maxCreatures, CombatDrillSolver.MAX_PER_SIDE)) {
            CombatDrillStats.skip("too many creatures");
            return false;
        }
        if (ai.getLife() > 900 || opp.getLife() > 900 || ai.getLife() <= 0 || opp.getLife() <= 0) {
            CombatDrillStats.skip("life");
            return false;
        }
        for (Card c : mine) {
            String why = unsupportedReason(c);
            if (why != null) {
                CombatDrillStats.skip(why.startsWith("keyword") ? "keyword" : why);
                if (cfg.verbose) {
                    System.out.println("[combat-drill] skip " + c + ": " + why);
                }
                return false;
            }
        }
        for (Card c : theirs) {
            String why = unsupportedReason(c);
            if (why != null) {
                CombatDrillStats.skip(why.startsWith("keyword") ? "keyword" : why);
                if (cfg.verbose) {
                    System.out.println("[combat-drill] skip " + c + ": " + why);
                }
                return false;
            }
        }

        final List<Card> myList = new ArrayList<>(mine);
        final List<Card> opList = new ArrayList<>(theirs);
        final Creature[] me = new Creature[myList.size()];
        final Creature[] op = new Creature[opList.size()];
        int noAttack = 0, stayTapped = 0, preferred = 0, oppTapped = 0;
        for (int i = 0; i < me.length; i++) {
            Card c = myList.get(i);
            me[i] = toCreature(c);
            if (combat.isAttacking(c)) {
                preferred |= 1 << i;
            } else if (!CombatUtil.canAttack(c, opp)) {
                noAttack |= 1 << i;
            }
            if (c.isTapped()) {
                stayTapped |= 1 << i;
            }
        }
        for (int i = 0; i < op.length; i++) {
            op[i] = toCreature(opList.get(i));
            if (opList.get(i).isTapped()) {
                oppTapped |= 1 << i;
            }
        }
        final State root = new State((1 << me.length) - 1, (1 << op.length) - 1, ai.getLife(), opp.getLife(), 0,
                oppTapped);
        CombatDrillStats.eligible.incrementAndGet();

        // iterative deepening over whole rounds; keep the deepest finished answer
        final long deadline = t0 + cfg.budgetMs * 1_000_000L;
        Choice choice = null;
        int donePly = 0;
        CombatDrillSolver prev = null;
        for (int ply = cfg.minPly; ply <= cfg.maxPly; ply += 2) {
            CombatDrillSolver solver = new CombatDrillSolver(me, op, ply);
            if (prev != null) {
                solver.shareCombatCache(prev);
            }
            prev = solver;
            solver.setRootNoAttack(noAttack);
            solver.setRootStayTapped(stayTapped);
            solver.setDeadline(deadline);
            try {
                choice = solver.choose(root, preferred);
                donePly = ply;
            } catch (CombatDrillSolver.TimeoutException e) {
                break;
            }
            if (CombatDrillSolver.classify(choice.value)[0] != 0) {
                break; // forced result: deeper search will not change the verdict
            }
        }
        CombatDrillStats.nanos.addAndGet(System.nanoTime() - t0);
        if (choice == null) {
            CombatDrillStats.timeouts.incrementAndGet();
            return false;
        }
        CombatDrillStats.searched.incrementAndGet();
        CombatDrillStats.plyHist(donePly);
        if (choice.mask == preferred) {
            return false;
        }
        int clsBest = CombatDrillSolver.classify(choice.value)[0];
        int clsPref = CombatDrillSolver.classify(choice.preferredValue)[0];
        boolean decisiveOnly = cfg.mode == Mode.DECISIVE;
        if (cfg.cautious && !opp.getCardsIn(ZoneType.Hand).isEmpty()
                && !opp.getLandsInPlay().filter(c -> !c.isTapped()).isEmpty()) {
            decisiveOnly = true;
        }
        if (decisiveOnly && !(clsBest == 1 && clsPref != 1) && !(clsPref == -1 && clsBest != -1)) {
            CombatDrillStats.skip("held back (open mana)");
            return false;
        }
        if (clsBest == 1 && clsPref != 1) {
            CombatDrillStats.foundWins.incrementAndGet();
        } else if (clsPref == -1 && clsBest != -1) {
            CombatDrillStats.avoidedLosses.incrementAndGet();
        }

        if (cfg.verbose) {
            System.out.println("[combat-drill] me " + describe(me) + " vs op " + describe(op) + " life "
                    + ai.getLife() + "-" + opp.getLife() + " oppHand " + opp.getCardsIn(ZoneType.Hand).size()
                    + " oppOpenLands " + opp.getLandsInPlay().filter(c -> !c.isTapped()).size() + " ply " + donePly + ": original " + names(myList, preferred)
                    + " (" + choice.preferredValue + ") -> " + names(myList, choice.mask) + " (" + choice.value + ")");
        }
        for (int i = 0; i < myList.size(); i++) {
            Card c = myList.get(i);
            boolean want = (choice.mask >> i & 1) != 0;
            if (!want && combat.isAttacking(c)) {
                combat.removeFromCombat(c);
            } else if (want && !combat.isAttacking(c) && CombatUtil.canAttack(c, opp)) {
                combat.addAttacker(c, opp);
            }
        }
        CombatDrillStats.overrides.incrementAndGet();
        return true;
    }

    private static String describe(Creature[] cs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(cs[i]);
        }
        return sb.append(']').toString();
    }

    private static String names(List<Card> cards, int mask) {
        List<String> res = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            if ((mask >> i & 1) != 0) {
                res.add(cards.get(i).getName());
            }
        }
        return res.toString();
    }
}
