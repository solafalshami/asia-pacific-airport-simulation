# Final Requirements Traceability

Status is `VERIFIED` only when implementation plus focused or integrated runtime evidence exists. No official System requirement remains `ISSUE FOUND`.

## Basic requirements

| ID | Exact requirement | Class and method | Active thread(s) | Shared monitor / concept | Runtime and test evidence | Status |
|---|---|---|---|---|---|---|
| BR-01 | One runway serves both landing and take-off | `Runway.tryAcquire/release`; `ATCDesk.tryCreateDecision/tryAllocateLanding`; `Aircraft.coastToGate/takeOff` | ATC reserves; Aircraft releases | `Runway` monitor; mutual exclusion | Final output: runway empty, max occupancy 1, zero violations; `Checkpoint2Test`, `Checkpoint4Test`, `Checkpoint8Test`, `Checkpoint9StressTest` | VERIFIED |
| BR-02 | At most three aircraft are on airport grounds, including the runway | `AirportCapacity.tryEnter/leave`; `ATCDesk.tryAllocateLanding` | ATC reserves; Aircraft leaves | Atomic capacity check/update under `AirportCapacity` monitor | Stress maximum 3, final ground count 0, zero violations; checkpoints 2, 4, 7, 8, 9 | VERIFIED |
| BR-03 | Exactly three gates | `Main.runSimulation`; `GatePool.GatePool` | Main creates; ATC/Aircraft use | Three protected `Gate` objects | Output prints Gate-1/2/3; all empty finally; checkpoints 4, 7, 8, 9 | VERIFIED |
| BR-04 | No aircraft may land and wait on the ground for a gate | `ATCDesk.tryAllocateLanding` | ATC | Atomic gate + capacity + runway reservation with rollback | Blocked landing has no partial resource; emergency waits until safe; checkpoints 3 and 7 | VERIFIED |
| BR-05 | Complete lifecycle: land, coast, gate, dock, disembark, supplies/fuel, embark, undock, runway, take-off | `Aircraft.run` and its lifecycle methods | Aircraft plus service actors | Ordered actor lifecycle and monitored turnaround | `SAMPLE_OUTPUT.txt`; checkpoint 4 stages 1/2/6, checkpoints 5, 6, 8, 9 | VERIFIED |
| BR-06 | Each lifecycle step takes time | `Aircraft.pauseForSimulation`; `PassengerGroup.run`; `CleaningSupplies.run`; `FuelTruck.service` | Respective actor | `Thread.sleep()` models duration only; monitors enforce order | Timed, interleaved runtime output; checkpoint 5 proves dependencies are not sleep-based | VERIFIED |
| BR-07 | Passenger work can occur concurrently at three gates | `Aircraft.performTurnaround`; `PassengerGroup.run` | One PassengerGroup per occupied aircraft | Independent threads and per-aircraft `TurnaroundState` | Cross-gate overlap/interleaving in checkpoint 5; six-aircraft runs use all gates | VERIFIED |
| BR-08 | Cleaning and supply refill occur concurrently where safe | `CleaningSupplies.run`; `TurnaroundState` methods | CleaningSupplies threads | Condition synchronization after disembarking | Checkpoint 5 interval evidence and visible cross-gate interleaving | VERIFIED |
| BR-09 | Refuelling can overlap independent work | `FuelTruck.service`; `TurnaroundState.awaitReadyForBoarding` | FuelTruck and gate service threads | One truck consumer; cleaning/fuel independent after disembarking | Checkpoint 6 proves cleaning/fuel overlap while users never exceed 1 | VERIFIED |
| BR-10 | Passenger disembarkation precedes embarking | `PassengerGroup.run`; `TurnaroundState.markDisembarkingComplete/awaitReadyForBoarding` | PassengerGroup | Guarded dependency conditions | Output order and timestamp assertions in checkpoints 5, 6, 8, 9 | VERIFIED |
| BR-11 | Exactly six aircraft try to land | `Main.AIRCRAFT_COUNT`; `Main.runSimulation/startAircraft` | Six Aircraft threads | Explicit `Thread` creation/start/join | `Aircraft Served: 6 / 6`; final Main and all stress runs include the emergency scenario | VERIFIED |
| BR-12 | New aircraft arrive at random 0/1/2-second intervals | `Main.runSimulation/startAircraft` using `Random.nextInt(3)` | Main schedules starts | Taught `Random` plus generated delay; monitor state controls scenario correctness | Output logs six delays; eight stress seeds cover 0, 1, and 2; checkpoints 8 and 9 | VERIFIED |
| BR-13 | Each aircraft carries at most 50 passengers | `Main.runSimulation`; `Aircraft` and `PassengerGroup` constructors; `Statistics.recordPassengersBoarded` | Main generates; PassengerGroup records | Validation plus synchronized total | Every generated value is 1-50 and totals match; checkpoints 8 and 9 | VERIFIED |
| BR-14 | No passenger-terminal capacity restriction | `PassengerGroup.run` | PassengerGroup | No terminal-capacity monitor or artificial queue | Every group progresses using only turnaround conditions; checkpoints 5, 8, 9 | VERIFIED |
| BR-15 | Dynamic output from pilots/aircraft, passengers, and ATC | `EventLog.log`; actor `run` methods | Aircraft, ATC, PassengerGroup, CleaningSupplies, FuelTruck | Synchronized complete-line output | Actual interleaved actor output in both evidence files; checkpoints 3, 5-9 | VERIFIED |
| BR-16 | Output identifies the actual process/thread; one actor must not act for another | `EventLog.formatLine`; all actor log call sites | Actual caller thread | `Thread.currentThread().getName()` | Aircraft-5 prints low fuel/request, ATC prints emergency selection/grant, FuelTruck/passenger/cleaning print their work, and main prints only factual scenario state; checkpoints 3, 5, 6, 8, 9 | VERIFIED |
| BR-17 | Simulation completes within 60 seconds | `Main.runSimulation`; bounded action delays and clean joins | All actors | Liveness and explicit shutdown | Final stress min/avg/max 5.408/6.085/6.957 seconds; all under 60 | VERIFIED |
| BR-18 | ATC manager checks all gates are empty after all planes leave | `ATC.run`; `FinalReport.printReport`; `GatePool.getOccupancySnapshot` | ATC | Synchronized final snapshot | ATC prints Gate-1/2/3 `EMPTY` and final PASS; checkpoints 8 and 9 | VERIFIED |
| BR-19 | Print minimum plane waiting time | `Statistics.recordLandingWaitingTime/getMinimumWaitingMillis`; `FinalReport.printReport` | ATC records and reports | Synchronized exactly-once aggregation | Actual minimum printed; arithmetic and six-record assertions in checkpoint 8 | VERIFIED |
| BR-20 | Print average plane waiting time | `Statistics.getAverageWaitingMillis`; `FinalReport.printReport` | ATC | Protected total/count | Actual average printed and verified between min/max; checkpoints 8 and 9 | VERIFIED |
| BR-21 | Print maximum plane waiting time | `Statistics.getMaximumWaitingMillis`; `FinalReport.printReport` | ATC | Protected maximum | Actual maximum printed; arithmetic test in checkpoint 8 | VERIFIED |
| BR-22 | Print planes served | `Statistics.recordAircraftServed/getAircraftServed`; `FinalReport.printReport` | Aircraft records; ATC reports | Synchronized exactly-once set | Final output `6 / 6`; checkpoints 8 and 9 | VERIFIED |
| BR-23 | Print passengers boarded | `Statistics.recordPassengersBoarded/getTotalPassengersBoarded`; `FinalReport.printReport` | PassengerGroup records; ATC reports | Synchronized exactly-once set and total | Final total equals generated passenger sum; checkpoints 8 and 9 | VERIFIED |
| BR-24 | Implement in Java | Entire `src` tree | All explicit Java actors | Java 8 threads and intrinsic monitors | Clean Java 8 compilation and all tests pass | VERIFIED |

