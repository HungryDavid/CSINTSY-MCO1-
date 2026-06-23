package solver;

import java.util.*;

public class SokoBot {

    // --- GLOBAL VARIABLES ---
    private boolean[][] deadTiles;
    private int width, height;

    // --- STATE REPRESENTATION ---
    class GameState {
        int playerR, playerC;
        List<Integer> cratePositions;
        String path;

        public GameState(int pr, int pc, List<Integer> crates, String path) {
            this.playerR = pr;
            this.playerC = pc;
            this.cratePositions = new ArrayList<>(crates);
            Collections.sort(this.cratePositions);
            this.path = path;
        }

        public String getHash() {
            return playerR + "," + playerC + "-" + cratePositions.toString();
        }
    }

    // --- MAIN EXECUTION LOOP ---
    public String solveSokobanPuzzle(int w, int h, char[][] mapData, char[][] itemsData) {
        this.width = w;
        this.height = h;
        long startTime = System.currentTimeMillis();

        // 1. Pre-compute the static traps
        initDeadTiles(mapData);

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

        // 3. Initialize BFS Queue and Visited Memory
        Queue<GameState> queue = new LinkedList<>();
        HashSet<String> visited = new HashSet<>();

        GameState initialState = new GameState(startPr, startPc, startCrates, "");
        queue.add(initialState);
        visited.add(initialState.getHash());

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

            // Check if we won
            if (isSolved(curr.cratePositions, mapData)) {
                return curr.path; 
            }

            // Generate valid moves
            for (int i = 0; i < 4; i++) {
                int nextPr = curr.playerR + dr[i];
                int nextPc = curr.playerC + dc[i];

                if (mapData[nextPr][nextPc] == '#') continue;

                int nextPlayerPos = nextPr * width + nextPc;
                List<Integer> nextCrates = new ArrayList<>(curr.cratePositions);
                boolean validMove = true;

                if (nextCrates.contains(nextPlayerPos)) {
                    int pushR = nextPr + dr[i];
                    int pushC = nextPc + dc[i];
                    int pushPos = pushR * width + pushC;

                    // Filter out walls, other crates, and our newly extracted dead tiles
                    if (mapData[pushR][pushC] == '#' || nextCrates.contains(pushPos) || deadTiles[pushR][pushC]) {
                        validMove = false;
                    } else {
                        nextCrates.remove(Integer.valueOf(nextPlayerPos));
                        nextCrates.add(pushPos);
                        
                        // DYNAMIC FILTER: Check if the push created a 2x2 freeze
                        if (isTwoByTwoDeadlock(pushR, pushC, nextCrates, mapData)) {
                            validMove = false;
                        }
                    }
                }

                if (validMove) {
                    GameState nextState = new GameState(nextPr, nextPc, nextCrates, curr.path + dirChars[i]);
                    String hash = nextState.getHash();

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
     * Scans the board at the start of the game to mark unreachable dead corners.
     */
    private void initDeadTiles(char[][] mapData) {
        deadTiles = new boolean[height][width];
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (mapData[r][c] == '#' || mapData[r][c] == '.') {
                    continue; 
                }

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

    /**
     * Checks if all crates are successfully placed on target locations.
     */
    private boolean isSolved(List<Integer> crates, char[][] mapData) {
        for (int cratePos : crates) {
            int cr = cratePos / width;
            int cc = cratePos % width;
            if (mapData[cr][cc] != '.') {
                return false; 
            }
        }
        return true;
    }

    /**
     * Checks if the newly pushed crate forms a 2x2 block of walls and crates.
     */
    private boolean isTwoByTwoDeadlock(int crateR, int crateC, List<Integer> crates, char[][] mapData) {
        int[][] quadrants = {
            {-1, -1}, {-1, 0}, {0, -1}, {0, 0}
        };

        for (int[] quad : quadrants) {
            int r = crateR + quad[0];
            int c = crateC + quad[1];

            // If all 4 tiles in this 2x2 square are solid (walls or crates)
            if (isWallOrCrate(r, c, crates, mapData) &&
                isWallOrCrate(r + 1, c, crates, mapData) &&
                isWallOrCrate(r, c + 1, crates, mapData) &&
                isWallOrCrate(r + 1, c + 1, crates, mapData)) {
                
                // It's a 2x2 freeze! But is it a deadlock?
                // It is ONLY a deadlock if at least one CRATE in this 2x2 is NOT on a target.
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

    /**
     * Helper to verify if a specific tile contains a crate that is NOT on a target.
     */
    private boolean isCrateNotOnTarget(int r, int c, List<Integer> crates, char[][] mapData) {
        return crates.contains(r * width + c) && mapData[r][c] != '.';
    }

    /**
     * Helper for the 2x2 checker to identify solid objects.
     */
    private boolean isWallOrCrate(int r, int c, List<Integer> crates, char[][] mapData) {
        if (mapData[r][c] == '#') return true;
        if (crates.contains(r * width + c)) return true;
        return false;
    }
}