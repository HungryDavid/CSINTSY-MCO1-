package solver;

import java.util.Arrays;
import java.util.Objects;

public class State {
    public final int[] boxPositions;
    public final int actualPlayerPos;       
    public final int normalizedPlayerPos;   
    public final int gCost; 
    public final int hCost; 
    public final int fCost; 
    public final int lastPushedBoxPos; 
    public final String path; 
    private final int cachedHash;

    public State(int[] boxPositions, int actualPlayerPos, int normalizedPlayerPos, int gCost, int hCost, int lastPushedBoxPos, String path) {
        this.boxPositions = boxPositions.clone(); 
        Arrays.sort(this.boxPositions);
        this.actualPlayerPos = actualPlayerPos;
        this.normalizedPlayerPos = normalizedPlayerPos;
        this.gCost = gCost;
        this.hCost = hCost;
        this.lastPushedBoxPos = lastPushedBoxPos; 
        this.path = path;
        this.fCost = gCost + (5 * hCost); // Dynamic weighting for ultra-fast searches
        this.cachedHash = 31 * Objects.hash(this.normalizedPlayerPos) + Arrays.hashCode(this.boxPositions);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof State)) return false;
        State state = (State) o;
        return normalizedPlayerPos == state.normalizedPlayerPos &&
               Arrays.equals(boxPositions, state.boxPositions);
    }

    @Override
    public int hashCode() {
        return cachedHash;
    }
}