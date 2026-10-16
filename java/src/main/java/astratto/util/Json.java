package astratto.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON reader/writer used for content definitions and save files.
 * Objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Double, plus String/Boolean/null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw new IllegalArgumentException("Trailing JSON content at " + j.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) { return (Map<String, Object>) parse(text); }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; }
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw err("Unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return str();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return num();
        }
    }

    private void expect(String w) {
        if (!s.startsWith(w, i)) throw err("Expected " + w);
        i += w.length();
    }

    private RuntimeException err(String m) { return new IllegalArgumentException(m + " at offset " + i); }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = str();
            ws();
            if (s.charAt(i) != ':') throw err("Expected :");
            i++; ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("Expected , or }");
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
        }
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("Expected , or ]");
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
        }
    }

    private String str() {
        if (s.charAt(i) != '"') throw err("Expected string");
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
    }

    private Double num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("Bad value");
        return Double.parseDouble(s.substring(st, i));
    }

    // ------------------------------------------------------------------ writer
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.toString();
    }

    private static void indent(StringBuilder b, int n) { for (int k = 0; k < n; k++) b.append("  "); }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, int d) {
        if (v == null) b.append("null");
        else if (v instanceof String) quote(b, (String) v);
        else if (v instanceof Boolean) b.append(v);
        else if (v instanceof Number) {
            double x = ((Number) v).doubleValue();
            if (x == Math.rint(x) && Math.abs(x) < 1e15) b.append((long) x);
            else b.append(x);
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                indent(b, d + 1);
                quote(b, e.getKey());
                b.append(": ");
                write(b, e.getValue(), d + 1);
                if (++n < m.size()) b.append(',');
                b.append('\n');
            }
            indent(b, d);
            b.append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) { b.append("[]"); return; }
            boolean simple = true;
            for (Object o : l) if (o instanceof Map || o instanceof List) { simple = false; break; }
            b.append(simple ? "[" : "[\n");
            for (int k = 0; k < l.size(); k++) {
                if (!simple) indent(b, d + 1);
                write(b, l.get(k), d + 1);
                if (k < l.size() - 1) b.append(simple ? ", " : ",");
                if (!simple) b.append('\n');
            }
            if (!simple) indent(b, d);
            b.append(']');
        } else quote(b, v.toString());
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------------ typed access helpers
    public static double num(Map<String, Object> m, String k, double def) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }
    public static int integer(Map<String, Object> m, String k, int def) { return (int) Math.round(num(m, k, def)); }
    public static long lng(Map<String, Object> m, String k, long def) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof String) { try { return Long.parseLong((String) o); } catch (Exception e) { return def; } }
        return o instanceof Number ? ((Number) o).longValue() : def;
    }
    public static String str(Map<String, Object> m, String k, String def) {
        Object o = m == null ? null : m.get(k);
        return o instanceof String ? (String) o : def;
    }
    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Boolean ? (Boolean) o : def;
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }
    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }
    public static List<String> strList(Map<String, Object> m, String k) {
        List<String> r = new ArrayList<>();
        for (Object o : list(m, k)) if (o != null) r.add(o.toString());
        return r;
    }
}
