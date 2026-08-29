package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 版本一致性契約（Supported）：AceLibVersion.VERSION、plugin.yml 的 version 欄位、
 * 與 docs/reference/runtime-compatibility-matrix.json 的 libraryVersion 必須四個來源一致，
 * 且矩陣必須記錄已驗證的 SUPPORTED 與 VERIFIED-BETA runtime。
 *
 * <p>此測試是 TDD 的 Red→Green 錨點：在版本號尚未同步至 1.2.0 前會失敗。</p>
 */
class VersionConsistencyTest {

    private static final String EXPECTED_VERSION = "1.2.0";
    private static final Pattern BUILD_VERSION =
        Pattern.compile("^version[ \\t]*=[ \\t]*\"([^\"]+)\"[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern PLUGIN_VERSION =
        Pattern.compile("^[ \\t]*version:[ \\t]*([^\\s#]+)[ \\t]*$", Pattern.MULTILINE);

    @Test
    @DisplayName("AceLibVersion.VERSION 應為 " + EXPECTED_VERSION)
    void versionConstant_isExpected() {
        assertEquals(EXPECTED_VERSION, AceLibVersion.VERSION);
    }

    @Test
    @DisplayName("build.gradle.kts、plugin.yml 與 AceLibVersion.VERSION 版本一致")
    void versionSources_areConsistent() throws IOException {
        Path projectRoot = resolveProjectRoot();
        String buildVersion = extractVersion(
            BUILD_VERSION, Files.readString(projectRoot.resolve("build.gradle.kts"), StandardCharsets.UTF_8),
            "build.gradle.kts");
        String pluginYml = readResource("/plugin.yml");
        assertNotNull(pluginYml, "classpath 上找不到 plugin.yml");
        String pluginVersion = extractVersion(PLUGIN_VERSION, pluginYml, "plugin.yml");

        assertEquals(EXPECTED_VERSION, buildVersion, "build.gradle.kts version 必須為 release 版本");
        assertEquals(buildVersion, AceLibVersion.VERSION, "AceLibVersion.VERSION 必須等於 build.gradle.kts");
        assertEquals(buildVersion, pluginVersion, "plugin.yml version 必須等於 build.gradle.kts");
    }

    @Test
    @DisplayName("runtime-compatibility-matrix.json 存在且 libraryVersion 一致")
    void matrix_libraryVersion_consistent() throws IOException {
        Path matrix = resolveMatrixFile();
        assertTrue(Files.exists(matrix), "找不到 runtime-compatibility-matrix.json：" + matrix);
        Map<String, Object> document = parseJsonObject(Files.readString(matrix, StandardCharsets.UTF_8));
        String matrixVersion = stringField(document, "libraryVersion", "矩陣");
        String buildVersion = extractVersion(
            BUILD_VERSION,
            Files.readString(resolveProjectRoot().resolve("build.gradle.kts"), StandardCharsets.UTF_8),
            "build.gradle.kts");

        assertEquals(EXPECTED_VERSION, matrixVersion, "矩陣 libraryVersion 必須為 " + EXPECTED_VERSION);
        assertEquals(buildVersion, matrixVersion, "矩陣 libraryVersion 必須等於 build.gradle.kts");
        assertEquals(AceLibVersion.VERSION, matrixVersion, "矩陣 libraryVersion 必須等於 AceLibVersion.VERSION");
    }

    @Test
    @DisplayName("矩陣必須記錄 SUPPORTED 的 Paper/Folia 26.1.2 與 VERIFIED-BETA 的 26.2")
    void matrix_recordsVerifiedRuntimes() throws IOException {
        Path matrix = resolveMatrixFile();
        List<Object> runtimes = arrayField(
            parseJsonObject(Files.readString(matrix, StandardCharsets.UTF_8)), "runtimes", "矩陣");
        Map<String, RuntimeExpectation> expected = Map.of(
            "26.1.2-72", new RuntimeExpectation("Paper", "SUPPORTED"),
            "26.1.2-8", new RuntimeExpectation("Folia", "SUPPORTED"),
            "26.2-120", new RuntimeExpectation("Paper", "VERIFIED-BETA"),
            "26.2-7", new RuntimeExpectation("Folia", "VERIFIED-BETA"),
            "26.2-4", new RuntimeExpectation("Folia", "VERIFIED-BETA")
        );
        Map<String, RuntimeExpectation> actual = new LinkedHashMap<>();
        assertEquals(expected.size(), runtimes.size(), "矩陣 runtime 記錄數必須固定為五筆");
        for (Object runtimeValue : runtimes) {
            Map<String, Object> runtime = asObject(runtimeValue, "runtime 記錄");
            String version = stringField(runtime, "version", "runtime");
            RuntimeExpectation expectation = new RuntimeExpectation(
                stringField(runtime, "platform", version),
                stringField(runtime, "status", version));
            String evidence = stringField(runtime, "evidence", version);
            assertTrue(!evidence.isBlank(), "runtime " + version + " 必須有非空 evidence");
            assertTrue(expected.containsKey(version), "矩陣含有未預期的 runtime：" + version);
            assertTrue(actual.put(version, expectation) == null, "矩陣 runtime 不得重複：" + version);
            assertEquals(expected.get(version), expectation, "runtime 平台或 status 不符：" + version);
        }
        assertEquals(expected, actual, "矩陣的 runtime build/status 對應必須完整一致");
    }

    private static String extractVersion(Pattern pattern, String content, String source) {
        Matcher matcher = pattern.matcher(content);
        assertTrue(matcher.find(), source + " 必須包含可解析的版本行");
        return matcher.group(1);
    }

    private static Map<String, Object> parseJsonObject(String json) {
        return asObject(new JsonParser(json).parse(), "JSON root");
    }

    private static Map<String, Object> asObject(Object value, String description) {
        assertTrue(value instanceof Map<?, ?>, description + " 必須是 JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    private static List<Object> arrayField(Map<String, Object> object, String field, String description) {
        Object value = object.get(field);
        assertTrue(value instanceof List<?>, description + " 必須包含 JSON array：" + field);
        @SuppressWarnings("unchecked")
        List<Object> array = (List<Object>) value;
        return array;
    }

    private static String stringField(Map<String, Object> object, String field, String description) {
        Object value = object.get(field);
        assertTrue(value instanceof String, description + " 必須包含字串欄位：" + field);
        return (String) value;
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = VersionConsistencyTest.class.getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    private static Path resolveMatrixFile() {
        return resolveProjectRoot().resolve("docs").resolve("reference")
            .resolve("runtime-compatibility-matrix.json");
    }

    private static Path resolveProjectRoot() {
        // 從目前工作目錄向上尋找含 build.gradle.kts 的專案根。
        Path dir = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i < 8; i++) {
            if (Files.exists(dir.resolve("build.gradle.kts"))) {
                return dir;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        return dir;
    }

    private record RuntimeExpectation(String platform, String status) {
    }

    /** A deliberately small JSON parser keeps this contract test independent of runtime dependencies. */
    private static final class JsonParser {
        private final String input;
        private int position;

        private JsonParser(String input) {
            this.input = input;
        }

        private Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (position != input.length()) {
                throw error("unexpected trailing content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (position >= input.length()) {
                throw error("expected a JSON value");
            }
            return switch (input.charAt(position)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            while (true) {
                skipWhitespace();
                if (position >= input.length() || input.charAt(position) != '"') {
                    throw error("expected an object key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) {
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (consume(']')) {
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (position < input.length()) {
                char character = input.charAt(position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    if (position >= input.length()) {
                        throw error("unfinished escape sequence");
                    }
                    char escape = input.charAt(position++);
                    switch (escape) {
                        case '"', '\\', '/' -> value.append(escape);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(parseUnicodeEscape());
                        default -> throw error("invalid escape sequence");
                    }
                } else {
                    if (character < 0x20) {
                        throw error("unescaped control character");
                    }
                    value.append(character);
                }
            }
            throw error("unterminated string");
        }

        private char parseUnicodeEscape() {
            if (position + 4 > input.length()) {
                throw error("short unicode escape");
            }
            int codePoint = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                codePoint = codePoint * 16 + digit;
            }
            return (char) codePoint;
        }

        private Object parseNumber() {
            int start = position;
            if (consume('-')) {
                requireDigit();
            }
            consumeDigits();
            if (consume('.')) {
                requireDigit();
                consumeDigits();
            }
            if (consume('e') || consume('E')) {
                consume('+');
                consume('-');
                requireDigit();
                consumeDigits();
            }
            String number = input.substring(start, position);
            try {
                return number.contains(".") || number.contains("e") || number.contains("E")
                    ? Double.parseDouble(number)
                    : Long.parseLong(number);
            } catch (NumberFormatException exception) {
                throw error("invalid number");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, position)) {
                throw error("invalid literal");
            }
            position += literal.length();
            return value;
        }

        private void consumeDigits() {
            while (position < input.length() && Character.isDigit(input.charAt(position))) {
                position++;
            }
        }

        private void requireDigit() {
            if (position >= input.length() || !Character.isDigit(input.charAt(position))) {
                throw error("expected a digit");
            }
        }

        private void skipWhitespace() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        private boolean consume(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON offset " + position);
        }
    }
}
