package solver;

import java.util.*;

public class SokoBot {

    // --- GLOBAL VARIABLES ---
    private boolean[][] deadTiles;
    private int[][][] trueDistances; // UPGRADED: [targetIndex][row][col]
    private int width, height;
    private List<Integer> targets;
    // --- STATE REPRESENTATION (Upgraded for A*) ---
    class GameState implements Comparable<GameState> {
        int playerR, playerC;
        List<Integer> cratePositions;
        String path;
        int h; 
        int pushes; // NEW: Track the number of pushes, not footsteps

        public GameState(int pr, int pc, List<Integer> crates, String path, int pushes) {
            this.playerR = pr;
            this.playerC = pc;
            this.cratePositions = new ArrayList<>(crates);
            Collections.sort(this.cratePositions);
            this.path = path;
            this.h = calculateHeuristic(this.cratePositions);
            this.pushes = pushes; // NEW
        }

        public String getHash() {
            return playerR + "," + playerC + "-" + cratePositions.toString();
        }

        @Override
        public int compareTo(GameState other) {
            int thisScore = this.pushes + this.h; 
            int otherScore = other.pushes + other.h;

            // THE TIE-BREAKER: If the overall scores are identical...
            if (thisScore == otherScore) {
                // Prioritize the timeline that has made MORE pushes.
                // This gives the bot "momentum" so it doesn't abandon crates.
                return Integer.compare(other.pushes, this.pushes);
            }

            return Integer.compare(thisScore, otherScore);
        }
    }

