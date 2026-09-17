public final class EventLog {
    private static final long START_NANOS = System.nanoTime();

    private EventLog() {
    }

    public static synchronized void log(String category, String message) {
        // One monitor keeps each actor's output line intact without changing its identity.
        System.out.println(formatLine(category, message));
    }

    static String formatLine(String category, String message) {
        double elapsedSeconds = (System.nanoTime() - START_NANOS) / 1_000_000_000.0;
        return String.format(
                "[%07.3fs] [%s] [%s] %s",
                elapsedSeconds,
                Thread.currentThread().getName(),
                category,
                message);
    }
}

