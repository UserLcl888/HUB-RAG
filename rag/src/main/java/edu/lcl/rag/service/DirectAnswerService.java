package edu.lcl.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 与知识库无关的简单问题直答：寒暄、身份询问、纯算术。
 * <p>
 * 为什么需要：这类问题走完整 RAG 必然得到"在知识库中未找到…"，体验很差。
 * 判定规则保持**保守**（短且明确才算），避免把正经的知识库问题误判成寒暄：
 * <ul>
 *   <li>纯算术/符号表达式 → 本地求值，瞬时回答；</li>
 *   <li>短寒暄/身份询问 → 一次无上下文的模型直答。</li>
 * </ul>
 * 直答不检索、不标注引用、不返回来源（与"知识库未找到"一样，都不给来源）。
 *
 * @author Codex
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectAnswerService {

    private final ChatClient chatClient;

    /** 只含数字、运算符、括号、等号与问号的表达式 */
    private static final Pattern ARITHMETIC = Pattern.compile("^[\\d\\s+\\-*/×÷()=＝？?.．,，]+$");

    /** 寒暄/身份/能力询问的关键词（命中其一且问题很短才直答） */
    private static final List<String> SMALL_TALK = List.of(
            "你好", "您好", "谢谢", "多谢", "你是谁", "你叫什么", "你能做什么", "你会什么",
            "怎么用", "怎么使用", "hello", "hi");

    /** 直答问题的最大长度：超过这个长度一律走知识库检索 */
    private static final int MAX_DIRECT_LENGTH = 30;

    /** 寒暄类问题的最大长度 */
    private static final int MAX_SMALL_TALK_LENGTH = 15;

    private static final String DIRECT_SYSTEM = """
            你是农田建设领域的知识助手。
            用户这句话与知识库资料无关（日常寒暄、身份询问、算术等）。请直接用中文简洁回答：
            1. 不要提及知识库、检索或资料；
            2. 不要输出任何引用标注（如 [参考1]）；
            3. 不要编造专业知识、条文或标准号，也不要假装查过资料。
            """;

    /**
     * 判断是否属于"与知识库无关的简单问题"。
     *
     * @param question 用户问题
     * @return true 表示直接回答，不做检索
     */
    public boolean shouldAnswerDirectly(String question) {
        if (question == null) {
            return false;
        }
        String trimmed = question.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DIRECT_LENGTH) {
            return false;
        }
        if (isArithmetic(trimmed)) {
            return true;
        }
        String lower = trimmed.toLowerCase();
        return trimmed.length() <= MAX_SMALL_TALK_LENGTH
                && SMALL_TALK.stream().anyMatch(word -> lower.contains(word.toLowerCase()));
    }

    /** 流式直答（SSE 用）：算术本地算，其余交给模型 */
    public Flux<String> streamAnswer(String question) {
        Optional<String> computed = evaluate(question);
        if (computed.isPresent()) {
            return Flux.just(computed.get());
        }
        return chatClient.prompt().system(DIRECT_SYSTEM).user(question).stream().content();
    }

    /** 同步直答（/api/v1/rag/query 用） */
    public String answer(String question) {
        Optional<String> computed = evaluate(question);
        if (computed.isPresent()) {
            return computed.get();
        }
        return chatClient.prompt().system(DIRECT_SYSTEM).user(question).call().content();
    }

    /** 纯算术表达式：全部字符合法、含运算符、含数字 */
    private boolean isArithmetic(String question) {
        if (!ARITHMETIC.matcher(question).matches()) {
            return false;
        }
        String compact = question.replaceAll("[\\s=＝？?.．,，]", "");
        return compact.matches(".*[+\\-*/×÷].*") && compact.matches(".*\\d.*");
    }

    /**
     * 本地求值（只支持 + - * / 与括号）。算不了就返回空，交给模型。
     *
     * @param question 形如 {@code 1+1=?}
     * @return 形如 {@code 1+1 = 2} 的答案
     */
    Optional<String> evaluate(String question) {
        String expression = question.replaceAll("[=＝？?\\s]", "")
                .replace('×', '*').replace('÷', '/')
                .replace('．', '.').replace('，', ',');
        if (!expression.matches("[\\d+\\-*/().]+")) {
            return Optional.empty();
        }
        try {
            double value = new Parser(expression).parse();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return Optional.empty();
            }
            String printed = value == Math.rint(value) && Math.abs(value) < 1e15
                    ? String.valueOf((long) value)
                    : String.valueOf(value);
            return Optional.of(expression.replace('*', '×').replace('/', '÷') + " = " + printed);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 极简递归下降解析器：expression → term → factor */
    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        double parse() {
            double value = expression();
            if (pos < text.length()) {
                throw new IllegalArgumentException("表达式未解析完：" + text);
            }
            return value;
        }

        private double expression() {
            double value = term();
            while (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                char operator = text.charAt(pos++);
                double right = term();
                value = operator == '+' ? value + right : value - right;
            }
            return value;
        }

        private double term() {
            double value = factor();
            while (pos < text.length() && (text.charAt(pos) == '*' || text.charAt(pos) == '/')) {
                char operator = text.charAt(pos++);
                double right = factor();
                if (operator == '/' && right == 0) {
                    throw new ArithmeticException("除数为 0");
                }
                value = operator == '*' ? value * right : value / right;
            }
            return value;
        }

        private double factor() {
            if (pos < text.length() && text.charAt(pos) == '(') {
                pos++;
                double value = expression();
                if (pos >= text.length() || text.charAt(pos) != ')') {
                    throw new IllegalArgumentException("括号不匹配");
                }
                pos++;
                return value;
            }
            if (pos < text.length() && text.charAt(pos) == '-') {
                pos++;
                return -factor();
            }
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("缺少数字");
            }
            return Double.parseDouble(text.substring(start, pos));
        }
    }
}
