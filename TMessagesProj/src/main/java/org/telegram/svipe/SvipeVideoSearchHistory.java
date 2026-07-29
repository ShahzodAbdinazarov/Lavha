package org.telegram.svipe;

/**
 * Most-recent-first ledger of the user's video / discover search queries — the "recent searches" row
 * on the video-search screen. Identical behaviour and cap to {@link SvipeMusicSearchHistory}; only
 * the storage key differs, so the two lists never collide.
 */
public class SvipeVideoSearchHistory extends SvipeMusicSearchHistory {

    public SvipeVideoSearchHistory(int account) {
        super(account);
    }

    @Override
    protected String prefKey() {
        return SvipeConfig.PREF_VIDEO_SEARCH_HISTORY;
    }
}
