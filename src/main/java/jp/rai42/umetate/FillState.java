package jp.rai42.umetate;

import java.util.BitSet;

/** World-independent occupancy, fountain reservations and bounded request accounting. */
public final class FillState {
    private final int capacity;
    private final BitSet occupied;
    private final BitSet reserved;
    private int filled;
    private int waiting;
    private int directWaiting;
    private final BitSet directReserved = new BitSet();
    /** Cells holding a full, opaque cube that every ray through them hits. */
    private final BitSet occluding = new BitSet();
    /** No free, unreserved cell exists below this index; lets bulk fills avoid rescanning from 0. */
    private int searchFrom;
    public FillState(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        occupied = new BitSet(capacity); reserved = new BitSet(capacity);
    }
    public int capacity() { return capacity; }
    public int filled() { return filled; }
    public int pending() { return waiting + directWaiting + reserved.cardinality(); }
    public boolean full() { return filled == capacity; }
    public boolean reserved(int index) { check(index); return reserved.get(index); }
    public int nextUnoccupied(int from) {
        for(int i=occupied.nextClearBit(Math.max(0,from));i<capacity;i=occupied.nextClearBit(i+1))
            if(!reserved.get(i))return i;
        return -1;
    }
    public boolean occupied(int index) { check(index); return occupied.get(index); }
    public boolean occluding(int index) { check(index); return occluding.get(index); }
    public void setOccluding(int index, boolean value) { check(index); occluding.set(index, value); }
    /** True when every cell in [from, to) is occluding. */
    public boolean allOccluding(int from, int to) { return from < to && occluding.nextClearBit(from) >= to; }
    /** Next occupied index at or after from whose cell one layer below is free and unreserved, or -1. */
    public int nextFallCandidate(int from, int layer) {
        for (int i = occupied.nextSetBit(Math.max(from, layer)); i >= 0 && i < capacity; i = occupied.nextSetBit(i + 1))
            if (!occupied.get(i - layer) && !reserved.get(i - layer)) return i;
        return -1;
    }
    public void set(int index, boolean value) {
        check(index);
        if (occupied.get(index) != value) { occupied.set(index, value); filled += value ? 1 : -1; if (!value) searchFrom = Math.min(searchFrom, index); }
    }
    public int enqueue(int count) { return enqueue(count, false); }
    public int enqueue(int count, boolean direct) {
        if (count < 0) throw new IllegalArgumentException("count");
        if (full()) { waiting = directWaiting = 0; return 0; }
        int accepted = Math.min(count, Integer.MAX_VALUE - pending());
        if (direct) directWaiting += accepted; else waiting += accepted; return accepted;
    }
    public int directWaiting() { return directWaiting; }
    /** Lowest index that may be free; a starting point for nextFree. */
    public int searchHint() { return searchFrom; }
    /** Next free, unreserved index at or after from, or -1. Does not reserve. */
    public int nextFree(int from) {
        for (int i = occupied.nextClearBit(Math.max(0, from)); i < capacity; i = occupied.nextClearBit(i + 1))
            if (!reserved.get(i)) return i;
        return -1;
    }
    /** Direct placement completes in the same call, so it skips the reservation bits (clearing them is costly in bulk). */
    public void placeDirect(int index) {
        check(index);
        if (directWaiting == 0) throw new IllegalStateException("no direct request");
        directWaiting--; set(index, true);
        if (full()) waiting = directWaiting = 0;
    }
    public int reserve() { return reserve(false); }
    public int reserve(boolean direct) {
        if (full()) { waiting = directWaiting = 0; return -1; }
        if ((direct ? directWaiting : waiting) == 0) return -1;
        for (int i = occupied.nextClearBit(searchFrom); i < capacity; i = occupied.nextClearBit(i + 1)) {
            if (!reserved.get(i)) { reserved.set(i); directReserved.set(i,direct); if(direct) directWaiting--;else waiting--; searchFrom = i + 1; return i; }
        }
        searchFrom = capacity;
        return -1;
    }
    /** Retry a flight if a player occupied its target before landing. */
    public void finish(int index, boolean placed) {
        check(index);
        if (!reserved.get(index)) throw new IllegalStateException("unreserved landing");
        reserved.clear(index);
        if (!placed) searchFrom = Math.min(searchFrom, index);
        if (placed) set(index, true);
        else if (!full()) { if(directReserved.get(index)) directWaiting++;else waiting++; }
        directReserved.clear(index);
        if (full()) waiting = directWaiting = 0;
    }
    public void cancelPending() { waiting = directWaiting = 0; reserved.clear(); directReserved.clear(); searchFrom = 0; }
    private void check(int index) {
        if (index < 0 || index >= capacity) throw new IndexOutOfBoundsException(index);
    }
}
