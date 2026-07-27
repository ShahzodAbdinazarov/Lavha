package org.telegram.svipe;

/**
 * Pure decision logic for the message-sync consent prompt (JVM-testable, no Android). The Android glue
 * in {@code ChatActivity} supplies the persisted state (from {@link SvipeConfig}) and the clock, and
 * acts on the returned code.
 *
 * <p>Flow (owner-designed): the big 3-option dialog is shown ONCE. After a rejection the user is nudged
 * with a snackbar — once per chat per day — and the big dialog auto-reappears a month later (once),
 * after which it only returns when re-armed from Settings. Granting (with_partner / self_only) stops
 * every prompt. The prompt is asked of ANY 1:1 chat, not only when the peer is a Svipe user — it records
 * the user's own consent; a chat still only syncs once BOTH sides have granted (enforced server-side).
 */
public final class SvipeMsgSyncPrompt {

    private SvipeMsgSyncPrompt() {}

    public static final int NONE = 0;
    public static final int BIG_DIALOG = 1;
    public static final int SNACKBAR = 2;

    /** The monthly re-ask interval. */
    public static final long ONE_MONTH_MS = 30L * 24 * 60 * 60 * 1000;

    /**
     * @param mode               "" (undecided) / "off" (rejected) / a granted mode
     * @param bigShown           has the big dialog been shown at least once
     * @param nextBigAt          epoch ms the big dialog is due to reappear, or 0 for none
     * @param nowMs              current time
     * @param snackbarShownToday has the nudge snackbar already shown in THIS chat today
     */
    public static int decide(String mode, boolean bigShown, long nextBigAt, long nowMs,
                             boolean snackbarShownToday) {
        if (SvipeMessageSync.MODE_WITH_PARTNER.equals(mode)
                || SvipeMessageSync.MODE_SELF_ONLY.equals(mode)) {
            return NONE;                      // already granted — never nag
        }
        if (!bigShown) {
            return BIG_DIALOG;                // first ask
        }
        if (nextBigAt > 0 && nowMs >= nextBigAt) {
            return BIG_DIALOG;                // a scheduled monthly re-ask is due
        }
        return snackbarShownToday ? NONE : SNACKBAR;
    }

    /** Day index used for the once-per-chat-per-day snackbar rule. */
    public static long epochDay(long nowMs) {
        return nowMs / (24L * 60 * 60 * 1000);
    }
}
