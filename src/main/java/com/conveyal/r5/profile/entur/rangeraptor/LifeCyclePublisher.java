package com.conveyal.r5.profile.entur.rangeraptor;


import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The publisher register listener callbacks, which later will be notified
 * when the Range Raptor events occur:
 * <ol>
 *     <li><b>setupIteration</b> with iteration departureTime</li>
 *     <li><b>prepareForNextRound</b></li>
 *     <li><b>roundComplete</b> with flag to indicate if the destination is reached</li>
 *     <li><b>iterationComplete</b></li>
 * </ol>
 */
public interface LifeCyclePublisher {

    /**
     * Subscribe to 'setup iteration' events by register a int consumer. Every time
     * an iteration start the listener(the input parameter) is notified with
     * the {@code iterationDepartureTime} passed in as an argument.
     *
     * @param setupIterationWithDepartureTime if {@code null} nothing is added to the publisher.
     */
    void onSetupIteration(IntConsumer setupIterationWithDepartureTime);

    /**
     * Subscribe to 'prepare for next round' events by register listener.
     * Every time a new round start the listener(the input parameter) is
     * notified/invoked.
     *
     * @param prepareForNextRound if {@code null} nothing is added to the publisher.
     */
    void onPrepareForNextRound(Runnable prepareForNextRound);

    /**
     * Subscribe to 'round complete' events by register a boolean consumer. Every time
     * a round finish the listener(the input parameter) is notified with
     * a flag indicating if the destination is reached in the current round.
     *
     * @param roundCompleteWithDestinationReached if {@code null} nothing is added to the publisher.
     */
    void onRoundComplete(Consumer<Boolean> roundCompleteWithDestinationReached);

    /**
     * Subscribe to 'iteration complete' events by register listener.
     * Every time an iteration finish/completes the listener(the input parameter) is
     * notified/invoked.
     *
     * @param iterationComplete if {@code null} nothing is added to the publisher.
     */
    void onIterationComplete(Runnable iterationComplete);
}
