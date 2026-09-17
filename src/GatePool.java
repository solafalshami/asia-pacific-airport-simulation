public final class GatePool {
    private final Gate[] gates;
    private int gateViolations;

    public GatePool(int gateCount) {
        if (gateCount <= 0) {
            throw new IllegalArgumentException("Gate count must be positive");
        }
        gates = new Gate[gateCount];
        for (int index = 0; index < gateCount; index++) {
            gates[index] = new Gate(index + 1);
        }
    }

    public synchronized Gate tryReserveGate(String aircraftName) {
        requireAircraftName(aircraftName);
        // The pool monitor prevents one aircraft taking two gates during a scan.
        if (findGateFor(aircraftName) != null) {
            gateViolations++;
            throw new IllegalStateException(aircraftName + " already has a gate");
        }

        for (Gate gate : gates) {
            if (gate.reserve(aircraftName)) {
                notifyAll();
                return gate;
            }
        }
        return null;
    }

    public synchronized void releaseGate(String aircraftName) {
        requireAircraftName(aircraftName);
        Gate gate = findGateFor(aircraftName);
        if (gate == null) {
            gateViolations++;
            throw new IllegalStateException(aircraftName + " has no reserved gate");
        }
        gate.release(aircraftName);
        notifyAll();
    }

    public synchronized Gate findGateFor(String aircraftName) {
        requireAircraftName(aircraftName);
        for (Gate gate : gates) {
            if (aircraftName.equals(gate.getAircraftName())) {
                return gate;
            }
        }
        return null;
    }

    public synchronized int getOccupiedCount() {
        int occupied = 0;
        for (Gate gate : gates) {
            if (!gate.isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    public synchronized boolean areAllEmpty() {
        return getOccupiedCount() == 0;
    }

    public synchronized String[] getOccupancySnapshot() {
        String[] snapshot = new String[gates.length];
        for (int index = 0; index < gates.length; index++) {
            snapshot[index] = gates[index].getAircraftName();
        }
        return snapshot;
    }

    public int getGateCount() {
        return gates.length;
    }

    public synchronized int getGateViolations() {
        return gateViolations;
    }

    public synchronized boolean awaitOccupiedCount(int expected, long timeoutMillis)
            throws InterruptedException {
        if (expected < 0 || expected > gates.length || timeoutMillis < 0) {
            throw new IllegalArgumentException("Invalid occupied-gate wait condition");
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (getOccupiedCount() != expected && timeoutMillis > 0L) {
            wait(timeoutMillis);
            timeoutMillis = deadline - System.currentTimeMillis();
        }
        return getOccupiedCount() == expected;
    }

    private static void requireAircraftName(String aircraftName) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
    }
}

