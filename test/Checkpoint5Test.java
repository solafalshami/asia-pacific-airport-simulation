import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public final class Checkpoint5Test {
    private static final long JOIN_TIMEOUT_MILLIS = 10000L;

    private Checkpoint5Test() {
    }

    public static void main(String[] arguments) throws Exception {
        PrintStream originalOutput = System.out;
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
        PrintStream capturedOutput = new PrintStream(capturedBytes, true, "UTF-8");
        System.setOut(capturedOutput);
        try {
            testDependenciesAndActorOwnership(capturedBytes);
            capturedBytes.reset();
            testConcurrentServiceAcrossGates(capturedBytes);
            capturedBytes.reset();
            testIntegratedAircraftTurnaround(capturedBytes);
        } finally {
            System.setOut(originalOutput);
            capturedOutput.close();
        }
        System.out.println("CHECKPOINT 5 TESTS: PASS");
    }

    private static void testDependenciesAndActorOwnership(ByteArrayOutputStream outputBytes)
            throws Exception {
        TurnaroundState state = new TurnaroundState();
        Thread cleaningThread = new Thread(
                new CleaningSupplies("Aircraft-A", state, 20L),
                "CleaningSupplies-Aircraft-A");
        Thread passengerThread = new Thread(
                new PassengerGroup("Aircraft-A", state, 20L),
                "PassengerGroup-Aircraft-A");

        cleaningThread.start();
        passengerThread.start();
        joinOrFail(passengerThread, "PassengerGroup dependency test");
        joinOrFail(cleaningThread, "CleaningSupplies dependency test");

        assertTrue(state.isTurnaroundComplete(), "Turnaround did not complete");
        assertFalse(state.hasFailure(), "Turnaround actor reported failure");
        assertTrue(
                state.getDisembarkingCompletedAtNanos()
                        <= state.getCleaningSuppliesStartedAtNanos(),
                "Cleaning started before disembarking completed");
        assertTrue(
                state.getCleaningSuppliesCompletedAtNanos() <= state.getBoardingStartedAtNanos(),
                "Boarding started before cleaning and supplies completed");

        String output = outputBytes.toString("UTF-8");
        assertTrue(
                output.contains(
                        "[PassengerGroup-Aircraft-A] [PASSENGERS] "
                                + "Passengers disembarking Aircraft-A"),
                "PassengerGroup did not log from its own thread");
        assertTrue(
                output.contains(
                        "[CleaningSupplies-Aircraft-A] [CLEANING] "
                                + "Cleaning and refilling supplies for Aircraft-A"),
                "CleaningSupplies did not log from its own thread");
    }

    private static void testConcurrentServiceAcrossGates(ByteArrayOutputStream outputBytes)
            throws Exception {
        final StartGate startGate = new StartGate();
        final TurnaroundState stateA = new TurnaroundState();
        final TurnaroundState stateB = new TurnaroundState();
        Thread[] serviceThreads = {
            createGatedThread(
                    startGate,
                    new PassengerGroup("Aircraft-A", stateA, 120L),
                    "PassengerGroup-Aircraft-A"),
            createGatedThread(
                    startGate,
                    new CleaningSupplies("Aircraft-A", stateA, 30L),
                    "CleaningSupplies-Aircraft-A"),
            createGatedThread(
                    startGate,
                    new PassengerGroup("Aircraft-B", stateB, 20L),
                    "PassengerGroup-Aircraft-B"),
            createGatedThread(
                    startGate,
                    new CleaningSupplies("Aircraft-B", stateB, 100L),
                    "CleaningSupplies-Aircraft-B")
        };

        for (Thread serviceThread : serviceThreads) {
            serviceThread.start();
        }
        startGate.awaitWaitingThreads(serviceThreads.length);
        startGate.open();
        for (Thread serviceThread : serviceThreads) {
            joinOrFail(serviceThread, serviceThread.getName());
        }

        assertTrue(stateA.isTurnaroundComplete(), "Aircraft-A turnaround incomplete");
        assertTrue(stateB.isTurnaroundComplete(), "Aircraft-B turnaround incomplete");
        assertTrue(
                intervalsOverlap(
                        stateA.getDisembarkingStartedAtNanos(),
                        stateA.getDisembarkingCompletedAtNanos(),
                        stateB.getCleaningSuppliesStartedAtNanos(),
                        stateB.getCleaningSuppliesCompletedAtNanos()),
                "Passenger and cleaning activities at different gates did not overlap");

        String output = outputBytes.toString("UTF-8");
        int aircraftBCleaning = output.indexOf("Cleaning and refilling supplies for Aircraft-B");
        int aircraftADisembarked = output.indexOf("Passengers disembarked Aircraft-A");
        assertTrue(
                aircraftBCleaning >= 0 && aircraftBCleaning < aircraftADisembarked,
                "Output did not visibly interleave service across different gates");
    }

    private static void testIntegratedAircraftTurnaround(ByteArrayOutputStream outputBytes)
            throws Exception {
        Runway runway = new Runway();
        GatePool gatePool = new GatePool(3);
        AirportCapacity airportCapacity = new AirportCapacity(3);
        ATCDesk atcDesk = new ATCDesk(runway, gatePool, airportCapacity);
        Thread atcThread = new Thread(new ATC(atcDesk), "ATC");
        Aircraft[] aircraft = new Aircraft[3];
        Thread[] aircraftThreads = new Thread[3];

        atcThread.start();
        for (int index = 0; index < aircraft.length; index++) {
            String aircraftName = "Aircraft-" + (index + 1);
            aircraft[index] = new Aircraft(
                    aircraftName,
                    atcDesk,
                    runway,
                    gatePool,
                    airportCapacity,
                    2L,
                    40L);
            aircraftThreads[index] = new Thread(aircraft[index], aircraftName);
            aircraftThreads[index].start();
        }

        for (Thread aircraftThread : aircraftThreads) {
            joinOrFail(aircraftThread, aircraftThread.getName());
        }
        atcDesk.requestShutdown();
        joinOrFail(atcThread, "ATC integrated turnaround test");

        boolean observedOverlap = false;
        for (int first = 0; first < aircraft.length; first++) {
            Aircraft plane = aircraft[first];
            assertTrue(plane.isComplete(), plane.getAircraftName() + " did not depart");
            assertTrue(plane.getFailure() == null, plane.getAircraftName() + " failed");
            TurnaroundState state = plane.getTurnaroundState();
            assertTrue(state != null && state.isTurnaroundComplete(),
                    plane.getAircraftName() + " turnaround incomplete");
            for (int second = first + 1; second < aircraft.length; second++) {
                TurnaroundState otherState = aircraft[second].getTurnaroundState();
                if (otherState != null
                        && intervalsOverlap(
                                state.getDisembarkingStartedAtNanos(),
                                state.getBoardingCompletedAtNanos(),
                                otherState.getDisembarkingStartedAtNanos(),
                                otherState.getBoardingCompletedAtNanos())) {
                    observedOverlap = true;
                }
            }
        }
        assertTrue(observedOverlap, "Integrated gate turnarounds were accidentally serialized");
        assertTrue(runway.isEmpty(), "Runway not empty after integrated turnaround");
        assertTrue(gatePool.areAllEmpty(), "Gates not empty after integrated turnaround");
        assertEquals(0, airportCapacity.getCurrentCount(), "Ground capacity not released");
        assertEquals(0, runway.getRunwayViolations(), "Runway violation detected");
        assertEquals(0, gatePool.getGateViolations(), "Gate violation detected");
        assertEquals(0, airportCapacity.getCapacityViolations(), "Capacity violation detected");

        String output = outputBytes.toString("UTF-8");
        for (Aircraft plane : aircraft) {
            String aircraftName = plane.getAircraftName();
            int turnaroundComplete = output.indexOf(aircraftName + " gate turnaround complete");
            int takeoffRequest = output.indexOf(aircraftName + " requesting take-off permission");
            assertTrue(
                    turnaroundComplete >= 0 && turnaroundComplete < takeoffRequest,
                    aircraftName + " requested take-off before turnaround completion");
        }
    }

    private static Thread createGatedThread(
            final StartGate startGate,
            final Runnable activity,
            String threadName) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startGate.arriveAndAwaitOpen();
                    activity.run();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                }
            }
        }, threadName);
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
}

