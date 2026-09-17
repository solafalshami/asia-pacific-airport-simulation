public final class Runway {
    private String reservedFor;
    private int runwayViolations;
    private int maximumObservedOccupancy;

    public synchronized void acquire(String aircraftName) throws InterruptedException {
        requireAircraftName(aircraftName);
        rejectDuplicateAcquisition(aircraftName);
        while (reservedFor != null) {
            wait();
        }
        reservedFor = aircraftName;
        maximumObservedOccupancy = 1;
    }

    public synchronized boolean tryAcquire(String aircraftName) {
        requireAircraftName(aircraftName);
        rejectDuplicateAcquisition(aircraftName);
        // Check and reservation share one critical section, preventing two users.
        if (reservedFor != null) {
            return false;
        }
        reservedFor = aircraftName;
        maximumObservedOccupancy = 1;
        return true;
    }

    public synchronized void release(String aircraftName) {
        requireAircraftName(aircraftName);
        if (!aircraftName.equals(reservedFor)) {
            runwayViolations++;
            throw new IllegalStateException(
                    aircraftName + " cannot release runway reserved for " + reservedFor);
        }
        reservedFor = null;
        notifyAll();
    }

    public synchronized boolean isEmpty() {
        return reservedFor == null;
    }

    public synchronized String getReservedFor() {
        return reservedFor;
    }

    public synchronized int getRunwayViolations() {
        return runwayViolations;
    }

    public synchronized int getMaximumObservedOccupancy() {
        return maximumObservedOccupancy;
    }

    private void rejectDuplicateAcquisition(String aircraftName) {
        if (aircraftName.equals(reservedFor)) {
            runwayViolations++;
            throw new IllegalStateException(aircraftName + " already holds the runway");
        }
    }

    private static void requireAircraftName(String aircraftName) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
    }
}

