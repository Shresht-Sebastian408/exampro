package com.exampro.config.schema;

import com.exampro.dao.SubjectDao;
import com.exampro.dao.TopicDao;
import com.exampro.dao.UserDao;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.model.entity.Subject;
import com.exampro.model.entity.Topic;
import com.exampro.model.entity.User;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.enums.Role;
import com.exampro.model.question.NumericQuestion;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.model.question.ShortAnswerQuestion;
import com.exampro.model.question.TrueFalseQuestion;
import com.exampro.service.QuestionBankService;
import com.exampro.util.PasswordHasher;

import java.util.List;
import java.util.Map;

/**
 * Demo data, inserted through the normal DAO layer rather than raw SQL.
 *
 * That choice is deliberate: seeding exercises every insert path, so a broken
 * DAO fails loudly on first launch instead of quietly at demo time.
 *
 * Runs only when the users table is empty.
 */
public class DataSeeder {

    public static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    public static final String DEFAULT_FACULTY_PASSWORD = "faculty123";
    public static final String DEFAULT_STUDENT_PASSWORD = "student123";

    private static final int STUDENT_COUNT = 20;
    private static final int QUESTIONS_PER_LEAF_TOPIC = 2;

    private static final QuestionType[] TYPES = QuestionType.values();
    private static final Difficulty[] DIFFICULTIES = Difficulty.values();

    private final UserDao userDao = new UserDaoImpl();
    private final SubjectDao subjectDao = new SubjectDaoImpl();
    private final TopicDao topicDao = new TopicDaoImpl();
    // Saved through the service, so seeding also exercises the validation
    // rules. A seed question that fails validation is a bug in one or the
    // other, and this is where it should surface.
    private final QuestionBankService questionBank = new QuestionBankService();

    /** A root topic and its children. Lists, not maps, so display_order is stable. */
    private record TopicSpec(String name, List<String> children) {}

    private static final Map<String, List<TopicSpec>> TOPIC_TREE = Map.of(
        "LAW101", List.of(
            new TopicSpec("Contract Formation",
                          List.of("Offer and Acceptance", "Consideration", "Intention")),
            new TopicSpec("Breach and Remedies",
                          List.of("Damages", "Specific Performance")),
            new TopicSpec("Vitiating Factors",
                          List.of("Misrepresentation", "Duress", "Mistake"))),
        "CS201", List.of(
            new TopicSpec("Linear Structures",
                          List.of("Arrays", "Linked Lists", "Stacks and Queues")),
            new TopicSpec("Trees",
                          List.of("Binary Search Trees", "Balanced Trees", "Heaps")),
            new TopicSpec("Graphs",
                          List.of("Traversal", "Shortest Paths", "Spanning Trees"))),
        "MG150", List.of(
            new TopicSpec("Forecasting",
                          List.of("Time Series", "Regression")),
            new TopicSpec("Inventory",
                          List.of("Economic Order Quantity", "Safety Stock")),
            new TopicSpec("Quality",
                          List.of("Control Charts", "Process Capability")))
    );

    /** @return true if seeding ran, false if the database already had data. */
    public boolean seedIfEmpty() {
        userDao.ensureRolesSeeded();
        if (userDao.count() > 0) {
            return false;
        }
        long adminId = seedUsers();
        seedContent(adminId);
        return true;
    }

    private long seedUsers() {
        User admin = new User("admin", PasswordHasher.hash(DEFAULT_ADMIN_PASSWORD),
                              "System Administrator", "admin@exampro.local", Role.ADMIN);
        long adminId = userDao.insert(admin);

        userDao.insert(new User("faculty1", PasswordHasher.hash(DEFAULT_FACULTY_PASSWORD),
                                "Dr. Anita Rao", "anita.rao@exampro.local", Role.FACULTY));
        userDao.insert(new User("faculty2", PasswordHasher.hash(DEFAULT_FACULTY_PASSWORD),
                                "Prof. Daniel Okoro", "daniel.okoro@exampro.local", Role.FACULTY));

        // One hash reused across the student accounts: 20 separate BCrypt
        // hashes at cost 10 adds several seconds to first launch for no gain,
        // since they all share the same demo password anyway.
        String studentHash = PasswordHasher.hash(DEFAULT_STUDENT_PASSWORD);
        for (int i = 1; i <= STUDENT_COUNT; i++) {
            String username = String.format("student%02d", i);
            userDao.insert(new User(username, studentHash,
                                    "Student " + i, username + "@exampro.local", Role.STUDENT));
        }
        return adminId;
    }

