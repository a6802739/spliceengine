package com.splicemachine.stats.frequency;

import org.sparkproject.guava.primitives.Longs;
import org.sparkproject.guava.primitives.Shorts;

/**
 * @author Scott Fines
 *         Date: 2/24/15
 */
class ShortValueEstimate implements ShortFrequencyEstimate {
    private short value;
    private long count;
    private long epsilon;

    public ShortValueEstimate(short v, long c, long eps) {
        this.value = v;
        this.count = c;
        this.epsilon = eps;
    }

    @Override public short value() { return value; }

    @Override
    public int compareTo(ShortFrequencyEstimate o) {
        int compare = Shorts.compare(value, o.value());
        if(compare!=0) return compare;
        compare = Longs.compare(count, o.count());
        if(compare!=0) return compare;
        return Longs.compare(epsilon,o.error());
    }

    @Override public Short getValue() { return value; }
    @Override public long count() { return count; }
    @Override public long error() { return epsilon; }

    @Override
    public FrequencyEstimate<Short> merge(FrequencyEstimate<Short> other) {
        this.count+=other.count();
        this.epsilon+=other.error();
        return this;
    }

    @Override
    public String toString() {
        return "("+value+","+count+","+epsilon+")";
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof FrequencyEstimate)) return false;
        if(obj instanceof ShortFrequencyEstimate)
            return value==((ShortFrequencyEstimate)obj).value();
        else {
            FrequencyEstimate est = (FrequencyEstimate) obj;
            Object o = est.getValue();
            //not comparing with a short frequency estimate
            return o instanceof Short && (Short) o == value;
        }
    }

    @Override
    public int hashCode() {
        return Shorts.hashCode(value);
    }
}