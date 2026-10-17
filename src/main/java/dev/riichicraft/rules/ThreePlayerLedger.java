package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable single-round accounts. The round owner supplies actual opportunities and established liability. */
public final class ThreePlayerLedger {
    public enum Kind { HAND, HONBA, DEPOSIT, NOTEN, NAGASHI }
    /** fromSeat -1 is the deposit pool, used only for DEPOSIT transfers. */
    public record Transfer(int fromSeat, int toSeat, int points, Kind kind) { }
    public record RiichiEntry(ThreePlayerLedger ledger, ThreePlayerRiichi state) { }
    public record Settlement(ThreePlayerLedger ledger, List<Integer> winners, List<Transfer> transfers) {
        public Settlement { winners = List.copyOf(winners); transfers = List.copyOf(transfers); }
    }
    /** Trigger/provenance is established by the future round engine; never infer liability from yaku alone. */
    public record Pao(int liableSeat, Yaku component) {
        public Pao {
            requireSeat(liableSeat);
            if (component != Yaku.DAISANGEN && component != Yaku.DAISUUSHII) {
                throw new IllegalArgumentException("Only daisangen/daisuushii can have responsibility");
            }
        }
    }

    private final UUID roundId;
    private final int dealerSeat;
    private final int honba;
    private final List<Integer> points;
    private final int depositSticks;
    private final boolean finished;
    private final Set<DiscardResponseWindow.Id> processedDeclarations;

    private ThreePlayerLedger(UUID roundId, int dealerSeat, int honba, List<Integer> points, int depositSticks,
                        boolean finished, Set<DiscardResponseWindow.Id> processedDeclarations) {
        this.roundId = Objects.requireNonNull(roundId, "roundId");
        requireSeat(dealerSeat);
        if (honba < 0 || depositSticks < 0) throw new IllegalArgumentException("Honba and deposit counts cannot be negative");
        this.points = List.copyOf(points);
        if (this.points.size() != 3 || this.points.stream().anyMatch(p -> p % 100 != 0)
                || this.points.stream().mapToLong(Integer::longValue).sum() + depositSticks * 1_000L != 105_000L) {
            throw new IllegalArgumentException("Three scores plus deposit pool must conserve 105000 points in hundreds");
        }
        this.dealerSeat = dealerSeat;
        this.honba = honba;
        this.depositSticks = depositSticks;
        this.finished = finished;
        this.processedDeclarations = Set.copyOf(processedDeclarations);
    }

    public static ThreePlayerLedger initial(UUID roundId, int dealerSeat) {
        return begin(roundId, dealerSeat, 0, List.of(35_000,35_000,35_000), 0);
    }

    /** A new, never-reused round ID with accounts carried by the match owner from the preceding round. */
    public static ThreePlayerLedger begin(UUID roundId, int dealerSeat, int honba, List<Integer> points, int depositSticks) {
        return new ThreePlayerLedger(roundId,dealerSeat,honba,points,depositSticks,false,Set.of());
    }

    public UUID roundId() { return roundId; }
    public int dealerSeat() { return dealerSeat; }
    public int honba() { return honba; }
    public List<Integer> points() { return points; }
    public int depositSticks() { return depositSticks; }
    public boolean finished() { return finished; }

    /** Resolve and book exactly this declaration once, including a no-charge cancellation on ron. */
    public RiichiEntry establishRiichi(ThreePlayerRiichi pending, ThreePlayerDiscardWindow window) {
        requireActive(); requireRound(window.id());
        if (processedDeclarations.contains(window.id())) throw new IllegalStateException("Declaration already processed");
        var entry = pending.resolve(window);
        int seat = pending.pending().orElseThrow().seat();
        if (entry.pointsDelta() < 0 && points.get(seat) < 1_000) {
            throw new IllegalStateException("The actual ledger cannot fund this declaration");
        }
        List<Integer> next = new ArrayList<>(points);
        next.set(seat, Math.addExact(next.get(seat),entry.pointsDelta()));
        Set<DiscardResponseWindow.Id> processed = new HashSet<>(processedDeclarations); processed.add(window.id());
        var ledger = new ThreePlayerLedger(roundId,dealerSeat,honba,next,Math.addExact(depositSticks,entry.depositSticksAdded()),false,processed);
        return new RiichiEntry(ledger,entry.state());
    }

