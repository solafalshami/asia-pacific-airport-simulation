import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public final class Checkpoint4Test {
    private static final long JOIN_TIMEOUT_MILLIS = 10000L;

    private Checkpoint4Test() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected aircraft count: 1, 2, or 6");
        }
        int aircraftCount = Integer.parseInt(arguments[0]);
        if (aircraftCount != 1 && aircraftCount != 2 && aircraftCount != 6) {
            throw new IllegalArgumentException("Aircraft count must be 1, 2, or 6");
        }

        PrintStream originalOutput = System.out;
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
        PrintStream capturedOutput = new PrintStream(capturedBytes, true, "UTF-8");
        System.setOut(capturedOutput);
        try {
            runScenario(aircraftCount, capturedBytes);
        } finally {
            System.setOut(originalOutput);
            capturedOutput.close();
        }
        System.out.println("CHECKPOINT 4 TEST (" + aircraftCount + " aircraft): PASS");
    }

    private static void runScenario(int aircraftCount, ByteArrayOutputStream capturedBytes)
            throws Exception {
        Runway runway = new Runway();
        GatePool gatePool = new GatePool(3);
        AirportCapacity airportCapacity = new AirportCapacity(3);
        ATCDesk atcDesk = new ATCDesk(runway, gatePool, airportCapacity);
        Thread atcThread = new Thread(new ATC(atcDesk), "ATC");
        Aircraft[] aircraft = new Aircraft[aircraftCount];
        Thread[] aircraftThreads = new Thread[aircraftCount];

        atcThread.start();
        for (int index = 0; index < aircraftCount; index++) {
            String aircraftName = "Aircraft-" + (index + 1);
            aircraft[index] = new Aircraft(
                    aircraftName,
                    atcDesk,
                    runway,
                    gatePool,
                    airportCapacity,
                    5L);
            aircraftThreads[index] = new Thread(aircraft[index], aircraftName);
            aircraftThreads[index].start();
        }

        long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MILLIS;
        for (Thread aircraftThread : aircraftThreads) {
            joinBeforeDeadline(aircraftThread, deadline);
            assertFalse(aircraftThread.isAlive(), aircraftThread.getName() + " did not complete");
        }

        atcDesk.requestShutdown();
        joinBeforeDeadline(atcThread, deadline);
        assertFalse(atcThread.isAlive(), "ATC did not shut down cleanly");

        for (Aircraft plane : aircraft) {
            assertTrue(plane.isComplete(), plane.getAircraftName() + " did not depart");
            assertTrue(plane.getFailure() == null, plane.getAircraftName() + " recorded a failure");
        }
        assertTrue(runway.isEmpty(), "Runway was not empty after scenario");
        assertTrue(gatePool.areAllEmpty(), "Gates were not empty after scenario");
        assertEquals(0, airportCapacity.getCurrentCount(), "Airport capacity was not released");
        assertEquals(0, runway.getRunwayViolations(), "Runway violation detected");
        assertEquals(0, gatePool.getGateViolations(), "Gate violation detected");
        assertEquals(0, airportCapacity.getCapacityViolations(), "Capacity violation detected");
        assertEquals(0, atcDesk.getEmergencyLandingQueueSize(), "Emergency queue not empty");
        assertEquals(0, atcDesk.getNormalLandingQueueSize(), "Landing queue not empty");
        assertEquals(0, atcDesk.getTakeoffQueueSize(), "Take-off queue not empty");

        String output = capturedBytes.toString("UTF-8");
        for (Aircraft plane : aircraft) {
            verifyLifecycleOrder(output, plane.getAircraftName());
            verifyActorOwnership(output, plane.getAircraftName());
        }
        assertTrue(output.contains("[ATC] [SYSTEM] ATC stopped"), "ATC stop output missing");
    }

    private static void verifyLifecycleOrder(String output, String aircraftName) {
        String[] expectedMessages = {
            aircraftName + " approaching airport",
            aircraftName + " requesting landing permission",
            aircraftName + " landing on runway",
            aircraftName + " coasting to Gate-",
            aircraftName + " docked at Gate-",
            aircraftName + " starting gate turnaround",
            aircraftName + " gate turnaround complete",
            aircraftName + " requesting take-off permission",
            aircraftName + " undocking from Gate-",
            aircraftName + " coasting to runway",
            aircraftName + " taking off",
            aircraftName + " left airport"
        };
        int previousIndex = -1;
        for (String expectedMessage : expectedMessages) {
            int currentIndex = output.indexOf(expectedMessage, previousIndex + 1);
            assertTrue(currentIndex > previousIndex,
                    "Missing or out-of-order event for " + aircraftName + ": " + expectedMessage);
            previousIndex = currentIndex;
        }
    }

    private static void verifyActorOwnership(String output, String aircraftName) {
        assertTrue(
                output.contains("[" + aircraftName + "] [ARRIVAL] "
                        + aircraftName + " approaching airport"),
                aircraftName + " did not log its own arrival");
        assertTrue(
                output.contains("[ATC] [LANDING] Landing permission granted to " + aircraftName),
                "ATC did not log landing permission for " + aircraftName);
        assertTrue(
                output.contains("[ATC] [TAKEOFF] Take-off permission granted to " + aircraftName),
                "ATC did not log take-off permission for " + aircraftName);
        assertFalse(
                output.contains("[" + aircraftName + "] [LANDING] Landing permission granted"),
                aircraftName + " impersonated ATC landing output");
        assertFalse(
                output.contains("[" + aircraftName + "] [TAKEOFF] Take-off permission granted"),
                aircraftName + " impersonated ATC take-off output");
    }

    private static void joinBeforeDeadline(Thread thread, long deadline) throws InterruptedException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining > 0L) {
            thread.join(remaining);
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
}

