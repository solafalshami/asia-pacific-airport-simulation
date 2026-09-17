public final class FuelRequest {
    private final String aircraftName;
    private final TurnaroundState turnaroundState;
    private boolean refuellingStarted;
    private boolean completed;
    private Throwable failure;

    public FuelRequest(String aircraftName, TurnaroundState turnaroundState) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
        if (turnaroundState == null) {
            throw new IllegalArgumentException("TurnaroundState must not be null");
        }
        this.aircraftName = aircraftName;
        this.turnaroundState = turnaroundState;
    }

    public void beginRefuelling() throws InterruptedException {
        turnaroundState.awaitDisembarkingComplete();
        synchronized (this) {
            if (refuellingStarted || completed || failure != null) {
                throw new IllegalStateException("Fuel request cannot start in its current state");
            }
            refuellingStarted = true;
        }
        turnaroundState.markFuelStarted();
    }

    public void complete() {
        turnaroundState.markFuelComplete();
        synchronized (this) {
            if (!refuellingStarted || completed || failure != null) {
                throw new IllegalStateException("Fuel request cannot complete in its current state");
            }
            completed = true;
            notifyAll();
        }
    }

    public void fail(Throwable throwable) {
        synchronized (this) {
            if (!completed && failure == null) {
                failure = throwable;
                notifyAll();
            }
        }
        turnaroundState.reportFailure("FuelTruck", throwable);
    }

    public synchronized void awaitCompletion() throws InterruptedException {
        while (!completed && failure == null) {
            wait();
        }
        if (failure != null) {
            throw new IllegalStateException("Refuelling failed for " + aircraftName, failure);
        }
    }

    public String getAircraftName() {
        return aircraftName;
    }

    public synchronized boolean isRefuellingStarted() {
        return refuellingStarted;
    }

    public synchronized boolean isCompleted() {
        return completed;
    }

    public synchronized boolean hasFailure() {
        return failure != null;
    }
}

