package com.conveyal.r5.otp2.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;


/**
 * This class is used to collect data and print a summary after some period of time.
 *
 * <pre>
 *       METHOD CALLS DURATION |              SUCCESS               |         FAILURE
 *                             |  Min   Max  Avg     Count   Total  | Average  Count   Total
 *       AvgTimer:main t1      | 1345  3715 2592 ms     50  129,6 s | 2843 ms      5   14,2 s
 *       AvgTimer:main t2      |   45   699  388 ms     55   21,4 s |    0 ms      0    0,0 s
 *       AvgTimer:main t3      |    4   692  375 ms    110   41,3 s |    0 ms      0    0,0 s
 * </pre>
 *
 * See the {@link #main(String[])} for usage and example code.
 */
public abstract class AvgTimer {
    public static boolean NOOP = true;
    private static final String RESULT_TABLE_TITLE = "METHOD CALLS DURATION";
    //    private static final Logger LOG = LoggerFactory.getLogger(AvgTimer.class);

    /**
     * Keep a list of methods in the order they are added, so that we can list all timers in the same
     * order for printing at the end of the program. This more or less will resemble the call stack.
     */
    private static List<String> methods = new ArrayList<>();
    private static Map<String, AvgTimer> allTimers = new HashMap<>();


    protected final String method;
    protected long startTime = 0;
    private long lapTime = 0;
    private long minTime = Long.MAX_VALUE;
    private long maxTime = -1;
    private long totalTimeSuccess = 0;
    private long totalTimeFailed = 0;
    private int counterSuccess = 0;
    private int counterFailed = 0;


    /**
     * @param method Use: <SimpleClassName>::<methodName>
     */
    private AvgTimer(String method) {
        this.method = method;
    }

    /**
     * @param method Use: <SimpleClassName>::<methodName>
     */
    public static AvgTimer timerMilliSec(final String method) {
        return timer(method, NOOP ? AvgTimerNoop::new : AvgTimerMilliSec::new);
    }

    /**
     * @param method Use: <SimpleClassName>::<methodName>
     */
    public static AvgTimer timerMicroSec(final String method) {
        return timer(method, NOOP ? AvgTimerNoop::new : AvgTimerMicroSec::new);
    }

    private static AvgTimer timer(final String method, final Function<String, ? extends AvgTimer> factory) {
        return allTimers.computeIfAbsent(method, methid -> {
            methods.add(methid);
            return factory.apply(methid);
        });
    }


    public static List<String> listResults() {
        final int width = Math.max(
                RESULT_TABLE_TITLE.length(),
                allTimers().mapToInt(it -> it.method.length()).max().orElse(0)
        );
        final List<String> result = new ArrayList<>();
        result.add(header1(width));
        result.add(header2(width));
        for (String method : methods) {
            AvgTimer timer = allTimers.get(method);
            if(timer.used()) {
                result.add(timer.toString(width));
            }
        }
        return result;
    }

    /**
     * If you want to "warm up" your code then start timing,
     * you may call this method after the warm up is done.
     */
    public static void resetAll() {
        allTimers().forEach(AvgTimer::reset);
    }

    private void reset() {
        startTime = 0;
        lapTime = 0;
        minTime = Long.MAX_VALUE;
        maxTime = -1;
        totalTimeSuccess = 0;
        totalTimeFailed = 0;
        counterSuccess = 0;
        counterFailed = 0;
    }

    public void start() {
        startTime = currentTime();
    }

    public void stop() {
        if (startTime == 0) {
            throw new IllegalStateException("Timer not started!");
        }
        lapTime = currentTime() - startTime;
        minTime = Math.min(minTime, lapTime);
        maxTime = Math.max(maxTime, lapTime);
        if (lapTime < 1_000_000) {
            totalTimeSuccess += lapTime;
        }
        ++counterSuccess;
        startTime = 0;
    }

    /**
     * If not started, do nothing and return.
     * Else assume the request failed and collect data.
     */
    public void failIfStarted() {
        if (startTime == 0) {
            return;
        }
        lapTime = currentTime() - startTime;
        totalTimeFailed += lapTime;
        ++counterFailed;
        startTime = 0;
    }

    public void time(Runnable body) {
        try {
            start();
            body.run();
            stop();
        } finally {
            failIfStarted();
        }
    }

    public <T> T timeAndReturn(Supplier<T> body) {
        try {
            start();
            T result = body.get();
            stop();
            return result;
        } finally {
            failIfStarted();
        }
    }

    public long lapTime() {
        return lapTime;
    }

    public long avgTime() {
        return average(totalTimeSuccess, counterSuccess);
    }

    public String totalTimeInSeconds() {
        return toSec(totalTimeSuccess + totalTimeFailed);
    }


