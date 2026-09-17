import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public final class Checkpoint3Test {
    private Checkpoint3Test() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
        PrintStream capturedOut = new PrintStream(capturedBytes, true, "UTF-8");

        try {
            System.setOut(capturedOut);
            testLandingAndTakeoffCommunication();
            testBlockedLandingHasNoPartialReservation();
            testEmergencyQueuePrecedesNormalQueue();
            testInterruptedAircraftCancelsQueuedLanding();
            testCancelledSelectedRequestsReleaseReservations();
        } finally {
            System.setOut(originalOut);
            capturedOut.close();
        }

        verifyActorOwnership(capturedBytes.toString("UTF-8"));
        System.out.println("CHECKPOINT 3 TESTS: PASS");
    }

    private static void testLandingAndTakeoffCommunication() throws Exception {
        final TestEnvironment environment = new TestEnvironment();
        final FailureBox failure = new FailureBox();
        environment.startATC();

        Thread aircraft = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String aircraftName = Thread.currentThread().getName();
                    EventLog.log("ATC", "Requesting landing");
                    LandingRequest landingRequest = new LandingRequest(aircraftName, false);
                    environment.desk.submitLandingRequest(landingRequest);
                    Gate gate = landingRequest.awaitPermission();

                    assertNotNull(gate, "Landing permission did not include a gate");
                    assertTrue(environment.capacity.contains(aircraftName),
                            "Landing grant did not reserve ground capacity");
                    assertEquals(aircraftName, environment.runway.getReservedFor(),
                            "Landing grant did not reserve the runway");

                    EventLog.log("LANDING", "Landing completed");
                    environment.runway.release(aircraftName);
                    environment.desk.signalResourceChange();

                    EventLog.log("ATC", "Requesting take-off");
                    TakeoffRequest takeoffRequest = new TakeoffRequest(aircraftName);
                    environment.desk.submitTakeoffRequest(takeoffRequest);
                    takeoffRequest.awaitPermission();

                    assertEquals(aircraftName, environment.runway.getReservedFor(),
                            "Take-off grant did not reserve the runway");
                    environment.gatePool.releaseGate(aircraftName);
                    environment.runway.release(aircraftName);
                    environment.capacity.leave(aircraftName);
                    environment.desk.signalResourceChange();
                    EventLog.log("TAKEOFF", "Take-off completed");
                } catch (Throwable throwable) {
                    failure.record(throwable);
                }
            }
        }, "Aircraft-1");

        aircraft.start();
        joinWithTimeout(aircraft, 5_000);
        failure.rethrowIfPresent();
        environment.shutdown();

        assertTrue(environment.gatePool.areAllEmpty(), "Gate remained occupied after take-off");
        assertTrue(environment.runway.isEmpty(), "Runway remained occupied after take-off");
        assertEquals(0, environment.capacity.getCurrentCount(),
                "Ground capacity remained occupied after take-off");
    }

    private static void testBlockedLandingHasNoPartialReservation() throws Exception {
        TestEnvironment environment = new TestEnvironment();
        occupyGateAndGround(environment, "Blocker-1");
        occupyGateAndGround(environment, "Blocker-2");
        occupyGateAndGround(environment, "Blocker-3");
        LandingRequest waitingRequest = new LandingRequest("Waiting-Aircraft", false);

        environment.desk.submitLandingRequest(waitingRequest);
        environment.startATC();
        waitForQueueSize(environment.desk, 1, 2_000);
        Thread.sleep(40);

        assertTrue(!waitingRequest.isGranted(), "Blocked landing was granted without capacity");
        assertTrue(!environment.capacity.contains("Waiting-Aircraft"),
                "Blocked landing partially reserved capacity");
        assertNull(environment.gatePool.findGateFor("Waiting-Aircraft"),
                "Blocked landing partially reserved a gate");
        assertTrue(environment.runway.isEmpty(), "Blocked landing partially reserved the runway");

        environment.gatePool.releaseGate("Blocker-3");
        environment.capacity.leave("Blocker-3");
        environment.desk.signalResourceChange();
        waitUntilGranted(waitingRequest, 2_000);

        cleanupLandedAircraft(environment, "Waiting-Aircraft");
        environment.gatePool.releaseGate("Blocker-1");
        environment.capacity.leave("Blocker-1");
        environment.gatePool.releaseGate("Blocker-2");
        environment.capacity.leave("Blocker-2");
        environment.shutdown();
    }

    private static void testEmergencyQueuePrecedesNormalQueue() throws Exception {
        TestEnvironment environment = new TestEnvironment();
        LandingRequest normalRequest = new LandingRequest("Normal-Aircraft", false);
        LandingRequest emergencyRequest = new LandingRequest("Emergency-Aircraft", true);

        environment.desk.submitLandingRequest(normalRequest);
        environment.desk.submitLandingRequest(emergencyRequest);
        environment.startATC();
        waitUntilGranted(emergencyRequest, 2_000);

        assertTrue(!normalRequest.isGranted(),
                "Normal landing was granted before an already queued emergency");
        assertEquals("Emergency-Aircraft", environment.runway.getReservedFor(),
                "Runway was not reserved for the emergency aircraft");

        cleanupLandedAircraft(environment, "Emergency-Aircraft");
        waitUntilGranted(normalRequest, 2_000);
        cleanupLandedAircraft(environment, "Normal-Aircraft");
        environment.shutdown();
    }

    private static void testInterruptedAircraftCancelsQueuedLanding() throws Exception {
        TestEnvironment environment = new TestEnvironment();
        assertTrue(environment.runway.tryAcquire("Runway-Blocker"),
                "Unable to block runway for interruption test");
        environment.startATC();
        Aircraft interruptedAircraft = new Aircraft(
                "Interrupted-Aircraft",
                environment.desk,
                environment.runway,
                environment.gatePool,
                environment.capacity,
                0L);
        Thread aircraftThread = new Thread(interruptedAircraft, "Interrupted-Aircraft");
        aircraftThread.start();
        waitForQueueSize(environment.desk, 1, 2_000);

        aircraftThread.interrupt();
        joinWithTimeout(aircraftThread, 2_000);

        assertEquals(0, environment.desk.getNormalLandingQueueSize(),
                "Interrupted landing request remained queued");
        assertNull(environment.gatePool.findGateFor("Interrupted-Aircraft"),
                "Interrupted aircraft retained a gate");
        assertTrue(!environment.capacity.contains("Interrupted-Aircraft"),
                "Interrupted aircraft retained ground capacity");
        assertTrue(interruptedAircraft.getFailure() instanceof InterruptedException,
                "Aircraft did not record its interruption");

        environment.runway.release("Runway-Blocker");
        environment.desk.signalResourceChange();
        environment.shutdown();
    }

    private static void testCancelledSelectedRequestsReleaseReservations() throws Exception {
        TestEnvironment landingEnvironment = new TestEnvironment();
        LandingRequest landingRequest = new LandingRequest("Cancelled-Landing", false);
        landingEnvironment.desk.submitLandingRequest(landingRequest);
        ATCDecision landingDecision = landingEnvironment.desk.awaitNextDecision();
        assertTrue(landingEnvironment.desk.cancelLandingRequest(landingRequest),
                "Selected landing request was not cancellable before grant");
        landingEnvironment.desk.releaseCancelledLanding(landingDecision);
        assertTrue(landingEnvironment.runway.isEmpty(),
                "Cancelled selected landing retained runway");
        assertTrue(landingEnvironment.gatePool.areAllEmpty(),
                "Cancelled selected landing retained gate");
        assertEquals(0, landingEnvironment.capacity.getCurrentCount(),
                "Cancelled selected landing retained capacity");

        TestEnvironment takeoffEnvironment = new TestEnvironment();
        occupyGateAndGround(takeoffEnvironment, "Cancelled-Takeoff");
        TakeoffRequest takeoffRequest = new TakeoffRequest("Cancelled-Takeoff");
        takeoffEnvironment.desk.submitTakeoffRequest(takeoffRequest);
        ATCDecision takeoffDecision = takeoffEnvironment.desk.awaitNextDecision();
        assertTrue(takeoffEnvironment.desk.cancelTakeoffRequest(takeoffRequest),
                "Selected take-off request was not cancellable before grant");
        takeoffEnvironment.desk.releaseCancelledTakeoff(takeoffDecision);
        assertTrue(takeoffEnvironment.runway.isEmpty(),
                "Cancelled selected take-off retained runway");
        takeoffEnvironment.gatePool.releaseGate("Cancelled-Takeoff");
        takeoffEnvironment.capacity.leave("Cancelled-Takeoff");
    }

    private static void occupyGateAndGround(TestEnvironment environment, String aircraftName) {
        assertNotNull(environment.gatePool.tryReserveGate(aircraftName),
                "Unable to prepare occupied gate for " + aircraftName);
        assertTrue(environment.capacity.tryEnter(aircraftName),
                "Unable to prepare occupied capacity for " + aircraftName);
    }

    private static void cleanupLandedAircraft(TestEnvironment environment, String aircraftName) {
        environment.runway.release(aircraftName);
        environment.gatePool.releaseGate(aircraftName);
        environment.capacity.leave(aircraftName);
        environment.desk.signalResourceChange();
    }

    private static void verifyActorOwnership(String output) {
        String[] lines = output.split("\\R");
        boolean aircraftRequestFound = false;
        boolean atcGrantFound = false;
        boolean emergencyGrantFound = false;

        for (String line : lines) {
            if (line.contains("Requesting landing")) {
                aircraftRequestFound = aircraftRequestFound || line.contains("[Aircraft-1] [ATC]");
            }
            if (line.contains("Landing permission granted to Aircraft-1")) {
                atcGrantFound = line.contains("[ATC] [LANDING]");
            }
            if (line.contains("Landing permission granted to Emergency-Aircraft")) {
                emergencyGrantFound = line.contains("[ATC] [EMERGENCY]");
            }
        }

        assertTrue(aircraftRequestFound, "Aircraft request was not logged by the Aircraft thread");
        assertTrue(atcGrantFound, "Normal landing grant was not logged by the ATC thread");
        assertTrue(emergencyGrantFound, "Emergency landing grant was not logged by the ATC thread");
    }

    private static void waitForQueueSize(ATCDesk desk, int expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (desk.getNormalLandingQueueSize() != expected
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, desk.getNormalLandingQueueSize(), "Unexpected normal queue size");
    }

    private static void waitUntilGranted(LandingRequest request, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!request.isGranted() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(request.isGranted(), "Landing request was not granted before timeout");
    }

    private static void joinWithTimeout(Thread thread, long timeoutMillis)
            throws InterruptedException {
        thread.join(timeoutMillis);
        assertTrue(!thread.isAlive(), "Thread did not terminate: " + thread.getName());
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

    private static void assertEquals(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertNotNull(Object value, String message) {
        assertTrue(value != null, message);
    }

    private static void assertNull(Object value, String message) {
        assertTrue(value == null, message);
    }

    private static final class TestEnvironment {
        private final Runway runway = new Runway();
        private final GatePool gatePool = new GatePool(3);
        private final AirportCapacity capacity = new AirportCapacity(3);
        private final ATCDesk desk = new ATCDesk(runway, gatePool, capacity);
        private final Thread atcThread = new Thread(new ATC(desk), "ATC");

        private void startATC() {
            atcThread.start();
        }

        private void shutdown() throws InterruptedException {
            desk.requestShutdown();
            joinWithTimeout(atcThread, 5_000);
        }
    }

    private static final class FailureBox {
        private Throwable failure;

        private synchronized void record(Throwable throwable) {
            if (failure == null) {
                failure = throwable;
            }
        }

        private synchronized void rethrowIfPresent() {
            if (failure == null) {
                return;
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            throw new RuntimeException(failure);
        }
    }
}

