package dev.wachipayox.bootoptim.compat.client;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free deterministic harness for the MCEF owner/reentry coordinator. */
public final class McefInitializationStateMachineHarness {
    private McefInitializationStateMachineHarness() {
    }

    public static void main(String[] args) throws Exception {
        ownerCallbackReentryAfterPublicationDoesNotWait();
        ownerReentryBeforePublicationFailsDeterministically();
        concurrentConsumerWaitsForOwnerSuccess();
        errorReleasesAllWaitersAndRemainsTerminal();
        longWaitNeverPublishesAborted();
        System.out.println("MCEF coordinator harness: all cases passed");
    }

    private static void ownerCallbackReentryAfterPublicationDoesNotWait() {
        McefInitializationStateMachine machine = claimedMachine();
        Thread owner = Thread.currentThread();
        check(machine.beginInitialization(owner), "owner must enter INITIALIZING");
        check(machine.state() == McefInitializationStateMachine.State.INITIALIZING, "state must be INITIALIZING");

        McefInitializationStateMachine.ConsumerAction reentry = machine.beforeConsumer(owner, true);
        check(reentry == McefInitializationStateMachine.ConsumerAction.BYPASS,
                "published owner callback reentry must bypass instead of waiting");

        machine.finishInitialization(true, null);
        check(machine.awaitCompletion(), "owner success must complete true");
        check(machine.state() == McefInitializationStateMachine.State.COMPLETE, "owner success must be COMPLETE");
    }

