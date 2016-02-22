package com.conveyal.r5.api.util;

import java.util.Objects;

/**
 * Saves transfer from alightStop of previous leg to boardStop of the next
 *
 * Used when calculating street paths between transit stop transfers in Point to point routing
 */
public class Transfer implements Comparable<Transfer> {
    final public int alightStop;
    final public int boardStop;
    //In which transitSegment is this transfer used. From transitSegment with this index to the next
    final int transitSegmentIndex;

    public Transfer(int alightStop, int boardStop, int transitSegmentIndex) {
        this.alightStop = alightStop;
        this.boardStop = boardStop;
        this.transitSegmentIndex = transitSegmentIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Transfer transfer = (Transfer) o;
        return alightStop == transfer.alightStop &&
            boardStop == transfer.boardStop &&
            transitSegmentIndex == transfer.transitSegmentIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(alightStop, boardStop, transitSegmentIndex);
    }

    /**
     * Compares this object with the specified object for order.  Returns a
     * negative integer, zero, or a positive integer as this object is less
     * than, equal to, or greater than the specified object.
     * <p>
     * <p>The implementor must ensure <tt>sgn(x.compareTo(y)) ==
     * -sgn(y.compareTo(x))</tt> for all <tt>x</tt> and <tt>y</tt>.  (This
     * implies that <tt>x.compareTo(y)</tt> must throw an exception iff
     * <tt>y.compareTo(x)</tt> throws an exception.)
     * <p>
     * <p>The implementor must also ensure that the relation is transitive:
     * <tt>(x.compareTo(y)&gt;0 &amp;&amp; y.compareTo(z)&gt;0)</tt> implies
     * <tt>x.compareTo(z)&gt;0</tt>.
     * <p>
     * <p>Finally, the implementor must ensure that <tt>x.compareTo(y)==0</tt>
     * implies that <tt>sgn(x.compareTo(z)) == sgn(y.compareTo(z))</tt>, for
     * all <tt>z</tt>.
     * <p>
     * <p>It is strongly recommended, but <i>not</i> strictly required that
     * <tt>(x.compareTo(y)==0) == (x.equals(y))</tt>.  Generally speaking, any
     * class that implements the <tt>Comparable</tt> interface and violates
     * this condition should clearly indicate this fact.  The recommended
     * language is "Note: this class has a natural ordering that is
     * inconsistent with equals."
     * <p>
     * <p>In the foregoing description, the notation
     * <tt>sgn(</tt><i>expression</i><tt>)</tt> designates the mathematical
     * <i>signum</i> function, which is defined to return one of <tt>-1</tt>,
     * <tt>0</tt>, or <tt>1</tt> according to whether the value of
     * <i>expression</i> is negative, zero or positive.
     *
     * @param o the object to be compared.
     * @return a negative integer, zero, or a positive integer as this object
     * is less than, equal to, or greater than the specified object.
     * @throws NullPointerException if the specified object is null
     * @throws ClassCastException   if the specified object's type prevents it
     *                              from being compared to this object.
     */
    @Override
    public int compareTo(Transfer o) {
        int c;
        c = Integer.compare(this.alightStop, o.alightStop);
        if (c==0) {
            return Integer.compare(this.boardStop, o.boardStop);
        }
        return c;
    }

    public Integer getAlightStop() {
        return alightStop;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Transfer{");
        sb.append("alightStop=").append(alightStop);
        sb.append(", boardStop=").append(boardStop);
        sb.append(", transitSegmentIndex=").append(transitSegmentIndex);
        sb.append('}');
        return sb.toString();
    }
}
