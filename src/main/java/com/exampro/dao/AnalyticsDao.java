package com.exampro.dao;

import com.exampro.analytics.ItemObservation;
import com.exampro.analytics.ItemStat;

import java.util.List;

public interface AnalyticsDao {

    /**
     * Every (attempt, question) pair on the papers of an exam's submitted attempts,
     * including questions the student never touched (those score zero).
     */
    List<ItemObservation> observations(long examId);

    /** Replaces the exam's rows in item_statistics in one commit. */
    void saveStats(long examId, List<ItemStat> stats);
}
