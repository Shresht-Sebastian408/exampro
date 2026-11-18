package com.exampro.model.question;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Free text, matched against expected keywords.
 *
 * The keyword hit count is advisory: this is the one type
 * {@link com.exampro.model.enums.QuestionType#isAutoGradable()} excludes,
 * so the number feeds a faculty review screen rather than a final score.
 */
public class ShortAnswerQuestion extends Question {

    /** Insertion ordered, so the editor shows keywords as they were typed. */
    private final Set<String> keywords = new LinkedHashSet<>();

    @Override
    public QuestionType getType() {
        return QuestionType.SHORT_ANSWER;
    }

    @Override
    public boolean isAttempted(Response response) {
        return response != null
            && response.getTextAnswer() != null
            && !response.getTextAnswer().isBlank();
    }

    @Override
    public String getAnswerSummary() {
        return keywords.isEmpty() ? "no keywords set" : String.join(", ", keywords);
    }

    public Set<String> getKeywords() {
        return keywords;
    }

    /** How many expected keywords appear in the answer, case insensitive. */
    public long keywordHits(String answer) {
        if (answer == null || answer.isBlank()) {
            return 0;
        }
        String haystack = answer.toLowerCase();
        return keywords.stream()
                       .filter(k -> haystack.contains(k.toLowerCase()))
                       .count();
    }
}
