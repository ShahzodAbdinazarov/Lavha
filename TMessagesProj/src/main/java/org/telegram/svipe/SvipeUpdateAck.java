package org.telegram.svipe;

/**
 * Pure decision: what does a "check for updates" press acknowledge on screen, immediately?
 *
 * <p>Android-free like {@link SvipeUpdateFiles} / {@link SvipeUpdateThrottle}, because this project
 * cannot run Robolectric and {@code SvipeUpdater.check} itself is untestable.
 *
 * <p>Why this exists. The acknowledgement used to live <em>only</em> in the "a request is already in
 * flight" branch, so the very first press on an idle updater went straight into an HTTP GET with a 15 s
 * connect + 25 s read timeout and changed nothing on screen for up to ~40 s. The user pressed again, and
 * only that second press said "Checking for updates…" — the dead-button symptom, inverted. A manual
 * check must therefore be acknowledged <em>before</em> the request goes out.
 *
 * <p>Once it is acknowledged up front, a second press that coalesces onto the same in-flight check must
 * not repeat itself, hence {@code checkAlreadyAnnounced}. That flag is not simply "drop the message from
 * the coalesce branch": the first manual press can land while the automatic cold-start check is still
 * running, and that press has never been told anything, so it still needs the message.
 */
public final class SvipeUpdateAck {

    /** What to show the moment the user presses, before anything blocks on the network. */
    public enum Ack {
        /** Say nothing: not a manual press, or this check was already acknowledged. */
        NONE,
        /** "Checking for updates…" */
        CHECKING,
        /** "Downloading update…" — a download the user started already owns the answer. */
        DOWNLOADING
    }

    private SvipeUpdateAck() {}

    /**
     * @param force                 true only for a manual "check for updates" press. An automatic
     *                              (cold-start / resume) check must stay silent: nobody asked it anything.
     * @param checkInFlight         a request is already running.
     * @param downloadInFlight      a download is already running.
     * @param checkAlreadyAnnounced the in-flight check has already been acknowledged to the user.
     */
    public static Ack forCheck(boolean force, boolean checkInFlight, boolean downloadInFlight,
                               boolean checkAlreadyAnnounced) {
        if (!force) return Ack.NONE;
        // A running download outranks a running check: it is the thing the user actually started, and the
        // check's own answer still arrives later through the force-waiter latch.
        if (downloadInFlight) return Ack.DOWNLOADING;
        if (checkInFlight) return checkAlreadyAnnounced ? Ack.NONE : Ack.CHECKING;
        return Ack.CHECKING; // idle: this press is about to start the request, acknowledge it first
    }
}
