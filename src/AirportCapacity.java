import java.util.HashSet;
import java.util.Set;

public final class AirportCapacity {
    private final int maximumAircraft;
    private final Set<String> aircraftOnGround = new HashSet<String>();
    private int capacityViolations;
    private int maximumObservedCount;

    public AirportCapacity(int maximumAircraft) {
        if (maximumAircraft <= 0) {
            throw new IllegalArgumentException("Maximum aircraft must be positive");
        }
        this.maximumAircraft = maximumAircraft;
    }

    public synchronized void enter(String aircraftName) throws InterruptedException {
        requireAircraftName(aircraftName);
        rejectDuplicateEntry(aircraftName);
        while (aircraftOnGround.size() >= maximumAircraft) {
            wait();
        }
        addAircraft(aircraftName);
    }

    public synchronized boolean tryEnter(String aircraftName) {
        requireAircraftName(aircraftName);
        rejectDuplicateEntry(aircraftName);
        // The limit check and set update must be atomic because the runway counts too.
        if (aircraftOnGround.size() >= maximumAircraft) {
            return false;
        }
        addAircraft(aircraftName);
        return true;
    }

    public synchronized void leave(String aircraftName) {
        requireAircraftName(aircraftName);
        if (!aircraftOnGround.remove(aircraftName)) {
            capacityViolations++;
            throw new IllegalStateException(aircraftName + " is not on airport grounds");
        }
        notifyAll();
    }

    public synchronized int getCurrentCount() {
        return aircraftOnGround.size();
    }

    public int getMaximumAircraft() {
        return maximumAircraft;
    }

    public synchronized boolean contains(String aircraftName) {
        return aircraftOnGround.contains(aircraftName);
    }

    public synchronized int getCapacityViolations() {
        return capacityViolations;
    }

    public synchronized int getMaximumObservedCount() {
        return maximumObservedCount;
    }

    private void addAircraft(String aircraftName) {
        aircraftOnGround.add(aircraftName);
        if (aircraftOnGround.size() > maximumObservedCount) {
            maximumObservedCount = aircraftOnGround.size();
        }
    }

    private void rejectDuplicateEntry(String aircraftName) {
        if (aircraftOnGround.contains(aircraftName)) {
            capacityViolations++;
            throw new IllegalStateException(aircraftName + " is already on airport grounds");
        }
    }

    private static void requireAircraftName(String aircraftName) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
    }
}

