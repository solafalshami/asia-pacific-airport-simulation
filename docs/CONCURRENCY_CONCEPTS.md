# Concurrency Concepts in the Final System

This map uses only concepts and Java facilities found in the supplied lectures/examples and permitted by the official brief.

| Concept | Exact final example | Why it is needed |
|---|---|---|
| `Thread` and `Runnable` | `Main.runSimulation` creates named threads for `ATC`, `FuelTruck`, and each `Aircraft`; `Aircraft.performTurnaround` creates `PassengerGroup` and `CleaningSupplies` threads | Each real-world actor executes independently and produces its own output. |
| `start()` | `Main.runSimulation`; `Aircraft.performTurnaround` | Starts true concurrent activity instead of calling `run()` sequentially. |
| `join()` and lifecycle | `Main.runSimulation`; `Aircraft.performTurnaround` | Main waits for all aircraft before shutting services down; Aircraft waits for its service actors before departure. |
| Concurrency and interleaving | `PassengerGroup.run`, `CleaningSupplies.run`, and `FuelTruck.run` | Independent work at different occupied gates can overlap; output visibly interleaves actual actor threads. |
| Shared resource | `Runway`, `GatePool`, `AirportCapacity`, `ATCDesk`, `FuelTruck`, `Statistics` | Multiple threads read or change the same airport state, so uncontrolled access would create races. |
| Atomicity | `ATCDesk.tryAllocateLanding` while holding the ATCDesk monitor | Gate, capacity, and runway are treated as one landing decision; partial reservations are rolled back before another ATC decision. |
| Critical section | `Runway.tryAcquire/release`, `Gate.reserve/release`, `Statistics.recordAircraftServed` | Check-and-update operations execute under one intrinsic monitor so two threads cannot both observe stale availability. |
| Race-condition prevention | All mutable fields in `Runway`, `GatePool`, `AirportCapacity`, `ATCDesk`, `FuelTruck`, request objects, `TurnaroundState`, and `Statistics` are accessed through synchronized methods/blocks | Synchronization provides both mutual exclusion and Java memory visibility. |
| Mutual exclusion | `Runway.tryAcquire`; `FuelTruck` single-consumer `service`; `Gate.reserve` | At most one aircraft uses the runway, one aircraft is refuelled, and one aircraft occupies each gate. |
| Monitor | `ATCDesk`, `LandingRequest`, `TakeoffRequest`, `FuelRequest`, `TurnaroundState`, and `EmergencyScenarioControl` | Each object combines protected state with synchronized operations and its own wait set. |
| Condition synchronization | `LandingRequest.awaitPermission`, `FuelTruck.awaitNextRequest`, `TurnaroundState.awaitReadyForBoarding` | A thread sleeps until the required state is true instead of polling or guessing with delays. |
| `wait()` / `notifyAll()` | `ATCDesk.awaitNextDecision/signalResourceChange`, request `await/grant` methods, `FuelTruck.awaitNextRequest/submitRequest`, `TurnaroundState` await/mark methods | Every wait uses a `while` guard; state-changing methods notify all potentially different waiter conditions. |
| Simulated time with `sleep()` | `Aircraft.pauseForSimulation`, `PassengerGroup.run`, `CleaningSupplies.run`, `FuelTruck.service`, arrival scheduling in `Main` | Makes required actions visible and models 0/1/2-second arrivals. Correctness never depends on a sleep finishing before another thread. |
| Safety | `FinalReport.printReport`; violation counters in `Runway`, `GatePool`, `AirportCapacity`, `FuelTruck` | Runtime evidence verifies runway <= 1, ground <= 3, one aircraft per gate, truck users <= 1, and clean final resources. |
| Liveness | `ATCDesk.awaitNextDecision`, `FuelTruck.awaitNextRequest`, explicit shutdown methods, and `Main` joins | Requests eventually progress; shutdown changes guarded conditions and notifies waiters so no domain thread remains blocked. |
| Fairness | FIFO `LinkedList` queues in `ATCDesk` and `FuelTruck`; emergency queue examined before normal landings | Order is preserved within each queue. Emergency priority is bounded by actual demand; normal FIFO traffic resumes afterward. |
| Starvation prevention | `ATCDesk.tryCreateDecision` allows take-off when a blocked landing cannot obtain resources, and returns to normal FIFO after emergency demand clears | Releasing a gate/capacity slot prevents a full airport from blocking forever; emergency traffic does not permanently exclude normal requests. |
| Deadlock avoidance | `FuelTruck.service` does not hold the queue monitor during refuelling; Aircraft releases one resource monitor at a time then signals ATC; no cyclic nested lock path | Monitors remain short, sleeps occur outside shared monitors, and lock direction has no cycle. |
| Actor ownership | `EventLog.formatLine` uses `Thread.currentThread().getName()`; each actor invokes logs from its own `run` path | The formatter serializes lines but never fabricates identity. ATC grants, FuelTruck service, aircraft actions, passengers, and cleaning all appear under their actual threads. |
| Interruption cleanup | `Aircraft.recoverOrCancelLandingRequest/recoverOrCancelTakeoffRequest`; `ATCDesk.cancel*` and `releaseCancelled*` | An interrupted waiter removes a queued request or marks an already selected request cancelled; ATC then releases any reservation before selecting more work. |

## Clearest presentation examples

1. **Runway mutual exclusion:** show `Runway.tryAcquire` and `release`, then the final `maximumObservedOccupancy = 1` and zero-violation output.
2. **Condition synchronization:** show `LandingRequest.awaitPermission` with `while (!granted) wait()` and `grant` setting state before `notifyAll()`.
3. **Concurrent turnaround:** show `Aircraft.performTurnaround` starting PassengerGroup and CleaningSupplies while FuelTruck independently consumes the request.
4. **Dependency safety:** show `TurnaroundState.awaitReadyForBoarding`; boarding cannot begin until cleaning/supplies and required fuel are complete.
5. **Atomic safe landing:** show `ATCDesk.tryAllocateLanding`; gate, capacity, and runway all succeed or partial state is rolled back.
6. **Emergency fairness:** show `ATCDesk.tryCreateDecision`; emergency is selected for the next safe opportunity, then normal FIFO traffic resumes.

## Wait-site audit

Every production `wait()` is executed while owning the same object's monitor and inside a condition-rechecking `while` loop. Its paired state-changing operation calls `notifyAll()`. ATC and FuelTruck shutdown flags are set under their monitors and followed by `notifyAll()`. Turnaround failures are also stored and broadcast so dependent actors do not wait forever.

## Facilities deliberately not used

No executor, fork/join pool, future framework, parallel stream, scheduled timer, reactive/event-loop framework, `PriorityBlockingQueue`, thread priority, lock framework, semaphore, atomic-variable package, busy waiting, or daemon-based shutdown is used. The system stays within the taught explicit thread-and-monitor approach.

