import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Random;

public final class Checkpoint7Test {
    private static final long WAIT_TIMEOUT_MILLIS = 10000L;
    private static final String EVIDENCE_FILE = "EMERGENCY_SCENARIO_OUTPUT.txt";
    private static long scenarioSeed;
    private static int[] arrivalDelays;
    private static String keyStateEvidence;

    private Checkpoint7Test() {
    }

    public static void main(String[] arguments) throws Exception {
        PrintStream originalOutput = System.out;
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
        PrintStream capturedOutput = new PrintStream(capturedBytes, true, "UTF-8");
        System.setOut(capturedOutput);
        try {
            runEmergencyCongestionScenario();
        } finally {
            System.setOut(originalOutput);
            capturedOutput.close();
        }
        String output = capturedBytes.toString("UTF-8");
        verifyOutputOrdering(output);
        writeEvidence(output);
        System.out.println("CHECKPOINT 7 EMERGENCY/CONGESTION TEST: PASS");
    }

    private static void runEmergencyCongestionScenario() throws Exception {
        Runway runway = new Runway();
        GatePool gatePool = new GatePool(3);
        AirportCapacity airportCapacity = new AirportCapacity(3);
        ATCDesk atcDesk = new ATCDesk(runway, gatePool, airportCapacity);
        FuelTruck fuelTruck = new FuelTruck(15L);
        ScenarioControl scenarioControl = new ScenarioControl();

        Thread atcThread = new Thread(new ATC(atcDesk), "ATC");
        Thread fuelTruckThread = new Thread(fuelTruck, "FuelTruck");
        Thread holderOne = new Thread(
                new HoldingAircraft(
                        "Aircraft-Holder-1",
                        true,
                        atcDesk,
                        runway,
                        gatePool,
                        airportCapacity,
                        scenarioControl),
                "Aircraft-Holder-1");
        Thread holderTwo = new Thread(
                new HoldingAircraft(
                        "Aircraft-Holder-2",
                        false,
                        atcDesk,
                        runway,
                        gatePool,
                        airportCapacity,
                        scenarioControl),
                "Aircraft-Holder-2");

        atcThread.start();
        fuelTruckThread.start();
        holderOne.start();
        holderTwo.start();

        assertTrue(scenarioControl.awaitAircraftAtGates(2, WAIT_TIMEOUT_MILLIS),
                "Two setup aircraft did not reach their gates");
        assertTrue(gatePool.awaitOccupiedCount(2, WAIT_TIMEOUT_MILLIS),
                "Actual gate occupancy did not reach two");
        scenarioControl.allowLeaderTakeoffRequest();
        assertTrue(scenarioControl.awaitLeaderHoldingRunway(WAIT_TIMEOUT_MILLIS),
                "Departure leader did not reserve the runway");

        scenarioSeed = findSeedForImmediateCongestionArrivals();
        Random arrivalRandom = new Random(scenarioSeed);
        arrivalDelays = new int[] {
            arrivalRandom.nextInt(3),
            arrivalRandom.nextInt(3),
            arrivalRandom.nextInt(3)
        };

        Aircraft normalOne = createAircraft(
                "Aircraft-Normal-1", false, atcDesk, runway, gatePool, airportCapacity, fuelTruck);
        Aircraft normalTwo = createAircraft(
                "Aircraft-Normal-2", false, atcDesk, runway, gatePool, airportCapacity, fuelTruck);
        Aircraft emergency = createAircraft(
                "Aircraft-Emergency", true, atcDesk, runway, gatePool, airportCapacity, fuelTruck);
        Thread normalOneThread = new Thread(normalOne, normalOne.getAircraftName());
        Thread normalTwoThread = new Thread(normalTwo, normalTwo.getAircraftName());
        Thread emergencyThread = new Thread(emergency, emergency.getAircraftName());

        startAfterOfficialDelay(normalOneThread, arrivalDelays[0]);
        assertTrue(atcDesk.awaitLandingQueueCounts(1, 0, WAIT_TIMEOUT_MILLIS),
                "First normal aircraft did not enter the landing queue");
        startAfterOfficialDelay(normalTwoThread, arrivalDelays[1]);
        assertTrue(atcDesk.awaitLandingQueueCounts(2, 0, WAIT_TIMEOUT_MILLIS),
                "Two normal aircraft did not enter the landing queue");
        startAfterOfficialDelay(emergencyThread, arrivalDelays[2]);
        assertTrue(atcDesk.awaitLandingQueueCounts(2, 1, WAIT_TIMEOUT_MILLIS),
                "Emergency aircraft did not enter the emergency queue");

        String[] gateSnapshot = gatePool.getOccupancySnapshot();
        String[] normalQueue = atcDesk.getNormalLandingQueueSnapshot();
        String[] emergencyQueue = atcDesk.getEmergencyLandingQueueSnapshot();
        assertEquals(2, gatePool.getOccupiedCount(), "Key moment did not have two occupied gates");
        assertEquals(2, normalQueue.length, "Key moment did not have two normal waiters");
        assertEquals(1, emergencyQueue.length, "Key moment did not have one emergency waiter");
        assertEquals(2, airportCapacity.getCurrentCount(),
                "Unexpected ground count at the congested key moment");
        assertEquals("Aircraft-Holder-1", runway.getReservedFor(),
                "Runway was not genuinely unavailable at the key moment");
        assertEquals(Aircraft.State.WAITING_TO_LAND, emergency.getState(),
                "Emergency aircraft bypassed safety before the runway was free");
        assertArrayEquals(
                new String[] {"Aircraft-Normal-1", "Aircraft-Normal-2"},
                normalQueue,
                "Normal landing queue order was incorrect");
        assertArrayEquals(
                new String[] {"Aircraft-Emergency"},
                emergencyQueue,
                "Emergency landing queue was incorrect");

        keyStateEvidence = "occupiedGates=" + gatePool.getOccupiedCount()
                + ", gates=" + Arrays.toString(gateSnapshot)
                + ", normalQueue=" + Arrays.toString(normalQueue)
                + ", emergencyQueue=" + Arrays.toString(emergencyQueue)
                + ", runwayReservedFor=" + runway.getReservedFor()
                + ", aircraftOnGround=" + airportCapacity.getCurrentCount();
        EventLog.log("SCENARIO", "ACTUAL CONGESTED STATE: " + keyStateEvidence);
        EventLog.log(
                "SCENARIO",
                "Emergency remains queued because runway is occupied; no unsafe grant occurred");

        scenarioControl.releaseHoldingAircraft();

        joinOrFail(holderOne, "First gate holder");
        joinOrFail(holderTwo, "Second gate holder");
        joinOrFail(emergencyThread, "Emergency aircraft");
        joinOrFail(normalOneThread, "First normal waiting aircraft");
        joinOrFail(normalTwoThread, "Second normal waiting aircraft");

        assertTrue(emergency.isComplete(), "Emergency aircraft did not complete");
        assertTrue(normalOne.isComplete(), "First normal aircraft starved");
        assertTrue(normalTwo.isComplete(), "Second normal aircraft starved");
        assertTrue(scenarioControl.getFailure() == null, "Holding aircraft failed");
        assertEquals(2, scenarioControl.getCompletedAircraft(),
                "Holding aircraft did not both complete");

        atcDesk.requestShutdown();
        fuelTruck.requestShutdown();
        joinOrFail(atcThread, "ATC emergency scenario");
        joinOrFail(fuelTruckThread, "FuelTruck emergency scenario");

        assertTrue(runway.isEmpty(), "Runway was not empty after emergency scenario");
        assertTrue(gatePool.areAllEmpty(), "Gates were not empty after emergency scenario");
        assertEquals(0, airportCapacity.getCurrentCount(),
                "Airport capacity was not released after emergency scenario");
        assertEquals(1, runway.getMaximumObservedOccupancy(),
                "Runway occupancy evidence was incorrect");
        assertTrue(airportCapacity.getMaximumObservedCount() <= 3,
                "Airport ground capacity exceeded three");
        assertEquals(0, runway.getRunwayViolations(), "Runway safety violation detected");
        assertEquals(0, gatePool.getGateViolations(), "Gate safety violation detected");
        assertEquals(0, airportCapacity.getCapacityViolations(),
                "Airport capacity violation detected");
        assertEquals(1, fuelTruck.getMaximumObservedUsers(),
                "FuelTruck did not remain a single active resource");
        assertEquals(0, fuelTruck.getExclusivityViolations(),
                "FuelTruck exclusivity violation detected");
        assertEquals(0, atcDesk.getNormalLandingQueueSize(), "Normal queue did not drain");
        assertEquals(0, atcDesk.getEmergencyLandingQueueSize(),
                "Emergency queue did not drain");
        assertEquals(0, atcDesk.getTakeoffQueueSize(), "Take-off queue did not drain");

        EventLog.log(
                "SCENARIO",
                "VERIFIED COMPLETE: emergency and both normal waiters departed; all safety counters are zero");
    }

