package edu.lcl.rag.assistant;

import java.util.List;

/** 单次请求最多两个独立子任务；混合请求不是第四类意图。 */
public record IntentPlan(List<IntentDecision> tasks) {

    public static IntentPlan single(IntentDecision decision) {
        return new IntentPlan(List.of(decision));
    }
}
