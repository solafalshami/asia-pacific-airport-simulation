# Asia Pacific Airport Concurrent Simulation

Java-based concurrent airport simulation demonstrating multithreading, synchronization, shared-resource management, emergency handling, and concurrency testing.

> **Project attribution:** This was a **Group Academic Project**. Based on her stated role, **Solaf Amir Alshami served as the Primary/Lead Developer**. It is not presented as an individual assignment.

## Overview

The simulation models six aircraft competing for limited airport resources while independent actors coordinate landing, gate turnaround, refuelling, passenger operations, and take-off.

The standard run includes:

- Exactly six aircraft
- One shared runway
- Three gates
- A maximum of three aircraft on the ground
- An active Air Traffic Control (`ATC`) thread
- One exclusive `FuelTruck` service thread
- Concurrent passenger and cleaning/supply operations
- A reproducible low-fuel emergency landing scenario
- Final statistics and resource-safety checks

## Concurrency design

The implementation uses Java's intrinsic concurrency mechanisms:

- `Thread` and `Runnable` actors
- `synchronized` methods and critical sections
- Guarded `wait()` loops and `notifyAll()` notifications
- Explicit `start()` and `join()` lifecycle management
- Protected landing, emergency, take-off, and fuel queues
- Mutual exclusion for the runway, gates, airport capacity, and fuel truck
- Cancellation and cleanup of interrupted ATC requests

The emergency queue receives priority only when resources can be granted safely. Normal traffic resumes after the emergency aircraft lands.

## Project structure

```text
.
├── src/                 # Simulation and concurrency implementation
├── test/                # Standalone regression and stress test programs
├── docs/                # Test evidence, traceability, and sample output
├── .gitignore
└── README.md
```

Key classes include:

- `Main`: creates the six-aircraft scenario and joins all actors.
- `Aircraft`: manages each aircraft lifecycle.
- `ATC` and `ATCDesk`: process protected landing and take-off requests.
- `Runway`, `Gate`, `GatePool`, and `AirportCapacity`: enforce resource invariants.
- `FuelTruck` and `FuelRequest`: implement exclusive FIFO refuelling.
- `PassengerGroup`, `CleaningSupplies`, and `TurnaroundState`: coordinate concurrent gate services.
- `EmergencyScenarioControl`: coordinates the reproducible congestion scenario without sleep-based correctness.
- `Statistics` and `FinalReport`: record metrics and verify clean shutdown.

## Requirements

- JDK 8 or newer
- No external libraries or build framework

The source remains Java 8 compatible.

## Compile

From the repository root:

### Windows PowerShell

```powershell
New-Item -ItemType Directory -Force build | Out-Null
javac -source 1.8 -target 1.8 -d build (Get-ChildItem src\*.java).FullName (Get-ChildItem test\*.java).FullName
```

### macOS or Linux

```bash
mkdir -p build
javac -source 8 -target 8 -d build src/*.java test/*.java
```

## Run the simulation

Use a fresh random seed:

```bash
java -cp build Main
```

Use a reproducible seed:

```bash
java -cp build Main 407
```

## Run the tests

The test programs use plain Java assertions and throw `AssertionError` on failure; no test framework is required.

Run the complete checkpoint suite after compilation:

### Windows PowerShell

```powershell
2..8 | ForEach-Object { java -cp build "Checkpoint$($_)Test" }
java -cp build Checkpoint9StressTest
```

### macOS or Linux

```bash
for checkpoint in 2 3 4 5 6 7 8; do
  java -cp build "Checkpoint${checkpoint}Test"
done
java -cp build Checkpoint9StressTest
```

The tests cover resource exclusivity, interrupted-request cleanup, aircraft lifecycles, passenger and cleaning coordination, fuel-truck serialization, emergency priority, final-state invariants, and multi-seed stress runs.

## Verified safety properties

The included test evidence reports:

- Runway occupancy never exceeds one aircraft.
- No more than three aircraft are on the ground.
- Each gate holds at most one aircraft.
- The single fuel truck services at most one request at a time.
- Emergency landing receives the first safe grant ahead of normal waiters.
- All six aircraft complete their lifecycles.
- Queues drain and shared resources return to an empty state.
- ATC, fuel-truck, aircraft, passenger, and cleaning threads terminate cleanly.

See [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) and [`docs/REQUIREMENTS_TRACEABILITY.md`](docs/REQUIREMENTS_TRACEABILITY.md) for detailed evidence.

## Publication notes

- Compiled `.class` files and IDE metadata are excluded.
- No credentials, secrets, personal records, or local machine paths are required by the project.
- Sample logs contain only simulated aircraft and system events.


