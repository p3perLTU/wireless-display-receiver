package lt.tvcast.testing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

/**
 * A tiny test runner, so the tests need nothing but the JDK (the project has no Gradle or
 * Maven). Every {@code static void test...()} method of the classes named on the command line
 * is one test; a test fails by throwing. Exit code 1 if any test failed.
 *
 *   java -cp tests\out\java lt.tvcast.testing.TestRunner lt.tvcast.common.ProtoTest ...
 */
public final class TestRunner {
    private TestRunner() {}

    public static void main(String[] args) throws ClassNotFoundException {
        int passed = 0, failed = 0;
        for (String name : args) {
            Class<?> c = Class.forName(name);
            Method[] methods = c.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                String id = c.getSimpleName() + "." + m.getName();
                try {
                    m.invoke(null);
                    passed++;
                    System.out.println("  ok    " + id);
                } catch (InvocationTargetException e) {
                    failed++;
                    Throwable t = e.getCause();
                    System.out.println("  FAIL  " + id + ": " + t);
                    for (StackTraceElement s : t.getStackTrace()) {
                        if (s.getClassName().equals(c.getName())) System.out.println("          at line " + s.getLineNumber());
                    }
                } catch (IllegalAccessException e) {
                    failed++;
                    System.out.println("  FAIL  " + id + ": " + e);
                }
            }
        }
        System.out.println("Java: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 && passed > 0 ? 0 : 1);
    }
}
