package org.telegram.svipe;

import java.net.URLDecoder;

/**
 * Extracts the signed Mini App initData (the {@code tgWebAppData} fragment param) from a
 * messages.requestWebView result URL. Decoded exactly once: the backend's parse_qsl performs the
 * second decode, matching how Telegram computes the hash over fully-decoded values.
 * Pure Java so it can be unit-tested on the JVM.
 */
public class SvipeInitData {

    public static String extract(String webViewUrl) {
        if (webViewUrl == null) return null;
        int hash = webViewUrl.indexOf('#');
        if (hash < 0 || hash == webViewUrl.length() - 1) return null;
        String fragment = webViewUrl.substring(hash + 1);
        for (String param : fragment.split("&")) {
            if (param.startsWith("tgWebAppData=")) {
                String value = param.substring("tgWebAppData=".length());
                if (value.isEmpty()) return null;
                try {
                    return URLDecoder.decode(value, "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