    // --- MAIN EXECUTION LOOP ---
    public String solveSokobanPuzzle(int w, int h, char[][] mapData, char[][] itemsData) {
        this.width = w;
        this.height = h;
        long startTime = System.currentTimeMillis();

        // 1. Pre-compute static traps and true distances
        initDeadTiles(mapData);
        initTargets(mapData);       // MUST BE BEFORE TRUE DISTANCES
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

        GameState initialState = new GameState(startPr, startPc, startCrates, "", 0); 
        queue.add(initialState);
        visited.add(initialState.getHash());

        int[] dr = {-1, 1, 0, 0};
        int[] dc = {0, 0, -1, 1};
        char[] dirChars = {'u', 'd', 'l', 'r'};

        // 4. Execution Search
        while (!queue.isEmpty()) {
            int statesExplored = 0;
            int maxDepth = 0;
            // Safety Switch
            if (System.currentTimeMillis() - startTime > 14000) {
                System.out.println("Time limit reached! Returning best effort.");
                return queue.peek().path; 
            }

            GameState curr = queue.poll();

            statesExplored++;
            if (curr.pushes > maxDepth) maxDepth = curr.pushes;

            // Print a diagnostic heartbeat every 5,000 states
            if (statesExplored % 100 == 0) {
                System.out.println("Explored: " + statesExplored + 
                                   " | Queue: " + queue.size() + 
                                   " | Max Depth (Pushes): " + maxDepth + 
                                   " | Current Score (h): " + curr.h);
            }

            // Check if we won
            if (curr.h == 0) { // If distance to targets is 0, we won!
                return curr.path; 
            }

            // 1. Map out the player's territory and footsteps
            HashMap<Integer, String> reachable = getReachableTiles(curr.playerR, curr.playerC, curr.cratePositions, mapData);

            int[] pushDr = {-1, 1, 0, 0};
            int[] pushDc = {0, 0, -1, 1};
            char[] pushChars = {'u', 'd', 'l', 'r'};

            // 2. Iterate over EVERY crate on the board
            for (int i = 0; i < curr.cratePositions.size(); i++) {
                int cratePos = curr.cratePositions.get(i);
                int cr = cratePos / width;
                int cc = cratePos % width;

                // 3. For each crate, check all 4 possible push directions
                for (int dir = 0; dir < 4; dir++) {
                    
                    // The trick: to push a crate DOWN (dir=1), the player must stand ABOVE it
                    int pushStandR = cr - pushDr[dir];
                    int pushStandC = cc - pushDc[dir];
                    int pushStandPos = pushStandR * width + pushStandC;

                    // Where the crate will end up after the push
                    int newCrateR = cr + pushDr[dir];
                    int newCrateC = cc + pushDc[dir];
                    int newCratePos = newCrateR * width + newCrateC;

                    // --- THE DIGITAL FILTERS ---
                    
                    // Filter 1: Can the player physically walk to the pushing position?
                    if (!reachable.containsKey(pushStandPos)) continue;

                    // Filter 2: Is the destination an illegal tile? (Wall, another crate, or dead corner)
                    if (mapData[newCrateR][newCrateC] == '#' || 
                        deadTiles[newCrateR][newCrateC] || 
                        curr.cratePositions.contains(newCratePos)) {
                        continue;
                    }

                    // Generate the temporary crate layout for the deadlock check
                    List<Integer> nextCrates = new ArrayList<>(curr.cratePositions);
                    nextCrates.remove(Integer.valueOf(cratePos));
                    nextCrates.add(newCratePos);

                    // Filter 3: Does this specific push create a 2x2 freeze?
                    if (isTwoByTwoDeadlock(newCrateR, newCrateC, nextCrates, mapData)) {
                        continue;
                    }

                    // --- MACRO-MOVE VALIDATED ---
                    
                    // The player steps into the crate's old position after pushing
                    int nextPlayerR = cr;
                    int nextPlayerC = cc;

                    // Combine the walking footsteps with the actual push command
                    String walkPath = reachable.get(pushStandPos);
                    String fullPath = curr.path + walkPath + pushChars[dir];

                    // NEW: Pass in curr.pushes + 1
                    GameState nextState = new GameState(nextPlayerR, nextPlayerC, nextCrates, fullPath, curr.pushes + 1);
                    String hash = nextState.getHash();

                    // If we haven't seen this exact board state before, queue it!
                    if (!visited.contains(hash)) {
                        visited.add(hash);
                        queue.add(nextState);
                    }
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
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (mapData[r][c] == '.') {
                    targets.add(r * width + c);
                }
            }
        }
    }

    private void initDeadTiles(char[][] mapData) {
        deadTiles = new boolean[height][width];
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (mapData[r][c] == '#' || mapData[r][c] == '.') continue; 

                boolean wallUp = mapData[r - 1][c] == '#';
                boolean wallDown = mapData[r + 1][c] == '#';
                boolean wallLeft = mapData[r][c - 1] == '#';
                boolean wallRight = mapData[r][c + 1] == '#';

                if ((wallUp || wallDown) && (wallLeft || wallRight)) {
                    deadTiles[r][c] = true;
                }
            }
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
        return crates.contains(r * width + c) && mapData[r][c] != '.';
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
     * Maps out every reachable floor tile AND the footsteps taken to get there.
     */
    private HashMap<Integer, String> getReachableTiles(int startPr, int startPc, List<Integer> crates, char[][] mapData) {
        HashMap<Integer, String> reachable = new HashMap<>();
        Queue<int[]> queue = new LinkedList<>();
        Queue<String> pathQueue = new LinkedList<>(); // Tracks the string of footsteps

        int startPos = startPr * width + startPc;
        queue.add(new int[]{startPr, startPc});
        pathQueue.add("");
        reachable.put(startPos, "");

        int[] dr = {-1, 1, 0, 0};
        int[] dc = {0, 0, -1, 1};
        char[] dirChars = {'u', 'd', 'l', 'r'};

        while (!queue.isEmpty()) {
            int[] curr = queue.poll();
            String path = pathQueue.poll();
            int r = curr[0];
            int c = curr[1];

            for (int i = 0; i < 4; i++) {
                int nr = r + dr[i];
                int nc = c + dc[i];
                int nPos = nr * width + nc;

                // Ignore walls, out-of-bounds, and crates
                if (nr < 0 || nr >= height || nc < 0 || nc >= width || mapData[nr][nc] == '#') continue;
                if (crates.contains(nPos)) continue;

                // If it's a new floor tile, log it and the path to reach it!
                if (!reachable.containsKey(nPos)) {
                    reachable.put(nPos, path + dirChars[i]);
                    queue.add(new int[]{nr, nc});
                    pathQueue.add(path + dirChars[i]);
                }
            }
        }
        return reachable;
    }
}