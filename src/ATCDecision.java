public final class ATCDecision {
    public enum Type {
        LANDING,
        TAKEOFF
    }

    private final Type type;
    private final LandingRequest landingRequest;
    private final TakeoffRequest takeoffRequest;
    private final Gate assignedGate;

    private ATCDecision(
            Type type,
            LandingRequest landingRequest,
            TakeoffRequest takeoffRequest,
            Gate assignedGate) {
        this.type = type;
        this.landingRequest = landingRequest;
        this.takeoffRequest = takeoffRequest;
        this.assignedGate = assignedGate;
    }

    public static ATCDecision landing(LandingRequest request, Gate assignedGate) {
        return new ATCDecision(Type.LANDING, request, null, assignedGate);
    }

    public static ATCDecision takeoff(TakeoffRequest request) {
        return new ATCDecision(Type.TAKEOFF, null, request, null);
    }

    public Type getType() {
        return type;
    }

    public LandingRequest getLandingRequest() {
        return landingRequest;
    }

    public TakeoffRequest getTakeoffRequest() {
        return takeoffRequest;
    }

    public Gate getAssignedGate() {
        return assignedGate;
    }
}


