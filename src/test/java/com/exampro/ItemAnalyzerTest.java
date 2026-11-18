package com.exampro;

import com.exampro.analytics.ItemAnalyzer;
import com.exampro.analytics.ItemObservation;
import com.exampro.analytics.ItemStat;
import com.exampro.analytics.ScoreSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Item analysis on small tables that can be checked by hand. */
class ItemAnalyzerTest {

    private static ItemObservation obs(long attempt, long question, double score, Long... chosen) {
        return new ItemObservation(attempt, question, 1, score, List.of(chosen));
    }

    /**
     * Four students, two questions. Q1 is answered right by the two strongest
     * students only; Q2 by everyone.
     *   student: A  B  C  D
     *   Q1:      1  1  0  0
     *   Q2:      1  1  1  1   (so totals: A=2 B=2 C=1 D=1)
     */
    private List<ItemObservation> cohort() {
        return List.of(
            obs(1, 1, 1, 10L), obs(1, 2, 1, 20L),
            obs(2, 1, 1, 10L), obs(2, 2, 1, 20L),
            obs(3, 1, 0, 11L), obs(3, 2, 1, 20L),
            obs(4, 1, 0, 12L), obs(4, 2, 1, 20L));
    }

    @Test
    void pValueIsTheMeanFractionOfMarks() {
        List<ItemStat> stats = new ItemAnalyzer().analyze(cohort());
        assertEquals(0.5, stats.get(0).pValue());
        assertEquals(1.0, stats.get(1).pValue());
    }

    @Test
    void anItemThatSeparatesStrongFromWeakCorrelatesPerfectly() {
        ItemStat q1 = new ItemAnalyzer().analyze(cohort()).get(0);
        assertEquals(1.0, q1.discrimination(), 1e-9);
    }

    @Test
    void anItemEveryoneGetsRightCannotDiscriminate() {
        ItemStat q2 = new ItemAnalyzer().analyze(cohort()).get(1);
        assertNull(q2.discrimination(), "no spread in the item means no correlation");
        assertTrue(q2.needsRevision(), "p of 1.0 is too easy");
    }

    @Test
    void anItemWeakStudentsGetRightAndStrongOnesMissIsNegative() {
        List<ItemObservation> list = new ArrayList<>(cohort());
        // flip Q1 so the bottom two get it right
        list.set(0, obs(1, 1, 0));
        list.set(2, obs(2, 1, 0));
        list.set(4, obs(3, 1, 1));
        list.set(6, obs(4, 1, 1));
        // totals now: A=1 B=1 C=2 D=2, Q1 = 0 0 1 1 -> still positive with its own total, so add a clean anchor item
        list.add(obs(1, 3, 1)); list.add(obs(2, 3, 1)); list.add(obs(3, 3, 0)); list.add(obs(4, 3, 0));
        list.add(obs(1, 4, 1)); list.add(obs(2, 4, 1)); list.add(obs(3, 4, 0)); list.add(obs(4, 4, 0));
        // totals: A=3 B=3 C=2 D=2; Q1 = 0 0 1 1 -> negatively correlated with total
        ItemStat q1 = new ItemAnalyzer().analyze(list).stream().filter(s -> s.questionId() == 1).findFirst().orElseThrow();
        assertTrue(q1.discrimination() < 0, "a negative item should be flagged");
        assertTrue(q1.needsRevision());
    }

    @Test
    void distractorsCountEveryChoiceAndBlanksAreCountedSeparately() {
        List<ItemObservation> list = List.of(
            obs(1, 1, 1, 10L), obs(2, 1, 0, 11L), obs(3, 1, 0, 11L), obs(4, 1, 0));
        ItemStat s = new ItemAnalyzer().analyze(list).get(0);
        assertEquals(1, s.distractors().get(10L));
        assertEquals(2, s.distractors().get(11L));
        assertEquals(1, s.blanks());
    }

    @Test
    void partialCreditCountsAsAFractionOfTheMarks() {
        List<ItemObservation> list = List.of(
            new ItemObservation(1, 1, 4, 4.0, List.of()),
            new ItemObservation(2, 1, 4, 2.0, List.of()),
            new ItemObservation(3, 1, 4, 0.0, List.of()));
        assertEquals(0.5, new ItemAnalyzer().analyze(list).get(0).pValue(), 1e-9);
    }

    @Test
    void unsettledManualMarksAreLeftOutRatherThanCountedAsZero() {
        List<ItemObservation> list = List.of(
            new ItemObservation(1, 1, 4, 4.0, List.of()),
            new ItemObservation(2, 1, 4, null, List.of()));   // awaiting a marker
        ItemStat s = new ItemAnalyzer().analyze(list).get(0);
        assertEquals(1, s.attempts());
        assertEquals(1.0, s.pValue());
    }

    @Test
    void emptyInputGivesNothingAndNoCrash() {
        assertTrue(new ItemAnalyzer().analyze(List.of()).isEmpty());
        assertEquals(0, new ItemAnalyzer().summarize(List.of()).attempts());
    }

    @Test
    void summaryReportsMeanMedianAndBands() {
        // percentages: A=100, B=100, C=50, D=50
        ScoreSummary s = new ItemAnalyzer().summarize(List.of(
            obs(1, 1, 1), obs(1, 2, 1), obs(2, 1, 1), obs(2, 2, 1),
            obs(3, 1, 1), obs(3, 2, 0), obs(4, 1, 0), obs(4, 2, 1)));
        assertEquals(4, s.attempts());
        assertEquals(75.0, s.mean());
        assertEquals(75.0, s.median());
        assertEquals(50.0, s.min());
        assertEquals(100.0, s.max());
        assertEquals(2, s.bands()[5], "50% is in the 50-59 band");
        assertEquals(2, s.bands()[9], "100% is in the top band");
        assertFalse(s.bands()[0] > 0);
    }
}
