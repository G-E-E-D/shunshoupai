package com.shunshoupai.capture;

import org.junit.jupiter.api.Test;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void utf8JsonLinesAndPartialFinalLineReadSafely() throws Exception {
        StringReader reader = new StringReader("{\"text\":\"中文\"}\r\n{}\nlast");
        assertEquals("中文", Json.object(Main.readLine(reader)).get("text").getAsString()); assertEquals("{}", Main.readLine(reader)); assertEquals("last", Main.readLine(reader)); assertNull(Main.readLine(reader));
    }
    @Test void oversizedHostMessageRejectedBeforeUnboundedAllocation() {
        assertThrows(java.io.IOException.class, () -> Main.readLine(new StringReader("x".repeat(512 * 1024 + 1))));
    }
    @Test void chineseSpacesNormalizeWithoutDestroyingLineBoundariesOrEnglish() {
        assertEquals("明天开会\n明天写周报\nProject meeting", OcrWorker.normalize(" 明 天 开 会\r\n明 天 写 周 报\r\nProject meeting \n"));
    }
}
