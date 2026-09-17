import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public final class Checkpoint6Test {
    private static final long JOIN_TIMEOUT_MILLIS = 10000L;

    private Checkpoint6Test() {
    }

    public static void main(String[] arguments) throws Exception {
        PrintStream originalOutput = System.out;
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
        PrintStream capturedOutput = new PrintStream(capturedBytes, true, "UTF-8");
        System.setOut(capturedOutput);
        try {
            testConcurrentFuelRequestsAndExclusivity(capturedBytes);
            capturedBytes.reset();
            testIntegratedFuelTurnaround(capturedBytes);
        } finally {
            System.setOut(originalOutput);
            capturedOutput.close();
        }
        System.out.println("CHECKPOINT 6 TESTS: PASS");
    }

    private static void testConcurrentFuelRequestsAndExclusivity(
            ByteArrayOutputStream outputBytes) throws Exception {
        final int requestCount = 5;
        final FuelTruck fuelTruck = new FuelTruck(20L);
        final FuelRequest[] requests = new FuelRequest[requestCount];
        final Thread[] submitterThreads = new Thread[requestCount];
        final StartGate startGate = new StartGate();
        final SubmissionTracker submissionTracker = new SubmissionTracker();

        for (int index = 0; index < requestCount; index++) {
            String aircraftName = "Aircraft-F" + (index + 1);
            TurnaroundState state = fuelReadyState();
            requests[index] = new FuelRequest(aircraftName, state);
            final FuelRequest request = requests[index];
            submitterThreads[index] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startGate.arriveAndAwaitOpen();
                        fuelTruck.submitRequest(request);
                        submissionTracker.markSubmitted();
                        request.awaitCompletion();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(exception);
                    }
                }
            }, aircraftName);
            submitterThreads[index].start();
        }

        startGate.awaitWaitingThreads(requestCount);
        startGate.open();
        submissionTracker.awaitSubmissions(requestCount);
        String[] queuedOrder = fuelTruck.getQueuedAircraftSnapshot();
        assertEquals(requestCount, queuedOrder.length, "Not all fuel requests were queued");
        assertTrue(fuelTruck.getMaximumQueueSize() >= 2,
                "Multiple fuel requests were not concurrently pending");

        Thread fuelTruckThread = new Thread(fuelTruck, "FuelTruck");
        fuelTruckThread.start();
        for (Thread submitterThread : submitterThreads) {
            joinOrFail(submitterThread, submitterThread.getName());
        }
        fuelTruck.requestShutdown();
        joinOrFail(fuelTruckThread, "FuelTruck focused test");

        for (FuelRequest request : requests) {
            assertTrue(request.isCompleted(), request.getAircraftName() + " request was lost");
            assertFalse(request.hasFailure(), request.getAircraftName() + " refuelling failed");
        }
        assertEquals(requestCount, fuelTruck.getCompletedRequests(),
                "Incorrect number of completed fuel requests");
        assertEquals(1, fuelTruck.getMaximumObservedUsers(),
                "FuelTruck did not demonstrate one active user");
        assertEquals(0, fuelTruck.getExclusivityViolations(),
                "Simultaneous refuelling violation detected");
        assertEquals(0, fuelTruck.getActiveUsers(), "FuelTruck active-user count leaked");
        assertEquals(0, fuelTruck.getQueueSize(), "Fuel request queue did not drain");
        assertArrayEquals(queuedOrder, fuelTruck.getServiceOrderSnapshot(),
                "Fuel requests were not serviced in FIFO order");

        String output = outputBytes.toString("UTF-8");
        for (FuelRequest request : requests) {
            assertTrue(
                    output.contains("[FuelTruck] [FUEL] Refuelling " + request.getAircraftName()),
                    "FuelTruck actor output missing for " + request.getAircraftName());
            assertFalse(
                    output.contains("[" + request.getAircraftName() + "] [FUEL] Refuelling"),
                    request.getAircraftName() + " impersonated FuelTruck");
        }
    }

    private static void testIntegratedFuelTurnaround(ByteArrayOutputStream outputBytes)
            throws Exception {
        Runway runway = new Runway();
        GatePool gatePool = new GatePool(3);
        AirportCapacity airportCapacity = new AirportCapacity(3);
        ATCDesk atcDesk = new ATCDesk(runway, gatePool, airportCapacity);
        FuelTruck fuelTruck = new FuelTruck(40L);
        Thread atcThread = new Thread(new ATC(atcDesk), "ATC");
        Thread fuelTruckThread = new Thread(fuelTruck, "FuelTruck");
        Aircraft[] aircraft = new Aircraft[3];
        Thread[] aircraftThreads = new Thread[3];

        atcThread.start();
        fuelTruckThread.start();
        for (int index = 0; index < aircraft.length; index++) {
            String aircraftName = "Aircraft-" + (index + 1);
            aircraft[index] = new Aircraft(
                    aircraftName,
                    atcDesk,
                    runway,
                    gatePool,
                    airportCapacity,
                    2L,
                    20L,
                    fuelTruck);
            aircraftThreads[index] = new Thread(aircraft[index], aircraftName);
            aircraftThreads[index].start();
        }

        for (Thread aircraftThread : aircraftThreads) {
            joinOrFail(aircraftThread, aircraftThread.getName());
        }
        atcDesk.requestShutdown();
        fuelTruck.requestShutdown();
        joinOrFail(atcThread, "ATC fuel integration test");
        joinOrFail(fuelTruckThread, "FuelTruck integration test");

        boolean fuelOverlappedIndependentWork = false;
        String output = outputBytes.toString("UTF-8");
        for (Aircraft plane : aircraft) {
            String aircraftName = plane.getAircraftName();
            TurnaroundState state = plane.getTurnaroundState();
            assertTrue(plane.isComplete(), aircraftName + " did not depart");
            assertTrue(plane.getFailure() == null, aircraftName + " recorded a failure");
            assertTrue(state != null && state.isTurnaroundComplete(),
                    aircraftName + " turnaround incomplete");
            assertTrue(state.isFuelRequired() && state.isFuelComplete(),
                    aircraftName + " departed without completed fuel");
            assertTrue(state.getFuelCompletedAtNanos() <= state.getBoardingStartedAtNanos(),
                    aircraftName + " boarded before refuelling completed");
            if (intervalsOverlap(
                    state.getFuelStartedAtNanos(),
                    state.getFuelCompletedAtNanos(),
                    state.getCleaningSuppliesStartedAtNanos(),
                    state.getCleaningSuppliesCompletedAtNanos())) {
                fuelOverlappedIndependentWork = true;
            }

            int fuelComplete = output.indexOf("Refuelling complete for " + aircraftName);
            int boarding = output.indexOf("New passengers embarking " + aircraftName);
            int takeoffRequest = output.indexOf(aircraftName + " requesting take-off permission");
            int departure = output.indexOf(aircraftName + " left airport");
            assertTrue(
                    fuelComplete >= 0
                            && fuelComplete < boarding
                            && boarding < takeoffRequest
                            && takeoffRequest < departure,
                    aircraftName + " output shows premature boarding or departure");
            assertTrue(
                    output.contains("[Aircraft-" + aircraftName.substring(9)
                            + "] [REQUEST] " + aircraftName + " requesting refuelling"),
                    aircraftName + " did not log its fuel request");
            assertTrue(
                    output.contains("[FuelTruck] [FUEL] Refuelling " + aircraftName),
                    "FuelTruck did not log service for " + aircraftName);
            assertFalse(
                    output.contains("[" + aircraftName + "] [FUEL] Refuelling"),
                    aircraftName + " impersonated FuelTruck");
        }

        assertTrue(fuelOverlappedIndependentWork,
                "Refuelling did not overlap independent cleaning/supply activity");
        assertEquals(aircraft.length, fuelTruck.getCompletedRequests(),
                "Integrated FuelTruck lost a request");
        assertEquals(1, fuelTruck.getMaximumObservedUsers(),
                "Integrated FuelTruck did not remain exclusive");
        assertEquals(0, fuelTruck.getExclusivityViolations(),
                "Integrated simultaneous refuelling violation detected");
        assertEquals(0, fuelTruck.getActiveUsers(), "Integrated FuelTruck user leaked");
        assertEquals(0, fuelTruck.getQueueSize(), "Integrated fuel queue not empty");
        assertTrue(runway.isEmpty(), "Runway not empty after fuel integration");
        assertTrue(gatePool.areAllEmpty(), "Gates not empty after fuel integration");
        assertEquals(0, airportCapacity.getCurrentCount(), "Ground capacity not released");
        assertFalse(atcThread.isAlive(), "ATC did not stop");
        assertFalse(fuelTruckThread.isAlive(), "FuelTruck did not stop");
    }

    private static TurnaroundState fuelReadyState() {
        TurnaroundState state = new TurnaroundState(true);
        state.markDisembarkingStarted();
        state.markDisembarkingComplete();
        return state;
    }

    private static boolean intervalsOverlap(
            long firstStart,
            long firstEnd,
            long secondStart,
            long secondEnd) {
        return firstStart > 0L
                && secondStart > 0L
                && firstStart < secondEnd
                && secondStart < firstEnd;
    }

    private static void joinOrFail(Thread thread, String description) throws InterruptedException {
        thread.join(JOIN_TIMEOUT_MILLIS);
        assertFalse(thread.isAlive(), description + " did not complete");
    }

    private static void assertArrayEquals(String[] expected, String[] actual, String message) {
        if (expected.length != actual.length) {
            throw new AssertionError(message + ": different lengths");
        }
        for (int index = 0; index < expected.length; index++) {
            if (!expected[index].equals(actual[index])) {
                throw new AssertionError(message + " at index " + index);
            }
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }

    private static final class StartGate {
        private int waitingThreads;
        private boolean open;

        public synchronized void arriveAndAwaitOpen() throws InterruptedException {
            waitingThreads++;
            notifyAll();
            while (!open) {
                wait();
            }
        }

        public synchronized void awaitWaitingThreads(int expected) throws InterruptedException {
            while (waitingThreads < expected) {
                wait();
            }
        }

        public synchronized void open() {
            open = true;
            notifyAll();
        }
    }

    private static final class SubmissionTracker {
        private int submitted;

        public synchronized void markSubmitted() {
            submitted++;
            notifyAll();
        }

        public synchronized void awaitSubmissions(int expected) throws InterruptedException {
            while (submitted < expected) {
                wait();
            }
        }
    }
}

