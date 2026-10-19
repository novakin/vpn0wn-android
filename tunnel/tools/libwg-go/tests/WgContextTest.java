/* SPDX-License-Identifier: Apache-2.0 */
public final class WgContextTest {
    private static native int exercise(Service service, Thread startup) throws InterruptedException;
    private static native long createRetained(Service service, Thread startup);
    private static native int checkRetained(long handle);
    private static native void closeRetained(long handle);
    private static native boolean callerInterrupted(Object caller);
    private static native int exerciseCaptured(Service service, Thread startup, Capture capture);
    private static native long retainCapture(Service service, Thread startup, Capture capture);
    private static native void freeCapture(long handle);

    private static final class Capture {
        final Thread owner = Thread.currentThread();
        final java.util.List<String> calls = new java.util.ArrayList<>();
        String denied;
        String failed;
        final Error failure = new AssertionError("Synthetic captured callback defect");

        private boolean callback(String stage) {
            if (Thread.currentThread() == owner)
                throw new AssertionError("Captured callback did not attach a worker");
            calls.add(stage);
            if (stage.equals(failed))
                throw failure;
            return !stage.equals(denied);
        }

        private boolean prepare(int socket) {
            if (socket != 42)
                throw new AssertionError("Captured descriptor changed");
            return callback("prepare");
        }

        private boolean report(long generation, boolean ready) {
            if (generation != (ready ? 91 : 92))
                throw new AssertionError("Captured generation changed");
            return callback(ready ? "report" : "empty");
        }

        private boolean confirm(long generation) {
            if (generation != 91)
                throw new AssertionError("Confirmed generation changed");
            return callback("confirm");
        }
    }

    private static final class FailedCaller {
        final Error failure = new AssertionError("Synthetic caller defect");
        public boolean isInterrupted() { throw failure; }
    }

    private static final class Service {
        final Thread expectedStartup = Thread.currentThread();
        boolean allowProtection = true;
        boolean destroyed;
        boolean workerCallbacksOnly = true;
        Error failure;
        Error observationFailure;
        int protectedCalls;
        int startupCalls;
        int laterCalls;
        int observationCalls;

        private boolean isNativeServiceActive(Thread startup) throws InterruptedException {
            if (workerCallbacksOnly && Thread.currentThread() == expectedStartup)
                throw new AssertionError("Callback did not attach a worker");
            if (startup == expectedStartup)
                startupCalls++;
            else if (startup == null)
                laterCalls++;
            else
                throw new AssertionError("Wrong activation thread");
            if (startup != null && startup.isInterrupted())
                throw new InterruptedException("Synthetic startup interruption");
            return !destroyed;
        }

        private boolean protectNativeSocket(int socket) {
            if (socket != 42)
                throw new AssertionError("Wrong descriptor");
            protectedCalls++;
            if (failure != null)
                throw failure;
            return allowProtection;
        }

        private void onNativeHandshakeObservation(long activation, byte[] peer, int event, boolean retry, String endpoint, long episode) {
            if ((workerCallbacksOnly && Thread.currentThread() == expectedStartup) || activation != 73 || peer.length != 32
                    || peer[0] != 9 || event != 3 || !retry || !endpoint.equals("[::1]:51820") || episode != 91)
                throw new AssertionError("Handshake callback lost scoped observation");
            observationCalls++;
            if (observationFailure != null)
                throw observationFailure;
        }
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        if (callerInterrupted(Thread.currentThread()))
            throw new AssertionError("Fresh recovery caller reported interrupted");
        Thread.currentThread().interrupt();
        if (!callerInterrupted(Thread.currentThread()) || !Thread.currentThread().isInterrupted())
            throw new AssertionError("Recovery caller interruption was lost");
        Thread.interrupted();
        FailedCaller failedCaller = new FailedCaller();
        try {
            callerInterrupted(failedCaller);
            throw new AssertionError("Caller defect was swallowed");
        } catch (Error actual) {
            if (actual != failedCaller.failure)
                throw new AssertionError("Caller defect identity changed");
        }
        Service service = new Service();
        if (exercise(service, Thread.currentThread()) != 31 || service.protectedCalls != 2
                || service.startupCalls != 3 || service.laterCalls != 3 || service.observationCalls != 2)
            throw new AssertionError("Startup/later worker callbacks lost context");

        Service denied = new Service();
        denied.allowProtection = false;
        if (exercise(denied, Thread.currentThread()) != 22 || denied.protectedCalls != 2 || denied.observationCalls != 2)
            throw new AssertionError("Protection denial became success");

