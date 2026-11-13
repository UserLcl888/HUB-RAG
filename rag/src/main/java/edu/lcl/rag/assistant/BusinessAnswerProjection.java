package edu.lcl.rag.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** ID 只供后端解析，回答模型只接收业务展示投影；不修改完整会话快照。 */
@Component
public class BusinessAnswerProjection {
    private final ObjectMapper mapper;

    public BusinessAnswerProjection(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 展示偏好逐字段更新；“投资不展示，再说成员单位”不应误隐藏成员。 */
    public String mergePreference(Object previous, String proposed) {
        var hidden = new LinkedHashSet<String>();
        updateHidden(hidden, previous == null ? "" : previous.toString());
        updateHidden(hidden, proposed == null ? "" : proposed);
        return hidden.isEmpty() ? "" : "不展示" + String.join("、", hidden);
    }

    private void updateHidden(Set<String> hidden, String expression) {
        for (String clause : expression.split("[,，。；;]|但是|不过|同时|另外")) {
            boolean negative = clause.matches("(?s).*(?:不|别|无需|省略|隐藏|去掉).*");
            boolean restore = clause.matches("(?s).*(?:恢复|重新展示|重新显示|展示|显示|列出).*") && !negative;
            if (!negative && !restore) continue;
            updateField(hidden, "投资金额", clause.matches("(?s).*(?:投资|金额|资金).*"), negative);
            updateField(hidden, "面积", clause.contains("面积"), negative);
            updateField(hidden, "成员姓名", clause.matches("(?s).*(?:姓名|名单|成员).*"), negative);
            updateField(hidden, "编号", clause.matches("(?s).*(?:编号|编码|代码).*"), negative);
        }
    }

    private void updateField(Set<String> hidden, String field, boolean mentioned, boolean negative) {
        if (!mentioned) return;
        if (negative) hidden.add(field); else hidden.remove(field);
    }

    public ObjectNode project(Object facts, String preference) {
        ObjectNode tree = mapper.valueToTree(facts);
        JsonNode evidence = tree.get("evidence");
        if (evidence != null && evidence.isTextual() && evidence.asText().strip().startsWith("{")) {
            try {
                tree.set("evidence", mapper.readTree(evidence.asText()));
            } catch (Exception invalidJson) {
                throw new IllegalArgumentException("业务回答依据不是有效 JSON", invalidJson);
            }
        }
        strip(tree, mergePreference(null, preference));
        return tree;
    }

    private void strip(JsonNode node, String preference) {
        if (node instanceof ObjectNode object) {
            var remove = new ArrayList<String>();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey();
                if (key.equals("id") || key.endsWith("Id") || key.endsWith("Ids")
                        || Set.of(BusinessSnapshots.KEY, "activeBusinessTool", "delFlag", "status",
                        "constructionStatusCode", "statusCode", "subjectType", "userRole",
                        "pointCoordinate", "tenantId", "appliedArguments", "arguments").contains(key)
                        || excluded(key, preference)) remove.add(key);
                else strip(field.getValue(), preference);
            }
            object.remove(remove);
        } else if (node.isArray()) node.forEach(value -> strip(value, preference));
    }

    private boolean excluded(String key, String preference) {
        if (preference.isBlank()) return false;
        // displayPreference 是用户的“不展示…”原话，只作字段投影，不是模型执行指令。
        if (!preference.matches("(?s).*(?:不|别|无需|省略|隐藏|去掉).*")) return false;
        if (preference.matches("(?s).*(?:投资|金额|资金).*")
                && Set.of("totalInvestmentWanYuan", "contractAmountYuan", "winningBidAmountYuan").contains(key)) return true;
        if (preference.contains("面积") && key.toLowerCase().contains("area") && !key.endsWith("Enabled")) return true;
        if (preference.matches("(?s).*(?:姓名|名单|成员).*") && key.equals("memberUsers")) return true;
        if (preference.matches("(?s).*(?:编号|编码|代码).*") && key.endsWith("Code")) return true;
        return false;
    }
}
