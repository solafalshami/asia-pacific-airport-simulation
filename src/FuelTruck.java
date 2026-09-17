import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public final class FuelTruck implements Runnable {
    private final LinkedList<FuelRequest> requestQueue = new LinkedList<FuelRequest>();
    private final List<String> serviceOrder = new ArrayList<String>();
    private final long refuellingDelayMillis;
    private FuelRequest currentRequest;
    private boolean shutdownRequested;
    private int activeUsers;
    private int maximumObservedUsers;
    private int exclusivityViolations;
    private int completedRequests;
    private int maximumQueueSize;

    public FuelTruck(long refuellingDelayMillis) {
        if (refuellingDelayMillis < 0) {
            throw new IllegalArgumentException("Refuelling delay must not be negative");
        }
        this.refuellingDelayMillis = refuellingDelayMillis;
    }

    public synchronized void submitRequest(FuelRequest request) {
        if (shutdownRequested) {
            throw new IllegalStateException("FuelTruck is shutting down");
        }
        if (request == null) {
            throw new IllegalArgumentException("FuelRequest must not be null");
        }
        rejectDuplicateRequest(request.getAircraftName());
        requestQueue.addLast(request);
        if (requestQueue.size() > maximumQueueSize) {
            maximumQueueSize = requestQueue.size();
        }
        notifyAll();
    }

    public synchronized void requestShutdown() {
        shutdownRequested = true;
        notifyAll();
    }

    @Override
    public void run() {
        EventLog.log("SYSTEM", "FuelTruck started");
        try {
            while (true) {
                FuelRequest request = awaitNextRequest();
                if (request == null) {
                    break;
                }
                service(request);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failPendingRequests(exception);
            EventLog.log("SYSTEM", "FuelTruck interrupted");
        }
        EventLog.log("SYSTEM", "FuelTruck stopped");
    }

    private synchronized FuelRequest awaitNextRequest() throws InterruptedException {
        while (requestQueue.isEmpty() && !shutdownRequested) {
            wait();
        }
        if (requestQueue.isEmpty()) {
            return null;
        }
        currentRequest = requestQueue.removeFirst();
        return currentRequest;
    }

    private void service(FuelRequest request) throws InterruptedException {
        boolean active = false;
        try {
            // Service runs outside the queue monitor so aircraft may enqueue concurrently.
            request.beginRefuelling();
            beginActiveService();
            active = true;
            EventLog.log("FUEL", "Refuelling " + request.getAircraftName());
            Thread.sleep(refuellingDelayMillis);
            EventLog.log("FUEL", "Refuelling complete for " + request.getAircraftName());
            request.complete();
            recordCompletion(request.getAircraftName());
        } catch (InterruptedException exception) {
            request.fail(exception);
            throw exception;
        } catch (RuntimeException exception) {
            request.fail(exception);
            EventLog.log("FUEL", "Refuelling failed for " + request.getAircraftName());
        } finally {
            finishService(active);
        }
    }

    private synchronized void beginActiveService() {
        // Only the single FuelTruck thread may increase this protected user count.
        activeUsers++;
        if (activeUsers > maximumObservedUsers) {
            maximumObservedUsers = activeUsers;
        }
        if (activeUsers > 1) {
            exclusivityViolations++;
        }
    }

    private synchronized void recordCompletion(String aircraftName) {
        completedRequests++;
        serviceOrder.add(aircraftName);
    }

    private synchronized void finishService(boolean active) {
        if (active) {
            activeUsers--;
        }
        currentRequest = null;
        notifyAll();
    }

    private synchronized void failPendingRequests(Throwable throwable) {
        if (currentRequest != null && !currentRequest.isCompleted()) {
            currentRequest.fail(throwable);
            currentRequest = null;
        }
        while (!requestQueue.isEmpty()) {
            requestQueue.removeFirst().fail(throwable);
        }
        notifyAll();
    }

    private void rejectDuplicateRequest(String aircraftName) {
        if (currentRequest != null && aircraftName.equals(currentRequest.getAircraftName())) {
            throw new IllegalStateException(aircraftName + " already has an active fuel request");
        }
        for (FuelRequest request : requestQueue) {
            if (aircraftName.equals(request.getAircraftName())) {
                throw new IllegalStateException(aircraftName + " already has a queued fuel request");
            }
        }
    }

    public synchronized int getQueueSize() {
        return requestQueue.size();
    }

    public synchronized int getMaximumQueueSize() {
        return maximumQueueSize;
    }

    public synchronized int getActiveUsers() {
        return activeUsers;
    }

    public synchronized int getMaximumObservedUsers() {
        return maximumObservedUsers;
    }

    public synchronized int getExclusivityViolations() {
        return exclusivityViolations;
    }

    public synchronized int getCompletedRequests() {
        return completedRequests;
    }

    public synchronized String[] getQueuedAircraftSnapshot() {
        String[] snapshot = new String[requestQueue.size()];
        for (int index = 0; index < requestQueue.size(); index++) {
            snapshot[index] = requestQueue.get(index).getAircraftName();
        }
        return snapshot;
    }

    public synchronized String[] getServiceOrderSnapshot() {
        return serviceOrder.toArray(new String[serviceOrder.size()]);
    }
}

