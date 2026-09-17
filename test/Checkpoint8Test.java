import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Random;

public final class Checkpoint8Test {
    private static final String SAMPLE_OUTPUT_FILE = "SAMPLE_OUTPUT.txt";

    private Checkpoint8Test() {
    }

    public static void main(String[] arguments) throws Exception {
        testStatisticsMonitor();

        PrintStream originalOutput = System.out;
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
        PrintStream capturedOutput = new PrintStream(capturedBytes, true, "UTF-8");
        long seed = findSeedWithImmediateSixAircraftArrivals();
        SimulationResult result;
        System.setOut(capturedOutput);
        try {
            result = Main.runSimulation(seed);
        } finally {
            System.setOut(originalOutput);
            capturedOutput.close();
        }

        String output = capturedBytes.toString("UTF-8");
        verifyCompleteSimulation(result, output);
        writeSampleOutput(seed, output);
        System.out.println("CHECKPOINT 8 STATISTICS/FINAL SIMULATION TESTS: PASS");
    }

    private static void testStatisticsMonitor() {
        Statistics statistics = new Statistics();
        statistics.recordLandingWaitingTime("Aircraft-A", 100_000_000L);
        statistics.recordLandingWaitingTime("Aircraft-B", 300_000_000L);
        statistics.recordLandingWaitingTime("Aircraft-C", 200_000_000L);
        statistics.recordPassengersBoarded("Aircraft-A", 10);
        statistics.recordPassengersBoarded("Aircraft-B", 20);
        statistics.recordPassengersBoarded("Aircraft-C", 30);
        statistics.recordAircraftServed("Aircraft-A");
        statistics.recordAircraftServed("Aircraft-B");
        statistics.recordAircraftServed("Aircraft-C");

        assertDoubleEquals(100.0, statistics.getMinimumWaitingMillis(),
                "Incorrect minimum waiting time");
        assertDoubleEquals(200.0, statistics.getAverageWaitingMillis(),
                "Incorrect average waiting time");
        assertDoubleEquals(300.0, statistics.getMaximumWaitingMillis(),
                "Incorrect maximum waiting time");
        assertEquals(3, statistics.getAircraftServed(), "Incorrect served count");
        assertEquals(60, statistics.getTotalPassengersBoarded(),
                "Incorrect passenger total");
        assertThrowsDuplicate(statistics);
    }

