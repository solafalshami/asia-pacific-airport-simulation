public final class TakeoffRequest {
    private final String aircraftName;
    private boolean granted;
    private boolean cancelled;

    public TakeoffRequest(String aircraftName) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
        this.aircraftName = aircraftName;
    }

    public synchronized void awaitPermission() throws InterruptedException {
        // Permission and cancellation are both monitor conditions.
        while (!granted && !cancelled) {
            wait();
        }
        if (cancelled) {
            throw new IllegalStateException("Take-off request was cancelled for " + aircraftName);
        }
    }

    public synchronized void grant() {
        if (granted || cancelled) {
            throw new IllegalStateException("Take-off request has already been granted");
        }
        granted = true;
        notifyAll();
    }

    public synchronized boolean cancel() {
        if (granted || cancelled) {
            return false;
        }
        cancelled = true;
        notifyAll();
        return true;
    }

    public String getAircraftName() {
        return aircraftName;
    }

    public synchronized boolean isGranted() {
        return granted;
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }
}

