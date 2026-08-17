package org.telegram.svipe;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the app-start warm-ups one after another, never at the same time.
 *
 * Each warm-up (reels, music, whatever comes next) pulls a feed page, resolves channels over MTProto
 * and starts a download. Two of them overlapping fight each other for the same link the user's chat
 * list is still loading over — and the loser is whichever surface the user actually opens. The first
 * version staggered them with hand-picked delays (4s, then 9s), which is a guess in both directions:
 * too short and they overlap anyway, too long and the second one is still not ready when it could
 * have been.
 *
 * A queue removes the guess. One task runs at a time, the next starts the moment its predecessor
 * says it is done, and adding a third warm-up is one line rather than a new delay to reason about.
 * Order is priority order: whatever the user is most likely to open first goes first.
 *
 * Every task is bounded. A warm-up that never reports completion — a callback lost to a dead
 * network is exactly the failure this codebase keeps meeting — must not strand the ones behind it.
 */
public final class SvipeWarmup {

    /** A unit of warm-up work. MUST call {@code done} exactly once, on any outcome. */
    public interface Task {
        void run(int account, Runnable done);
    }

    /** How long the app is left alone to finish opening before the UNURGENT warm-ups start.
     *
     * <p>This buys the cold start four seconds of quiet, and it is worth having for work the user
     * did not ask for. It is NOT what gates the tab they DID open: a screen fetches its own data the
     * moment it is created, and since {@link org.telegram.svipe.SvipeApi} got its own threads that
     * fetch no longer queues behind these.
     *
     * <p>It is also not for everything any more. Measured on a signed-in cold start, 2026-08-17:
     * the process came up at 31.32 s, the first frame drew at 32.72, and {@code GET /v1/feed} was
     * not even ISSUED until 36.33 — 5.0 s after launch, of which 4.0 s was this constant and only
     * 631 ms was the network. The reels feed is the one thing a person opens this app to see, so it
     * no longer waits; see {@link #enqueueNow}. */
    private static final long SETTLE_MS = 4000;
    /** A task that has not reported back by now is presumed stuck; the queue moves on without it. */
    private static final long TASK_DEADLINE_MS = 45_000;

    private static final ArrayDeque<Named> queue = new ArrayDeque<>();
    /** Work that starts with the app rather than after it. Drained before {@link #queue}. */
    private static final ArrayDeque<Named> urgent = new ArrayDeque<>();
    private static final java.util.HashSet<String> everEnqueued = new java.util.HashSet<>();
    private static boolean started;

    private static final class Named {
        final String name;
        final Task task;
        Named(String name, Task task) { this.name = name; this.task = task; }
    }

    private SvipeWarmup() {}

    /**
     * Add work to the tail. Order of enqueueing is the order things get warmed.
     *
     * A name is accepted once per process: the host view can be rebuilt (the tab pager does exactly
     * that) and would otherwise queue the same warm-up again on every pass.
     */
    public static void enqueue(String name, Task task) {
        synchronized (queue) {
            if (!everEnqueued.add(name)) return;
            queue.add(new Named(name, task));
        }
    }

    /**
     * Add work that must NOT wait out the settle delay, and runs ahead of everything enqueued.
     *
     * For the one surface a person opens this app to see. Waiting four seconds to ask for a feed
     * that answers in under one is four seconds of a loading spinner bought for nothing — and the
     * reason the settle existed (a warm-up fighting the chat list for a thread) stopped applying
     * when {@link org.telegram.svipe.SvipeApi} got threads of its own.
     */
    public static void enqueueNow(String name, Task task) {
        synchronized (queue) {
            if (!everEnqueued.add(name)) return;
            urgent.add(new Named(name, task));
        }
    }

    /** Begin, once per process. Urgent work starts now; the rest after the settle delay. */
    public static void start(final int account) {
        if (started) return;
        started = true;
        // Fill the window rather than hand out one task and wait for it to come back.
        for (int i = 0; i < MAX_IN_FLIGHT; i++) next(account);
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < MAX_IN_FLIGHT; i++) next(account);
        }, SETTLE_MS);
    }

    /** How many warm-ups may be in flight at once.
     *
     * <p>They were strictly serial, which made the LAST one — music — wait for the two before it:
     * measured on a cold start, reels finished at 8.8 s, video at 10.8 s and music at 14.0 s, and
     * music's own work took 3.2 s of that. They do not depend on one another, so the only thing the
     * ordering bought was a queue.
     *
     * <p>Three, not unbounded: this is the number of media warm-ups there are, and each is already
     * internally bounded. `settings` still runs first because it is enqueued first and finishes in
     * a millisecond. */
    private static final int MAX_IN_FLIGHT = 3;

    private static int inFlight;

    private static void next(final int account) {
        final Named entry;
        synchronized (queue) {
            if (inFlight >= MAX_IN_FLIGHT) return;
            // Urgent first, always. Before the settle fires this is the ONLY queue with anything in
            // it, so the early call to next() cannot accidentally start the unurgent work early.
            final Named picked = urgent.isEmpty() ? queue.poll() : urgent.poll();
            if (picked == null) return;
            entry = picked;
            inFlight++;
        }
        final AtomicBoolean finished = new AtomicBoolean();
        final long startedAt = System.currentTimeMillis();
        final Runnable done = () -> {
            if (!finished.compareAndSet(false, true)) return;
            FileLog.d("svipe: warm-up '" + entry.name + "' done in " + (System.currentTimeMillis() - startedAt) + "ms");
            synchronized (queue) {
                inFlight--;
            }
            next(account);
        };
        AndroidUtilities.runOnUIThread(() -> {
            if (finished.get()) return;
            FileLog.d("svipe: warm-up '" + entry.name + "' overran its deadline — moving on");
            done.run();
        }, TASK_DEADLINE_MS);
        try {
            entry.task.run(account, done);
        } catch (Exception e) {
            FileLog.e(e);
            done.run();
        }
    }
}
