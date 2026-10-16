package astratto.tests;

import static org.junit.jupiter.api.Assertions.*;

import astratto.Astratto;
import astratto.render.Shaders;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The boundary: the engine is a thing of its own. Nothing in it names a game, reads a game's files or reaches outside
 * its own directory to build; the native core never calls into Java; the Java side is all in the engine's packages.
 */
class BoundaryTest {
    private static final File ENGINE = Fixtures.engineDir();

    private static List<Path> files(String dir, String... exts) throws IOException {
        Path root = new File(ENGINE, dir).toPath();
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).filter(p -> { String n = p.toString(); for (String e : exts) if (n.endsWith(e)) return true; return false; }).toList();
        }
    }

    /** Code without its comments and string contents (a game may be named in history notes, never in code). */
    static String code(String src, boolean keepStrings) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') { while (i < n && src.charAt(i) != '\n') i++; }
            else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') { int e = src.indexOf("*/", i + 2); i = e < 0 ? n : e + 2; out.append(' '); }
            else if (c == '"') {
                int j = i + 1;
                while (j < n && src.charAt(j) != '"') { if (src.charAt(j) == '\\') j++; j++; }
                out.append(keepStrings ? src.substring(i, Math.min(n, j + 1)) : "\"\"");
                i = j + 1;
            } else { out.append(c); i++; }
        }
        return out.toString();
    }

    private static String read(Path p) throws IOException { return Files.readString(p, StandardCharsets.UTF_8); }

    @Test void noJavaCodeNamesTheGame() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : files("java/src/main/java", ".java")) if (code(read(p), true).toLowerCase().contains("absentialis")) bad.add(p.toString());
        assertEquals(List.of(), bad, "engine Java code naming the game");
    }

    @Test void noNativeCodeNamesTheGame() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : Stream.of(files("native/src", ".cpp", ".h"), files("native/include", ".h"), files("native/modules", ".cpp", ".h"), files("native/tests", ".cpp"),
                files("shaders", ".vert", ".frag", ".comp", ".glsl")).flatMap(List::stream).toList())
            if (code(read(p), true).toLowerCase().contains("absentialis")) bad.add(p.toString());
        assertEquals(List.of(), bad, "engine native code or shaders naming the game");
    }

    @Test void everyJavaClassIsInTheEnginesPackages() throws IOException {
        Pattern pkg = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
        List<String> bad = new ArrayList<>();
        for (Path p : files("java/src/main/java", ".java")) {
            Matcher m = pkg.matcher(read(p));
            if (!m.find() || !(m.group(1).equals("astratto") || m.group(1).startsWith("astratto."))) bad.add(p.toString());
        }
        assertEquals(List.of(), bad);
    }

    @Test void theEngineReadsNoGameFiles() throws IOException {
        // content, saves and missions are a game's: the engine is told where its data is, it never assumes a path
        Pattern path = Pattern.compile("\"/?(content|saves|missions|assets)/");
        List<String> bad = new ArrayList<>();
        for (Path p : files("java/src/main/java", ".java")) if (path.matcher(code(read(p), true)).find()) bad.add(p.toString());
        assertEquals(List.of(), bad, "engine code reading a game's files by path");
    }

    @Test void theNativeCoreNeverCallsJava() throws IOException {
        // no JNI anywhere in the native core: it is bound through plain C calls, and never calls back
        Pattern jni = Pattern.compile("jni\\.h|JNIEnv|JavaVM|Call\\w*Method|AttachCurrentThread");
        List<String> bad = new ArrayList<>();
        for (Path p : Stream.of(files("native/src", ".cpp", ".h"), files("native/include", ".h")).flatMap(List::stream).toList())
            if (jni.matcher(code(read(p), false)).find()) bad.add(p.toString());
        assertEquals(List.of(), bad);
    }

    @Test void theInterfaceHasNoCallbacksIntoTheCaller() throws IOException {
        // astratto.h's only function pointer type is the GL loader (a C function of the platform layer)
        String h = code(read(new File(ENGINE, "native/include/astratto/astratto.h").toPath()), false);
        Matcher m = Pattern.compile("\\(\\s*\\*\\s*(\\w+)\\s*\\)").matcher(h);
        List<String> pointers = new ArrayList<>();
        while (m.find()) pointers.add(m.group(1));
        assertEquals(List.of("astratto_gl_loader"), pointers);
    }

    @Test void theBuildStaysInsideTheEngineDirectory() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String f : new String[]{"build.gradle", "settings.gradle", "native/CMakeLists.txt", "native/build.bat"}) {
            Path p = new File(ENGINE, f).toPath();
            assertTrue(Files.isRegularFile(p), f + " is missing");
            String c = code(read(p), true).replace("\r", "");
            for (String line : c.split("\n")) {
                if (line.trim().startsWith("rem ") || line.trim().startsWith("#")) continue;
                if (line.contains("../") || line.contains("..\\") || line.toLowerCase().contains("absentialis")) bad.add(f + ": " + line.trim());
            }
        }
        assertEquals(List.of(), bad);
    }

    @Test void theEngineCarriesItsLicenceAndNotices() {
        for (String f : new String[]{"LICENSE.md", "NOTICE.md", "README.md", "docs/ARCHITECTURE.md", "docs/API.md", "resources/fonts/LICENSE-DejaVu.txt"})
            assertTrue(new File(ENGINE, f).isFile(), f + " is missing");
    }

    @Test void everyShaderTheRenderersUseIsBundled() {
        for (String s : Shaders.PROGRAMS) assertFalse(Shaders.source(s).isBlank(), s);
        assertTrue(Shaders.class.getResource("/astratto/shaders/lighting.frag") != null, "shaders are bundled into the jar's resources");
        assertTrue(Shaders.class.getResource("/astratto/resources/fonts/DejaVuSans.ttf") != null, "the engine's own resources are bundled");
    }

    @Test void javaFallbacksAreOffForTheseTests() {
        assertFalse(Astratto.javaFallbackAllowed(), "the test task runs with ASTRATTO_JAVA_FALLBACK=0");
    }
}
