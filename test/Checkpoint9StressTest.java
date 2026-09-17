import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class Checkpoint9StressTest {
    private static final int DISTINCT_SEEDS = 8;
    private static final int RUNS_PER_SEED = 2;

    private Checkpoint9StressTest() {
    }

    public static void main(String[] arguments) throws Exception {
        long[] seeds = selectDiverseSeeds();
        double minimumRuntime = Double.MAX_VALUE;
        double maximumRuntime = 0.0;
        double totalRuntime = 0.0;
        int completedRuns = 0;
        PrintStream originalOutput = System.out;

        for (long seed : seeds) {
            for (int repetition = 1; repetition <= RUNS_PER_SEED; repetition++) {
                ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
                PrintStream capturedOutput = new PrintStream(capturedBytes, true, "UTF-8");
                SimulationResult result;
                System.setOut(capturedOutput);
                try {
                    result = Main.runSimulation(seed);
                } finally {
                    System.setOut(originalOutput);
                    capturedOutput.close();
                }

                String output = capturedBytes.toString("UTF-8");
                verifyRun(seed, repetition, result, output);
                double runtime = result.getFinalReport().getRuntimeSeconds();
                if (runtime < minimumRuntime) {
                    minimumRuntime = runtime;
                }
                if (runtime > maximumRuntime) {
                    maximumRuntime = runtime;
                }
                totalRuntime += runtime;
                completedRuns++;
            }
        }

        System.out.println("CHECKPOINT 9 MULTI-SEED STRESS TEST: PASS");
        System.out.println("Runs: " + completedRuns);
        System.out.println("Distinct seeds: " + seeds.length);
        for (long seed : seeds) {
            System.out.println(
                    "Seed " + seed + " arrival pattern "
                            + Arrays.toString(arrivalPattern(seed)));
        }
        System.out.println(String.format(
                "Runtime seconds min/average/max: %.3f / %.3f / %.3f",
                minimumRuntime,
                totalRuntime / completedRuns,
                maximumRuntime));
        System.out.println("Safety violations across all runs: 0");
        System.out.println("Deadlock/starvation/livelock/missed-notification findings: none");
    }

    private static void verifyRun(
            long seed,
            int repetition,
            SimulationResult result,
            String output) {
        String runDescription = "seed " + seed + " repetition " + repetition;
        Statistics statistics = result.getStatistics();
        GatePool gatePool = result.getGatePool();
        Runway runway = result.getRunway();
        AirportCapacity airportCapacity = result.getAirportCapacity();
        FuelTruck fuelTruck = result.getFuelTruck();
        ATCDesk atcDesk = result.getAtcDesk();
        int expectedPassengerTotal = 0;

        assertEquals(6, statistics.getAircraftServed(),
                runDescription + " did not serve 6/6 aircraft");
        assertEquals(6, statistics.getWaitingTimeCount(),
                runDescription + " lost a waiting-time record");
        assertEquals(6, statistics.getBoardingRecordCount(),
                runDescription + " lost a boarding record");
        for (int passengerCount : result.getPassengerCounts()) {
            assertTrue(passengerCount >= 1 && passengerCount <= 50,
                    runDescription + " generated an invalid passenger count");
            expectedPassengerTotal += passengerCount;
        }
        assertEquals(expectedPassengerTotal, statistics.getTotalPassengersBoarded(),
                runDescription + " passenger total was incorrect");
        assertTrue(statistics.getMinimumWaitingMillis() >= 0.0,
                runDescription + " minimum waiting time was negative");
        assertTrue(statistics.getMinimumWaitingMillis() <= statistics.getAverageWaitingMillis(),
                runDescription + " waiting-time minimum exceeded average");
        assertTrue(statistics.getAverageWaitingMillis() <= statistics.getMaximumWaitingMillis(),
                runDescription + " waiting-time average exceeded maximum");

        for (int delay : result.getArrivalDelays()) {
            assertTrue(delay >= 0 && delay <= 2,
                    runDescription + " produced an invalid arrival delay");
        }
        assertTrue(result.getFinalReport().isPrinted(),
                runDescription + " did not print a final report");
        assertTrue(result.getFinalReport().isSafetyPassed(),
                runDescription + " final safety result failed");
        assertTrue(result.getFinalReport().getRuntimeSeconds() < 60.0,
                runDescription + " exceeded 60 seconds");
        assertTrue(result.areAllAircraftStopped(),
                runDescription + " retained an Aircraft thread");
        assertTrue(result.isAtcStopped(), runDescription + " retained ATC");
        assertTrue(result.isFuelTruckStopped(), runDescription + " retained FuelTruck");

        assertTrue(gatePool.areAllEmpty(), runDescription + " leaked a gate");
        assertTrue(runway.isEmpty(), runDescription + " leaked the runway");
        assertEquals(0, airportCapacity.getCurrentCount(),
                runDescription + " leaked ground capacity");
        assertEquals(0, fuelTruck.getActiveUsers(),
                runDescription + " left FuelTruck active");
        assertEquals(0, fuelTruck.getQueueSize(),
                runDescription + " left a fuel request queued");
        assertEquals(6, fuelTruck.getCompletedRequests(),
                runDescription + " lost a fuel request");
        assertEquals(0, atcDesk.getNormalLandingQueueSize(),
                runDescription + " left a landing request queued");
        assertEquals(0, atcDesk.getEmergencyLandingQueueSize(),
                runDescription + " left an emergency request queued");
        assertEquals(0, atcDesk.getTakeoffQueueSize(),
                runDescription + " left a take-off request queued");

        assertTrue(runway.getMaximumObservedOccupancy() <= 1,
                runDescription + " exceeded runway occupancy");
        assertTrue(airportCapacity.getMaximumObservedCount() <= 3,
                runDescription + " exceeded ground capacity");
        assertTrue(fuelTruck.getMaximumObservedUsers() <= 1,
                runDescription + " refuelled simultaneously");
        assertEquals(0, runway.getRunwayViolations(),
                runDescription + " recorded a runway violation");
        assertEquals(0, gatePool.getGateViolations(),
                runDescription + " recorded a gate violation");
        assertEquals(0, airportCapacity.getCapacityViolations(),
                runDescription + " recorded a capacity violation");
        assertEquals(0, fuelTruck.getExclusivityViolations(),
                runDescription + " recorded a FuelTruck violation");

        assertTrue(output.contains("[ATC] [SAFETY] FINAL SAFETY RESULT: PASSED"),
                runDescription + " output lacked final safety PASS");
        assertTrue(output.contains("[ATC] [SYSTEM] ATC stopped"),
                runDescription + " output lacked ATC shutdown");
        assertTrue(output.contains("[FuelTruck] [SYSTEM] FuelTruck stopped"),
                runDescription + " output lacked FuelTruck shutdown");
        assertTrue(output.contains("ACTUAL CONGESTED STATE: occupiedGates=2")
                        && output.contains("emergencyQueue=[Aircraft-5]")
                        && output.contains("aircraftOnGround=2")
                        && (output.contains("normalQueue=[Aircraft-3, Aircraft-4]")
                                || output.contains("normalQueue=[Aircraft-4, Aircraft-3]")),
                runDescription + " did not prove the official congestion state");
        assertTrue(output.contains("[Aircraft-5] [EMERGENCY] Aircraft-5 has low fuel"),
                runDescription + " lacked Aircraft-owned low-fuel output");
        int emergencyGrant = output.indexOf(
                "[ATC] [EMERGENCY] Landing permission granted to Aircraft-5");
        int normalThreeGrant = output.indexOf(
                "[ATC] [LANDING] Landing permission granted to Aircraft-3");
        int normalFourGrant = output.indexOf(
                "[ATC] [LANDING] Landing permission granted to Aircraft-4");
        assertTrue(emergencyGrant >= 0
                        && emergencyGrant < normalThreeGrant
                        && emergencyGrant < normalFourGrant,
                runDescription + " did not grant the emergency first safe landing");
        assertFalse(output.contains(" failed:"), runDescription + " logged an actor failure");
        assertFalse(output.contains(" interrupted"), runDescription + " logged an interruption");

        for (int index = 1; index <= 6; index++) {
            String aircraftName = "Aircraft-" + index;
            int fuelComplete = output.indexOf("Refuelling complete for " + aircraftName);
            int boardingComplete = output.indexOf("new passengers boarded " + aircraftName);
            int turnaroundComplete = output.indexOf(aircraftName + " gate turnaround complete");
            int takeoffRequest = output.indexOf(aircraftName + " requesting take-off permission");
            int departure = output.indexOf(aircraftName + " left airport");
            assertTrue(
                    fuelComplete >= 0
                            && fuelComplete < boardingComplete
                            && boardingComplete < turnaroundComplete
                            && turnaroundComplete < takeoffRequest
                            && takeoffRequest < departure,
                    runDescription + " premature lifecycle progress for " + aircraftName);
            assertEquals(1, countOccurrences(output, aircraftName + " left airport"),
                    runDescription + " departure count incorrect for " + aircraftName);
        }
        assertNoDomainThreadsRemain(runDescription);
    }

    private static long[] selectDiverseSeeds() {
        List<Long> seeds = new ArrayList<Long>();
        List<String> patterns = new ArrayList<String>();
        for (long seed = 0L; seed < 1_000_000L && seeds.size() < DISTINCT_SEEDS; seed++) {
            int[] pattern = arrivalPattern(seed);
            int totalDelay = 0;
            boolean hasZero = false;
            boolean hasOne = false;
            boolean hasTwo = false;
            for (int delay : pattern) {
                totalDelay += delay;
                hasZero = hasZero || delay == 0;
                hasOne = hasOne || delay == 1;
                hasTwo = hasTwo || delay == 2;
            }
            String signature = Arrays.toString(pattern);
            if (hasZero
                    && hasOne
                    && hasTwo
                    && totalDelay <= 5
                    && !patterns.contains(signature)) {
                seeds.add(seed);
                patterns.add(signature);
            }
        }
        if (seeds.size() != DISTINCT_SEEDS) {
            throw new IllegalStateException("Unable to select diverse stress seeds");
        }
        long[] result = new long[seeds.size()];
        for (int index = 0; index < seeds.size(); index++) {
            result[index] = seeds.get(index).longValue();
        }
        return result;
    }

    private static int[] arrivalPattern(long seed) {
        Random random = new Random(seed);
        int[] pattern = new int[6];
        for (int index = 0; index < pattern.length; index++) {
            pattern[index] = random.nextInt(3);
        }
        return pattern;
    }

    private static void assertNoDomainThreadsRemain(String runDescription) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive()
                    && (thread.getName().equals("ATC")
                            || thread.getName().equals("FuelTruck")
                            || thread.getName().startsWith("Aircraft-")
                            || thread.getName().startsWith("PassengerGroup-")
                            || thread.getName().startsWith("CleaningSupplies-"))) {
                throw new AssertionError(
                        runDescription + " left domain thread alive: " + thread.getName());
            }
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

