public final class LandingRequest {
    private final String aircraftName;
    private final boolean emergency;
    private final long requestedAtNanos;
    private Gate assignedGate;
    private boolean granted;
    private boolean cancelled;

    public LandingRequest(String aircraftName, boolean emergency) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
        this.aircraftName = aircraftName;
        this.emergency = emergency;
        this.requestedAtNanos = System.nanoTime();
    }

    public synchronized Gate awaitPermission() throws InterruptedException {
        // Always recheck the condition after notification or a spurious wake-up.
        while (!granted && !cancelled) {
            wait();
        }
        if (cancelled) {
            throw new IllegalStateException("Landing request was cancelled for " + aircraftName);
        }
        return assignedGate;
    }

    public synchronized void grant(Gate gate) {
        if (gate == null) {
            throw new IllegalArgumentException("A landing request requires an assigned gate");
        }
        if (granted || cancelled) {
            throw new IllegalStateException("Landing request has already been granted");
        }
        assignedGate = gate;
        granted = true;
        notifyAll();
    }

    public synchronized boolean cancel() {
        if (granted || cancelled) {
            return false;
        }
        cancelled = true;
        // Wake any waiter so cancellation is visible immediately.
        notifyAll();
        return true;
    }

    public String getAircraftName() {
        return aircraftName;
    }

    public boolean isEmergency() {
        return emergency;
    }

    public long getRequestedAtNanos() {
        return requestedAtNanos;
    }

    public synchronized boolean isGranted() {
        return granted;
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized Gate getAssignedGate() {
        return assignedGate;
    }
}

