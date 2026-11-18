package com.exampro.generation;

import com.exampro.model.entity.Blueprint;
import com.exampro.model.question.Question;

import java.util.List;

/** Everything a strategy may draw from: the subject's active questions and the exam's blueprint. */
public record QuestionPool(List<Question> questions, Blueprint blueprint) {}
