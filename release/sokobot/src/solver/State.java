package solver;

import java.util.Arrays;
import java.util.Objects;

public class State {
    public final int[] boxPositions;
    public final int actualPlayerPos;       // ADDED: The real position for generating walking paths
    public final int normalizedPlayerPos;   // The top-left position used for saving memory
    public final int gCost; 
    public final int hCost; 
    public final int fCost; 
    public final String path; 
    private final int cachedHash;

    // Added actualPlayerPos to the constructor
    public State(int[] boxPositions, int actualPlayerPos, int normalizedPlayerPos, int gCost, int hCost, String path) {
        this.boxPositions = boxPositions.clone(); 
        Arrays.sort(this.boxPositions);
        this.actualPlayerPos = actualPlayerPos;
        this.normalizedPlayerPos = normalizedPlayerPos;
        this.gCost = gCost;
        this.hCost = hCost;
        this.fCost = gCost + hCost; 
        this.path = path;
        this.cachedHash = 31 * Objects.hash(this.normalizedPlayerPos) + Arrays.hashCode(this.boxPositions);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof State)) return false;
        State state = (State) o;
        // Notice we still only compare the normalized position to save memory!
        return normalizedPlayerPos == state.normalizedPlayerPos &&
               Arrays.equals(boxPositions, state.boxPositions);
    }

    @Override
    public int hashCode() {
        return cachedHash;
    }
}