public final class EmergencyScenarioControl {
    private boolean runwayHolderReady;
    private boolean gateHolderReady;
    private boolean releaseHolders;

    public synchronized void holdWithRunwayUntilScenarioReady() throws InterruptedException {
        runwayHolderReady = true;
        notifyAll();
        while (!releaseHolders) {
            wait();
        }
    }

    public synchronized void awaitGateHolderBeforeTakingRunway() throws InterruptedException {
        while (!gateHolderReady) {
            wait();
        }
    }

    public synchronized void holdAtGateUntilScenarioReady() throws InterruptedException {
        gateHolderReady = true;
        notifyAll();
        while (!releaseHolders) {
            wait();
        }
    }

    public synchronized boolean awaitBothHoldersReady(long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while ((!runwayHolderReady || !gateHolderReady) && timeoutMillis > 0L) {
            wait(timeoutMillis);
            timeoutMillis = deadline - System.currentTimeMillis();
        }
        return runwayHolderReady && gateHolderReady;
    }

    public synchronized void releaseHolders() {
        releaseHolders = true;
        notifyAll();
    }
}

