import java.util.LinkedList;

public final class ATCDesk {
    private final LinkedList<LandingRequest> emergencyLandingQueue =
            new LinkedList<LandingRequest>();
    private final LinkedList<LandingRequest> normalLandingQueue =
            new LinkedList<LandingRequest>();
    private final LinkedList<TakeoffRequest> takeoffQueue =
            new LinkedList<TakeoffRequest>();

    private final Runway runway;
    private final GatePool gatePool;
    private final AirportCapacity airportCapacity;
    private boolean shutdownRequested;

    public ATCDesk(Runway runway, GatePool gatePool, AirportCapacity airportCapacity) {
        if (runway == null || gatePool == null || airportCapacity == null) {
            throw new IllegalArgumentException("ATCDesk resources must not be null");
        }
        this.runway = runway;
        this.gatePool = gatePool;
        this.airportCapacity = airportCapacity;
    }

    public synchronized void submitLandingRequest(LandingRequest request) {
        requireOpenDesk();
        if (request == null) {
            throw new IllegalArgumentException("Landing request must not be null");
        }
        rejectDuplicateRequest(request.getAircraftName());
        if (request.isEmergency()) {
            emergencyLandingQueue.addLast(request);
        } else {
            normalLandingQueue.addLast(request);
        }
        notifyAll();
    }

    public synchronized void submitTakeoffRequest(TakeoffRequest request) {
        requireOpenDesk();
        if (request == null) {
            throw new IllegalArgumentException("Take-off request must not be null");
        }
        rejectDuplicateRequest(request.getAircraftName());
        if (!airportCapacity.contains(request.getAircraftName())
                || gatePool.findGateFor(request.getAircraftName()) == null) {
            throw new IllegalStateException(
                    request.getAircraftName() + " must be on the ground at a gate before take-off");
        }
        takeoffQueue.addLast(request);
        notifyAll();
    }

    public synchronized boolean cancelLandingRequest(LandingRequest request) {
        emergencyLandingQueue.remove(request);
        normalLandingQueue.remove(request);
        boolean cancelled = request.cancel();
        notifyAll();
        return cancelled;
    }

    public synchronized boolean cancelTakeoffRequest(TakeoffRequest request) {
        takeoffQueue.remove(request);
        boolean cancelled = request.cancel();
        notifyAll();
        return cancelled;
    }

    public synchronized void releaseCancelledLanding(ATCDecision decision) {
        String aircraftName = decision.getLandingRequest().getAircraftName();
        if (aircraftName.equals(runway.getReservedFor())) {
            runway.release(aircraftName);
        }
        if (gatePool.findGateFor(aircraftName) != null) {
            gatePool.releaseGate(aircraftName);
        }
        if (airportCapacity.contains(aircraftName)) {
            airportCapacity.leave(aircraftName);
        }
        notifyAll();
    }

    public synchronized void releaseCancelledTakeoff(ATCDecision decision) {
        String aircraftName = decision.getTakeoffRequest().getAircraftName();
        if (aircraftName.equals(runway.getReservedFor())) {
            runway.release(aircraftName);
        }
        notifyAll();
    }

    public synchronized ATCDecision awaitNextDecision() throws InterruptedException {
        while (true) {
            ATCDecision decision = tryCreateDecision();
            if (decision != null) {
                return decision;
            }
            if (shutdownRequested && queuesAreEmpty()) {
                return null;
            }
            wait();
        }
    }

    public synchronized void signalResourceChange() {
        notifyAll();
    }

    public synchronized void requestShutdown() {
        shutdownRequested = true;
        notifyAll();
    }

    public synchronized int getEmergencyLandingQueueSize() {
        return emergencyLandingQueue.size();
    }

    public synchronized int getNormalLandingQueueSize() {
        return normalLandingQueue.size();
    }

    public synchronized int getTakeoffQueueSize() {
        return takeoffQueue.size();
    }

