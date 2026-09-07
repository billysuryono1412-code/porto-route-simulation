package porto.sweep.eval;

import porto.sweep.model.StableRouteRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JaccardEvaluator {
    public static final String MATCHED = "MATCHED";
    public static final String UNMATCHED = "UNMATCHED";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String NO_REFERENCE = "NO_REFERENCE";
    public static final String UNMATCHED_ROUTE_ID = "__UNMATCHED__";

    private JaccardEvaluator() {}

    /**
     * Legacy set-based match retained as a cheap diagnostic.
     */
    public static MatchResult bestMatch(Set<String> simulatedEdges, List<StableRouteRef> realRoutes) {
        double best = 0.0;
        String bestRouteId = "";
        for (StableRouteRef ref : realRoutes) {
            double score = jaccard(simulatedEdges, ref.getEdgeSet());
            if (score > best || (score == best && bestRouteId.isBlank())) {
                best = score;
                bestRouteId = ref.getRouteId();
            }
        }
        return new MatchResult(best, bestRouteId);
    }

    /**
     * Classifies a realized ordered edge sequence against the fixed observed
     * route references for the same OD. A trip is assigned to an observed route
     * only when the best score clears both the minimum score and separation
     * margin. Otherwise it is placed in the common unmatched bucket.
     */
    public static SequenceMatchResult bestSequenceMatch(
            List<String> simulatedEdges,
            List<StableRouteRef> realRoutes,
            int shortlistTopK,
            double shortlistMinJaccard,
            double nlcsWeight,
            double bigramWeight,
            double minScore,
            double minMargin
    ) {
        if (realRoutes == null || realRoutes.isEmpty()) {
            return new SequenceMatchResult(
                    0.0, 0.0, 0.0, 0.0,
                    UNMATCHED_ROUTE_ID, "", NO_REFERENCE
            );
        }

        Set<String> simulatedSet = new LinkedHashSet<>(simulatedEdges);
        List<ShortlistEntry> candidates = new ArrayList<>();
        for (StableRouteRef ref : realRoutes) {
            candidates.add(new ShortlistEntry(ref, jaccard(simulatedSet, ref.getEdgeSet())));
        }
        candidates.sort(Comparator
                .comparingDouble((ShortlistEntry e) -> e.setJaccard).reversed()
                .thenComparing(e -> e.ref.getRouteId()));

        int limit = shortlistTopK <= 0
                ? candidates.size()
                : Math.min(shortlistTopK, candidates.size());
        List<ShortlistEntry> shortlist = new ArrayList<>();
        for (ShortlistEntry entry : candidates) {
            if (shortlist.size() >= limit) {
                break;
            }
            if (entry.setJaccard >= shortlistMinJaccard) {
                shortlist.add(entry);
            }
        }
        // Always score the nearest reference so that rejected trips still have
        // useful diagnostics even when no route clears the shortlist threshold.
        if (shortlist.isEmpty() && !candidates.isEmpty()) {
            shortlist.add(candidates.get(0));
        }

        double weightSum = nlcsWeight + bigramWeight;
        if (!Double.isFinite(weightSum) || weightSum <= 0.0) {
            throw new IllegalArgumentException("Route-match NLCS and bigram weights must sum to a positive value.");
        }

        ScoredRoute best = null;
        ScoredRoute second = null;
        for (ShortlistEntry entry : shortlist) {
            double nlcs = normalizedLcs(simulatedEdges, entry.ref.getEdgeSeq());
            double bigram = bigramJaccard(simulatedEdges, entry.ref.getEdgeSeq());
            double score = (nlcsWeight * nlcs + bigramWeight * bigram) / weightSum;
            ScoredRoute scored = new ScoredRoute(entry.ref, entry.setJaccard, nlcs, bigram, score);
            if (best == null || scored.score > best.score
                    || (scored.score == best.score
                    && scored.ref.getRouteId().compareTo(best.ref.getRouteId()) < 0)) {
                second = best;
                best = scored;
            } else if (second == null || scored.score > second.score
                    || (scored.score == second.score
                    && scored.ref.getRouteId().compareTo(second.ref.getRouteId()) < 0)) {
                second = scored;
            }
        }

        if (best == null) {
            return new SequenceMatchResult(
                    0.0, 0.0, 0.0, 0.0,
                    UNMATCHED_ROUTE_ID, "", UNMATCHED
            );
        }

        double secondScore = second == null ? 0.0 : second.score;
        double margin = second == null ? best.score : best.score - second.score;
        String status;
        String assignedRouteId;
        if (best.score < minScore) {
            status = UNMATCHED;
            assignedRouteId = UNMATCHED_ROUTE_ID;
        } else if (second != null && margin < minMargin) {
            status = AMBIGUOUS;
            assignedRouteId = UNMATCHED_ROUTE_ID;
        } else {
            status = MATCHED;
            assignedRouteId = best.ref.getRouteId();
        }

        return new SequenceMatchResult(
                best.setJaccard,
                best.score,
                secondScore,
                margin,
                assignedRouteId,
                best.ref.getRouteId(),
                status
        );
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) {
            return 0.0;
        }
        return (double) inter.size() / (double) union.size();
    }

    public static double normalizedLcs(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        // Use the shorter sequence for the DP row to keep auxiliary memory low.
        List<String> rows = a;
        List<String> cols = b;
        if (cols.size() > rows.size()) {
            rows = b;
            cols = a;
        }
        int[] previous = new int[cols.size() + 1];
        int[] current = new int[cols.size() + 1];
        for (String rowValue : rows) {
            for (int j = 1; j <= cols.size(); j++) {
                if (rowValue.equals(cols.get(j - 1))) {
                    current[j] = previous[j - 1] + 1;
                } else {
                    current[j] = Math.max(previous[j], current[j - 1]);
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
            java.util.Arrays.fill(current, 0);
        }
        int lcs = previous[cols.size()];
        return (2.0 * lcs) / (a.size() + b.size());
    }

    public static double bigramJaccard(List<String> a, List<String> b) {
        Set<String> aBigrams = bigrams(a);
        Set<String> bBigrams = bigrams(b);
        return jaccard(aBigrams, bBigrams);
    }

    private static Set<String> bigrams(List<String> sequence) {
        Set<String> out = new LinkedHashSet<>();
        if (sequence.size() == 1) {
            out.add("SINGLE\u0000" + sequence.get(0));
            return out;
        }
        for (int i = 0; i + 1 < sequence.size(); i++) {
            out.add(sequence.get(i) + "\u0000" + sequence.get(i + 1));
        }
        return out;
    }

    private static class ShortlistEntry {
        final StableRouteRef ref;
        final double setJaccard;

        ShortlistEntry(StableRouteRef ref, double setJaccard) {
            this.ref = ref;
            this.setJaccard = setJaccard;
        }
    }

    private static class ScoredRoute {
        final StableRouteRef ref;
        final double setJaccard;
        final double nlcs;
        final double bigram;
        final double score;

        ScoredRoute(StableRouteRef ref, double setJaccard, double nlcs, double bigram, double score) {
            this.ref = ref;
            this.setJaccard = setJaccard;
            this.nlcs = nlcs;
            this.bigram = bigram;
            this.score = score;
        }
    }

    public static class MatchResult {
        public final double score;
        public final String routeId;

        public MatchResult(double score, String routeId) {
            this.score = score;
            this.routeId = routeId;
        }
    }

    public static class SequenceMatchResult {
        public final double bestSetJaccard;
        public final double bestScore;
        public final double secondBestScore;
        public final double margin;
        public final String assignedRouteId;
        public final String nearestRouteId;
        public final String status;

        public SequenceMatchResult(double bestSetJaccard, double bestScore,
                                   double secondBestScore, double margin,
                                   String assignedRouteId, String nearestRouteId,
                                   String status) {
            this.bestSetJaccard = bestSetJaccard;
            this.bestScore = bestScore;
            this.secondBestScore = secondBestScore;
            this.margin = margin;
            this.assignedRouteId = assignedRouteId;
            this.nearestRouteId = nearestRouteId;
            this.status = status;
        }
    }
}
