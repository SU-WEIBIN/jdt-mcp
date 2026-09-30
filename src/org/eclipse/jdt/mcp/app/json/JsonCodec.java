package org.eclipse.jdt.mcp.app.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无第三方依赖的精简 JSON 编解码器：为配置解析和 MCP JSON-RPC 提供 parse、parseObject
 * 和 stringify，仅覆盖当前需要的 JSON 类型。
 *
 * <p>This is intentionally limited to the JSON types needed by the phase-0
 * configuration and stdio protocol. It will be replaced or isolated behind
 * the MCP protocol adapter when the final SDK dependency is introduced.</p>
 */
public final class JsonCodec {

    /**
     * 工具类，禁止实例化。
     */
    private JsonCodec() {
    }

    /**
     * 解析任意 JSON 值，并拒绝尾随多余字符。
     */
    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input must not be null");
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw parser.error("Unexpected characters after JSON value");
        }
        return value;
    }

    /**
     * 解析 JSON 并断言根为对象。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    /**
     * 把 Java 值序列化为 JSON 文本。
     */
    public static String stringify(Object value) {
        StringBuilder result = new StringBuilder();
        write(value, result);
        return result.toString();
    }

    /**
     * 按运行时类型分派写出一个 JSON 值。
     */
    private static void write(Object value, StringBuilder result) {
        if (value == null) {
            result.append("null");
        } else if (value instanceof String) {
            writeString((String) value, result);
        } else if (value instanceof Number || value instanceof Boolean) {
            result.append(value);
        } else if (value instanceof Map<?, ?>) {
            writeObject((Map<?, ?>) value, result);
        } else if (value instanceof Iterable<?>) {
            writeArray((Iterable<?>) value, result);
        } else if (value.getClass().isArray()) {
            result.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    result.append(',');
                }
                write(java.lang.reflect.Array.get(value, i), result);
            }
            result.append(']');
        } else {
            writeString(String.valueOf(value), result);
        }
    }

    /**
     * 写出 JSON 对象，键统一按字符串转义。
     */
    private static void writeObject(Map<?, ?> value, StringBuilder result) {
        result.append('{');
        Iterator<? extends Map.Entry<?, ?>> entries = value.entrySet().iterator();
        boolean first = true;
        while (entries.hasNext()) {
            Map.Entry<?, ?> entry = entries.next();
            if (!first) {
                result.append(',');
            }
            first = false;
            writeString(String.valueOf(entry.getKey()), result);
            result.append(':');
            write(entry.getValue(), result);
        }
        result.append('}');
    }

    /**
     * 写出 JSON 数组。
     */
    private static void writeArray(Iterable<?> value, StringBuilder result) {
        result.append('[');
        boolean first = true;
        for (Object item : value) {
            if (!first) {
                result.append(',');
            }
            first = false;
            write(item, result);
        }
        result.append(']');
    }

    /**
     * 写出 JSON 字符串，转义引号、反斜杠和控制字符。
     */
    private static void writeString(String value, StringBuilder result) {
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
            case '"':
                result.append("\\\"");
                break;
            case '\\':
                result.append("\\\\");
                break;
            case '\b':
                result.append("\\b");
                break;
            case '\f':
                result.append("\\f");
                break;
            case '\n':
                result.append("\\n");
                break;
            case '\r':
                result.append("\\r");
                break;
            case '\t':
                result.append("\\t");
                break;
            default:
                if (character < 0x20) {
                    result.append(String.format("\\u%04x", (int) character));
                } else {
                    result.append(character);
                }
                break;
            }
        }
        result.append('"');
    }

    /**
     * 递归下降 JSON 解析器：从输入字符串解析对象、数组、字符串、数字、布尔值和 null，
     * 并在格式错误时抛出带位置信息的异常。
     */
    private static final class Parser {
        private final String input;
        private int position;

        /**
         * 基于输入字符串创建解析器。
         */
        Parser(String input) {
            this.input = input;
        }

        /**
         * 解析任意 JSON 值并按首字符分派。
         */
        Object parseValue() {
            skipWhitespace();
            if (isAtEnd()) {
                throw error("Expected a JSON value");
            }
            char character = input.charAt(position);
            switch (character) {
            case '{':
                return parseObjectValue();
            case '[':
                return parseArrayValue();
            case '"':
                return parseString();
            case 't':
                consumeLiteral("true");
                return Boolean.TRUE;
            case 'f':
                consumeLiteral("false");
                return Boolean.FALSE;
            case 'n':
                consumeLiteral("null");
                return null;
            default:
                if (character == '-' || Character.isDigit(character)) {
                    return parseNumber();
                }
                throw error("Unexpected character: " + character);
            }
        }

        /**
         * 解析 JSON 对象。
         */
        Map<String, Object> parseObjectValue() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (accept('}')) {
                return object;
            }
            while (true) {
                skipWhitespace();
                if (isAtEnd() || input.charAt(position) != '"') {
                    throw error("Expected an object property name");
                }
                String name = parseString();
                skipWhitespace();
                expect(':');
                object.put(name, parseValue());
                skipWhitespace();
                if (accept('}')) {
                    return object;
                }
                expect(',');
            }
        }

        /**
         * 解析 JSON 数组。
         */
        List<Object> parseArrayValue() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (accept(']')) {
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (accept(']')) {
                    return array;
                }
                expect(',');
            }
        }

        /**
         * 解析 JSON 字符串并处理转义序列。
         */
        String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!isAtEnd()) {
                char character = input.charAt(position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (isAtEnd()) {
                    throw error("Unterminated escape sequence");
                }
                char escaped = input.charAt(position++);
                switch (escaped) {
                case '"':
                    value.append('"');
                    break;
                case '\\':
                    value.append('\\');
                    break;
                case '/':
                    value.append('/');
                    break;
                case 'b':
                    value.append('\b');
                    break;
                case 'f':
                    value.append('\f');
                    break;
                case 'n':
                    value.append('\n');
                    break;
                case 'r':
                    value.append('\r');
                    break;
                case 't':
                    value.append('\t');
                    break;
                case 'u':
                    value.append(parseUnicodeEscape());
                    break;
                default:
                    throw error("Unknown escape sequence: \\" + escaped);
                }
            }
            throw error("Unterminated string");
        }

        /**
         * 解析 Unicode 转义序列（反斜杠、u 加四位十六进制数字）。
         */
        char parseUnicodeEscape() {
            if (position + 4 > input.length()) {
                throw error("Incomplete unicode escape");
            }
            String digits = input.substring(position, position + 4);
            position += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException exception) {
                throw error("Invalid unicode escape: " + digits);
            }
        }

        /**
         * 解析整数或浮点数。
         */
        Number parseNumber() {
            int start = position;
            if (accept('-')) {
                // sign consumed
            }
            consumeDigits();
            boolean decimal = false;
            if (accept('.')) {
                decimal = true;
                consumeDigits();
            }
            if (!isAtEnd() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (!isAtEnd() && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
                    position++;
                }
                consumeDigits();
            }
            String number = input.substring(start, position);
            try {
                if (decimal) {
                    return Double.valueOf(number);
                }
                return Long.valueOf(number);
            } catch (NumberFormatException exception) {
                throw error("Invalid number: " + number);
            }
        }

        /**
         * 至少消费一位数字，否则报错。
         */
        void consumeDigits() {
            int start = position;
            while (!isAtEnd() && Character.isDigit(input.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw error("Expected a digit");
            }
        }

        /**
         * 匹配并消费 true/false/null 字面量。
         */
        void consumeLiteral(String literal) {
            if (!input.startsWith(literal, position)) {
                throw error("Expected " + literal);
            }
            position += literal.length();
        }

        /**
         * 跳过空白后期望并消费指定字符。
         */
        void expect(char expected) {
            skipWhitespace();
            if (isAtEnd() || input.charAt(position) != expected) {
                throw error("Expected '" + expected + "'");
            }
            position++;
        }

        /**
         * 若下一个字符匹配则消费并返回 true。
         */
        boolean accept(char expected) {
            if (!isAtEnd() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        /**
         * 跳过 JSON 空白字符。
         */
        void skipWhitespace() {
            while (!isAtEnd() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        /**
         * 判断是否已到达输入末尾。
         */
        boolean isAtEnd() {
            return position >= input.length();
        }

        /**
         * 构造带当前位置信息的解析异常。
         */
        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + position);
        }
    }
}