    /** All winners in the actual collected discard resolution; honba/deposits go only to the closest. */
    public Settlement settleRon(ThreePlayerDiscardWindow window, Map<Integer, Pao> responsibility) {
        requireActive(); requireRound(window.id());
        var result = window.resolution().orElseThrow(() -> new IllegalStateException("Responses incomplete"));
        if (result.outcome() != DiscardResponseWindow.Outcome.RON) throw new IllegalArgumentException("No ron result to settle");
        return settleCollectedRon(window.discarderSeat(),result.ronSeats(),result.ronValues(),responsibility);
    }

    /** Robbed kan uses the same multi-ron accounting; the declarer is the payer and no new kan is established. */
    public Settlement settleRon(ThreePlayerKanWindow window, Map<Integer,Pao> responsibility) {
        requireActive(); requireRound(window.id());
        var result=window.resolution().orElseThrow(()->new IllegalStateException("Kan responses incomplete"));
        if (!result.robbed()) throw new IllegalArgumentException("No robbery result to settle");
        return settleCollectedRon(window.proposal().declarerSeat(),result.ronSeats(),result.ronValues(),responsibility);
    }

    private Settlement settleCollectedRon(int payer,List<Integer> winners,Map<Integer,ThreePlayerScoreResult> values,Map<Integer,Pao> responsibility) {
        responsibility = Map.copyOf(responsibility);
        if (!winners.containsAll(responsibility.keySet())) throw new IllegalArgumentException("Only actual winners can have liability inputs");
        List<Transfer> transfers = new ArrayList<>();
        for (int winner : winners) {
            ThreePlayerScoreResult value = values.get(winner);
            requirePayment(value,winner,false);
            Pao pao = responsibility.get(winner);
            int responsible = responsibilityPoints(value,winner,pao,false).ronPoints();
            int ordinary = value.points().payment().ronPoints() - responsible;
            pay(transfers,payer,winner,ordinary,Kind.HAND);
            if (pao != null) {
                if (pao.liableSeat() == payer) pay(transfers,pao.liableSeat(),winner,responsible,Kind.HAND);
                else {
                    pay(transfers,payer,winner,responsible / 2,Kind.HAND);
                    pay(transfers,pao.liableSeat(),winner,responsible / 2,Kind.HAND);
                }
            }
        }
        int closest = winners.getFirst();
        int honbaPayer = responsibility.containsKey(closest) ? responsibility.get(closest).liableSeat() : payer;
        pay(transfers,honbaPayer,closest,Math.multiplyExact(honba,200),Kind.HONBA);
        payDeposits(transfers,closest);
        return settle(winners,transfers);
    }

    /** The owner must establish the real self-draw opportunity; an allowed supplied decision alone is not network authority. */
    public Settlement settleTsumo(int winner, ThreePlayerWinEvaluator.Decision decision, Optional<Pao> responsibility) {
        requireActive(); requireSeat(winner);
        Objects.requireNonNull(responsibility,"responsibility");
        if (!decision.allowed()) throw new IllegalArgumentException("A rejected hand cannot be paid");
        ThreePlayerScoreResult value = decision.handValue().orElseThrow();
        requirePayment(value,winner,true);
        Pao pao = responsibility.orElse(null);
        var covered = responsibilityPoints(value,winner,pao,true);
        List<Transfer> transfers = new ArrayList<>();
        for (int payer = 0; payer < 3; payer++) {
            if (payer == winner) continue;
            int full = payer == dealerSeat ? value.points().payment().fromDealer() : value.points().payment().fromNonDealer();
            int part = payer == dealerSeat ? covered.fromDealer() : covered.fromNonDealer();
            pay(transfers,payer,winner,full-part,Kind.HAND);
            if (pao == null) pay(transfers,payer,winner,Math.multiplyExact(honba,100),Kind.HONBA);
        }
        if (pao != null) {
            pay(transfers,pao.liableSeat(),winner,covered.totalPoints(),Kind.HAND);
            pay(transfers,pao.liableSeat(),winner,Math.multiplyExact(honba,200),Kind.HONBA);
        }
        payDeposits(transfers,winner);
        return settle(List.of(winner),transfers);
    }

