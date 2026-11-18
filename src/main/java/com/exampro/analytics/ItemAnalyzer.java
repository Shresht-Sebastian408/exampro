package com.exampro.analytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Classical item analysis on one exam's observations. Pure arithmetic, no
 * database, so it can be checked by hand against a small table.
 *
 * Discrimination is the Pearson correlation between the item's fraction of
 * marks and the student's total. With a right/wrong item that is exactly the
 * point-biserial; with partial credit it still means the same thing. The
 * total includes the item itself, which flatters short exams slightly.
 * ponytail: "corrected" item-total (total minus the item) is the upgrade.
 */
public class ItemAnalyzer {

    public List<ItemStat> analyze(List<ItemObservation> observations) {
        Map<Long, Double> totals = observations.stream()
            .filter(o -> o.score() != null)
            .collect(Collectors.groupingBy(ItemObservation::attemptId,
                                           Collectors.summingDouble(ItemObservation::score)));

        Map<Long, List<ItemObservation>> byQuestion = new LinkedHashMap<>();
        for (ItemObservation o : observations) {
            byQuestion.computeIfAbsent(o.questionId(), k -> new ArrayList<>()).add(o);
        }

        List<ItemStat> stats = new ArrayList<>();
        for (Map.Entry<Long, List<ItemObservation>> e : byQuestion.entrySet()) {
            List<ItemObservation> settled = e.getValue().stream().filter(o -> o.score() != null).toList();

            double[] fractions = settled.stream()
                .mapToDouble(o -> o.marks() == 0 ? 0 : o.score() / o.marks()).toArray();
            double[] itemTotals = settled.stream().mapToDouble(o -> totals.get(o.attemptId())).toArray();

            Double p = fractions.length == 0 ? null : Arrays.stream(fractions).average().getAsDouble();
            Double d = pearson(fractions, itemTotals);

            Map<Long, Integer> distractors = new HashMap<>();
            int blanks = 0;
            for (ItemObservation o : e.getValue()) {
                if (o.selectedOptionIds().isEmpty()) {
                    blanks++;
                }
                for (Long id : o.selectedOptionIds()) {
                    distractors.merge(id, 1, Integer::sum);
                }
            }
            stats.add(new ItemStat(e.getKey(), settled.size(), p, d, distractors, blanks));
        }
        return stats;
    }

    /** Percentage-score distribution over the attempts in the observations. */
    public ScoreSummary summarize(List<ItemObservation> observations) {
        Map<Long, double[]> perAttempt = new LinkedHashMap<>();   // [earned, possible]
        for (ItemObservation o : observations) {
            double[] t = perAttempt.computeIfAbsent(o.attemptId(), k -> new double[2]);
            t[1] += o.marks();
            if (o.score() != null) {
                t[0] += o.score();
            }
        }
        double[] pct = perAttempt.values().stream().filter(t -> t[1] > 0)
            .mapToDouble(t -> 100 * t[0] / t[1]).sorted().toArray();
        if (pct.length == 0) {
            return ScoreSummary.EMPTY;
        }

        int[] bands = new int[10];
        for (double v : pct) {
            bands[(int) Math.max(0, Math.min(9, Math.floor(v / 10)))]++;
        }
        double median = pct.length % 2 == 1
            ? pct[pct.length / 2]
            : (pct[pct.length / 2 - 1] + pct[pct.length / 2]) / 2;
        return new ScoreSummary(pct.length, Arrays.stream(pct).average().getAsDouble(), median,
                                pct[0], pct[pct.length - 1], bands);
    }

    /** Null when fewer than two points or either side has no spread. */
    static Double pearson(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) {
            return null;
        }
        double mx = Arrays.stream(x).average().getAsDouble();
        double my = Arrays.stream(y).average().getAsDouble();
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        if (sxx < 1e-12 || syy < 1e-12) {
            return null;
        }
        return sxy / Math.sqrt(sxx * syy);
    }
}
