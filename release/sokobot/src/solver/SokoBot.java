package solver;

import java.util.*;

public class SokoBot {

    // --- GLOBAL VARIABLES ---
    private boolean[][] deadTiles;
    private int[][][] trueDistances;
    private int width, height;
    private List<Integer> targets;
    private boolean[] isTargetTile; // NEW: Instant target lookup
    
    // Zero-Allocation BFS Memory
    private int[] reachable;
    private String[] movePaths;
    private int bfsToken = 0;

    class GameState implements Comparable<GameState> {
        int playerR, playerC;
        List<Integer> cratePositions;
        String path;
        int h; 
        int gCost; // Replaces pushes. Tracks total walk cost + pushes + penalties
        int lastPushedPos; // Tracks the last box pushed for momentum

        public GameState(int pr, int pc, List<Integer> crates, String path, int gCost, int lastPushedPos) {
            this.playerR = pr;
            this.playerC = pc;
            this.cratePositions = new ArrayList<>(crates);
            Collections.sort(this.cratePositions);
            this.path = path;
            this.h = calculateHeuristic(this.cratePositions);
            this.gCost = gCost; 
            this.lastPushedPos = lastPushedPos;
        }

        public String getHash() {
            return playerR + "," + playerC + "-" + cratePositions.toString();
        }

        @Override
        public int compareTo(GameState other) {
            int thisScore = this.gCost + (5 * this.h); 
            int otherScore = other.gCost + (5 * other.h);

            // Tie-breaker: If scores are equal, prioritize the state physically closer to the goals
            if (thisScore == otherScore) {
                return Integer.compare(this.h, other.h); 
            }
            return Integer.compare(thisScore, otherScore);
        }
    }

