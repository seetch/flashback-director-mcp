package dev.seetch.flashbackdirector.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 8259 JSON parser and serializer using pure JDK 25 with no external dependencies.
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 64;

    private MiniJson() {
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON text is null");
        }
        Parser parser = new Parser(text);
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.isEof()) {
            throw new IllegalArgumentException("Unexpected trailing characters at position " + parser.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object parsed = parse(text);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) map;
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting depth exceeded");
        }
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            if (n instanceof Double d) {
                if (!Double.isFinite(d)) {
                    sb.append("null");
                } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                    sb.append(d);
                } else {
                    sb.append(d);
                }
            } else if (n instanceof Float f) {
                if (!Float.isFinite(f)) {
                    sb.append("null");
                } else {
                    sb.append(f);
                }
            } else {
                sb.append(n);
            }
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item, depth + 1);
            }
            sb.append(']');
        } else if (value instanceof Object[] array) {
            sb.append('[');
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, array[i], depth + 1);
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean isEof() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON nesting depth exceeded");
            }
            skipWhitespace();
            if (isEof()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
                }
            };
        }

        private Map<String, Object> parseObject(int depth) {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!isEof() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (isEof() || src.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key at position " + pos);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object val = parseValue(depth);
                map.put(key, val);
                skipWhitespace();
                if (isEof()) {
                    throw new IllegalArgumentException("Unterminated object");
                }
                char next = src.charAt(pos++);
                if (next == '}') {
                    return map;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at position " + (pos - 1));
                }
            }
        }

        private List<Object> parseArray(int depth) {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!isEof() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue(depth));
                skipWhitespace();
                if (isEof()) {
                    throw new IllegalArgumentException("Unterminated array");
                }
                char next = src.charAt(pos++);
                if (next == ']') {
                    return list;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at position " + (pos - 1));
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!isEof()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (isEof()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape at position " + pos);
                            }
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape '\\" + esc + "'");
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("Unescaped control character in string at position " + (pos - 1));
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            if (isEof()) {
                throw new IllegalArgumentException("Invalid number at position " + start);
            }
            if (src.charAt(pos) == '0') {
                pos++;
            } else if (src.charAt(pos) >= '1' && src.charAt(pos) <= '9') {
                while (!isEof() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            } else {
                throw new IllegalArgumentException("Invalid number at position " + start);
            }

            boolean isFloat = false;
            if (!isEof() && src.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                int fracStart = pos;
                while (!isEof() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
                if (pos == fracStart) {
                    throw new IllegalArgumentException("Invalid fraction at position " + pos);
                }
            }
            if (!isEof() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (!isEof() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                int expStart = pos;
                while (!isEof() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
                if (pos == expStart) {
                    throw new IllegalArgumentException("Invalid exponent at position " + pos);
                }
            }

            String numStr = src.substring(start, pos);
            if (!isFloat) {
                try {
                    long val = Long.parseLong(numStr);
                    if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                        return (int) val;
                    }
                    return val;
                } catch (NumberFormatException ignored) {
                    return Double.parseDouble(numStr);
                }
            }
            return Double.parseDouble(numStr);
        }

        private Object parseLiteral(String literal, Object value) {
            if (src.startsWith(literal, pos)) {
                pos += literal.length();
                return value;
            }
            throw new IllegalArgumentException("Expected '" + literal + "' at position " + pos);
        }

        private void expect(char expected) {
            if (isEof() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }
    }
}