    /** Actual exhaustive draw only; the round owner establishes tenpai and nagashi eligibility. */
    public Settlement settleExhaustive(Set<Integer> tenpai, Set<Integer> nagashi) {
        requireActive();
        tenpai = Set.copyOf(tenpai); nagashi = Set.copyOf(nagashi);
        tenpai.forEach(ThreePlayerLedger::requireSeat); nagashi.forEach(ThreePlayerLedger::requireSeat);
        List<Transfer> transfers = new ArrayList<>();
        if (!nagashi.isEmpty()) {
            // User-confirmed variant: each winner receives an independent mangan tsumo,
            // including payments from other nagashi winners. No honba or deposit collection.
            for (int winner : nagashi.stream().sorted().toList()) {
                var payment = ThreePlayerPointCalculator.calculate(5,30,0,winner==dealerSeat,true).payment();
                for (int payer=0; payer<3; payer++) if (payer!=winner) {
                    pay(transfers,payer,winner,payer==dealerSeat?payment.fromDealer():payment.fromNonDealer(),Kind.NAGASHI);
                }
            }
        } else if (!tenpai.isEmpty() && tenpai.size()!=3) {
            // Record conserved net obligations in hundreds. In the two/two case, fictitious
            // 750-point pairwise transfers would violate the actual point-stick denomination.
            int[] owed = new int[3], receivable = new int[3];
            for (int seat=0; seat<3; seat++) {
                if (tenpai.contains(seat)) receivable[seat]=2_000/tenpai.size();
                else owed[seat]=2_000/(3-tenpai.size());
            }
            for (int payer=0; payer<3; payer++) for (int receiver=0; receiver<3; receiver++) {
                int amount=Math.min(owed[payer],receivable[receiver]);
                if (amount>0) { pay(transfers,payer,receiver,amount,Kind.NOTEN); owed[payer]-=amount; receivable[receiver]-=amount; }
            }
        }
        return settle(nagashi.stream().sorted().toList(),transfers,depositSticks);
    }

    /** Actual abortive draw: no noten payments, no honba payment, existing deposits carry. */
    public Settlement settleAbortive() {
        requireActive();
        return settle(List.of(),List.of(),depositSticks);
    }

    private void requirePayment(ThreePlayerScoreResult value,int winner,boolean tsumo) {
        var expected = ThreePlayerPointCalculator.calculate(value.han(),value.fu(),value.yakumanUnits(),winner == dealerSeat,tsumo);
        if (!expected.equals(value.points())) throw new IllegalArgumentException("Hand payment does not match the actual dealer and method");
    }

    private ThreePlayerPointCalculator.Payment responsibilityPoints(ThreePlayerScoreResult value,int winner,Pao pao,boolean tsumo) {
        if (pao == null) return new ThreePlayerPointCalculator.Payment(0,0,0,0);
        if (pao.liableSeat() == winner) throw new IllegalArgumentException("Winner cannot be its own responsible payer");
        int units = value.yaku().stream().filter(y -> y.yaku() == pao.component()).mapToInt(YakuAward::yakumanUnits).sum();
        if (units <= 0 || units > value.yakumanUnits()) throw new IllegalArgumentException("Liability component is absent from this true yakuman");
        return ThreePlayerPointCalculator.calculate(0,0,units,winner == dealerSeat,tsumo).payment();
    }

    private void payDeposits(List<Transfer> transfers,int winner) {
        pay(transfers,-1,winner,Math.multiplyExact(depositSticks,1_000),Kind.DEPOSIT);
    }

    private Settlement settle(List<Integer> winners,List<Transfer> transfers) {
        return settle(winners,transfers,0);
    }

    private Settlement settle(List<Integer> winners,List<Transfer> transfers,int remainingDeposits) {
        long[] next = points.stream().mapToLong(Integer::longValue).toArray();
        for (Transfer transfer : transfers) {
            if (transfer.fromSeat() >= 0) next[transfer.fromSeat()] -= transfer.points();
            next[transfer.toSeat()] += transfer.points();
        }
        List<Integer> result = new ArrayList<>(); for (long point : next) result.add(Math.toIntExact(point));
        var ledger = new ThreePlayerLedger(roundId,dealerSeat,honba,result,remainingDeposits,true,processedDeclarations);
        return new Settlement(ledger,winners,transfers);
    }

    private static void pay(List<Transfer> transfers,int from,int to,int points,Kind kind) {
        if (points < 0 || points % 100 != 0 || from == to) throw new IllegalArgumentException("Invalid payment");
        if (points > 0) transfers.add(new Transfer(from,to,points,kind));
    }
    private void requireActive() { if (finished) throw new IllegalStateException("This round has already settled"); }
    private void requireRound(DiscardResponseWindow.Id id) { if (!roundId.equals(id.roundId())) throw new IllegalArgumentException("Window belongs to another round"); }
    private static void requireSeat(int seat) { if (seat < 0 || seat > 2) throw new IllegalArgumentException("Seat must be in 0..2"); }
}