    private static void ownerReentryBeforePublicationFailsDeterministically() {
        McefInitializationStateMachine machine = claimedMachine();
        Thread owner = Thread.currentThread();
        check(machine.beginInitialization(owner), "owner must enter INITIALIZING");

        boolean threw = false;
        try {
            machine.beforeConsumer(owner, false);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check(threw, "owner reentry before publication must fail instead of waiting or recursing");
        check(machine.state() == McefInitializationStateMachine.State.INITIALIZING,
                "invariant failure must not forge a terminal state behind the initializer");

        machine.finishInitialization(false, null);
        check(!machine.awaitCompletion(), "failed owner result must release completion false");
        check(machine.state() == McefInitializationStateMachine.State.FAILED, "false result must be FAILED");
    }

    private static void concurrentConsumerWaitsForOwnerSuccess() throws Exception {
        McefInitializationStateMachine machine = new McefInitializationStateMachine();
        check(machine.markDeferred(), "machine must arm defer");

        CountDownLatch ownerInitializing = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch waiterWaiting = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();

        Thread owner = thread("mcef-harness-owner", threadFailure, () -> {
            check(machine.beforeConsumer(Thread.currentThread(), false)
                            == McefInitializationStateMachine.ConsumerAction.INITIALIZE,
                    "first consumer must claim initialization");
            check(machine.beginInitialization(Thread.currentThread()), "owner must enter initializer");
            ownerInitializing.countDown();
            await(releaseOwner);
            machine.finishInitialization(true, null);
        });
        owner.start();
        await(ownerInitializing);

        Thread waiter = thread("mcef-harness-waiter", threadFailure, () -> {
            check(machine.beforeConsumer(Thread.currentThread(), false)
                            == McefInitializationStateMachine.ConsumerAction.WAIT,
                    "second consumer must wait behind owner");
            waiterWaiting.countDown();
            check(machine.awaitCompletion(), "second consumer must observe owner success");
        });
        waiter.start();
        await(waiterWaiting);
        check(waiter.isAlive(), "waiter must still be blocked before owner completion");

        releaseOwner.countDown();
        join(owner);
        join(waiter);
        rethrowThreadFailure(threadFailure);
        check(machine.state() == McefInitializationStateMachine.State.COMPLETE, "shared result must be COMPLETE");
    }

    private static void errorReleasesAllWaitersAndRemainsTerminal() throws Exception {
        McefInitializationStateMachine machine = new McefInitializationStateMachine();
        check(machine.markDeferred(), "machine must arm defer");

        CountDownLatch ownerInitializing = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch waitersEntered = new CountDownLatch(2);
        CountDownLatch waitersReleased = new CountDownLatch(2);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        AssertionError error = new AssertionError("synthetic MCEF Error");

        Throwable unwrapped = McefInitializationStateMachine.unwrapInitializerThrowable(new InvocationTargetException(error));
        check(unwrapped == error, "InvocationTargetException must expose the real Error for rethrow");

        Thread owner = thread("mcef-harness-error-owner", threadFailure, () -> {
            check(machine.beforeConsumer(Thread.currentThread(), false)
                            == McefInitializationStateMachine.ConsumerAction.INITIALIZE,
                    "error owner must claim initialization");
            check(machine.beginInitialization(Thread.currentThread()), "error owner must enter initializer");
            ownerInitializing.countDown();
            await(releaseOwner);
            machine.finishInitialization(false, error);
        });
        owner.start();
        await(ownerInitializing);

        Thread waiter1 = failingWaiter(machine, "mcef-harness-error-waiter-1", error, waitersEntered, waitersReleased, threadFailure);
        Thread waiter2 = failingWaiter(machine, "mcef-harness-error-waiter-2", error, waitersEntered, waitersReleased, threadFailure);
        waiter1.start();
        waiter2.start();
        await(waitersEntered);

        releaseOwner.countDown();
        check(waitersReleased.await(2, TimeUnit.SECONDS), "all waiters must be released after Error");
        join(owner);
        join(waiter1);
        join(waiter2);
        rethrowThreadFailure(threadFailure);
        check(machine.state() == McefInitializationStateMachine.State.FAILED, "Error must leave terminal FAILED");
        check(machine.initializerThread() == null, "Error must clear initializer owner");
        check(machine.beforeConsumer(Thread.currentThread(), false)
                        == McefInitializationStateMachine.ConsumerAction.BYPASS,
                "FAILED must be fail-open for later stock consumers");
    }

    private static void longWaitNeverPublishesAborted() throws Exception {
        McefInitializationStateMachine machine = new McefInitializationStateMachine();
        check(machine.markDeferred(), "machine must arm defer");

        CountDownLatch ownerInitializing = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch waiterEntered = new CountDownLatch(1);
        CountDownLatch waiterDone = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();

        Thread owner = thread("mcef-harness-long-owner", threadFailure, () -> {
            check(machine.beforeConsumer(Thread.currentThread(), false)
                            == McefInitializationStateMachine.ConsumerAction.INITIALIZE,
                    "long owner must claim initialization");
            check(machine.beginInitialization(Thread.currentThread()), "long owner must enter initializer");
            ownerInitializing.countDown();
            await(releaseOwner);
            machine.finishInitialization(true, null);
        });
        owner.start();
        await(ownerInitializing);

        Thread waiter = thread("mcef-harness-long-waiter", threadFailure, () -> {
            check(machine.beforeConsumer(Thread.currentThread(), false)
                            == McefInitializationStateMachine.ConsumerAction.WAIT,
                    "long waiter must wait behind owner");
            waiterEntered.countDown();
            check(machine.awaitCompletion(), "long waiter must eventually observe success");
            waiterDone.countDown();
        });
        waiter.start();
        await(waiterEntered);

        check(!waiterDone.await(250, TimeUnit.MILLISECONDS), "waiter must not invent a timeout result");
        check(!machine.abortBeforeInitialization(), "INITIALIZING must reject ABORTED publication from non-owner control paths");
        check(machine.state() == McefInitializationStateMachine.State.INITIALIZING,
                "elapsed wait must leave global state INITIALIZING, never ABORTED");
        check(machine.initializerThread() == owner, "long wait must retain the same owner");

        releaseOwner.countDown();
        join(owner);
        join(waiter);
        rethrowThreadFailure(threadFailure);
        check(machine.state() == McefInitializationStateMachine.State.COMPLETE,
                "owner success after long wait must become COMPLETE");
    }

    private static McefInitializationStateMachine claimedMachine() {
        McefInitializationStateMachine machine = new McefInitializationStateMachine();
        check(machine.markDeferred(), "machine must arm defer");
        check(machine.beforeConsumer(Thread.currentThread(), false)
                        == McefInitializationStateMachine.ConsumerAction.INITIALIZE,
                "first consumer must claim initialization");
        return machine;
    }

    private static Thread failingWaiter(
            McefInitializationStateMachine machine,
            String name,
            AssertionError expectedCause,
            CountDownLatch entered,
            CountDownLatch released,
            AtomicReference<Throwable> failure) {
        return thread(name, failure, () -> {
            check(machine.beforeConsumer(Thread.currentThread(), false)
                            == McefInitializationStateMachine.ConsumerAction.WAIT,
                    "error waiter must wait behind owner");
            entered.countDown();
            try {
                machine.awaitCompletion();
                throw new AssertionError("Error completion must be exceptional");
            } catch (CompletionException completion) {
                check(completion.getCause() == expectedCause, "waiter must observe the same Error cause");
            } finally {
                released.countDown();
            }
        });
    }

    private static Thread thread(String name, AtomicReference<Throwable> failure, ThrowingRunnable runnable) {
        return new Thread(() -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        }, name);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(2, TimeUnit.SECONDS), "timed out waiting for deterministic harness latch");
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(2_000L);
        check(!thread.isAlive(), "harness thread did not terminate: " + thread.getName());
    }

    private static void rethrowThreadFailure(AtomicReference<Throwable> failure) {
        Throwable throwable = failure.get();
        if (throwable != null) {
            if (throwable instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new AssertionError("harness thread failed", throwable);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