    public synchronized boolean awaitLandingQueueCounts(
            int minimumNormalRequests,
            int minimumEmergencyRequests,
            long timeoutMillis) throws InterruptedException {
        if (minimumNormalRequests < 0 || minimumEmergencyRequests < 0 || timeoutMillis < 0) {
            throw new IllegalArgumentException("Queue counts and timeout must not be negative");
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while ((normalLandingQueue.size() < minimumNormalRequests
                || emergencyLandingQueue.size() < minimumEmergencyRequests)
                && timeoutMillis > 0L) {
            wait(timeoutMillis);
            timeoutMillis = deadline - System.currentTimeMillis();
        }
        return normalLandingQueue.size() >= minimumNormalRequests
                && emergencyLandingQueue.size() >= minimumEmergencyRequests;
    }

    public synchronized String[] getNormalLandingQueueSnapshot() {
        return landingQueueSnapshot(normalLandingQueue);
    }

    public synchronized String[] getEmergencyLandingQueueSnapshot() {
        return landingQueueSnapshot(emergencyLandingQueue);
    }

    private ATCDecision tryCreateDecision() {
        if (!runway.isEmpty()) {
            return null;
        }

        // Emergency requests lead only when the same gate, capacity, and runway
        // safety checks used by normal landings can all succeed.
        if (!emergencyLandingQueue.isEmpty()) {
            LandingRequest emergencyRequest = emergencyLandingQueue.getFirst();
            Gate gate = tryAllocateLanding(emergencyRequest);
            if (gate != null) {
                emergencyLandingQueue.removeFirst();
                return ATCDecision.landing(emergencyRequest, gate);
            }
        }

        if (!takeoffQueue.isEmpty()) {
            TakeoffRequest takeoffRequest = takeoffQueue.getFirst();
            if (runway.tryAcquire(takeoffRequest.getAircraftName())) {
                takeoffQueue.removeFirst();
                return ATCDecision.takeoff(takeoffRequest);
            }
        }

        if (emergencyLandingQueue.isEmpty() && !normalLandingQueue.isEmpty()) {
            LandingRequest normalRequest = normalLandingQueue.getFirst();
            Gate gate = tryAllocateLanding(normalRequest);
            if (gate != null) {
                normalLandingQueue.removeFirst();
                return ATCDecision.landing(normalRequest, gate);
            }
        }

        return null;
    }

    private Gate tryAllocateLanding(LandingRequest request) {
        String aircraftName = request.getAircraftName();
        if (airportCapacity.contains(aircraftName)
                || airportCapacity.getCurrentCount() >= airportCapacity.getMaximumAircraft()
                || gatePool.getOccupiedCount() >= gatePool.getGateCount()) {
            return null;
        }

        Gate reservedGate = null;
        boolean capacityReserved = false;
        boolean runwayReserved = false;

        try {
            // The ATCDesk monitor keeps the multi-resource reservation atomic.
            // A landing is granted only after gate, capacity, and runway all succeed.
            reservedGate = gatePool.tryReserveGate(aircraftName);
            if (reservedGate == null) {
                return null;
            }

            capacityReserved = airportCapacity.tryEnter(aircraftName);
            if (!capacityReserved) {
                return null;
            }

            runwayReserved = runway.tryAcquire(aircraftName);
            if (!runwayReserved) {
                return null;
            }

            return reservedGate;
        } finally {
            // Roll back partial reservations so an aircraft never waits on the ground.
            if (!runwayReserved) {
                if (capacityReserved) {
                    airportCapacity.leave(aircraftName);
                }
                if (reservedGate != null) {
                    gatePool.releaseGate(aircraftName);
                }
            }
        }
    }

    private void rejectDuplicateRequest(String aircraftName) {
        if (containsLandingRequest(emergencyLandingQueue, aircraftName)
                || containsLandingRequest(normalLandingQueue, aircraftName)
                || containsTakeoffRequest(aircraftName)) {
            throw new IllegalStateException(aircraftName + " already has a pending ATC request");
        }
    }

    private boolean containsLandingRequest(
            LinkedList<LandingRequest> requests,
            String aircraftName) {
        for (LandingRequest request : requests) {
            if (aircraftName.equals(request.getAircraftName())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsTakeoffRequest(String aircraftName) {
        for (TakeoffRequest request : takeoffQueue) {
            if (aircraftName.equals(request.getAircraftName())) {
                return true;
            }
        }
        return false;
    }

    private String[] landingQueueSnapshot(LinkedList<LandingRequest> requests) {
        String[] snapshot = new String[requests.size()];
        for (int index = 0; index < requests.size(); index++) {
            snapshot[index] = requests.get(index).getAircraftName();
        }
        return snapshot;
    }

    private boolean queuesAreEmpty() {
        return emergencyLandingQueue.isEmpty()
                && normalLandingQueue.isEmpty()
                && takeoffQueue.isEmpty();
    }

    private void requireOpenDesk() {
        if (shutdownRequested) {
            throw new IllegalStateException("ATCDesk is shutting down");
        }
    }
}

