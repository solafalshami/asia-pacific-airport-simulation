import java.util.Arrays;
import java.util.Random;

public final class Main {
    private static final int AIRCRAFT_COUNT = 6;
    private static final int GATE_COUNT = 3;
    private static final int MAXIMUM_AIRCRAFT_ON_GROUND = 3;
    private static final long AIRCRAFT_STEP_DELAY_MILLIS = 80L;
    private static final long SERVICE_DELAY_MILLIS = 160L;
    private static final long REFUELLING_DELAY_MILLIS = 120L;
    private static final long SCENARIO_TIMEOUT_MILLIS = 10_000L;

    private Main() {
    }

    public static void main(String[] arguments) throws Exception {
        long seed = arguments.length == 0
                ? System.currentTimeMillis()
                : Long.parseLong(arguments[0]);
        runSimulation(seed);
    }

    public static SimulationResult runSimulation(long seed) throws Exception {
        long simulationStartedAtNanos = System.nanoTime();
        EventLog.log("SYSTEM", "Simulation random seed: " + seed);
        Runway runway = new Runway();
        GatePool gatePool = new GatePool(GATE_COUNT);
        AirportCapacity airportCapacity = new AirportCapacity(MAXIMUM_AIRCRAFT_ON_GROUND);
        ATCDesk atcDesk = new ATCDesk(runway, gatePool, airportCapacity);
        FuelTruck fuelTruck = new FuelTruck(REFUELLING_DELAY_MILLIS);
        Statistics statistics = new Statistics();
        EmergencyScenarioControl scenarioControl = new EmergencyScenarioControl();
        FinalReport finalReport = new FinalReport(
                statistics,
                gatePool,
                runway,
                airportCapacity,
                fuelTruck,
                AIRCRAFT_COUNT,
                simulationStartedAtNanos);
        Thread atcThread = new Thread(
                new ATC(atcDesk, statistics, finalReport),
                "ATC");
        Thread fuelTruckThread = new Thread(fuelTruck, "FuelTruck");
        Aircraft[] aircraft = new Aircraft[AIRCRAFT_COUNT];
        Thread[] aircraftThreads = new Thread[AIRCRAFT_COUNT];
        int[] arrivalDelays = new int[AIRCRAFT_COUNT];
        int[] passengerCounts = new int[AIRCRAFT_COUNT];
        Random arrivalRandom = new Random(seed);
        Random passengerRandom = new Random(seed ^ 0x5DEECE66DL);

        atcThread.start();
        fuelTruckThread.start();
        for (int index = 0; index < AIRCRAFT_COUNT; index++) {
            arrivalDelays[index] = arrivalRandom.nextInt(3);
            passengerCounts[index] = passengerRandom.nextInt(50) + 1;
            EventLog.log(
                    "SCHEDULE",
                    "Aircraft-" + (index + 1) + " arrival delay = "
                            + arrivalDelays[index] + " seconds");
        }

        try {
            startAircraft(0, aircraft, aircraftThreads, arrivalDelays, passengerCounts,
                    atcDesk, runway, gatePool, airportCapacity, fuelTruck, statistics,
                    scenarioControl, false, Aircraft.ScenarioRole.RUNWAY_HOLDER);
            startAircraft(1, aircraft, aircraftThreads, arrivalDelays, passengerCounts,
                    atcDesk, runway, gatePool, airportCapacity, fuelTruck, statistics,
                    scenarioControl, false, Aircraft.ScenarioRole.GATE_HOLDER);

            if (!scenarioControl.awaitBothHoldersReady(SCENARIO_TIMEOUT_MILLIS)) {
                throw new IllegalStateException("Congestion holders did not become ready");
            }

            startAircraft(2, aircraft, aircraftThreads, arrivalDelays, passengerCounts,
                    atcDesk, runway, gatePool, airportCapacity, fuelTruck, statistics,
                    scenarioControl, false, Aircraft.ScenarioRole.NONE);
            startAircraft(3, aircraft, aircraftThreads, arrivalDelays, passengerCounts,
                    atcDesk, runway, gatePool, airportCapacity, fuelTruck, statistics,
                    scenarioControl, false, Aircraft.ScenarioRole.NONE);

            if (!atcDesk.awaitLandingQueueCounts(2, 0, SCENARIO_TIMEOUT_MILLIS)) {
                throw new IllegalStateException("Two normal aircraft did not enter the landing queue");
            }

            startAircraft(4, aircraft, aircraftThreads, arrivalDelays, passengerCounts,
                    atcDesk, runway, gatePool, airportCapacity, fuelTruck, statistics,
                    scenarioControl, true, Aircraft.ScenarioRole.NONE);

            if (!atcDesk.awaitLandingQueueCounts(2, 1, SCENARIO_TIMEOUT_MILLIS)) {
                throw new IllegalStateException("Emergency aircraft did not enter the landing queue");
            }
            verifyAndLogCongestedState(atcDesk, runway, gatePool, airportCapacity);
        } finally {
            // Releasing the monitor condition guarantees holders cannot remain blocked on failure.
            scenarioControl.releaseHolders();
        }

        startAircraft(5, aircraft, aircraftThreads, arrivalDelays, passengerCounts,
                atcDesk, runway, gatePool, airportCapacity, fuelTruck, statistics,
                scenarioControl, false, Aircraft.ScenarioRole.NONE);

        boolean allAircraftStopped = true;
        for (int index = 0; index < aircraftThreads.length; index++) {
            aircraftThreads[index].join();
            allAircraftStopped = allAircraftStopped && !aircraftThreads[index].isAlive();
            if (aircraft[index].getFailure() != null) {
                throw new IllegalStateException(
                        aircraft[index].getAircraftName() + " failed",
                        aircraft[index].getFailure());
            }
        }

        fuelTruck.requestShutdown();
        fuelTruckThread.join();
        atcDesk.requestShutdown();
        atcThread.join();

        // Joins provide clean termination and visibility before returning final state.
        EventLog.log("SYSTEM", "Main joined every Aircraft, FuelTruck, and ATC thread");
        return new SimulationResult(
                statistics,
                finalReport,
                gatePool,
                runway,
                airportCapacity,
                fuelTruck,
                atcDesk,
                arrivalDelays,
                passengerCounts,
                allAircraftStopped,
                !atcThread.isAlive(),
                !fuelTruckThread.isAlive());
    }

