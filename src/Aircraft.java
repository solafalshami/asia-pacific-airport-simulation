public final class Aircraft implements Runnable {
    public enum ScenarioRole {
        NONE,
        RUNWAY_HOLDER,
        GATE_HOLDER
    }

    public enum State {
        CREATED,
        APPROACHING,
        WAITING_TO_LAND,
        LANDING,
        AT_GATE,
        TURNAROUND_COMPLETE,
        WAITING_TO_TAKE_OFF,
        TAKING_OFF,
        DEPARTED,
        FAILED
    }

    private final String aircraftName;
    private final ATCDesk atcDesk;
    private final Runway runway;
    private final GatePool gatePool;
    private final AirportCapacity airportCapacity;
    private final long stepDelayMillis;
    private final long serviceDelayMillis;
    private final FuelTruck fuelTruck;
    private final boolean emergency;
    private final int passengerCount;
    private final Statistics statistics;
    private final EmergencyScenarioControl emergencyScenarioControl;
    private final ScenarioRole scenarioRole;

    private Gate assignedGate;
    private boolean runwayHeld;
    private boolean gateHeld;
    private boolean capacityHeld;
    private State state = State.CREATED;
    private Throwable failure;
    private TurnaroundState turnaroundState;

    public Aircraft(
            String aircraftName,
            ATCDesk atcDesk,
            Runway runway,
            GatePool gatePool,
            AirportCapacity airportCapacity,
            long stepDelayMillis) {
        this(
                aircraftName,
                atcDesk,
                runway,
                gatePool,
                airportCapacity,
                stepDelayMillis,
                stepDelayMillis,
                null,
                false);
    }

    public Aircraft(
            String aircraftName,
            ATCDesk atcDesk,
            Runway runway,
            GatePool gatePool,
            AirportCapacity airportCapacity,
            long stepDelayMillis,
            long serviceDelayMillis) {
        this(
                aircraftName,
                atcDesk,
                runway,
                gatePool,
                airportCapacity,
                stepDelayMillis,
                serviceDelayMillis,
                null,
                false);
    }

    public Aircraft(
            String aircraftName,
            ATCDesk atcDesk,
            Runway runway,
            GatePool gatePool,
            AirportCapacity airportCapacity,
            long stepDelayMillis,
            long serviceDelayMillis,
            FuelTruck fuelTruck) {
        this(
                aircraftName,
                atcDesk,
                runway,
                gatePool,
                airportCapacity,
                stepDelayMillis,
                serviceDelayMillis,
                fuelTruck,
                false,
                0,
                null);
    }

    public Aircraft(
            String aircraftName,
            ATCDesk atcDesk,
            Runway runway,
            GatePool gatePool,
            AirportCapacity airportCapacity,
            long stepDelayMillis,
            long serviceDelayMillis,
            FuelTruck fuelTruck,
            boolean emergency) {
        this(
                aircraftName,
                atcDesk,
                runway,
                gatePool,
                airportCapacity,
                stepDelayMillis,
                serviceDelayMillis,
                fuelTruck,
                emergency,
                0,
                null);
    }

    public Aircraft(
            String aircraftName,
            ATCDesk atcDesk,
            Runway runway,
            GatePool gatePool,
            AirportCapacity airportCapacity,
            long stepDelayMillis,
            long serviceDelayMillis,
            FuelTruck fuelTruck,
            boolean emergency,
            int passengerCount,
            Statistics statistics) {
        this(
                aircraftName,
                atcDesk,
                runway,
                gatePool,
                airportCapacity,
                stepDelayMillis,
                serviceDelayMillis,
                fuelTruck,
                emergency,
                passengerCount,
                statistics,
                null,
                ScenarioRole.NONE);
    }

    public Aircraft(
            String aircraftName,
            ATCDesk atcDesk,
            Runway runway,
            GatePool gatePool,
            AirportCapacity airportCapacity,
            long stepDelayMillis,
            long serviceDelayMillis,
            FuelTruck fuelTruck,
            boolean emergency,
            int passengerCount,
            Statistics statistics,
            EmergencyScenarioControl emergencyScenarioControl,
            ScenarioRole scenarioRole) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
        if (atcDesk == null || runway == null || gatePool == null || airportCapacity == null) {
            throw new IllegalArgumentException("Aircraft resources must not be null");
        }
        if (stepDelayMillis < 0 || serviceDelayMillis < 0) {
            throw new IllegalArgumentException("Simulation delays must not be negative");
        }
        if (passengerCount < 0 || passengerCount > 50) {
            throw new IllegalArgumentException("Passenger count must be between 0 and 50");
        }
        if (scenarioRole == null) {
            throw new IllegalArgumentException("Scenario role must not be null");
        }
        if (scenarioRole != ScenarioRole.NONE && emergencyScenarioControl == null) {
            throw new IllegalArgumentException("Scenario holders require scenario control");
        }
        this.aircraftName = aircraftName;
        this.atcDesk = atcDesk;
        this.runway = runway;
        this.gatePool = gatePool;
        this.airportCapacity = airportCapacity;
        this.stepDelayMillis = stepDelayMillis;
        this.serviceDelayMillis = serviceDelayMillis;
        this.fuelTruck = fuelTruck;
        this.emergency = emergency;
        this.passengerCount = passengerCount;
        this.statistics = statistics;
        this.emergencyScenarioControl = emergencyScenarioControl;
        this.scenarioRole = scenarioRole;
    }

    @Override
    public void run() {
        try {
            approachAirport();
            requestLanding();
            land();
            coastToGate();
            dock();
            performTurnaround();
            requestTakeoff();
            undock();
            coastToRunway();
            takeOff();
            depart();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(exception);
            EventLog.log("SYSTEM", aircraftName + " interrupted");
        } catch (RuntimeException exception) {
            recordFailure(exception);
            EventLog.log("SYSTEM", aircraftName + " failed: " + exception.getMessage());
        } finally {
            releaseHeldResources();
        }
    }

    private void approachAirport() throws InterruptedException {
        setState(State.APPROACHING);
        EventLog.log("ARRIVAL", aircraftName + " approaching airport");
        pauseForSimulation();
    }

    private void requestLanding() throws InterruptedException {
        setState(State.WAITING_TO_LAND);
        if (emergency) {
            EventLog.log("EMERGENCY", aircraftName + " has low fuel");
            EventLog.log("REQUEST", aircraftName + " requesting emergency landing permission");
        } else {
            EventLog.log("REQUEST", aircraftName + " requesting landing permission");
        }
        LandingRequest request = new LandingRequest(aircraftName, emergency);
        atcDesk.submitLandingRequest(request);
        try {
            assignedGate = request.awaitPermission();
        } catch (InterruptedException exception) {
            recoverOrCancelLandingRequest(request);
            throw exception;
        }
        runwayHeld = true;
        gateHeld = true;
        capacityHeld = true;
    }

    private void land() throws InterruptedException {
        setState(State.LANDING);
        EventLog.log("LANDING", aircraftName + " landing on runway");
        pauseForSimulation();
    }

    private void coastToGate() throws InterruptedException {
        EventLog.log(
                "TAXI",
                aircraftName + " coasting to Gate-" + assignedGate.getNumber());
        pauseForSimulation();
        runway.release(aircraftName);
        runwayHeld = false;
        atcDesk.signalResourceChange();
        EventLog.log("RUNWAY", aircraftName + " cleared runway after landing");
    }

    private void dock() throws InterruptedException {
        setState(State.AT_GATE);
        EventLog.log("GATE", aircraftName + " docked at Gate-" + assignedGate.getNumber());
        pauseForSimulation();
    }

    private void performTurnaround() throws InterruptedException {
        EventLog.log("TURNAROUND", aircraftName + " starting gate turnaround");
        turnaroundState = new TurnaroundState(fuelTruck != null);
        Thread passengerThread = new Thread(
                new PassengerGroup(
                        aircraftName,
                        turnaroundState,
                        serviceDelayMillis,
                        passengerCount,
                        statistics),
                "PassengerGroup-" + aircraftName);
        Thread cleaningThread = new Thread(
                new CleaningSupplies(aircraftName, turnaroundState, serviceDelayMillis),
                "CleaningSupplies-" + aircraftName);

        passengerThread.start();
        cleaningThread.start();
        if (fuelTruck != null) {
            EventLog.log("REQUEST", aircraftName + " requesting refuelling");
            fuelTruck.submitRequest(new FuelRequest(aircraftName, turnaroundState));
        }
        try {
            turnaroundState.awaitTurnaroundComplete();
            passengerThread.join();
            cleaningThread.join();
        } catch (InterruptedException exception) {
            passengerThread.interrupt();
            cleaningThread.interrupt();
            passengerThread.join();
            cleaningThread.join();
            throw exception;
        } catch (RuntimeException exception) {
            passengerThread.interrupt();
            cleaningThread.interrupt();
            passengerThread.join();
            cleaningThread.join();
            throw exception;
        }

        setState(State.TURNAROUND_COMPLETE);
        EventLog.log("TURNAROUND", aircraftName + " gate turnaround complete");
    }

    private void requestTakeoff() throws InterruptedException {
        setState(State.WAITING_TO_TAKE_OFF);
        if (scenarioRole == ScenarioRole.RUNWAY_HOLDER) {
            EventLog.log("SCENARIO", aircraftName + " waiting for second occupied gate");
            emergencyScenarioControl.awaitGateHolderBeforeTakingRunway();
        }
        if (scenarioRole == ScenarioRole.GATE_HOLDER) {
            EventLog.log("SCENARIO", aircraftName + " holding occupied gate for congestion state");
            emergencyScenarioControl.holdAtGateUntilScenarioReady();
        }
        EventLog.log("REQUEST", aircraftName + " requesting take-off permission");
        TakeoffRequest request = new TakeoffRequest(aircraftName);
        atcDesk.submitTakeoffRequest(request);
        try {
            request.awaitPermission();
        } catch (InterruptedException exception) {
            recoverOrCancelTakeoffRequest(request);
            throw exception;
        }
        runwayHeld = true;
        if (scenarioRole == ScenarioRole.RUNWAY_HOLDER) {
            EventLog.log("SCENARIO", aircraftName + " holding runway and gate for congestion state");
            emergencyScenarioControl.holdWithRunwayUntilScenarioReady();
        }
    }

    private void recoverOrCancelLandingRequest(LandingRequest request) {
        boolean cancelled = atcDesk.cancelLandingRequest(request);
        if (!cancelled && request.isGranted()) {
            assignedGate = request.getAssignedGate();
            runwayHeld = true;
            gateHeld = true;
            capacityHeld = true;
        }
    }

    private void recoverOrCancelTakeoffRequest(TakeoffRequest request) {
        boolean cancelled = atcDesk.cancelTakeoffRequest(request);
        if (!cancelled && request.isGranted()) {
            runwayHeld = true;
        }
    }

    private void undock() throws InterruptedException {
        EventLog.log("GATE", aircraftName + " undocking from Gate-" + assignedGate.getNumber());
        pauseForSimulation();
        gatePool.releaseGate(aircraftName);
        gateHeld = false;
        atcDesk.signalResourceChange();
    }

    private void coastToRunway() throws InterruptedException {
        EventLog.log("TAXI", aircraftName + " coasting to runway");
        pauseForSimulation();
    }

    private void takeOff() throws InterruptedException {
        setState(State.TAKING_OFF);
        EventLog.log("TAKEOFF", aircraftName + " taking off");
        pauseForSimulation();
        runway.release(aircraftName);
        runwayHeld = false;
        airportCapacity.leave(aircraftName);
        capacityHeld = false;
        atcDesk.signalResourceChange();
    }

    private void depart() {
        if (statistics != null) {
            statistics.recordAircraftServed(aircraftName);
        }
        setState(State.DEPARTED);
        EventLog.log("DEPARTURE", aircraftName + " left airport");
    }

    private void pauseForSimulation() throws InterruptedException {
        Thread.sleep(stepDelayMillis);
    }

    private void releaseHeldResources() {
        boolean resourcesChanged = false;
        if (runwayHeld && aircraftName.equals(runway.getReservedFor())) {
            runway.release(aircraftName);
            runwayHeld = false;
            resourcesChanged = true;
        }
        if (gateHeld && gatePool.findGateFor(aircraftName) != null) {
            gatePool.releaseGate(aircraftName);
            gateHeld = false;
            resourcesChanged = true;
        }
        if (capacityHeld && airportCapacity.contains(aircraftName)) {
            airportCapacity.leave(aircraftName);
            capacityHeld = false;
            resourcesChanged = true;
        }
        if (resourcesChanged) {
            atcDesk.signalResourceChange();
        }
    }

    private synchronized void setState(State newState) {
        state = newState;
    }

    private synchronized void recordFailure(Throwable throwable) {
        failure = throwable;
        state = State.FAILED;
    }

    public String getAircraftName() {
        return aircraftName;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized boolean isComplete() {
        return state == State.DEPARTED;
    }

    public synchronized Throwable getFailure() {
        return failure;
    }

    public synchronized TurnaroundState getTurnaroundState() {
        return turnaroundState;
    }

    public boolean isEmergency() {
        return emergency;
    }

    public int getPassengerCount() {
        return passengerCount;
    }
}

