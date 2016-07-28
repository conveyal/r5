package com.conveyal.r5.util;

import org.slf4j.Logger;

/**
 * Allows counting iterations and logging inside lambda expressions, including those within parallel streams.
 * Java does not allow you to modify a primitive in a lambda expression, but you can modify a primitive via a constant
 * reference to an object containing it. The thinking behind disallowing such modifications is that it prevents
 * non-threadsafe access, which is odd considering that you can do all sorts of other non threadsafe things and
 * people are not always using parallel streams.
 *
 * To solve all these problems, this class allows you to update a counter in a threadsafe way and log messages when
 * it passes certain values. An instance that is "effectively final" can still have its increment method called in
 * a lambda function.
 */
public class LambdaCounter {

    private Logger logger;

    private int count = 0;

    private int total;

    private int logFrequency = 10000;

    private String message;

    /**
     * Create a counter that will log the number of iterations out of a specified total.
     * It expects a message string with two {} placeholders. The first is the count and the second is the total.
     */
    public LambdaCounter(Logger logger, int total, int logFrequency, String message) {
        this.logger = logger;
        this.total = total;
        this.logFrequency = logFrequency;
        this.message = message;
    }

    /**
     * Create a counter that will log only the number of iterations with no total. It expects a message string with
     * a single {} placeholder.
     */
    public LambdaCounter(Logger logger, int logFrequency, String message) {
        this(logger, 0, logFrequency, message);
    }

    public synchronized void increment() {
        count += 1;
        if (count % logFrequency == 0) {
            log();
        }
    }

    public synchronized int getCount() {
        return count;
    }

    private void log () {
        if (total > 0) {
            logger.info(message, count, total);
        } else {
            logger.info(message, count);
        }
    }

    public void done () {
        message = "Done. " + message;
        log();
    }

}
