package com.conveyal.r5.otp2.api.request;

import com.conveyal.r5.otp2.api.transit.TransitDataProvider;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;


/**
 * All input parameters to RangeRaptor that is spesific to a routing request.
 * See {@link TransitDataProvider} for transit data.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RangeRaptorRequest<T extends TripScheduleInfo> {
    private final SearchParams searchParams;
    private final RangeRaptorProfile profile;
    private final boolean searchForward;
    private final Set<Optimization> optimizations;
    private final McCostParams mcCostParams;
    private final DebugRequest<T> debug;


    static <T extends TripScheduleInfo>  RangeRaptorRequest<T> defaults() {
        return new RangeRaptorRequest<>();
    }

    private RangeRaptorRequest() {
        searchParams = SearchParams.defaults();
        profile = RangeRaptorProfile.MULTI_CRITERIA;
        searchForward = true;
        optimizations = Collections.emptySet();
        mcCostParams = McCostParams.DEFAULTS;
        debug = DebugRequest.defaults();
    }

    RangeRaptorRequest(RequestBuilder<T> builder) {
        this.searchParams = builder.searchParams().build();
        this.profile = builder.profile();
        this.searchForward = builder.searchForward();
        // TODO JAVA_9 - Cleanup next 3 lines: Set.of(...) and List.of(...)
        this.optimizations = Collections.unmodifiableSet(EnumSet.copyOf(builder.optimizations()));
        this.mcCostParams = new McCostParams(builder.mcCostFactors());
        this.debug = builder.debug().build();
        verify();
    }

    public RequestBuilder<T> mutate() {
        return new RequestBuilder<T>(this);
    }


    /**
     * Requered travel search parameters.
     */
    public SearchParams searchParams() {
        return searchParams;
    }

    /**
     * The profile/algorithm to use for this request.
     * <p/>
     * The default value is {@link RangeRaptorProfile#MULTI_CRITERIA}
     */
    public RangeRaptorProfile profile() {
        return profile;
    }

    /**
     * Set search direction to REVERSE to performed a search  from the destination
     * to the origin. Thie traverse the transit graph backwards in time.
     * This parameter is used internally to produce heuristics, and is normally not
     * something you would like to do unless you are testing or analyzing.
     * <p/>
     *
     * @return  NOT {@link #searchForward()}
     */
    public boolean searchInReverse() {
        return !searchForward;
    }

    /**
     * When TRUE the search is performed from the origin to the destination in a
     * normal way.
     * <p/>
     * Optional. Default value is 'true'.
     *
     * @return true is search is forward.
     */
    public boolean searchForward() {
        return searchForward;
    }

    /**
     * Return list of enabled optimizations.
     */
    public Collection<Optimization> optimizations() {
        return optimizations;
    }

    /**
     * TRUE if the given optimization is enabled.
     */
    public boolean optimizationEnabled(Optimization optimization) {
        return optimization.isOneOf(optimizations);
    }

    /**
     * The multi-criteria cost criteria factors.
     */
    public McCostParams multiCriteriaCostFactors() {
        return mcCostParams;
    }

    /**
     * Specify what to debug in the debug request.
     * <p/>
     * This feature is optional, by default debugging is turned off.
     */
    public DebugRequest<T> debug() {
        return debug;
    }

    @Override
    public String toString() {
        return "RangeRaptorRequest{" +
                "searchParams=" + searchParams +
                ", profile=" + profile +
                ", searchForward=" + searchForward +
                ", optimizations=" + optimizations +
                ", mcCostParams=" + mcCostParams +
                ", debug=" + debug +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RangeRaptorRequest<?> that = (RangeRaptorRequest<?>) o;
        return profile == that.profile &&
                Objects.equals(searchParams, that.searchParams) &&
                Objects.equals(mcCostParams, that.mcCostParams) &&
                Objects.equals(debug, that.debug);
    }

    @Override
    public int hashCode() {
        return Objects.hash(searchParams, profile, mcCostParams, debug);
    }


    /* private methods */

    private void verify() {
        searchParams.verify();
    }
}
