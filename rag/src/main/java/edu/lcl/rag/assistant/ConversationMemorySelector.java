package edu.lcl.rag.assistant;

import edu.lcl.rag.entity.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最近十轮是候选，不是必须传入模型的记忆。依据用户原话中的关联、补条件链和指代选取最多四轮，
 * 助手长回答不参与关键词打分，避免错误回答自我强化；不根据 intent 强行续接话题。
 */
@Service
@Slf4j
public class ConversationMemorySelector {

    public static final int CANDIDATE_ROUNDS = 10;
    public static final int MAX_SELECTED_ROUNDS = 4;
    private static final double MIN_SCORE = 2.5;
    private static final Pattern TEXT = Pattern.compile("[\\p{IsHan}]+|[a-z0-9_-]+");
    private static final Pattern PROJECT = Pattern.compile(
            "([\\p{IsHan}a-zA-Z0-9_-]{1,24})(?:项目|标段|片区)");
    private static final Pattern DATE = Pattern.compile(
            "\\d{4}[年/.-]\\d{1,2}(?:[月/.-]\\d{1,2}日?)?|\\d{1,2}月\\d{1,2}日");
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "这个|那个|这些|那些|刚才|上面|前面|上一|上个|继续|然后|接着|再说|再看看|它|该项目");
    private static final List<String> FILLERS = List.of(
            "麻烦你", "能不能", "可不可以", "能否", "帮忙", "帮我", "帮", "请问", "请你", "请",
            "我想", "想要", "了解一下", "告诉我", "分析一下", "分析", "看看", "看一下", "查一下",
            "查查", "查询", "查看", "一下", "具体", "相关", "实际", "情况", "内容", "怎么样",
            "怎么", "如何", "有没有", "是否", "为什么", "是什么", "什么", "哪些", "哪个", "多少",
            "这个", "那个", "这些", "那些", "刚才", "上面", "前面", "然后", "继续", "接着",
            "再看看", "再说", "它", "你", "我", "的", "了", "吗", "呢", "啊", "吧", "那", "再");
    // 仅作同义词扩展，不是按几个固定话题分桶；没有词典命中仍使用原文字符/实体关联。
    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "attendance", List.of("考勤", "打卡", "缺勤", "出勤", "迟到", "早退", "上下班", "上班", "下班"),
            "absence", List.of("缺勤", "缺考勤", "缺卡", "未打卡", "没打卡"),
            "planning", List.of("规划", "布局", "工程布置", "总体布置"),
            "acceptance", List.of("验收", "验收资料", "竣工"),
            "weather", List.of("天气", "气温", "下雨", "降雨", "预报"));

    public List<ChatMessage> selectForRouter(String question, List<ChatMessage> history) {
        return flatten(selectTurns(question, history), false);
    }

    public List<ChatMessage> selectForRag(String question, List<ChatMessage> history) {
        return flatten(selectTurns(question, history), true);
    }

    public List<ConversationTurn> selectTurns(String question, List<ChatMessage> history) {
        List<ConversationTurn> turns = assemble(history);
        if (turns.isEmpty() || question == null || question.isBlank()) {
            return List.of();
        }
        Features current = features(question);
        boolean followUp = FOLLOW_UP.matcher(question).find();
        List<ScoredTurn> scored = new ArrayList<>();
        List<Integer> dependencies = new ArrayList<>();
        for (int index = 0; index < turns.size(); index++) {
            ConversationTurn turn = turns.get(index);
            int parent = index > 0 && continuesClarification(turn.userText(), turns.get(index - 1))
                    ? index - 1 : -1;
            dependencies.add(parent);
            double score = relevance(current, features(turn.userText()));
            boolean clarification = index == turns.size() - 1 && continuesClarification(question, turn);
            boolean directReference = followUp && current.terms().isEmpty() && current.aliases().isEmpty()
                    && index == turns.size() - 1;
            if (clarification || directReference) {
                score += 8;
            }
            if (score > 0) {
                score += 1.0 / (turns.size() - index);
            }
            scored.add(new ScoredTurn(index, score));
        }

        Set<Integer> chosen = new LinkedHashSet<>();
        // 待补条件轮必须提供给分类模型；是否继续由模型结合当前问题判断，不靠固定问法和后缀。
        if (turns.getLast().hasClarification()) {
            chosen.add(turns.size() - 1);
        }
        List<ScoredTurn> ranked = scored.stream().filter(value -> value.score() >= MIN_SCORE)
                .sorted(Comparator.comparingDouble(ScoredTurn::score).reversed()
                        .thenComparing(Comparator.comparingInt(ScoredTurn::index).reversed()))
                .toList();
        for (ScoredTurn value : ranked) {
            if (chosen.size() >= MAX_SELECTED_ROUNDS && !chosen.contains(value.index())) {
                break;
            }
            chosen.add(value.index());
            int parent = dependencies.get(value.index());
            for (int depth = 0; parent >= 0 && depth < 2 && chosen.size() < MAX_SELECTED_ROUNDS; depth++) {
                chosen.add(parent);
                parent = dependencies.get(parent);
            }
        }
        List<ConversationTurn> selected = chosen.stream().sorted().map(turns::get).toList();
        log.debug("[Memory] candidates={} selected={} scores={}", turns.size(),
                selected.stream().map(turn -> turn.user().getId()).toList(),
                scored.stream().filter(value -> chosen.contains(value.index()))
                        .map(value -> String.format(Locale.ROOT, "%.2f", value.score())).toList());
        return selected;
    }

    public boolean isConsistent(String question, String resolvedQuery, List<ChatMessage> selectedHistory) {
        if (question == null || question.isBlank() || resolvedQuery == null || resolvedQuery.isBlank()) {
            return false;
        }
        Features original = features(question);
        Features query = features(resolvedQuery);
        if (!original.terms().isEmpty() || !original.aliases().isEmpty()) {
            return relevance(original, query) >= MIN_SCORE;
        }
        return selectedHistory.stream().filter(message -> "USER".equals(message.getRole()))
                .anyMatch(message -> relevance(features(message.getContent()), query) >= MIN_SCORE);
    }

    public String clarification(String question) {
        if (features(question).aliases().contains("attendance")) {
            return "你想了解考勤制度，还是查询考勤组配置和成员？请说明具体要看哪一部分。";
        }
        return "我还不能确定这次具体想问什么，请补充咨询内容，或说明要继续前面的哪一个问题。";
    }

    private boolean continuesClarification(String question, ConversationTurn previous) {
        ConversationState state = previous.pendingState();
        if (state == null) {
            return false;
        }
        if (state.missingParameter() != null && question.length() <= 35) {
            return true;
        }
        return FOLLOW_UP.matcher(question).find()
                || relevance(features(question), features(state.query())) >= MIN_SCORE;
    }

    private List<ConversationTurn> assemble(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<ConversationTurn> result = new ArrayList<>();
        ChatMessage user = null;
        List<ChatMessage> assistants = new ArrayList<>();
        for (ChatMessage message : history) {
            if ("USER".equals(message.getRole())) {
                if (user != null) {
                    result.add(new ConversationTurn(user, assistants));
                }
                user = message;
                assistants = new ArrayList<>();
            } else if (user != null && "ASSISTANT".equals(message.getRole())) {
                assistants.add(message);
            }
        }
        if (user != null) {
            result.add(new ConversationTurn(user, assistants));
        }
        return result.size() > CANDIDATE_ROUNDS
                ? result.subList(result.size() - CANDIDATE_ROUNDS, result.size()) : result;
    }

    private List<ChatMessage> flatten(List<ConversationTurn> turns, boolean ragOnly) {
        List<ChatMessage> result = new ArrayList<>();
        for (ConversationTurn turn : turns) {
            if (ragOnly && turn.assistants().stream().noneMatch(message -> message.getIntent() == Intent.RAG)) {
                continue;
            }
            result.addAll(turn.messages(ragOnly));
        }
        return result;
    }

    private Features features(String text) {
        String original = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<String> aliases = new LinkedHashSet<>();
        SYNONYMS.forEach((id, values) -> {
            if (values.stream().anyMatch(original::contains)) {
                aliases.add(id);
            }
        });
        String cleaned = original;
        for (String filler : FILLERS) {
            cleaned = cleaned.replace(filler, " ");
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TEXT.matcher(cleaned);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() < 2) {
                continue;
            }
            terms.add(token);
            if (token.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HAN)) {
                for (int start = 0; start + 2 <= token.length(); start++) {
                    String pair = token.substring(start, start + 2);
                    if (!Set.of("项目", "工程", "人员", "记录", "制度", "政策", "规范", "数据").contains(pair)) {
                        terms.add(pair);
                    }
                }
            }
        }
        terms.removeAll(Set.of("项目", "工程", "人员", "记录", "制度", "政策", "规范", "数据", "要求", "规定"));
        Set<String> entities = new LinkedHashSet<>();
        Matcher projects = PROJECT.matcher(cleaned.replace(" ", ""));
        while (projects.find()) {
            String entity = projects.group();
            if (!entity.matches("(?:这个|那个|哪个|某|该|什么|当前)(?:项目|标段|片区)")) {
                entities.add(entity);
            }
        }
        Matcher dates = DATE.matcher(original);
        while (dates.find()) {
            entities.add(dates.group());
        }
        return new Features(terms, aliases, entities);
    }

    private double relevance(Features left, Features right) {
        long overlap = left.terms().stream().filter(right.terms()::contains).count();
        long aliases = left.aliases().stream().filter(right.aliases()::contains).count();
        long entities = left.entities().stream().filter(right.entities()::contains).count();
        return Math.min(overlap, 5) * 3.0 + Math.min(aliases, 2) * 4.0 + Math.min(entities, 2) * 5.0;
    }

    private record Features(Set<String> terms, Set<String> aliases, Set<String> entities) {
    }

    private record ScoredTurn(int index, double score) {
    }
}
