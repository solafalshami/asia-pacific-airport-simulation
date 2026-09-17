public final class SimulationResult {
    private final Statistics statistics;
    private final FinalReport finalReport;
    private final GatePool gatePool;
    private final Runway runway;
    private final AirportCapacity airportCapacity;
    private final FuelTruck fuelTruck;
    private final ATCDesk atcDesk;
    private final int[] arrivalDelays;
    private final int[] passengerCounts;
    private final boolean allAircraftStopped;
    private final boolean atcStopped;
    private final boolean fuelTruckStopped;

    public SimulationResult(
            Statistics statistics,
            FinalReport finalReport,
            GatePool gatePool,
            Runway runway,
            AirportCapacity airportCapacity,
            FuelTruck fuelTruck,
            ATCDesk atcDesk,
            int[] arrivalDelays,
            int[] passengerCounts,
            boolean allAircraftStopped,
            boolean atcStopped,
            boolean fuelTruckStopped) {
        this.statistics = statistics;
        this.finalReport = finalReport;
        this.gatePool = gatePool;
        this.runway = runway;
        this.airportCapacity = airportCapacity;
        this.fuelTruck = fuelTruck;
        this.atcDesk = atcDesk;
        this.arrivalDelays = arrivalDelays.clone();
        this.passengerCounts = passengerCounts.clone();
        this.allAircraftStopped = allAircraftStopped;
        this.atcStopped = atcStopped;
        this.fuelTruckStopped = fuelTruckStopped;
    }

    public Statistics getStatistics() {
        return statistics;
    }

    public FinalReport getFinalReport() {
        return finalReport;
    }

    public GatePool getGatePool() {
        return gatePool;
    }

    public Runway getRunway() {
        return runway;
    }

    public AirportCapacity getAirportCapacity() {
        return airportCapacity;
    }

    public FuelTruck getFuelTruck() {
        return fuelTruck;
    }

    public ATCDesk getAtcDesk() {
        return atcDesk;
    }

    public int[] getArrivalDelays() {
        return arrivalDelays.clone();
    }

    public int[] getPassengerCounts() {
        return passengerCounts.clone();
    }

    public boolean areAllAircraftStopped() {
        return allAircraftStopped;
    }

    public boolean isAtcStopped() {
        return atcStopped;
    }

    public boolean isFuelTruckStopped() {
        return fuelTruckStopped;
    }
}

