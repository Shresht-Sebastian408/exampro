package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DemoSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.config.schema.DataSeeder;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.service.AnalyticsService;
import com.exampro.service.MonitorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The demo data has to actually surface the features it is there for. */
class DemoSeederTest {

    static {
        System.setProperty("exampro.jdbcUrl", "jdbc:h2:mem:exampro_demo_test;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    @Test
    void demoExamHasSpreadScoresFlagsAndLiveSittings() throws Exception {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();
        java.lang.reflect.Method seed = DemoSeeder.class.getDeclaredMethod("seed");
        seed.setAccessible(true);
        assertTrue((boolean) seed.invoke(null));
        assertTrue(!(boolean) seed.invoke(null), "second run changes nothing");

        long examId = new ExamDaoImpl().findAll().stream()
            .filter(e -> e.getTitle().contains("demo")).findFirst().orElseThrow().getId();

        assertTrue(new ExamDaoImpl().findById(examId).orElseThrow().isPublished(), "students can see it");

        assertEquals(12, new com.exampro.service.ManualGradingService().pending().stream()
            .filter(p -> p.exam().contains("demo")).count(), "twelve written answers wait for marks");

        var summary = new AnalyticsService().compute(examId).summary();
        assertEquals(18, summary.attempts());
        assertTrue(summary.mean() > 25 && summary.mean() < 90, "mean " + summary.mean());
        assertTrue(summary.max() > summary.min(), "scores are spread, not identical");

        var tiles = new MonitorService().snapshot(examId, 20).tiles();
        assertEquals(20, tiles.size());
        assertEquals(2, tiles.stream().filter(t -> !t.attempt().isFinished()).count(), "two still sitting");
    }
}