    private void seedContent(long adminId) {
        seedSubject(adminId, "LAW101", "Contract Law",
                    "Formation, performance, breach and remedies under contract law.");
        seedSubject(adminId, "CS201", "Data Structures",
                    "Linear structures, trees and graphs with complexity analysis.");
        seedSubject(adminId, "MG150", "Operations Management",
                    "Forecasting, inventory control and quality management.");
    }

    private void seedSubject(long adminId, String code, String name, String description) {
        Subject subject = new Subject(code, name, description);
        subject.setCreatedBy(adminId);
        long subjectId = subjectDao.insert(subject);

        int rootOrder = 0;
        for (TopicSpec spec : TOPIC_TREE.get(code)) {
            long rootId = topicDao.insert(new Topic(subjectId, null, spec.name(), rootOrder++));

            int childOrder = 0;
            for (String childName : spec.children()) {
                topicDao.insert(new Topic(subjectId, rootId, childName, childOrder++));
            }
        }

        seedQuestions(adminId, subjectId);
    }

    /**
     * Two questions per leaf topic, cycling the five types and the three
     * difficulties independently so every combination that matters appears
     * somewhere. Across the three subjects that is 46 questions, inside the
     * 40 to 60 the plan asks for, and it scales with the topic tree instead
     * of being a hand-maintained list.
     *
     * The stems are openly synthetic. Demo data that pretends to be a real
     * paper is worse than demo data that admits what it is.
     */
    private void seedQuestions(long adminId, long subjectId) {
        List<Topic> leaves = topicDao.findBySubject(subjectId).stream()
                                     .filter(t -> !t.isRoot())
                                     .toList();
        int n = 0;
        for (Topic leaf : leaves) {
            for (int k = 0; k < QUESTIONS_PER_LEAF_TOPIC; k++, n++) {
                Question question = buildQuestion(TYPES[n % TYPES.length],
                                                  DIFFICULTIES[n % DIFFICULTIES.length],
                                                  leaf, n);
                question.setCreatedBy(adminId);
                questionBank.save(question);
            }
        }
    }

    private Question buildQuestion(QuestionType type, Difficulty difficulty,
                                   Topic topic, int n) {
        String name = topic.getName();
        Question q = QuestionBankService.blank(type);
        q.setTopicId(topic.getId());
        q.setDifficulty(difficulty);
        q.setMarks(switch (type) {
            case MCQ, TRUE_FALSE -> 1;
            case MSQ, NUMERIC -> 2;
            case SHORT_ANSWER -> 5;
        });
        q.setNegativeMarks(difficulty == Difficulty.HARD && type == QuestionType.MCQ ? 0.25 : 0);
        q.setExplanation("Seeded example for " + name + ".");

        switch (type) {
            case MCQ -> {
                q.setStem("Which statement best describes " + name + "?");
                setOptions(q,
                    option("The standard account of " + name + " as taught in this unit", true),
                    option("A rule commonly confused with " + name, false),
                    option("A special case of " + name + " that does not generalise", false),
                    option("None of the above", false));
            }
            case MSQ -> {
                q.setStem("Select every statement that holds for " + name + ".");
                setOptions(q,
                    option(name + " applies under the conditions set out in the unit", true),
                    option(name + " has recognised limitations", true),
                    option(name + " applies without exception in every case", false),
                    option(name + " was superseded and is no longer examinable", false));
            }
            case TRUE_FALSE -> {
                boolean answer = n % 2 == 0;
                q.setStem(answer
                    ? name + " is covered by this unit's syllabus."
                    : name + " can be applied without checking its preconditions.");
                ((TrueFalseQuestion) q).setAnswer(answer);
            }
            case NUMERIC -> {
                NumericQuestion numeric = (NumericQuestion) q;
                double answer = 10 + n * 2.5;
                numeric.setStem("A standard " + name + " calculation gives what value, to one "
                                + "decimal place?");
                numeric.setAnswer(answer);
                numeric.setTolerance(0.5);
            }
            case SHORT_ANSWER -> {
                ShortAnswerQuestion short_ = (ShortAnswerQuestion) q;
                short_.setStem("Explain " + name + " in your own words, with one example.");
                short_.getKeywords().addAll(List.of("definition", "example", "limitation"));
            }
        }
        return q;
    }

    private static QuestionOption option(String text, boolean correct) {
        return new QuestionOption(text, correct, 0);
    }

    private static void setOptions(Question q, QuestionOption... options) {
        q.getOptions().clear();
        q.getOptions().addAll(List.of(options));
    }
}