    private static Aircraft createAircraft(
            String aircraftName,
            boolean emergency,
            ATCDesk atcDesk,
            Runway runway,
            GatePool gatePool,
            AirportCapacity airportCapacity,
            FuelTruck fuelTruck) {
        return new Aircraft(
                aircraftName,
                atcDesk,
                runway,
                gatePool,
                airportCapacity,
                2L,
                8L,
                fuelTruck,
                emergency);
    }

    private static void startAfterOfficialDelay(Thread aircraftThread, int delaySeconds)
            throws InterruptedException {
        if (delaySeconds < 0 || delaySeconds > 2) {
            throw new IllegalArgumentException("Arrival delay must be 0, 1, or 2 seconds");
        }
        Thread.sleep(delaySeconds * 1000L);
        aircraftThread.start();
    }

    private static long findSeedForImmediateCongestionArrivals() {
        for (long seed = 0L; seed < 100000L; seed++) {
            Random candidate = new Random(seed);
            if (candidate.nextInt(3) == 0
                    && candidate.nextInt(3) == 0
                    && candidate.nextInt(3) == 0) {
                return seed;
            }
        }
        throw new IllegalStateException("Unable to find reproducible scenario seed");
    }

    private static void verifyOutputOrdering(String output) {
        int emergencyArrival = output.indexOf(
                "[Aircraft-Emergency] [EMERGENCY] Aircraft-Emergency has low fuel");
        int keyState = output.indexOf("ACTUAL CONGESTED STATE: occupiedGates=2");
        int emergencySelection = output.indexOf(
                "[ATC] [EMERGENCY] Emergency request received and selected for next safe landing: "
                        + "Aircraft-Emergency");
        int emergencyGrant = output.indexOf(
                "[ATC] [EMERGENCY] Landing permission granted to Aircraft-Emergency");
        int normalOneGrant = output.indexOf(
                "[ATC] [LANDING] Landing permission granted to Aircraft-Normal-1");
        int normalTwoGrant = output.indexOf(
                "[ATC] [LANDING] Landing permission granted to Aircraft-Normal-2");
        int normalOneDeparture = output.indexOf("Aircraft-Normal-1 left airport");
        int normalTwoDeparture = output.indexOf("Aircraft-Normal-2 left airport");

        assertTrue(emergencyArrival >= 0 && emergencyArrival < keyState,
                "Emergency arrival was not visible before key-state evidence");
        assertTrue(keyState < emergencySelection && emergencySelection < emergencyGrant,
                "ATC did not select and grant emergency at the next released opportunity");
        assertTrue(emergencyGrant < normalOneGrant && normalOneGrant < normalTwoGrant,
                "Emergency priority or normal FIFO recovery was incorrect");
        assertTrue(normalOneDeparture > normalOneGrant && normalTwoDeparture > normalTwoGrant,
                "Normal waiting aircraft did not subsequently progress and depart");
        assertFalse(
                output.contains("[Aircraft-Emergency] [EMERGENCY] Landing permission granted"),
                "Emergency aircraft impersonated ATC");
    }

