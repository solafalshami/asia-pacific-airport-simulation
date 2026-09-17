public final class TurnaroundState {
    private final boolean fuelRequired;
    private boolean disembarkingStarted;
    private boolean disembarkingComplete;
    private boolean cleaningSuppliesStarted;
    private boolean cleaningSuppliesComplete;
    private boolean boardingStarted;
    private boolean boardingComplete;
    private boolean fuelStarted;
    private boolean fuelComplete;
    private long disembarkingStartedAtNanos;
    private long disembarkingCompletedAtNanos;
    private long cleaningSuppliesStartedAtNanos;
    private long cleaningSuppliesCompletedAtNanos;
    private long boardingStartedAtNanos;
    private long boardingCompletedAtNanos;
    private long fuelStartedAtNanos;
    private long fuelCompletedAtNanos;
    private String failedActor;
    private Throwable failure;

    public TurnaroundState() {
        this(false);
    }

    public TurnaroundState(boolean fuelRequired) {
        this.fuelRequired = fuelRequired;
    }

    public synchronized void markDisembarkingStarted() {
        requireHealthy();
        if (disembarkingStarted) {
            throw new IllegalStateException("Passenger disembarking already started");
        }
        disembarkingStarted = true;
        disembarkingStartedAtNanos = System.nanoTime();
        notifyAll();
    }

    public synchronized void markDisembarkingComplete() {
        requireHealthy();
        if (!disembarkingStarted || disembarkingComplete) {
            throw new IllegalStateException("Invalid passenger disembarking completion");
        }
        disembarkingComplete = true;
        disembarkingCompletedAtNanos = System.nanoTime();
        notifyAll();
    }

    public synchronized void awaitDisembarkingComplete() throws InterruptedException {
        while (!disembarkingComplete && failure == null) {
            wait();
        }
        requireHealthy();
    }

    public synchronized void markCleaningSuppliesStarted() {
        requireHealthy();
        if (!disembarkingComplete || cleaningSuppliesStarted) {
            throw new IllegalStateException(
                    "Cleaning and supplies require completed passenger disembarking");
        }
        cleaningSuppliesStarted = true;
        cleaningSuppliesStartedAtNanos = System.nanoTime();
        notifyAll();
    }

    public synchronized void markCleaningSuppliesComplete() {
        requireHealthy();
        if (!cleaningSuppliesStarted || cleaningSuppliesComplete) {
            throw new IllegalStateException("Invalid cleaning and supplies completion");
        }
        cleaningSuppliesComplete = true;
        cleaningSuppliesCompletedAtNanos = System.nanoTime();
        notifyAll();
    }

    public synchronized void awaitReadyForBoarding() throws InterruptedException {
        // A while guard handles both early notifications and spurious wake-ups.
        while ((!cleaningSuppliesComplete || (fuelRequired && !fuelComplete))
                && failure == null) {
            wait();
        }
        requireHealthy();
    }

    public synchronized void markFuelStarted() {
        requireHealthy();
        if (!fuelRequired || !disembarkingComplete || fuelStarted) {
            throw new IllegalStateException(
                    "Refuelling requires a fuel-enabled turnaround after disembarking");
        }
        fuelStarted = true;
        fuelStartedAtNanos = System.nanoTime();
        notifyAll();
    }

    public synchronized void markFuelComplete() {
        requireHealthy();
        if (!fuelStarted || fuelComplete) {
            throw new IllegalStateException("Invalid refuelling completion");
        }
        fuelComplete = true;
        fuelCompletedAtNanos = System.nanoTime();
        notifyAll();
    }

    public synchronized void markBoardingStarted() {
        requireHealthy();
        if (!cleaningSuppliesComplete
                || (fuelRequired && !fuelComplete)
                || boardingStarted) {
            throw new IllegalStateException(
                    "Passenger boarding requires completed cleaning, supplies, and fuel");
        }
        boardingStarted = true;
        boardingStartedAtNanos = System.nanoTime();
        notifyAll();
    }

    public synchronized void markBoardingComplete() {
        requireHealthy();
        if (!boardingStarted || boardingComplete) {
            throw new IllegalStateException("Invalid passenger boarding completion");
        }
        boardingComplete = true;
        boardingCompletedAtNanos = System.nanoTime();
        notifyAll();
    }

    public synchronized void awaitTurnaroundComplete() throws InterruptedException {
        while (!isTurnaroundCompleteCondition() && failure == null) {
            wait();
        }
        requireHealthy();
    }

    public synchronized void reportFailure(String actorName, Throwable throwable) {
        if (failure == null) {
            failedActor = actorName;
            failure = throwable;
        }
        notifyAll();
    }

    public synchronized boolean isTurnaroundComplete() {
        return isTurnaroundCompleteCondition() && failure == null;
    }

    public synchronized boolean hasFailure() {
        return failure != null;
    }

    public synchronized String getFailedActor() {
        return failedActor;
    }

    public synchronized long getDisembarkingStartedAtNanos() {
        return disembarkingStartedAtNanos;
    }

    public synchronized long getDisembarkingCompletedAtNanos() {
        return disembarkingCompletedAtNanos;
    }

    public synchronized long getCleaningSuppliesStartedAtNanos() {
        return cleaningSuppliesStartedAtNanos;
    }

    public synchronized long getCleaningSuppliesCompletedAtNanos() {
        return cleaningSuppliesCompletedAtNanos;
    }

    public synchronized long getBoardingStartedAtNanos() {
        return boardingStartedAtNanos;
    }

    public synchronized long getBoardingCompletedAtNanos() {
        return boardingCompletedAtNanos;
    }

    public synchronized boolean isFuelRequired() {
        return fuelRequired;
    }

    public synchronized boolean isFuelComplete() {
        return fuelComplete;
    }

    public synchronized long getFuelStartedAtNanos() {
        return fuelStartedAtNanos;
    }

    public synchronized long getFuelCompletedAtNanos() {
        return fuelCompletedAtNanos;
    }

    private boolean isTurnaroundCompleteCondition() {
        return disembarkingComplete
                && cleaningSuppliesComplete
                && (!fuelRequired || fuelComplete)
                && boardingComplete;
    }

    private void requireHealthy() {
        if (failure != null) {
            throw new IllegalStateException(
                    "Turnaround actor failed: " + failedActor,
                    failure);
        }
    }
}

