package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.rangeraptor.LifeCyclePublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;


/**
 * The responsibility of this class is to provide an interface for Range Raptor
 * lifecycle event listeners to register and later receive such events.
 * <p/>
 * This class do the job on behalf of the RangeRaptor Worker, which delegate
 * this to this class.
 * <p/>
 * By providing the ability to subscribe to such events each class can decide
 * independently of its relations to subscribe. For example can the DestinationArrivals
 * class subscribe to any events, without relying on its parent (WorkerState)
 * to delegate these events down the relationship three. This decouples the
 * code.
 */
public final class RangeRaptorLifeCyclePublisher implements LifeCyclePublisher {
    private final List<IntConsumer> setupIterationListeners = new ArrayList<>();
    private final List<Runnable> prepareForNextRoundListeners = new ArrayList<>();
    private final List<Consumer<Boolean>> roundCompleteListeners = new ArrayList<>();
    private final List<Runnable> iterationCompleteListeners = new ArrayList<>();


    /* Implement LifeCyclePublisher */

    @Override
    public void onSetupIteration(IntConsumer setupIterationWithDepartureTime) {
        if(setupIterationWithDepartureTime != null) {
            this.setupIterationListeners.add(setupIterationWithDepartureTime);
        }
    }

    @Override
    public void onPrepareForNextRound(Runnable prepareForNextRound) {
        if(prepareForNextRound != null) {
            this.prepareForNextRoundListeners.add(prepareForNextRound);
        }
    }

    @Override
    public void onRoundComplete(Consumer<Boolean> roundCompleteWithDestinationReached) {
        if(roundCompleteWithDestinationReached != null) {
            this.roundCompleteListeners.add(roundCompleteWithDestinationReached);
        }
    }

    @Override
    public void onIterationComplete(Runnable iterationComplete) {
        if(iterationComplete != null) {
            this.iterationCompleteListeners.add(iterationComplete);
        }
    }


    /* Lifecycle methods invoked by the Range Raptor Worker */

    public final void setupIteration(int iterationDepartureTime) {
        for (IntConsumer it : setupIterationListeners) {
            it.accept(iterationDepartureTime);
        }
    }

    public final void prepareForNextRound() {
        for (Runnable it : prepareForNextRoundListeners) {
            it.run();
        }
    }

    public final void roundComplete(boolean destinationReached) {
        for (Consumer<Boolean> it : roundCompleteListeners) {
            it.accept(destinationReached);
        }
    }

    public final void iterationComplete() {
        for (Runnable it : iterationCompleteListeners) {
            it.run();
        }
    }
}