    /* private methods */

    private boolean used() {
        return counterSuccess != 0 || counterFailed != 0;
    }

    private String toString(int width) {
        return formatLine(
                method,
                width,
                formatResultAvg(totalTimeSuccess, counterSuccess),
                formatResult(totalTimeFailed, counterFailed)
        );
    }

    private static Stream<AvgTimer> allTimers() {
        return allTimers.values().stream();
    }

    private static String header1(int width) {
        return formatLine(
                RESULT_TABLE_TITLE,
                width,
                "             SUCCESS",
                "        FAILURE"
        );
    }

    private static String header2(int width) {
        return formatLine(
                "",
                width,
                columnHeaderAvg(), columnFailureHeader()
        );
    }

    private String formatResultAvg(long time, int count) {
        return String.format(
                "%4s %5s %4s %s %6s %6s s",
                str(minTime()),
                str(maxTime),
                str(average(time, count)),
                unit(),
                str(count),
                toSec(time)
        );
    }

    private String str(long value) {
        return value < 10_000 ? Long.toString(value) : Long.toString(value/1000) + "'";
    }


    private static String columnHeaderAvg() {
        return " Min   Max  Avg     Count   Total";
    }

    private String formatResult(long time, int count) {
        return String.format("%4d %s %6d %6s s", average(time, count), unit(), count, toSec(time));
    }

    private static String columnFailureHeader() {
        return "Average  Count   Total";
    }

    private static String formatLine(String label, int labelWidth, String column1, String column2) {
        return String.format("%-" + labelWidth + "s | %-35s| %-24s", label, column1, column2);
    }

    private static long average(long total, int count) {
        return count == 0 ? 0 : total / count;
    }

    private long minTime() {
        return minTime == Long.MAX_VALUE ? -1 : minTime;
    }
    abstract long currentTime();

    abstract String unit();

    abstract String toSec(long time);


    public static final class AvgTimerMilliSec extends AvgTimer {
        private AvgTimerMilliSec(String method) {
            super(method);
        }

        @Override
        long currentTime() {
            return System.currentTimeMillis();
        }

        @Override
        String unit() {
            return "ms";
        }

        @Override
        String toSec(long time) {
            return String.format("%.2f", time / 1000d);
        }
    }

    public static final class AvgTimerMicroSec extends AvgTimer {
        private AvgTimerMicroSec(String method) {
            super(method);
        }

        @Override
        long currentTime() {
            return System.nanoTime() / 1000L;
        }

        @Override
        String unit() {
            return "µs";
        }

        @Override
        String toSec(long time) {
            return String.format("%.2f", time / 1_000_000d);
        }
    }

    private static final class AvgTimerTest extends AvgTimer {
        private static Random rnd = new Random(57);
        private static long previousTime = 1_000_000;

        private AvgTimerTest(String method) {
            super(method);
        }

        @Override
        long currentTime() {
            previousTime += rnd.nextInt(700) + 3L;
            return previousTime;
        }

        @Override
        String unit() {
            return "ms";
        }

        @Override
        String toSec(long time) {
            return String.format("%6.2f", time / 1000d);
        }
    }

    private static final class AvgTimerNoop extends AvgTimer {
        private AvgTimerNoop(String method) {
            super(method);
        }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void failIfStarted() { }
        @Override  public void time(Runnable body) { body.run(); }
        @Override public <T> T timeAndReturn(Supplier<T> body) { return body.get();  }
        @Override public long lapTime() {
            return 0;
        }
        @Override long currentTime() { return 0; }
        @Override String unit() {
            return "ms";
        }
        @Override String toSec(long time) {
            return "0";
        }
    }

    public static void main(String[] args) {
        // Change the #time() function to return a random time

        AvgTimer t1 = timer("AvgTimer:main t1", AvgTimerTest::new);
        AvgTimer t2 = timer("AvgTimer:main t2", AvgTimerTest::new);
        AvgTimer t3 = timer("AvgTimer:main t3", AvgTimerTest::new);

        for (int i = 0; i < 55; i++) {
            final int counter = i;
            try {
                t1.time(() -> {
                    t2.start();
                    // Do something here
                    t2.stop();

                    int sum = t3.timeAndReturn(
                            // Calculate something here
                            () -> counter * 7
                    );

                    sum += t3.timeAndReturn(() -> counter * 13);

                    if (counter % 10 == 5) {
                        throw new IllegalStateException("Ex");
                    }
                });
            } catch (Exception e) {
                System.out.print(e.getMessage());
            }
        }
        System.out.println("");
        AvgTimer.listResults().forEach(
                it -> System.out.println("  " + it)
        );
    }
}
