package edu.lcl.rag.assistant;

import edu.lcl.rag.entity.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * 后续业务查询接口的服务端适配契约。不接受模型提供 URL、SQL 或数据源。
 * 每个已接入实现需在后端自行解析和校验参数，只能进行只读查询。
 */
public interface BusinessQueryTool {

    Definition definition();

    Result query(String question, Map<String, String> arguments, List<ChatMessage> history);

    /** 仅允许具体适配器确认短回复可依据服务端保存的真实候选继续，默认不放宽一致性校验。 */
    default boolean supportsContextReply(String question, Map<String, String> arguments, List<ChatMessage> history) {
        return false;
    }

    /** 模型可见的上下文投影；完整服务端状态仍按原样保存，不默认暴露成员单位/岗位等明细。 */
    default Map<String, Object> modelContext(Map<String, Object> context) {
        return context;
    }

    record Definition(String id, String name, String description,
                      List<String> examples, List<Parameter> parameters, boolean available) {}

    /** 参数由服务端白名单声明；后续按真实接口增加，不提前编造查询口径。 */
    record Parameter(String name, String description, boolean required, String clarification) {}

    enum State { SUCCESS, NEEDS_CLARIFICATION, NOT_CONNECTED, FAILED }

    /**
     * missingParameter 使用目录中的参数名。追问可附带真实候选，但不能把候选说成已确认目标。
     * resolvedArguments 保存后端应用的默认条件；context 保存候选/选择等结构化状态，仅存当前会话。
     */
    record Result(State state, String text, String missingParameter,
                  Map<String, String> resolvedArguments, Map<String, Object> context) {
        public Result {
            resolvedArguments = resolvedArguments == null ? Map.of() : Map.copyOf(resolvedArguments);
            context = context == null ? Map.of() : Map.copyOf(context);
        }

        public Result(State state, String text) {
            this(state, text, null, Map.of(), Map.of());
        }

        public Result(State state, String text, String missingParameter) {
            this(state, text, missingParameter, Map.of(), Map.of());
        }
    }
}
