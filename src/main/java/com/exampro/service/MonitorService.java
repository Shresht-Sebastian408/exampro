package com.exampro.service;

import com.exampro.dao.AttemptDao;
import com.exampro.dao.ProctorEventDao;
import com.exampro.dao.UserDao;
import com.exampro.dao.impl.AttemptDaoImpl;
import com.exampro.dao.impl.ProctorEventDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.model.entity.Attempt;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.entity.User;
import com.exampro.model.enums.AttemptStatus;
import com.exampro.model.enums.Severity;
import com.exampro.proctor.IntegrityScore;
import com.exampro.proctor.IntegrityScorer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the live monitor shows: one tile per attempt, ordered so the ones that
 * need a look come first, plus a newest-first feed of everything that happened.
 */
public class MonitorService {

    /** @param lastHighAt time of the latest HIGH or CRITICAL event, null if none */
    public record Tile(Attempt attempt, String student, IntegrityScore integrity,
                       List<ProctorEvent> events, LocalDateTime lastHighAt) {

        public boolean live() {
            return attempt.getStatus() == AttemptStatus.IN_PROGRESS;
        }
    }

    public record FeedItem(ProctorEvent event, String student) {}

    public record Snapshot(List<Tile> tiles, List<FeedItem> feed) {}

    private final AttemptDao attempts;
    private final ProctorEventDao events;
    private final UserDao users;
    private final IntegrityScorer scorer;
    private final Map<Long, String> names = new HashMap<>();

    public MonitorService() {
        this(new AttemptDaoImpl(), new ProctorEventDaoImpl(), new UserDaoImpl(), new IntegrityScorer());
    }

    public MonitorService(AttemptDao attempts, ProctorEventDao events, UserDao users, IntegrityScorer scorer) {
        this.attempts = attempts;
        this.events = events;
        this.users = users;
        this.scorer = scorer;
    }

    public Snapshot snapshot(long examId, int feedSize) {
        List<Attempt> all = attempts.findByExam(examId);
        Map<Long, List<ProctorEvent>> byAttempt = new HashMap<>();
        List<ProctorEvent> everything = events.findByExam(examId);
        for (ProctorEvent e : everything) {
            byAttempt.computeIfAbsent(e.getAttemptId(), k -> new ArrayList<>()).add(e);
        }

        List<Tile> tiles = new ArrayList<>();
        for (Attempt a : all) {
            List<ProctorEvent> list = byAttempt.getOrDefault(a.getId(), List.of());
            LocalDateTime lastHigh = list.stream()
                .filter(e -> e.getSeverity().ordinal() >= Severity.HIGH.ordinal())
                .map(ProctorEvent::getOccurredAt).max(Comparator.naturalOrder()).orElse(null);
            tiles.add(new Tile(a, nameOf(a.getStudentId()), scorer.score(list), list, lastHigh));
        }
        tiles.sort(ORDER);

        Map<Long, String> studentOf = new HashMap<>();
        all.forEach(a -> studentOf.put(a.getId(), nameOf(a.getStudentId())));
        List<FeedItem> feed = new ArrayList<>();
        for (int i = everything.size() - 1; i >= 0 && feed.size() < feedSize; i--) {
            ProctorEvent e = everything.get(i);
            feed.add(new FeedItem(e, studentOf.get(e.getAttemptId())));
        }
        return new Snapshot(tiles, feed);
    }

    /** Live attempts first; then the most recent serious event; then the lowest integrity; then name. */
    static final Comparator<Tile> ORDER = Comparator
        .comparing((Tile t) -> !t.live())
        .thenComparing(Tile::lastHighAt, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparingDouble((Tile t) -> t.integrity().score())
        .thenComparing(Tile::student);

    private String nameOf(Long userId) {
        return names.computeIfAbsent(userId,
            id -> users.findById(id).map(User::getFullName).orElse("Student " + id));
    }
}
