public final class ATC implements Runnable {
    private final ATCDesk desk;
    private final Statistics statistics;
    private final FinalReport finalReport;

    public ATC(ATCDesk desk) {
        this(desk, null, null);
    }

    public ATC(ATCDesk desk, Statistics statistics, FinalReport finalReport) {
        if (desk == null) {
            throw new IllegalArgumentException("ATCDesk must not be null");
        }
        this.desk = desk;
        this.statistics = statistics;
        this.finalReport = finalReport;
    }

    @Override
    public void run() {
        EventLog.log("SYSTEM", "ATC started");
        try {
            while (true) {
                ATCDecision decision = desk.awaitNextDecision();
                if (decision == null) {
                    break;
                }
                process(decision);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            EventLog.log("SYSTEM", "ATC interrupted");
        }
        if (finalReport != null) {
            finalReport.printReport();
        }
        EventLog.log("SYSTEM", "ATC stopped");
    }

    private void process(ATCDecision decision) {
        if (decision.getType() == ATCDecision.Type.LANDING) {
            processLanding(decision);
        } else {
            processTakeoff(decision);
        }
    }

    private void processLanding(ATCDecision decision) {
        LandingRequest request = decision.getLandingRequest();
        String aircraftName = request.getAircraftName();
        String requestType = request.isEmergency() ? "EMERGENCY" : "normal";
        boolean cancelled;

        // The request monitor makes cancellation versus grant one atomic choice.
        synchronized (request) {
            cancelled = request.isCancelled();
            if (!cancelled) {
                if (request.isEmergency()) {
                    EventLog.log(
                            "EMERGENCY",
                            "Emergency request received and selected for next safe landing: "
                                    + aircraftName);
                } else {
                    EventLog.log(
                            "QUEUE",
                            "Processing " + requestType + " landing request from " + aircraftName);
                }
                EventLog.log(
                        "GATE",
                        "Gate-" + decision.getAssignedGate().getNumber()
                                + " reserved for " + aircraftName);
                EventLog.log(
                        request.isEmergency() ? "EMERGENCY" : "LANDING",
                        "Landing permission granted to " + aircraftName);
                if (statistics != null) {
                    statistics.recordLandingWaitingTime(
                            aircraftName,
                            System.nanoTime() - request.getRequestedAtNanos());
                }
                request.grant(decision.getAssignedGate());
            }
        }
        if (cancelled) {
            desk.releaseCancelledLanding(decision);
            EventLog.log("QUEUE", "Cancelled landing resources released for " + aircraftName);
        }
    }

    private void processTakeoff(ATCDecision decision) {
        TakeoffRequest request = decision.getTakeoffRequest();
        boolean cancelled;
        synchronized (request) {
            cancelled = request.isCancelled();
            if (!cancelled) {
                EventLog.log("QUEUE", "Processing take-off request from " + request.getAircraftName());
                EventLog.log("TAKEOFF", "Take-off permission granted to " + request.getAircraftName());
                request.grant();
            }
        }
        if (cancelled) {
            desk.releaseCancelledTakeoff(decision);
            EventLog.log(
                    "QUEUE",
                    "Cancelled take-off runway released for " + request.getAircraftName());
        }
    }
}

