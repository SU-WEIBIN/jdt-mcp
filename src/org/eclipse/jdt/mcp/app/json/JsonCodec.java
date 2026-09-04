package org.eclipse.jdt.mcp.app.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small dependency-free JSON codec used by the bootstrap application.
 *
 * <p>This is intentionally limited to the JSON types needed by the phase-0
 * configuration and stdio protocol. It will be replaced or isolated behind
 * the MCP protocol adapter when the final SDK dependency is introduced.</p>
 */
public final class JsonCodec {

    private JsonCodec() {
    }

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

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    public static String stringify(Object value) {
        StringBuilder result = new StringBuilder();
        write(value, result);
        return result.toString();
    }

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

    private static final class Parser {
        private final String input;
        private int position;

        Parser(String input) {
            this.input = input;
        }

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

        void consumeDigits() {
            int start = position;
            while (!isAtEnd() && Character.isDigit(input.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw error("Expected a digit");
            }
        }

        void consumeLiteral(String literal) {
            if (!input.startsWith(literal, position)) {
                throw error("Expected " + literal);
            }
            position += literal.length();
        }

        void expect(char expected) {
            skipWhitespace();
            if (isAtEnd() || input.charAt(position) != expected) {
                throw error("Expected '" + expected + "'");
            }
            position++;
        }

        boolean accept(char expected) {
            if (!isAtEnd() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        void skipWhitespace() {
            while (!isAtEnd() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        boolean isAtEnd() {
            return position >= input.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + position);
        }
    }
}
