public final class PassengerGroup implements Runnable {
    private final String aircraftName;
    private final TurnaroundState turnaroundState;
    private final long activityDelayMillis;
    private final int passengerCount;
    private final Statistics statistics;

    public PassengerGroup(
            String aircraftName,
            TurnaroundState turnaroundState,
            long activityDelayMillis) {
        this(aircraftName, turnaroundState, activityDelayMillis, 0, null);
    }

    public PassengerGroup(
            String aircraftName,
            TurnaroundState turnaroundState,
            long activityDelayMillis,
            int passengerCount,
            Statistics statistics) {
        requireAircraftName(aircraftName);
        if (turnaroundState == null) {
            throw new IllegalArgumentException("TurnaroundState must not be null");
        }
        if (activityDelayMillis < 0) {
            throw new IllegalArgumentException("Activity delay must not be negative");
        }
        if (passengerCount < 0 || passengerCount > 50) {
            throw new IllegalArgumentException("Passenger count must be between 0 and 50");
        }
        this.aircraftName = aircraftName;
        this.turnaroundState = turnaroundState;
        this.activityDelayMillis = activityDelayMillis;
        this.passengerCount = passengerCount;
        this.statistics = statistics;
    }

    @Override
    public void run() {
        try {
            disembark();
            turnaroundState.awaitReadyForBoarding();
            embark();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            turnaroundState.reportFailure(Thread.currentThread().getName(), exception);
            EventLog.log("PASSENGERS", "Passenger service interrupted for " + aircraftName);
        } catch (RuntimeException exception) {
            turnaroundState.reportFailure(Thread.currentThread().getName(), exception);
            EventLog.log("PASSENGERS", "Passenger service failed for " + aircraftName);
        }
    }

    private void disembark() throws InterruptedException {
        turnaroundState.markDisembarkingStarted();
        EventLog.log("PASSENGERS", "Passengers disembarking " + aircraftName);
        Thread.sleep(activityDelayMillis);
        EventLog.log("PASSENGERS", "Passengers disembarked " + aircraftName);
        turnaroundState.markDisembarkingComplete();
    }

    private void embark() throws InterruptedException {
        turnaroundState.markBoardingStarted();
        EventLog.log("PASSENGERS", "New passengers embarking " + aircraftName);
        Thread.sleep(activityDelayMillis);
        EventLog.log(
                "PASSENGERS",
                passengerCount + " new passengers boarded " + aircraftName);
        turnaroundState.markBoardingComplete();
        if (statistics != null) {
            statistics.recordPassengersBoarded(aircraftName, passengerCount);
        }
    }

    private static void requireAircraftName(String aircraftName) {
        if (aircraftName == null || aircraftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Aircraft name must not be blank");
        }
    }
}

