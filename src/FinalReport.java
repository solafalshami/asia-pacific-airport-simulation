public final class FinalReport {
    private final Statistics statistics;
    private final GatePool gatePool;
    private final Runway runway;
    private final AirportCapacity airportCapacity;
    private final FuelTruck fuelTruck;
    private final int expectedAircraft;
    private final long simulationStartedAtNanos;
    private boolean printed;
    private boolean safetyPassed;
    private double runtimeSeconds;

    public FinalReport(
            Statistics statistics,
            GatePool gatePool,
            Runway runway,
            AirportCapacity airportCapacity,
            FuelTruck fuelTruck,
            int expectedAircraft,
            long simulationStartedAtNanos) {
        if (statistics == null
                || gatePool == null
                || runway == null
                || airportCapacity == null
                || fuelTruck == null) {
            throw new IllegalArgumentException("Final report dependencies must not be null");
        }
        if (expectedAircraft <= 0) {
            throw new IllegalArgumentException("Expected aircraft count must be positive");
        }
        this.statistics = statistics;
        this.gatePool = gatePool;
        this.runway = runway;
        this.airportCapacity = airportCapacity;
        this.fuelTruck = fuelTruck;
        this.expectedAircraft = expectedAircraft;
        this.simulationStartedAtNanos = simulationStartedAtNanos;
    }

    public synchronized void printReport() {
        if (printed) {
            throw new IllegalStateException("Final report has already been printed");
        }
        runtimeSeconds = (System.nanoTime() - simulationStartedAtNanos) / 1_000_000_000.0;
        String[] gates = gatePool.getOccupancySnapshot();

        EventLog.log(
                "STATISTICS",
                String.format(
                        "Minimum landing waiting time: %.3f ms",
                        statistics.getMinimumWaitingMillis()));
        EventLog.log(
                "STATISTICS",
                String.format(
                        "Average landing waiting time: %.3f ms",
                        statistics.getAverageWaitingMillis()));
        EventLog.log(
                "STATISTICS",
                String.format(
                        "Maximum landing waiting time: %.3f ms",
                        statistics.getMaximumWaitingMillis()));
        EventLog.log(
                "STATISTICS",
                "Aircraft Served: " + statistics.getAircraftServed()
                        + " / " + expectedAircraft);
        EventLog.log(
                "STATISTICS",
                "Passengers Boarded: " + statistics.getTotalPassengersBoarded());
        EventLog.log(
                "STATISTICS",
                String.format("Total simulation runtime: %.3f seconds", runtimeSeconds));

        for (int index = 0; index < gates.length; index++) {
            EventLog.log(
                    "SAFETY",
                    "Gate-" + (index + 1) + " = "
                            + (gates[index] == null ? "EMPTY" : gates[index]));
        }
        EventLog.log("SAFETY", "Runway = " + (runway.isEmpty() ? "EMPTY" : "OCCUPIED"));
        EventLog.log(
                "SAFETY",
                "Aircraft on airport grounds = " + airportCapacity.getCurrentCount());
        EventLog.log(
                "SAFETY",
                "FuelTruck = "
                        + (fuelTruck.getActiveUsers() == 0 && fuelTruck.getQueueSize() == 0
                                ? "IDLE"
                                : "BUSY"));
        EventLog.log("SAFETY", "Runway violations = " + runway.getRunwayViolations());
        EventLog.log("SAFETY", "Gate violations = " + gatePool.getGateViolations());
        EventLog.log(
                "SAFETY",
                "Capacity violations = " + airportCapacity.getCapacityViolations());
        EventLog.log(
                "SAFETY",
                "FuelTruck violations = " + fuelTruck.getExclusivityViolations());

        safetyPassed = gatePool.areAllEmpty()
                && runway.isEmpty()
                && airportCapacity.getCurrentCount() == 0
                && fuelTruck.getActiveUsers() == 0
                && fuelTruck.getQueueSize() == 0
                && runway.getRunwayViolations() == 0
                && gatePool.getGateViolations() == 0
                && airportCapacity.getCapacityViolations() == 0
                && fuelTruck.getExclusivityViolations() == 0
                && statistics.getAircraftServed() == expectedAircraft
                && statistics.getWaitingTimeCount() == expectedAircraft
                && statistics.getBoardingRecordCount() == expectedAircraft
                && runtimeSeconds < 60.0;
        EventLog.log(
                "SAFETY",
                "FINAL SAFETY RESULT: " + (safetyPassed ? "PASSED" : "FAILED"));
        printed = true;
    }

    public synchronized boolean isPrinted() {
        return printed;
    }

    public synchronized boolean isSafetyPassed() {
        return safetyPassed;
    }

    public synchronized double getRuntimeSeconds() {
        return runtimeSeconds;
    }
}

