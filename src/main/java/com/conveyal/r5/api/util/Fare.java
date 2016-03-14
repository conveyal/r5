package com.conveyal.r5.api.util;

import com.conveyal.r5.transit.fare.RideType;

import java.util.Objects;

/**
 * Created by mabu on 30.10.2015.
 */
public class Fare {

    //TODO: change double to float since it has enough accuracy

    public RideType type;
    public double low;
    public double peak;
    public double senior;
    public boolean transferReduction;

    public Fare (Fare other) {
        this.accumulate(other);
    }

    public Fare (double base) {
        low = peak = senior = base;
    }

    public Fare (double low, double peak, double senior) {
        this.low = low;
        this.peak = peak;
        this.senior = senior;
    }

    public void accumulate (Fare other) {
        if (other != null) {
            low    += other.low;
            peak   += other.peak;
            senior += other.senior;
        }
    }

    public void discount(double amount) {
        low    -= amount;
        peak   -= amount;
        senior -= amount;
        transferReduction = true;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Fare{");
        sb.append("type=").append(type);
        sb.append(", low=").append(low);
        sb.append(", peak=").append(peak);
        sb.append(", senior=").append(senior);
        sb.append(", transferReduction=").append(transferReduction);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Fare fare = (Fare) o;
        return Double.compare(fare.low, low) == 0 &&
            Double.compare(fare.peak, peak) == 0 &&
            Double.compare(fare.senior, senior) == 0 &&
            transferReduction == fare.transferReduction &&
            type == fare.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, low, peak, senior, transferReduction);
    }
}
