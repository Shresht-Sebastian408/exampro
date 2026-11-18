package com.exampro.service;

import com.exampro.analytics.ItemAnalyzer;
import com.exampro.analytics.ItemObservation;
import com.exampro.analytics.ItemStat;
import com.exampro.analytics.ScoreSummary;
import com.exampro.dao.AnalyticsDao;
import com.exampro.dao.impl.AnalyticsDaoImpl;

import java.util.List;

/** Reads an exam's results, analyses them, and keeps the per-question figures in item_statistics. */
public class AnalyticsService {

    public record Report(ScoreSummary summary, List<ItemStat> items) {}

    private final AnalyticsDao dao;
    private final ItemAnalyzer analyzer = new ItemAnalyzer();

    public AnalyticsService() {
        this(new AnalyticsDaoImpl());
    }

    public AnalyticsService(AnalyticsDao dao) {
        this.dao = dao;
    }

    /** An exam nobody has sat gives an empty report, not an error. */
    public Report compute(long examId) {
        List<ItemObservation> observations = dao.observations(examId);
        List<ItemStat> items = analyzer.analyze(observations);
        dao.saveStats(examId, items);
        return new Report(analyzer.summarize(observations), items);
    }
}