    // --- MAIN EXECUTION LOOP ---
    public String solveSokobanPuzzle(int w, int h, char[][] mapData, char[][] itemsData) {
        this.width = w;
        this.height = h;
        long startTime = System.currentTimeMillis();

        // NEW: Initialize BFS arrays once to stop memory leaks
        reachable = new int[width * height];
        movePaths = new String[width * height];

        // 1. Pre-compute static traps and true distances
        initTargets(mapData);       // MUST GO FIRST! Creates the isTargetTile array.
        initDeadTiles(mapData);     // Now it can safely use the array.
        initTrueDistances(mapData);

        // 2. Find starting positions
        int startPr = 0, startPc = 0;
        List<Integer> startCrates = new ArrayList<>();
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (itemsData[r][c] == '@') {
                    startPr = r;
                    startPc = c;
                } else if (itemsData[r][c] == '$') {
                    startCrates.add(r * width + c);
                }
            }
        }

        // 3. UPGRADE: Initialize PriorityQueue for A* Search
        PriorityQueue<GameState> queue = new PriorityQueue<>();
        HashSet<String> visited = new HashSet<>();

        GameState initialState = new GameState(startPr, startPc, startCrates, "", 0, -1);
        queue.add(initialState);

        int[] dr = {-1, 1, 0, 0};
        int[] dc = {0, 0, -1, 1};
        char[] dirChars = {'u', 'd', 'l', 'r'};

        // 4. Execution Search
        while (!queue.isEmpty()) {
            // Safety Switch
            if (System.currentTimeMillis() - startTime > 14000) {
                System.out.println("Time limit reached! Returning best effort.");
                return queue.peek().path; 
            }

            GameState curr = queue.poll();

            // 1. Map territory AND get the normalized Room ID
            int normalizedPlayerID = runZeroAllocationBFS(curr.playerR, curr.playerC, curr.cratePositions, mapData);

            // 2. STATE NORMALIZATION: Have we seen this exact room+crate combo before?
            String normalizedHash = normalizedPlayerID + "-" + curr.cratePositions.toString();
            if (visited.contains(normalizedHash)) continue; // Instantly drop the duplicate!
            visited.add(normalizedHash); // Mark as seen

            // Check if we won
            if (curr.h == 0) { 
                return curr.path; 
            }

            int[] pushDr = {-1, 1, 0, 0};
            int[] pushDc = {0, 0, -1, 1};
            char[] pushChars = {'u', 'd', 'l', 'r'};

            // 3. Iterate over EVERY crate on the board
            for (int i = 0; i < curr.cratePositions.size(); i++) {
                int cratePos = curr.cratePositions.get(i);
                int cr = cratePos / width;
                int cc = cratePos % width;

                // 4. For each crate, check all 4 possible push directions
                for (int dir = 0; dir < 4; dir++) {
                    
                    int pushStandR = cr - pushDr[dir];
                    int pushStandC = cc - pushDc[dir];
                    int pushStandPos = pushStandR * width + pushStandC;

                    // Filter 1: Check our zero-allocation array to see if we can reach it
                    if (pushStandPos < 0 || pushStandPos >= (width*height) || reachable[pushStandPos] != bfsToken) continue;

                    int newCrateR = cr + pushDr[dir];
                    int newCrateC = cc + pushDc[dir];
                    int newCratePos = newCrateR * width + newCrateC;

                    // Filter 2: Did we hit a wall or another crate?
                    if (mapData[newCrateR][newCrateC] == '#' || curr.cratePositions.contains(newCratePos)) {
                        continue; 
                    }

                    List<Integer> nextCrates = new ArrayList<>(curr.cratePositions);
                    nextCrates.remove(Integer.valueOf(cratePos)); 
                    nextCrates.add(newCratePos);

                    // Filter 3: Static Deadlines, 2x2s, and Frozen Deadlocks
                    if (deadTiles[newCrateR][newCrateC] || 
                        isTwoByTwoDeadlock(newCrateR, newCrateC, nextCrates, mapData) ||
                        isFrozenDeadlock(nextCrates, mapData)) {
                        continue;
                    }

                    // --- THE SECRET SAUCE (Momentum & Penalties) ---
                    String walkPath = movePaths[pushStandPos];
                    int walkCost = walkPath.length();
                    
                    // SOFT PENALTY: Gently discourage pushing off targets, but allow it if necessary.
                    int targetLockPenalty = (isTargetTile[cratePos] && !isTargetTile[newCratePos]) ? 10 : 0;
                    
                    // SOFT PENALTY: Gently prefer keeping momentum on the same box, but allow shuffling.
                    int switchPenalty = (curr.lastPushedPos != -1 && curr.lastPushedPos != cratePos) ? 2 : 0;

                    // Add everything to the gCost
                    int newGCost = curr.gCost + walkCost + targetLockPenalty + switchPenalty + 1;
                    String fullPath = curr.path + walkPath + pushChars[dir];

                    GameState nextState = new GameState(cr, cc, nextCrates, fullPath, newGCost, newCratePos);
                    queue.add(nextState);
                }
            }
        }

        return ""; 
    }

    // --- HELPER METHODS ---

    /**
     * Instantly looks up the pre-calculated true distance for every crate.
     */
    /**
     * Calculates the heuristic by forcing every crate to claim a UNIQUE target.
     */
    private int calculateHeuristic(List<Integer> crates) {
        int totalDistance = 0;
        boolean[] targetUsed = new boolean[targets.size()];
        boolean[] crateUsed = new boolean[crates.size()];

        // Loop until every crate has claimed a target
        for (int step = 0; step < crates.size(); step++) {
            int minDistance = 999999;
            int bestCrateIndex = -1;
            int bestTargetIndex = -1;

            // Find the absolute closest unmatched Crate-Target pair
            for (int c = 0; c < crates.size(); c++) {
                if (crateUsed[c]) continue;
                int cr = crates.get(c) / width;
                int cc = crates.get(c) % width;

                for (int t = 0; t < targets.size(); t++) {
                    if (targetUsed[t]) continue;
                    
                    int dist = trueDistances[t][cr][cc];
                    if (dist < minDistance) {
                        minDistance = dist;
                        bestCrateIndex = c;
                        bestTargetIndex = t;
                    }
                }
            }

            // Lock in the claim and add the distance to our score
            if (bestCrateIndex != -1 && bestTargetIndex != -1) {
                crateUsed[bestCrateIndex] = true;
                targetUsed[bestTargetIndex] = true;
                totalDistance += minDistance;
            }
        }
        return totalDistance;
    }

    private void initTargets(char[][] mapData) {
        targets = new ArrayList<>();
        isTargetTile = new boolean[width * height];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (mapData[r][c] == '.') {
                    int pos = r * width + c;
                    targets.add(pos);
                    isTargetTile[pos] = true;
                }
            }
        }
    }

    private void initDeadTiles(char[][] mapData) {
        deadTiles = new boolean[height][width];
        
        // Step 1: Find all the standard 90-degree corners
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (mapData[r][c] == '#' || mapData[r][c] == '.') continue; 
                
                boolean wallUp = mapData[r - 1][c] == '#';
                boolean wallDown = mapData[r + 1][c] == '#';
                boolean wallLeft = mapData[r][c - 1] == '#';
                boolean wallRight = mapData[r][c + 1] == '#';
                
                if ((wallUp && wallLeft) || (wallUp && wallRight) || 
                    (wallDown && wallLeft) || (wallDown && wallRight)) {
                    deadTiles[r][c] = true;
                }
            }
        }
        
        // Step 2: Trace lines between corners to find Edge Deadlocks!
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (deadTiles[r][c]) {
                    if (mapData[r - 1][c] == '#' || mapData[r + 1][c] == '#') {
                        verifyAndMarkLine(r, c, 0, 1, mapData); // Trace horizontally
                    }
                    if (mapData[r][c - 1] == '#' || mapData[r][c + 1] == '#') {
                        verifyAndMarkLine(r, c, 1, 0, mapData); // Trace vertically
                    }
                }
            }
        }
    }

    private void verifyAndMarkLine(int startR, int startC, int dRow, int dCol, char[][] mapData) {
        int r = startR + dRow;
        int c = startC + dCol;
        List<int[]> pathCells = new ArrayList<>();
        
        while (r >= 0 && r < height && c >= 0 && c < width && mapData[r][c] != '#') {
            if (isTargetTile[r * width + c]) return; // Use the O(1) array!
            
            boolean hasWallSide1 = false;
            boolean hasWallSide2 = false;
            
            int side1R = r - dCol; int side1C = c - dRow;
            if (side1R >= 0 && side1R < height && side1C >= 0 && side1C < width) {
                hasWallSide1 = mapData[side1R][side1C] == '#';
            }
            int side2R = r + dCol; int side2C = c + dRow;
            if (side2R >= 0 && side2R < height && side2C >= 0 && side2C < width) {
                hasWallSide2 = mapData[side2R][side2C] == '#';
            }
            
            if (!hasWallSide1 && !hasWallSide2) return; // Wall gap, not a trap
            
            // If we hit another dead corner, the whole line is a trap!
            if (deadTiles[r][c]) {
                for (int[] cell : pathCells) {
                    deadTiles[cell[0]][cell[1]] = true;
                }
                return;
            }
            pathCells.add(new int[]{r, c});
            r += dRow;
            c += dCol;
        }
    }

    private boolean isTwoByTwoDeadlock(int crateR, int crateC, List<Integer> crates, char[][] mapData) {
        int[][] quadrants = {{-1, -1}, {-1, 0}, {0, -1}, {0, 0}};
        for (int[] quad : quadrants) {
            int r = crateR + quad[0];
            int c = crateC + quad[1];

            if (isWallOrCrate(r, c, crates, mapData) &&
                isWallOrCrate(r + 1, c, crates, mapData) &&
                isWallOrCrate(r, c + 1, crates, mapData) &&
                isWallOrCrate(r + 1, c + 1, crates, mapData)) {
                
                if (isCrateNotOnTarget(r, c, crates, mapData) ||
                    isCrateNotOnTarget(r + 1, c, crates, mapData) ||
                    isCrateNotOnTarget(r, c + 1, crates, mapData) ||
                    isCrateNotOnTarget(r + 1, c + 1, crates, mapData)) {
                    return true; 
                }
            }
        }
        return false;
    }

    private boolean isCrateNotOnTarget(int r, int c, List<Integer> crates, char[][] mapData) {
        return crates.contains(r * width + c) && !isTargetTile[r * width + c];
    }

    private boolean isWallOrCrate(int r, int c, List<Integer> crates, char[][] mapData) {
        return mapData[r][c] == '#' || crates.contains(r * width + c);
    }

    /**
     * Pre-computes the true walking distance from every floor tile to its nearest target.
     */
    /**
     * Pre-computes the perfect walking distance from every tile to EVERY SPECIFIC target.
     */
    private void initTrueDistances(char[][] mapData) {
        trueDistances = new int[targets.size()][height][width];
        
        for (int t = 0; t < targets.size(); t++) {
            int target = targets.get(t);
            int tr = target / width;
            int tc = target % width;

            // Fill this specific target's map with high numbers
            for (int r = 0; r < height; r++) {
                Arrays.fill(trueDistances[t][r], 999999);
            }

            Queue<int[]> queue = new LinkedList<>();
            trueDistances[t][tr][tc] = 0;
            queue.add(new int[]{tr, tc});

            int[] dr = {-1, 1, 0, 0};
            int[] dc = {0, 0, -1, 1};

            while (!queue.isEmpty()) {
                int[] curr = queue.poll();
                int r = curr[0];
                int c = curr[1];
                int currentDist = trueDistances[t][r][c];

                for (int i = 0; i < 4; i++) {
                    int nr = r + dr[i];
                    int nc = c + dc[i];

                    if (nr >= 0 && nr < height && nc >= 0 && nc < width && mapData[nr][nc] != '#') {
                        if (currentDist + 1 < trueDistances[t][nr][nc]) {
                            trueDistances[t][nr][nc] = currentDist + 1;
                            queue.add(new int[]{nr, nc});
                        }
                    }
                }
            }
        }
    }

    /**
     * Maps the territory using zero-allocation arrays AND returns a normalized Room ID.
     */
    private int runZeroAllocationBFS(int startPr, int startPc, List<Integer> crates, char[][] mapData) {
        bfsToken++; // Increment token instead of clearing a HashMap
        int startPos = startPr * width + startPc;
        int normalizedPos = startPos; // Start with player's actual position

        Queue<Integer> queue = new LinkedList<>();
        queue.add(startPos);
        reachable[startPos] = bfsToken;
        movePaths[startPos] = "";

        int[] dr = {-1, 1, 0, 0};
        int[] dc = {0, 0, -1, 1};
        char[] dirChars = {'u', 'd', 'l', 'r'};

        while (!queue.isEmpty()) {
            int curr = queue.poll();
            
            // STATE NORMALIZATION: Find the lowest coordinate in this room!
            if (curr < normalizedPos) normalizedPos = curr; 

            int r = curr / width;
            int c = curr % width;
            String currentPath = movePaths[curr];

            for (int i = 0; i < 4; i++) {
                int nr = r + dr[i];
                int nc = c + dc[i];
                int nPos = nr * width + nc;

                if (nr < 0 || nr >= height || nc < 0 || nc >= width || mapData[nr][nc] == '#') continue;
                if (crates.contains(nPos)) continue;

                if (reachable[nPos] != bfsToken) {
                    reachable[nPos] = bfsToken;
                    movePaths[nPos] = currentPath + dirChars[i];
                    queue.add(nPos);
                }
            }
        }
        return normalizedPos;
    }

    private boolean isFrozenDeadlock(List<Integer> crates, char[][] mapData) {
        for (int cratePos : crates) {
            int r = cratePos / width;
            int c = cratePos % width;
            
            if (isTargetTile[cratePos]) continue; // Safe on a target

            boolean wallUp = mapData[r-1][c] == '#';
            boolean wallDown = mapData[r+1][c] == '#';
            boolean wallLeft = mapData[r][c-1] == '#';
            boolean wallRight = mapData[r][c+1] == '#';
            
            boolean boxUp = crates.contains((r-1)*width + c);
            boolean boxDown = crates.contains((r+1)*width + c);
            boolean boxLeft = crates.contains(r*width + c - 1);
            boolean boxRight = crates.contains(r*width + c + 1);
            
            // Is it pinned against a wall by another crate and another wall?
            if (wallLeft) {
                if (boxUp && mapData[r-1][c-1] == '#') return true; 
                if (boxDown && mapData[r+1][c-1] == '#') return true; 
            }
            if (wallRight) {
                if (boxUp && mapData[r-1][c+1] == '#') return true;
                if (boxDown && mapData[r+1][c+1] == '#') return true;
            }
            if (wallUp) {
                if (boxLeft && mapData[r-1][c-1] == '#') return true;
                if (boxRight && mapData[r-1][c+1] == '#') return true;
            }
            if (wallDown) {
                if (boxLeft && mapData[r+1][c-1] == '#') return true;
                if (boxRight && mapData[r+1][c+1] == '#') return true;
            }
        }
        return false;
    }
}