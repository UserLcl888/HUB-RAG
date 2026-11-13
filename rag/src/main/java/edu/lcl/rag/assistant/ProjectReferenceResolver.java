package edu.lcl.rag.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.lcl.rag.business.project.ProjectNameMatcher;
import edu.lcl.rag.business.project.ProjectQueryModels.Candidate;
import edu.lcl.rag.business.project.ProjectQueryModels.Context;
import edu.lcl.rag.entity.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 各业务工具共享的项目指代解析。只使用当前会话有效状态中保存的真实候选及顺序，
 * 不从模型回复反推 ID，不查询数据库，也不因为目标业务未接入而丢失已选项目。
 */
@Component
public class ProjectReferenceResolver {
    private static final String CONTEXT_KEY = "projectQuery";
    private static final Pattern ORDINAL = Pattern.compile("第?([0-9一二两三四五六七八九十百千万]+)(?:个|项|号|组|标段)");
    private static final Pattern PROJECT_REFERENCE = Pattern.compile(
            "(?:第?[0-9一二两三四五六七八九十]+(?:个|项|号)项目|这个项目|该项目|那个项目|当前项目)");

    private final ObjectMapper mapper;
    private final ProjectNameMatcher names;

    public ProjectReferenceResolver(ObjectMapper mapper, ProjectNameMatcher names) {
        this.mapper = mapper;
        this.names = names;
    }

    public Context previous(List<ChatMessage> history) {
        ConversationState state = ConversationState.latestBusinessContext(history);
        Object value = state == null ? null : state.context().get(CONTEXT_KEY);
        if (value == null) return null;
        try {
            Context context = mapper.convertValue(value, Context.class);
            // 旧分页版本缺少新的候选字段时不复用，无需修改历史记录或迁移数据库。
            return context.displayedCandidates() == null || context.displayedSections() == null
                    || context.selectedProjectIds() == null || context.selectedSectionIds() == null ? null : context;
        } catch (IllegalArgumentException incompatibleHistory) {
            return null;
        }
    }

    public Map<String, Object> contextMap(Context context) {
        return Map.of(CONTEXT_KEY, mapper.convertValue(context,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    }

    /** 跨工具只接受唯一项目；新名称/年度不能被旧序号覆盖。 */
    public Resolution resolve(String question, Map<String, String> arguments, List<ChatMessage> history) {
        Map<String, String> args = arguments == null ? Map.of() : arguments;
        String selection = args.get("projectSelection");
        if (selection == null && question != null && PROJECT_REFERENCE.matcher(question).find()) {
            // 路由漏提取选择参数时仍可确定性解析，但用完整原话避免误取多选句中的第一个。
            selection = question;
        }
        if (selection == null || selection.isBlank() || question == null || !question.contains(selection.strip())) {
            return new Resolution(false, null, null);
        }
        Context previous = previous(history);
        if (previous == null) return new Resolution(true, null, null);
        String explicitName = args.get("projectName");
        if (explicitName != null && !isReference(explicitName)
                && !explicitName.equals(previous.projectName())) {
            return new Resolution(false, null, null);
        }
        String explicitYear = args.get("projectYear");
        if (explicitYear != null && !explicitYear.equals(String.valueOf(previous.projectYear()))) {
            return new Resolution(true, null, previous);
        }
        String resolvedSelection = selection;
        // 完整追问中的“这个项目的考勤组”等保留业务诉求，但只解析项目指代本身。
        Matcher reference = PROJECT_REFERENCE.matcher(selection);
        if (!ORDINAL.matcher(selection).find() && reference.find()
                && !selection.matches("(?s).*(?:不是|不在|都不|并非).*")) {
            String phrase = reference.group();
            if (!reference.find()) resolvedSelection = "当前项目".equals(phrase) ? "这个项目" : phrase;
        }
        List<Candidate> chosen = select(resolvedSelection, previous.displayedCandidates(), Candidate::projectName, "项目");
        if (chosen.size() != 1) return new Resolution(true, null, previous);
        Candidate project = chosen.getFirst();
        Context selected = new Context(previous.projectYear(), project.projectName(), List.of(project.projectId()),
                chosen, null, List.of(), List.of(), null, 1);
        return new Resolution(true, project, selected);
    }

    private boolean isReference(String value) {
        return PROJECT_REFERENCE.matcher(value).matches()
                || List.of("当前项目", "某项目").contains(value);
    }

    public <T> List<T> select(String selection, List<T> candidates, Function<T, String> name, String kind) {
        // 否定句不能确认为候选，“全部”不能把三个示例解释为全部匹配。
        if (selection.matches("(?s).*(?:全部|所有|都要|都看|都查|不是|不在|都不|并非|没有我要).*")) return List.of();
        if (candidates.size() == 1 && selection.strip().matches(
                "(?:看看|查看|查|看)?(?:当前这个|当前|这个|该|那个)(?:" + kind + ")?(?:的)?(?:标段|详情|信息|情况)?[。！!，,\\s]*")) {
            return candidates;
        }
        if (selection.strip().matches(
                "(?:看看|查看|查|看|选择)?(?:这些|这几个|这三个)(?:" + kind + ")?(?:的)?(?:标段|详情|信息|情况)?[。！!，,\\s]*")) {
            return candidates;
        }
        if (candidates.size() == 1 && selection.strip().matches(
                "(?:是|是的|对|对的|没错|确认|就是这个|就是这个" + kind + ")[。！!，,\\s]*")) {
            return candidates;
        }
        List<T> result = new ArrayList<>();
        Matcher ordinal = ORDINAL.matcher(selection);
        while (ordinal.find()) {
            int index = number(ordinal.group(1)) - 1;
            if (index < 0 || index >= candidates.size()) return List.of();
            T item = candidates.get(index);
            if (!result.contains(item)) result.add(item);
        }
        if (!result.isEmpty()) return result;
        String value = names.normalize(selection.replaceAll(
                "我选|我要|我想看|帮我查|查一下|查看|看看|选择|那个|这个|就是|确认|的|吧|标段", ""));
        if (value.isBlank()) return List.of();
        List<T> matches = candidates.stream().filter(item -> names.normalize(name.apply(item)).contains(value)).toList();
        return matches.size() == 1 ? matches : List.of();
    }

    private int number(String value) {
        if (value.matches("\\d{1,9}")) return Integer.parseInt(value);
        if (value.matches("[一二两三四五六七八九十]{1,3}")) {
            Map<Character, Integer> digits = Map.of('一', 1, '二', 2, '两', 2, '三', 3, '四', 4,
                    '五', 5, '六', 6, '七', 7, '八', 8, '九', 9);
            int ten = value.indexOf('十');
            if (ten < 0 && value.length() == 1) return digits.get(value.charAt(0));
            if (ten >= 0 && value.lastIndexOf('十') == ten && ten <= 1 && value.length() - ten <= 2) {
                int tens = ten == 0 ? 1 : digits.get(value.charAt(0));
                int units = ten == value.length() - 1 ? 0 : digits.get(value.charAt(ten + 1));
                return tens * 10 + units;
            }
        }
        throw new IllegalArgumentException("无法识别候选序号");
    }

    public record Resolution(boolean attempted, Candidate project, Context context) {
        public boolean resolved() {
            return project != null;
        }
    }
}
