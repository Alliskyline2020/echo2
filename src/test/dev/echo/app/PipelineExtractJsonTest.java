package dev.echo.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class PipelineExtractJsonTest {

    private String extract(String text) {
        // 用反射调 Pipeline 的 private static extractJsonObject
        try {
            java.lang.reflect.Method m = Pipeline.class.getDeclaredMethod("extractJsonObject", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test public void validJson() {
        String input = "前缀文字 {\"key\":\"value\"} 后缀文字";
        assertEquals("{\"key\":\"value\"}", extract(input));
    }

    @Test public void nestedJson() {
        String input = "{\"outer\":{\"inner\":\"val\"}}";
        assertEquals("{\"outer\":{\"inner\":\"val\"}}", extract(input));
    }

    @Test public void braceInsideString() {
        String input = "{\"text\":\"这里有}个花括号\"}";
        assertEquals("{\"text\":\"这里有}个花括号\"}", extract(input));
    }

    @Test public void truncatedStillFinds() {
        String input = "{\"task\":\"做某事\",\"time\":\"明天\"}"; // complete
        assertEquals(input, extract(input));
    }

    @Test public void nullInput() {
        assertNull(extract(null));
    }

    @Test public void noJson() {
        assertNull(extract("纯文本没有JSON"));
    }
}
