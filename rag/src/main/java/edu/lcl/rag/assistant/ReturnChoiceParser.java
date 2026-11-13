package edu.lcl.rag.assistant;

import edu.lcl.rag.business.ReturnPolicy.Choice;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 模型只提取本轮原话，不允许直接生成 all=true 或数字以绕过用户确认。 */
@Component
public class ReturnChoiceParser {
    private static final Pattern COUNT = Pattern.compile(
            "(?:前|返回|展示|显示|列出|给我|看|要)?\\s*([0-9]{1,9}|[零〇一二两三四五六七八九十百千万]+)\\s*(?:条|个|项|组)");

    public Choice parse(String raw, String question) {
        if (raw == null || raw.isBlank()) return null;
        if (question == null || !question.contains(raw.strip())) {
            throw new IllegalArgumentException("返回范围必须来自本轮用户原话");
        }
        if (raw.matches("(?s).*(?:不要全部|不全|不需要全部|别全).*")) return null;
        if (raw.matches("(?s).*(?:全部|所有|都返回|都展示|都列出|都给我|都要|都看).*")) return new Choice(null, true);
        if (raw.contains("第")) return null; // 第五个是选择，不是返回五条。
        Matcher match = COUNT.matcher(raw);
        return match.find() ? new Choice(number(match.group(1)), false) : null;
    }

    private int number(String value) {
        if (value.matches("\\d+")) return Integer.parseInt(value);
        Map<Character, Integer> digits = Map.of('零', 0, '〇', 0, '一', 1, '二', 2, '两', 2,
                '三', 3, '四', 4, '五', 5, '六', 6, '七', 7);
        int total = 0, section = 0, digit = 0;
        for (char c : value.toCharArray()) {
            if (c == '八' || c == '九' || digits.containsKey(c)) {
                digit = c == '八' ? 8 : c == '九' ? 9 : digits.get(c);
            } else if (c == '万') {
                total += (section + digit) * 10000;
                section = digit = 0;
            } else {
                int unit = c == '十' ? 10 : c == '百' ? 100 : 1000;
                section += (digit == 0 ? 1 : digit) * unit;
                digit = 0;
            }
        }
        return total + section + digit;
    }
}
