package com.conveyal.r5.otp2.rangeraptor.transit;

import com.conveyal.r5.otp2.rangeraptor.WorkerLifeCycle;
import org.junit.Test;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoundTrackerTest {
    private static final int ANY = 500;
    private IntConsumer setupIteration;
    private Consumer<Boolean> roundComplete;

    @Test
    public void testRoundTracker() {
        // Set number of rounds to 3, this include the access arrivals in round 0
        RoundTracker subject = new RoundTracker(3, 2, lifeCycle());

        setupIteration.accept(ANY);
        assertEquals(0, subject.round());

        assertTrue(subject.hasMoreRounds());
        assertEquals(1, subject.round());

        roundComplete.accept(false);
        // Verify the round counter is still correct in the "round complete" phase.
        assertEquals(1, subject.round());

        assertTrue(subject.hasMoreRounds());
        assertEquals(2, subject.round());

        assertFalse(subject.hasMoreRounds());

        // Start over for new iteration
        setupIteration.accept(ANY);
        assertEquals(0, subject.round());
        assertTrue(subject.hasMoreRounds());
        assertEquals(1, subject.round());
    }

    @Test
    public void testRoundTrackerWhenDestinationIsReached() {
        RoundTracker subject = new RoundTracker(10, 2, lifeCycle());

        setupIteration.accept(500);
        assertEquals(0, subject.round());

        assertTrue(subject.hasMoreRounds());
        assertEquals(1, subject.round());

        // Destination reached in round 1
        roundComplete.accept(true);

        assertTrue(subject.hasMoreRounds());
        assertEquals(2, subject.round());

        assertTrue(subject.hasMoreRounds());
        assertEquals(3, subject.round());

        assertFalse(subject.hasMoreRounds());
    }

    private WorkerLifeCycle lifeCycle() {
        return new WorkerLifeCycle() {
            @Override public void onSetupIteration(IntConsumer setupIteration) {
                RoundTrackerTest.this.setupIteration = setupIteration;
            }
            @Override public void onRoundComplete(Consumer<Boolean> roundCompleteWithDestinationReached) {
                RoundTrackerTest.this.roundComplete = roundCompleteWithDestinationReached;
            }
            @Override public void onPrepareForNextRound(Runnable prepareForNextRound) {
                throw new IllegalStateException("Not expected");
            }
            @Override public void onTransitsForRoundComplete(Runnable transitsForRoundComplete) {
                throw new IllegalStateException("Not expected");
            }
            @Override public void onTransfersForRoundComplete(Runnable transfersForRoundComplete) {
                throw new IllegalStateException("Not expected");
            }
            @Override public void onIterationComplete(Runnable iterationComplete) {
                throw new IllegalStateException("Not expected");
            }
        };
    }
}