import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

public final class Checkpoint2Test {
    private Checkpoint2Test() {
    }

    public static void main(String[] args) throws Exception {
        testEventLogConcurrentLines();
        testGatePoolAllocationAndRelease();
        testRunwayMutualExclusion();
        testAirportCapacityLimit();
        System.out.println("CHECKPOINT 2 TESTS: PASS");
    }

    private static void testEventLogConcurrentLines() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
        PrintStream capturedOut = new PrintStream(capturedBytes, true, "UTF-8");
        Thread[] threads = new Thread[6];

        try {
            System.setOut(capturedOut);
            for (int index = 0; index < threads.length; index++) {
                final int threadNumber = index + 1;
                threads[index] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        for (int message = 0; message < 20; message++) {
                            EventLog.log("TEST", "worker=" + threadNumber + " message=" + message);
                        }
                    }
                }, "LogWorker-" + threadNumber);
                threads[index].start();
            }
            joinAll(threads);
        } finally {
            System.setOut(originalOut);
            capturedOut.close();
        }

        String[] lines = capturedBytes.toString("UTF-8").split("\\R");
        assertEquals(120, lines.length, "EventLog should produce one complete line per call");
        for (String line : lines) {
            assertTrue(line.matches("\\[\\d+\\.\\d{3}s] \\[LogWorker-\\d+] \\[TEST] .+"),
                    "Malformed or interleaved EventLog line: " + line);
        }
    }

    private static void testGatePoolAllocationAndRelease() throws Exception {
        final GatePool gatePool = new GatePool(3);
        Gate first = gatePool.tryReserveGate("Aircraft-1");
        Gate second = gatePool.tryReserveGate("Aircraft-2");
        Gate third = gatePool.tryReserveGate("Aircraft-3");

        assertNotNull(first, "First gate reservation failed");
        assertNotNull(second, "Second gate reservation failed");
        assertNotNull(third, "Third gate reservation failed");
        assertEquals(3, uniqueGateCount(first, second, third), "Gate allocation was not unique");
        assertNull(gatePool.tryReserveGate("Aircraft-4"), "Fourth aircraft received a nonexistent gate");
        assertEquals(3, gatePool.getOccupiedCount(), "Occupied gate count is incorrect");

        gatePool.releaseGate("Aircraft-2");
        Gate reused = gatePool.tryReserveGate("Aircraft-4");
        assertNotNull(reused, "Released gate was not reusable");
        assertEquals(second.getNumber(), reused.getNumber(), "Unexpected gate was reused");

        gatePool.releaseGate("Aircraft-1");
        gatePool.releaseGate("Aircraft-3");
        gatePool.releaseGate("Aircraft-4");
        assertTrue(gatePool.areAllEmpty(), "Gates were not empty after release");
        assertEquals(0, gatePool.getGateViolations(), "Gate violation counter is not zero");
    }

    private static void testRunwayMutualExclusion() throws Exception {
        final Runway runway = new Runway();
        final ActivityTracker tracker = new ActivityTracker(1);
        Thread[] aircraft = new Thread[12];

        for (int index = 0; index < aircraft.length; index++) {
            final String aircraftName = "RunwayTestAircraft-" + (index + 1);
            aircraft[index] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int attempt = 0; attempt < 25; attempt++) {
                            runway.acquire(aircraftName);
                            tracker.enter();
                            Thread.sleep(1);
                            tracker.leave();
                            runway.release(aircraftName);
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(exception);
                    }
                }
            }, aircraftName);
            aircraft[index].start();
        }

        joinAll(aircraft);
        assertTrue(runway.isEmpty(), "Runway was not empty after all aircraft completed");
        assertEquals(1, tracker.getMaximumObserved(), "More than one aircraft used the runway");
        assertEquals(0, tracker.getViolations(), "Runway activity tracker detected a violation");
        assertEquals(0, runway.getRunwayViolations(), "Runway violation counter is not zero");
    }

    private static void testAirportCapacityLimit() throws Exception {
        final AirportCapacity capacity = new AirportCapacity(3);
        final ActivityTracker tracker = new ActivityTracker(3);
        Thread[] aircraft = new Thread[18];

        for (int index = 0; index < aircraft.length; index++) {
            final String aircraftName = "CapacityTestAircraft-" + (index + 1);
            aircraft[index] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        capacity.enter(aircraftName);
                        tracker.enter();
                        Thread.sleep(3);
                        tracker.leave();
                        capacity.leave(aircraftName);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(exception);
                    }
                }
            }, aircraftName);
            aircraft[index].start();
        }

        joinAll(aircraft);
        assertEquals(0, capacity.getCurrentCount(), "Airport capacity did not return to zero");
        assertTrue(tracker.getMaximumObserved() <= 3, "Airport capacity exceeded three aircraft");
        assertEquals(0, tracker.getViolations(), "Capacity activity tracker detected a violation");
        assertEquals(0, capacity.getCapacityViolations(), "Capacity violation counter is not zero");
    }

    private static int uniqueGateCount(Gate... gates) {
        Set<Integer> gateNumbers = new HashSet<Integer>();
        for (Gate gate : gates) {
            gateNumbers.add(gate.getNumber());
        }
        return gateNumbers.size();
    }

    private static void joinAll(Thread[] threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(10_000);
            assertTrue(!thread.isAlive(), "Thread did not terminate: " + thread.getName());
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertNotNull(Object value, String message) {
        assertTrue(value != null, message);
    }

    private static void assertNull(Object value, String message) {
        assertTrue(value == null, message);
    }

    private static final class ActivityTracker {
        private final int allowedMaximum;
        private int active;
        private int maximumObserved;
        private int violations;

        private ActivityTracker(int allowedMaximum) {
            this.allowedMaximum = allowedMaximum;
        }

        private synchronized void enter() {
            active++;
            if (active > maximumObserved) {
                maximumObserved = active;
            }
            if (active > allowedMaximum) {
                violations++;
            }
        }

        private synchronized void leave() {
            active--;
            if (active < 0) {
                violations++;
            }
        }

        private synchronized int getMaximumObserved() {
            return maximumObserved;
        }

        private synchronized int getViolations() {
            return violations;
        }
    }
}


