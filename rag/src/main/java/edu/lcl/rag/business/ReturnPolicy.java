package edu.lcl.rag.business;

import java.util.List;

/** 不分页：默认三条以内完整返回，超出先追问；用户可明确要求全部或前 N 条。 */
public final class ReturnPolicy {
    public static final int EXAMPLE_COUNT = 3;

    private ReturnPolicy() {}

    public record Choice(Integer count, boolean all) {
        public Choice {
            if ((!all && count == null) || (count != null && (count < 1 || all))) {
                throw new IllegalArgumentException("返回条数必须为正数，且不能与全部同时指定");
            }
        }
    }

    public static boolean needsChoice(long total, Choice choice) {
        return total > EXAMPLE_COUNT && choice == null;
    }

    public static <T> List<T> visible(List<T> values, Choice choice) {
        long limit = choice == null ? EXAMPLE_COUNT : choice.all() ? values.size() : choice.count();
        return values.stream().limit(limit).toList();
    }
}
