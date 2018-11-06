package com.conveyal.r5.profile.entur.api;


import java.util.function.Supplier;


/**
 * Tuning parameters - changing these parameters change the performance (speed and/or memory consumption).
 */
public class TuningParameters {

    /**
     * Default values.
     *
     * @see #TuningParameters() for parameter default values.
     */
    private static final TuningParameters DEFAULTS = new TuningParameters();

    /**
     * This parameter is used to allocate enough space for the search.
     * Set it to the maximum number of transfers for any given itinerary expected to
     * be found within the entire transit network.
     * <p/>
     * Default value is 12.
     */
    public final int maxNumberOfTransfers;


    /**
     * Intentionally private default constructor, which only serve as a place
     * to define default values.
     */
    private TuningParameters() {
        this.maxNumberOfTransfers = 12;
    }

    private TuningParameters(Builder builder) {
        this.maxNumberOfTransfers = builder.maxNumberOfTransfers;
    }


    /**
     * Maximum number of RangeRaptor rounds to perform.
     */
    public int nRounds() {
        return maxNumberOfTransfers + 1;
    }


    @Override
    public String toString() {
        return "TuningParameters{" +
                "maxNumberOfTransfers=" + maxNumberOfTransfers +
                '}';
    }

    public static class Builder {
        private int maxNumberOfTransfers = DEFAULTS.maxNumberOfTransfers;

        public Builder() {
        }

        public Builder maxNumberOfTransfers(int maxNumberOfTransfers) {
            this.maxNumberOfTransfers = range("maxNumberOfTransfers", maxNumberOfTransfers, 0, 50);
            return this;
        }

        public TuningParameters build() {
            return new TuningParameters(this);
        }

        private int range(String name, int value, int min, int max) {
            assertProperty(
                    min <= value && value < max,
                    () -> String.format("'%s' is not in range [%d, %d). Value: %d", name, min, max, value)
            );
            return value;
        }

        private void assertProperty(boolean predicate, Supplier<String> errorMessageProvider) {
            if (!predicate) {
                throw new IllegalArgumentException(RangeRaptorRequest.class.getSimpleName() + " error: " + errorMessageProvider.get());
            }
        }
    }
}
