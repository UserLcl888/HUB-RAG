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

    record Definition(String id, String name, String description,
                      List<String> examples, List<Parameter> parameters, boolean available) {}

    /** 参数由服务端白名单声明；后续按真实接口增加，不提前编造查询口径。 */
    record Parameter(String name, String description, boolean required, String clarification) {}

    enum State { SUCCESS, NEEDS_CLARIFICATION, NOT_CONNECTED, FAILED }

    /** missingParameter 使用目录中的参数名，不能从回答文字猜测；只有 SUCCESS 可包含真实查询结果。 */
    record Result(State state, String text, String missingParameter) {
        public Result(State state, String text) {
            this(state, text, null);
        }
    }
}
