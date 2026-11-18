package com.exampro.model.question;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Several correct options. This type owns the partial credit rule, which is
 * a property of the question rather than of the grader: the grader asks the
 * question what fraction was earned and applies the marks.
 */
public class MSQQuestion extends Question {

    @Override
    public QuestionType getType() {
        return QuestionType.MSQ;
    }

    @Override
    public boolean isAttempted(Response response) {
        return response != null && !response.getSelectedOptionIds().isEmpty();
    }

    @Override
    public String getAnswerSummary() {
        List<String> correct = correctOptions().stream().map(QuestionOption::getText).toList();
        return correct.isEmpty() ? "no correct options set" : String.join(" | ", correct);
    }

    /**
     * Fraction of the marks earned, 0 to 1.
     *
     * Any wrong selection scores zero. Partial credit for a subset of the
     * correct options is generous; partial credit for a selection that
     * includes a wrong option rewards ticking everything, which is the
     * failure mode this rule exists to prevent.
     */
    public double creditFraction(Collection<Long> selectedOptionIds) {
        Set<Long> correct = correctOptions().stream()
                                            .map(QuestionOption::getId)
                                            .collect(Collectors.toSet());
        if (correct.isEmpty() || selectedOptionIds == null || selectedOptionIds.isEmpty()) {
            return 0;
        }
        if (!correct.containsAll(selectedOptionIds)) {
            return 0;
        }
        return (double) Set.copyOf(selectedOptionIds).size() / correct.size();
    }
}