    private static void writeEvidence(String output) throws Exception {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(EVIDENCE_FILE), "UTF-8"));
        try {
            writer.println("ASIA PACIFIC AIRPORT - VERIFIED EMERGENCY/CONGESTION EVIDENCE");
            writer.println("Scenario Random Seed: " + scenarioSeed);
            writer.println("Generated Arrival Delays (seconds): " + Arrays.toString(arrivalDelays));
            writer.println("Verified Key State: " + keyStateEvidence);
            writer.println("Result: Emergency selected before both normal waiters at the next safe opportunity.");
            writer.println("Result: Both normal waiters later landed, completed turnaround, and departed.");
            writer.println("Result: Runway, gate, capacity, and FuelTruck violation counters were all zero.");
            writer.println();
            writer.println("--- CAPTURED RUNTIME OUTPUT ---");
            writer.print(output);
        } finally {
            writer.close();
        }
    }

    private static void joinOrFail(Thread thread, String description) throws InterruptedException {
        thread.join(WAIT_TIMEOUT_MILLIS);
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

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertArrayEquals(String[] expected, String[] actual, String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected " + Arrays.toString(expected)
                            + " but was " + Arrays.toString(actual));
        }
    }

    private static final class HoldingAircraft implements Runnable {
        private final String aircraftName;
        private final boolean departureLeader;
        private final ATCDesk atcDesk;
        private final Runway runway;
        private final GatePool gatePool;
        private final AirportCapacity airportCapacity;
        private final ScenarioControl scenarioControl;

        private HoldingAircraft(
                String aircraftName,
                boolean departureLeader,
                ATCDesk atcDesk,
                Runway runway,
                GatePool gatePool,
                AirportCapacity airportCapacity,
                ScenarioControl scenarioControl) {
            this.aircraftName = aircraftName;
            this.departureLeader = departureLeader;
            this.atcDesk = atcDesk;
            this.runway = runway;
            this.gatePool = gatePool;
            this.airportCapacity = airportCapacity;
            this.scenarioControl = scenarioControl;
        }

        @Override
        public void run() {
            try {
                EventLog.log("REQUEST", aircraftName + " requesting landing permission");
                LandingRequest landingRequest = new LandingRequest(aircraftName, false);
                atcDesk.submitLandingRequest(landingRequest);
                Gate gate = landingRequest.awaitPermission();
                EventLog.log("LANDING", aircraftName + " landed for congestion setup");
                runway.release(aircraftName);
                atcDesk.signalResourceChange();
                EventLog.log("GATE", aircraftName + " occupying Gate-" + gate.getNumber());
                scenarioControl.markAircraftAtGate();

                if (departureLeader) {
                    scenarioControl.awaitLeaderTakeoffRequest();
                } else {
                    scenarioControl.awaitReleaseHoldingAircraft();
                }

                EventLog.log("REQUEST", aircraftName + " requesting take-off permission");
                TakeoffRequest takeoffRequest = new TakeoffRequest(aircraftName);
                atcDesk.submitTakeoffRequest(takeoffRequest);
                takeoffRequest.awaitPermission();

                if (departureLeader) {
                    scenarioControl.markLeaderHoldingRunway();
                    scenarioControl.awaitReleaseHoldingAircraft();
                }

                EventLog.log("GATE", aircraftName + " releasing congestion setup gate");
                gatePool.releaseGate(aircraftName);
                runway.release(aircraftName);
                airportCapacity.leave(aircraftName);
                atcDesk.signalResourceChange();
                EventLog.log("DEPARTURE", aircraftName + " left airport");
                scenarioControl.markCompleted();
            } catch (Throwable throwable) {
                scenarioControl.reportFailure(throwable);
            }
        }
    }

    private static final class ScenarioControl {
        private int aircraftAtGates;
        private int completedAircraft;
        private boolean leaderTakeoffAllowed;
        private boolean leaderHoldingRunway;
        private boolean releaseHoldingAircraft;
        private Throwable failure;

        public synchronized void markAircraftAtGate() {
            aircraftAtGates++;
            notifyAll();
        }

        public synchronized boolean awaitAircraftAtGates(int expected, long timeoutMillis)
                throws InterruptedException {
            return awaitCount(expected, timeoutMillis, true);
        }

        public synchronized void allowLeaderTakeoffRequest() {
            leaderTakeoffAllowed = true;
            notifyAll();
        }

        public synchronized void awaitLeaderTakeoffRequest() throws InterruptedException {
            while (!leaderTakeoffAllowed && failure == null) {
                wait();
            }
            requireHealthy();
        }

        public synchronized void markLeaderHoldingRunway() {
            leaderHoldingRunway = true;
            notifyAll();
        }

        public synchronized boolean awaitLeaderHoldingRunway(long timeoutMillis)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!leaderHoldingRunway && failure == null && timeoutMillis > 0L) {
                wait(timeoutMillis);
                timeoutMillis = deadline - System.currentTimeMillis();
            }
            requireHealthy();
            return leaderHoldingRunway;
        }

        public synchronized void releaseHoldingAircraft() {
            releaseHoldingAircraft = true;
            notifyAll();
        }

        public synchronized void awaitReleaseHoldingAircraft() throws InterruptedException {
            while (!releaseHoldingAircraft && failure == null) {
                wait();
            }
            requireHealthy();
        }

        public synchronized void markCompleted() {
            completedAircraft++;
            notifyAll();
        }

        public synchronized int getCompletedAircraft() {
            return completedAircraft;
        }

        public synchronized void reportFailure(Throwable throwable) {
            if (failure == null) {
                failure = throwable;
            }
            notifyAll();
        }

        public synchronized Throwable getFailure() {
            return failure;
        }

        private boolean awaitCount(int expected, long timeoutMillis, boolean gateCount)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while ((gateCount ? aircraftAtGates : completedAircraft) < expected
                    && failure == null
                    && timeoutMillis > 0L) {
                wait(timeoutMillis);
                timeoutMillis = deadline - System.currentTimeMillis();
            }
            requireHealthy();
            return (gateCount ? aircraftAtGates : completedAircraft) >= expected;
        }

        private void requireHealthy() {
            if (failure != null) {
                throw new IllegalStateException("Scenario setup aircraft failed", failure);
            }
        }
    }
}