    private static void startAircraft(
            int index,
            Aircraft[] aircraft,
            Thread[] aircraftThreads,
            int[] arrivalDelays,
            int[] passengerCounts,
            ATCDesk atcDesk,
            Runway runway,
            GatePool gatePool,
            AirportCapacity airportCapacity,
            FuelTruck fuelTruck,
            Statistics statistics,
            EmergencyScenarioControl scenarioControl,
            boolean emergency,
            Aircraft.ScenarioRole scenarioRole) throws InterruptedException {
        Thread.sleep(arrivalDelays[index] * 1000L);
        String aircraftName = "Aircraft-" + (index + 1);
        aircraft[index] = new Aircraft(
                aircraftName,
                atcDesk,
                runway,
                gatePool,
                airportCapacity,
                AIRCRAFT_STEP_DELAY_MILLIS,
                SERVICE_DELAY_MILLIS,
                fuelTruck,
                emergency,
                passengerCounts[index],
                statistics,
                scenarioControl,
                scenarioRole);
        aircraftThreads[index] = new Thread(aircraft[index], aircraftName);
        aircraftThreads[index].start();
    }

    private static void verifyAndLogCongestedState(
            ATCDesk atcDesk,
            Runway runway,
            GatePool gatePool,
            AirportCapacity airportCapacity) {
        String[] normalQueue = atcDesk.getNormalLandingQueueSnapshot();
        String[] emergencyQueue = atcDesk.getEmergencyLandingQueueSnapshot();
        int occupiedGates = gatePool.getOccupiedCount();
        if (occupiedGates != 2
                || normalQueue.length < 2
                || emergencyQueue.length < 1
                || runway.isEmpty()
                || airportCapacity.getCurrentCount() != 2) {
            throw new IllegalStateException("Official congestion state was not reached safely");
        }
        EventLog.log(
                "SCENARIO",
                "ACTUAL CONGESTED STATE: occupiedGates=" + occupiedGates
                        + ", normalQueue=" + Arrays.toString(normalQueue)
                        + ", emergencyQueue=" + Arrays.toString(emergencyQueue)
                        + ", runwayReservedFor=" + runway.getReservedFor()
                        + ", aircraftOnGround=" + airportCapacity.getCurrentCount());
    }
}