    private static void verifyCompleteSimulation(SimulationResult result, String output) {
        Statistics statistics = result.getStatistics();
        int[] arrivalDelays = result.getArrivalDelays();
        int[] passengerCounts = result.getPassengerCounts();
        int expectedPassengerTotal = 0;

        assertEquals(6, arrivalDelays.length, "Final run did not schedule six aircraft");
        assertEquals(6, passengerCounts.length, "Final run did not assign six passenger counts");
        for (int delay : arrivalDelays) {
            assertTrue(delay >= 0 && delay <= 2, "Arrival delay was not 0, 1, or 2 seconds");
        }
        for (int passengerCount : passengerCounts) {
            assertTrue(passengerCount >= 1 && passengerCount <= 50,
                    "Passenger count exceeded official limit");
            expectedPassengerTotal += passengerCount;
        }

        assertEquals(6, statistics.getWaitingTimeCount(),
                "Not every aircraft received a waiting-time record");
        assertEquals(6, statistics.getAircraftServed(), "Aircraft were not served exactly once");
        assertEquals(6, statistics.getBoardingRecordCount(),
                "Passenger boarding was not recorded exactly once per aircraft");
        assertEquals(expectedPassengerTotal, statistics.getTotalPassengersBoarded(),
                "Passenger total did not match actual generated counts");
        assertTrue(statistics.getMinimumWaitingMillis() >= 0.0,
                "Minimum waiting time was negative");
        assertTrue(statistics.getMinimumWaitingMillis() <= statistics.getAverageWaitingMillis(),
                "Minimum waiting time exceeded average");
        assertTrue(statistics.getAverageWaitingMillis() <= statistics.getMaximumWaitingMillis(),
                "Average waiting time exceeded maximum");

        assertTrue(result.getFinalReport().isPrinted(), "ATC did not print final report");
        assertTrue(result.getFinalReport().isSafetyPassed(), "Final safety result did not pass");
        assertTrue(result.getFinalReport().getRuntimeSeconds() < 60.0,
                "Final simulation exceeded 60 seconds");
        assertTrue(result.areAllAircraftStopped(), "An Aircraft thread remained alive");
        assertTrue(result.isAtcStopped(), "ATC thread remained alive");
        assertTrue(result.isFuelTruckStopped(), "FuelTruck thread remained alive");
        assertTrue(result.getGatePool().areAllEmpty(), "A gate remained occupied");
        assertTrue(result.getRunway().isEmpty(), "Runway remained occupied");
        assertEquals(0, result.getAirportCapacity().getCurrentCount(),
                "Airport ground count did not return to zero");
        assertEquals(0, result.getFuelTruck().getActiveUsers(), "FuelTruck remained active");
        assertEquals(0, result.getFuelTruck().getQueueSize(), "Fuel queue did not drain");
        assertEquals(0, result.getRunway().getRunwayViolations(),
                "Runway violation detected");
        assertEquals(0, result.getGatePool().getGateViolations(), "Gate violation detected");
        assertEquals(0, result.getAirportCapacity().getCapacityViolations(),
                "Capacity violation detected");
        assertEquals(0, result.getFuelTruck().getExclusivityViolations(),
                "FuelTruck violation detected");

        assertTrue(output.contains("[ATC] [STATISTICS] Aircraft Served: 6 / 6"),
                "Statistics were not printed by ATC");
        assertTrue(output.contains("[ATC] [SAFETY] Gate-1 = EMPTY"),
                "Gate-1 final check missing");
        assertTrue(output.contains("[ATC] [SAFETY] Gate-2 = EMPTY"),
                "Gate-2 final check missing");
        assertTrue(output.contains("[ATC] [SAFETY] Gate-3 = EMPTY"),
                "Gate-3 final check missing");
        assertTrue(output.contains("[ATC] [SAFETY] Runway = EMPTY"),
                "Final runway check missing");
        assertTrue(output.contains("[ATC] [SAFETY] Aircraft on airport grounds = 0"),
                "Final ground-count check missing");
        assertTrue(output.contains("[ATC] [SAFETY] FuelTruck = IDLE"),
                "Final FuelTruck check missing");
        assertTrue(output.contains("[ATC] [SAFETY] FINAL SAFETY RESULT: PASSED"),
                "Final safety result missing");
        assertTrue(output.contains("ACTUAL CONGESTED STATE: occupiedGates=2")
                        && output.contains("emergencyQueue=[Aircraft-5]"),
                "Final Main run did not demonstrate real congestion and emergency demand");
        assertTrue(output.contains(
                        "[ATC] [EMERGENCY] Landing permission granted to Aircraft-5")
                        && output.indexOf(
                                "[ATC] [EMERGENCY] Landing permission granted to Aircraft-5")
                        < output.indexOf("[ATC] [LANDING] Landing permission granted to Aircraft-3")
                        && output.indexOf(
                                "[ATC] [EMERGENCY] Landing permission granted to Aircraft-5")
                        < output.indexOf(
                                "[ATC] [LANDING] Landing permission granted to Aircraft-4"),
                "Emergency did not receive the first safe landing before normal waiters");
        assertFalse(output.contains("[main] [SAFETY]"),
                "Main impersonated ATC final safety reporting");

        for (int index = 0; index < 6; index++) {
            String aircraftName = "Aircraft-" + (index + 1);
            int fuelComplete = output.indexOf("Refuelling complete for " + aircraftName);
            int boardingComplete = output.indexOf("new passengers boarded " + aircraftName);
            int takeoffRequest = output.indexOf(aircraftName + " requesting take-off permission");
            int departure = output.indexOf(aircraftName + " left airport");
            assertTrue(
                    fuelComplete >= 0
                            && fuelComplete < boardingComplete
                            && boardingComplete < takeoffRequest
                            && takeoffRequest < departure,
                    aircraftName + " lifecycle order was incorrect");
            assertEquals(1, countOccurrences(output, aircraftName + " left airport"),
                    aircraftName + " did not depart exactly once");
        }
    }

    private static long findSeedWithImmediateSixAircraftArrivals() {
        for (long seed = 0L; seed < 1_000_000L; seed++) {
            Random random = new Random(seed);
            boolean allZero = true;
            for (int index = 0; index < 6; index++) {
                if (random.nextInt(3) != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                return seed;
            }
        }
        throw new IllegalStateException("Unable to find reproducible final-simulation seed");
    }

    private static void writeSampleOutput(long seed, String output) throws Exception {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(SAMPLE_OUTPUT_FILE), "UTF-8"));
        try {
            writer.println("ASIA PACIFIC AIRPORT - REAL SUCCESSFUL SIX-AIRCRAFT RUN");
            writer.println("Reproducible Test Seed: " + seed);
            writer.println();
            writer.print(output);
        } finally {
            writer.close();
        }
    }

    private static void assertThrowsDuplicate(Statistics statistics) {
        try {
            statistics.recordAircraftServed("Aircraft-A");
            throw new AssertionError("Duplicate served record was accepted");
        } catch (IllegalStateException expected) {
            return;
        }
    }

    private static int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) >= 0) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    private static void assertDoubleEquals(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > 0.0001) {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
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

