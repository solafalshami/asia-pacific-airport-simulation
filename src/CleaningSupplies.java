public final class CleaningSupplies implements Runnable {
    private final String aircraftName;
    private final TurnaroundState turnaroundState;
    private final long activityDelayMillis;

    public CleaningSupplies(
            String aircraftName,
            TurnaroundState turnaroundState,
            long activityDelayMillis) {
        requireAircraftName(aircraftName);
        if (turnaroundState == null) {
            throw new IllegalArgumentException("TurnaroundState must not be null");
        }
        if (activityDelayMillis < 0) {
            throw new IllegalArgumentException("Activity delay must not be negative");
        }
        this.aircraftName = aircraftName;
        this.turnaroundState = turnaroundState;
        this.activityDelayMillis = activityDelayMillis;
    }

    @Override
    public void run() {
        try {
            turnaroundState.awaitDisembarkingComplete();
            turnaroundState.markCleaningSuppliesStarted();
            EventLog.log("CLEANING", "Cleaning and refilling supplies for " + aircraftName);
            Thread.sleep(activityDelayMillis);
            EventLog.log("CLEANING", "Cleaning and supplies complete for " + aircraftName);
            turnaroundState.markCleaningSuppliesComplete();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            turnaroundState.reportFailure(Thread.currentThread().getName(), exception);
            EventLog.log("CLEANING", "Cleaning and supplies interrupted for " + aircraftName);
        } catch (RuntimeException exception) {
            turnaroundState.reportFailure(Thread.currentThread().getName(), exception);
            EventLog.log("CLEANING", "Cleaning and supplies failed for " + aircraftName);
        }
    }

    private static void requireAircraftName(String aircraftName) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
    }
}

