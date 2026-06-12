package org.telegram.lavha;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LavhaInitDataTest {

    // Shape matches a real messages.requestWebView result: initData rides in the URL fragment as
    // the tgWebAppData param, itself URL-encoded one extra time.
    private static final String URL =
            "https://lavha-dev.abdinazarov.uz/webapp"
                    + "#tgWebAppData=query_id%3DAAHtest%26user%3D%257B%2522id%2522%253A777%257D"
                    + "%26auth_date%3D1662771648%26hash%3Dabc123"
                    + "&tgWebAppVersion=6.7&tgWebAppPlatform=android";

    @Test
    public void extractsAndDecodesExactlyOnce() {
        String initData = LavhaInitData.extract(URL);
        // Decoded once: separators become literal, values stay single-encoded for the backend.
        assertEquals("query_id=AAHtest&user=%7B%22id%22%3A777%7D&auth_date=1662771648&hash=abc123",
                initData);
    }

    @Test
    public void findsParamWhenNotFirstInFragment() {
        String url = "https://x/webapp#tgWebAppVersion=6.7&tgWebAppData=auth_date%3D1%26hash%3Dh";
        assertEquals("auth_date=1&hash=h", LavhaInitData.extract(url));
    }

    @Test
    public void nullWhenNoFragment() {
        assertNull(LavhaInitData.extract("https://x/webapp"));
        assertNull(LavhaInitData.extract("https://x/webapp#"));
    }

    @Test
    public void nullWhenNoOrEmptyParam() {
        assertNull(LavhaInitData.extract("https://x/webapp#tgWebAppVersion=6.7"));
        assertNull(LavhaInitData.extract("https://x/webapp#tgWebAppData="));
    }

    @Test
    public void nullOnNullInput() {
        assertNull(LavhaInitData.extract(null));
    }
}