Waiting time is defined, because the brief does not give exact boundaries, as elapsed time from `LandingRequest` creation/submission until the ATC thread grants safe landing permission.

## Additional requirements

| ID | Exact requirement | Class and method | Active thread(s) | Shared monitor / concept | Runtime and test evidence | Status |
|---|---|---|---|---|---|---|
| AR-01 | Exactly one refuelling truck responds to requests and refuels one aircraft at a time | `FuelTruck.run/awaitNextRequest/service`; `FuelRequest`; `Aircraft.performTurnaround` | One FuelTruck; multiple Aircraft submit | Protected FIFO `LinkedList`; single consumer; completion monitor | Five concurrent queued requests complete FIFO; max users 1, six full-run completions, zero violations; checkpoints 6, 8, 9 | VERIFIED |
| AR-02 | Two gates occupied and two normal planes waiting when a third low-fuel plane requests emergency landing | `Main.runSimulation/verifyAndLogCongestedState`; `EmergencyScenarioControl`; emergency `Aircraft.requestLanding` | Six final Aircraft actors, main observer, ATC | Real gate/runway/capacity state and protected normal/emergency queues | Every `SAMPLE_OUTPUT`/stress run records occupied gates 2, Aircraft-3/4 queued, Aircraft-5 emergency queued; focused checkpoint 7 also passes | VERIFIED |
| AR-03 | Emergency receives the next safe landing opportunity without bypassing safety | `ATCDesk.tryCreateDecision/tryAllocateLanding`; `ATC.processLanding` | ATC | Emergency-first application policy, not thread priority | Aircraft-5 waits while Aircraft-1 holds runway, then ATC grants it before Aircraft-3/4; max runway 1, ground 3, fuel 1, zero violations in checkpoints 8/9 | VERIFIED |
| AR-04 | Normal traffic progresses after the emergency | `ATCDesk.tryCreateDecision`; `Main.runSimulation` | ATC and normal Aircraft | FIFO normal queue and liveness | Aircraft-3 and Aircraft-4 subsequently land and depart; all six complete across 16 stress runs | VERIFIED |
| AR-05 | State assumptions and implementation choices | `OFFICIAL_REQUIREMENTS.md`, `README.md`, `CONCURRENCY_CONCEPTS.md` | Documentation | Explicit waiting-time, actor, resource, and facility assumptions | Documents identify assumptions and evidence; final audit completed | VERIFIED |

