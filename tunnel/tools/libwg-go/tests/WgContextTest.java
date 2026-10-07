/* SPDX-License-Identifier: Apache-2.0 */
public final class WgContextTest {
    private static native int exercise(Service service, Thread startup) throws InterruptedException;

    private static final class Service {
        final Thread expectedStartup = Thread.currentThread();
        boolean allowProtection = true;
        boolean destroyed;
        Error failure;
        int protectedCalls;
        int startupCalls;
        int laterCalls;

        private boolean isNativeServiceActive(Thread startup) throws InterruptedException {
            if (Thread.currentThread() == expectedStartup)
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
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        Service service = new Service();
        if (exercise(service, Thread.currentThread()) != 31 || service.protectedCalls != 2
                || service.startupCalls != 2 || service.laterCalls != 2)
            throw new AssertionError("Startup/later worker callbacks lost context");

        Service denied = new Service();
        denied.allowProtection = false;
        if (exercise(denied, Thread.currentThread()) != 22 || denied.protectedCalls != 2)
            throw new AssertionError("Protection denial became success");

        Service destroyed = new Service();
        destroyed.destroyed = true;
        if (exercise(destroyed, Thread.currentThread()) != 4 || destroyed.protectedCalls != 0)
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
        System.out.println("JNI context: five worker/lifetime/failure controls PASS");
    }
}
