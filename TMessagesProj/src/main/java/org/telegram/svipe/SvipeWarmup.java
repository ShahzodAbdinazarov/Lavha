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

    /** How long the app is left alone to finish opening before any of this starts. */
    private static final long SETTLE_MS = 4000;
    /** A task that has not reported back by now is presumed stuck; the queue moves on without it. */
    private static final long TASK_DEADLINE_MS = 45_000;

    private static final ArrayDeque<Named> queue = new ArrayDeque<>();
    private static final java.util.HashSet<String> everEnqueued = new java.util.HashSet<>();
    private static boolean running;
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

    /** Begin, once per process, after the settle delay. Safe to call more than once. */
    public static void start(final int account) {
        if (started) return;
        started = true;
        AndroidUtilities.runOnUIThread(() -> next(account), SETTLE_MS);
    }

    private static void next(final int account) {
        final Named entry;
        synchronized (queue) {
            if (running) return;
            entry = queue.poll();
            if (entry == null) return;
            running = true;
        }
        final AtomicBoolean finished = new AtomicBoolean();
        final long startedAt = System.currentTimeMillis();
        final Runnable done = () -> {
            if (!finished.compareAndSet(false, true)) return;
            FileLog.d("svipe: warm-up '" + entry.name + "' done in " + (System.currentTimeMillis() - startedAt) + "ms");
            synchronized (queue) {
                running = false;
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