## System marking criteria

| ID | Criterion | Strongest evidence | Status |
|---|---|---|---|
| MC-01 | Appropriate coding techniques and comments | Small actor/resource classes; named constants; focused comments explain monitor invariants, critical sections, guarded waits/notification, atomic allocation/rollback, emergency safety, FuelTruck exclusivity, and joins | VERIFIED |
| MC-02 | Appropriate Java concurrency facilities | Only taught explicit `Thread`/`Runnable`, intrinsic monitors, `wait/notifyAll`, `join`, and protected ordinary collections; forbidden scan has zero hits | VERIFIED |
| MC-03 | Basic requirements run correctly | Final six-aircraft Main output, checkpoints 2-9, 16 v2 stress runs, interruption cleanup tests, and all final safety checks pass | VERIFIED |
| MC-04 | Additional requirements met | One active FuelTruck plus exact real-state emergency/congestion proof inside ordinary six-aircraft `Main` and the focused test | VERIFIED |

## Independent-audit robustness correction

Interrupted Aircraft waiters now call `ATCDesk.cancelLandingRequest` or `cancelTakeoffRequest`. Queued requests are removed; selected-but-not-granted requests are marked cancelled and ATC releases their reserved gate/capacity/runway through `releaseCancelledLanding` or `releaseCancelledTakeoff`. `Checkpoint3Test` verifies queued interruption and selected-request resource cleanup.
| MC-05 | Concepts explained with relevant code | `CONCURRENCY_CONCEPTS.md` maps each taught concept to exact final methods and demonstrable evidence | VERIFIED |