        Service destroyed = new Service();
        destroyed.destroyed = true;
        if (exercise(destroyed, Thread.currentThread()) != 4 || destroyed.protectedCalls != 0 || destroyed.observationCalls != 0)
            throw new AssertionError("Destroyed service protected sockets");

        Service failed = new Service();
        Error original = new AssertionError("Synthetic callback defect");
        failed.failure = original;
        try {
            exercise(failed, Thread.currentThread());
            throw new AssertionError("Callback error was swallowed");
        } catch (Error actual) {
            if (actual != original || failed.protectedCalls != 1)
                throw new AssertionError("Callback defect identity or failure latch changed");
        }

        Service observationFailed = new Service();
        observationFailed.observationFailure = original;
        try {
            exercise(observationFailed, Thread.currentThread());
            throw new AssertionError("Observation defect was swallowed");
        } catch (Error actual) {
            if (actual != original || observationFailed.observationCalls != 1)
                throw new AssertionError("Observation defect identity or failure latch changed");
        }

        Thread.currentThread().interrupt();
        try {
            exercise(new Service(), Thread.currentThread());
            throw new AssertionError("Startup interruption was swallowed");
        } catch (InterruptedException expected) {
            if (!Thread.currentThread().isInterrupted())
                throw new AssertionError("Startup interrupt flag lost");
        } finally {
            Thread.interrupted();
        }

        Service retained = new Service();
        retained.workerCallbacksOnly = false;
        long handle = createRetained(retained, Thread.currentThread());
        if (handle == 0)
            throw new AssertionError("Retained context creation failed");
        try {
            Thread.currentThread().interrupt();
            try {
                if (checkRetained(handle) != 3 || !Thread.currentThread().isInterrupted())
                    throw new AssertionError("Retained context rejected or cleared caller interruption");
            } finally {
                Thread.interrupted();
            }
            if (checkRetained(handle) != 3 || retained.protectedCalls != 2 || retained.laterCalls != 6
                    || retained.startupCalls != 0 || retained.observationCalls != 2)
                throw new AssertionError("Retained context interruption poisoned retry callbacks");
        } finally {
            closeRetained(handle);
        }
        Capture capture = new Capture();
        if (exerciseCaptured(new Service(), Thread.currentThread(), capture) != 31
                || !capture.calls.equals(java.util.List.of("prepare", "report", "confirm", "empty")))
            throw new AssertionError("Captured worker callbacks lost order or identity");
        for (String stage : java.util.List.of("prepare", "report", "confirm", "empty")) {
            Capture deniedCapture = new Capture();
            deniedCapture.denied = stage;
            if (exerciseCaptured(new Service(), Thread.currentThread(), deniedCapture) != (31 & ~(1 << java.util.List.of("prepare", "report", "confirm", "empty").indexOf(stage))))
                throw new AssertionError("Captured denial became success: " + stage);
            Capture failedCapture = new Capture();
            failedCapture.failed = stage;
            try {
                exerciseCaptured(new Service(), Thread.currentThread(), failedCapture);
                throw new AssertionError("Captured defect was swallowed: " + stage);
            } catch (Error actual) {
                if (actual != failedCapture.failure || failedCapture.calls.size() != java.util.List.of("prepare", "report", "confirm", "empty").indexOf(stage) + 1)
                    throw new AssertionError("Captured defect identity or terminal latch changed: " + stage);
            }
        }
        Capture inactive = new Capture();
        Service inactiveService = new Service();
        inactiveService.destroyed = true;
        if (exerciseCaptured(inactiveService, Thread.currentThread(), inactive) != 16 || !inactive.calls.isEmpty())
            throw new AssertionError("Destroyed service reached capture");

        java.lang.ref.ReferenceQueue<Capture> queue = new java.lang.ref.ReferenceQueue<>();
        Capture retainedCapture = new Capture();
        java.lang.ref.WeakReference<Capture> weakCapture = new java.lang.ref.WeakReference<>(retainedCapture, queue);
        long captureHandle = retainCapture(new Service(), Thread.currentThread(), retainedCapture);
        if (captureHandle == 0)
            throw new AssertionError("Capture retention failed");
        retainedCapture = null;
        System.gc();
        if (weakCapture.get() == null)
            throw new AssertionError("Capture global reference did not retain owner");
        freeCapture(captureHandle);
        for (int attempt = 0; attempt < 100 && !weakCapture.isEnqueued(); attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        if (queue.poll() != weakCapture)
            throw new AssertionError("Capture global reference leaked after release");
        System.out.println("JNI context: seven legacy controls plus capture callback/denial/failure/lifetime controls PASS");
    }
}
