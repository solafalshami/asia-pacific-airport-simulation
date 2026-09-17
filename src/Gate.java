public final class Gate {
    private final int number;
    private String aircraftName;

    public Gate(int number) {
        if (number <= 0) {
            throw new IllegalArgumentException("Gate number must be positive");
        }
        this.number = number;
    }

    public synchronized boolean reserve(String requestedAircraftName) {
        requireAircraftName(requestedAircraftName);
        if (aircraftName != null) {
            return false;
        }
        aircraftName = requestedAircraftName;
        return true;
    }

    public synchronized void release(String releasingAircraftName) {
        requireAircraftName(releasingAircraftName);
        if (!releasingAircraftName.equals(aircraftName)) {
            throw new IllegalStateException(
                    releasingAircraftName + " cannot release Gate-" + number
                            + " occupied by " + aircraftName);
        }
        aircraftName = null;
    }

    public int getNumber() {
        return number;
    }

    public synchronized boolean isEmpty() {
        return aircraftName == null;
    }

    public synchronized String getAircraftName() {
        return aircraftName;
    }

    private static void requireAircraftName(String aircraftName) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
    }
}


