import java.util.HashSet;
import java.util.Set;

public final class Statistics {
    private final Set<String> waitingTimeAircraft = new HashSet<String>();
    private final Set<String> servedAircraft = new HashSet<String>();
    private final Set<String> boardedAircraft = new HashSet<String>();
    private long minimumWaitingNanos = Long.MAX_VALUE;
    private long maximumWaitingNanos;
    private long totalWaitingNanos;
    private int totalPassengersBoarded;

    public synchronized void recordLandingWaitingTime(
            String aircraftName,
            long waitingNanos) {
        requireAircraftName(aircraftName);
        if (waitingNanos < 0L) {
            throw new IllegalArgumentException("Waiting time must not be negative");
        }
        if (!waitingTimeAircraft.add(aircraftName)) {
            throw new IllegalStateException(
                    aircraftName + " already has a recorded landing waiting time");
        }
        if (waitingNanos < minimumWaitingNanos) {
            minimumWaitingNanos = waitingNanos;
        }
        if (waitingNanos > maximumWaitingNanos) {
            maximumWaitingNanos = waitingNanos;
        }
        totalWaitingNanos += waitingNanos;
    }

    public synchronized void recordPassengersBoarded(
            String aircraftName,
            int passengerCount) {
        requireAircraftName(aircraftName);
        if (passengerCount < 0 || passengerCount > 50) {
            throw new IllegalArgumentException("Passenger count must be between 0 and 50");
        }
        if (!boardedAircraft.add(aircraftName)) {
            throw new IllegalStateException(
                    aircraftName + " already has recorded boarded passengers");
        }
        totalPassengersBoarded += passengerCount;
    }

    public synchronized void recordAircraftServed(String aircraftName) {
        requireAircraftName(aircraftName);
        if (!servedAircraft.add(aircraftName)) {
            throw new IllegalStateException(aircraftName + " was already recorded as served");
        }
    }

    public synchronized int getWaitingTimeCount() {
        return waitingTimeAircraft.size();
    }

    public synchronized int getAircraftServed() {
        return servedAircraft.size();
    }

    public synchronized int getBoardingRecordCount() {
        return boardedAircraft.size();
    }

    public synchronized int getTotalPassengersBoarded() {
        return totalPassengersBoarded;
    }

    public synchronized double getMinimumWaitingMillis() {
        return waitingTimeAircraft.isEmpty() ? 0.0 : minimumWaitingNanos / 1_000_000.0;
    }

    public synchronized double getAverageWaitingMillis() {
        return waitingTimeAircraft.isEmpty()
                ? 0.0
                : totalWaitingNanos / 1_000_000.0 / waitingTimeAircraft.size();
    }

    public synchronized double getMaximumWaitingMillis() {
        return waitingTimeAircraft.isEmpty() ? 0.0 : maximumWaitingNanos / 1_000_000.0;
    }

    private static void requireAircraftName(String aircraftName) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
    }
}

