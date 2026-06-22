package solver;

import java.util.*;

public class SokoBot {

    public String solveSokobanPuzzle(int width, int height, char[][] mapData, char[][] itemsData) {
        long startTime = System.currentTimeMillis();

        // =========================================================
        // 1. ROBUST BOARD PARSING (Supports combined layers & symbols)
        // =========================================================
        List<Integer> targetNodes = new ArrayList<>();
        List<Integer> startBoxNodes = new ArrayList<>();
        int startPlayerPos = -1;

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int posId = r * width + c; 
                
                // Safe lookup in case rows have varying lengths
                char m = (r < mapData.length && c < mapData[r].length) ? mapData[r][c] : ' ';
                char i = (itemsData != null && r < itemsData.length && c < itemsData[r].length) ? itemsData[r][c] : ' ';
                
                // Targets can be represented as '.', '*' (box on target), or '+' (player on target)
                if (m == '.' || i == '.' || m == '*' || i == '*' || m == '+' || i == '+') {
                    targetNodes.add(posId);
                }
                // Player can be '@' or '+'
                if (m == '@' || i == '@' || m == '+' || i == '+') {
                    startPlayerPos = posId;
                }
                // Boxes can be '$' or '*'
                if (m == '$' || i == '$' || m == '*' || i == '*') {
                    startBoxNodes.add(posId);
                }
            }
        }

        int[] startBoxes = startBoxNodes.stream().mapToInt(box -> box).toArray();
        int[] targets = targetNodes.stream().mapToInt(target -> target).toArray();
        
        Arrays.sort(startBoxes);
        Arrays.sort(targets);

        // Safety fallback: if parsing completely failed, abort safely
        if (startPlayerPos == -1 || startBoxes.length == 0 || targets.length == 0) {
            return "";
        }

        // =========================================================
        // 2. DEADLOCK MAP (Pre-computing unplayable tiles)
        // =========================================================
        boolean[] staticDeadlockMap = new boolean[width * height];

        // PASS 1: Find all the Corners (Passed targets here)
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int posId = r * width + c;
                if (r < mapData.length && c < mapData[r].length && mapData[r][c] == '#') continue;

                if (isDeadlock(posId, width, mapData, targets)) {
                    staticDeadlockMap[posId] = true;
                }
            }
        }

        // PASS 2: Connect corners to find Dead Walls (Passed targets here)
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                int posId = r * width + c;
                if (staticDeadlockMap[posId]) {
                    scanAndMarkDeadWall(r, c, 0, 1, staticDeadlockMap, mapData, width, height, targets); 
                    scanAndMarkDeadWall(r, c, 1, 0, staticDeadlockMap, mapData, width, height, targets); 
                }
            }
        }
        
        // =========================================================
        // 3. INITIAL STATE CREATION (Normalizing the starting point)
        // =========================================================
        boolean[] initialReachable = getReachableTiles(startPlayerPos, startBoxes, mapData, width, height);
        int initialNormalizedPlayer = getNormalizedPlayerPos(initialReachable);

        // =========================================================
        // 4. A* GRAPH SEARCH LOOP
        // =========================================================
        PriorityQueue<State> openSet = new PriorityQueue<>(new Comparator<State>() {
            @Override
            public int compare(State s1, State s2) {
                int fCompare = Integer.compare(s1.fCost, s2.fCost);
                if (fCompare == 0) {
                    // Tie-breaker: If costs are equal, pick the one closer to the goal
                    return Integer.compare(s1.hCost, s2.hCost);
                }
                return fCompare;
            }
        });

        // Initialize the first state using the new constructor
        int initialH = getHeuristic(startBoxes, targets, width);
        State initialState = new State(startBoxes, startPlayerPos, initialNormalizedPlayer, 0, initialH, "");

        Set<State> closedSet = new HashSet<>();
        openSet.add(initialState);

        while (!openSet.isEmpty()) {
            State curr = openSet.poll();

            if (closedSet.contains(curr)) continue;
            closedSet.add(curr);

            // Win Condition
            if (Arrays.equals(curr.boxPositions, targets)) {
                System.out.println("SOLUTION FOUND: " + curr.path);
                return curr.path;
            }

            for (State nextState : getSuccessors(curr, mapData, staticDeadlockMap, width, height, targets)) {
                if (!closedSet.contains(nextState)) {
                    openSet.add(nextState);
                }
            }
        }

        System.out.println("NO SOLUTION, path length: " + "".length());
        return "";
    }

    private boolean isDeadlock(int pos, int width, char[][] mapData, int[] targets) {
        int r = pos / width;
        int c = pos % width;
        int height = mapData.length;
    
        if (Arrays.binarySearch(targets, pos) >= 0) return false;
    
        boolean wallUp    = (r == 0)          || mapData[r - 1][c] == '#';
        boolean wallDown  = (r == height - 1) || mapData[r + 1][c] == '#';
        boolean wallLeft  = (c == 0)          || mapData[r][c - 1] == '#';
        boolean wallRight = (c == width - 1)  || mapData[r][c + 1] == '#';
    
        // Only a deadlock if it's a TRUE corner: blocked on BOTH horizontal AND vertical
        if ((wallUp && wallDown)) return false; // open corridor vertically, not a corner
        if ((wallLeft && wallRight)) return false; // open corridor horizontally, not a corner
    
        return (wallUp && wallLeft) || (wallUp && wallRight) || (wallDown && wallLeft) || (wallDown && wallRight);
    }
    
    private void scanAndMarkDeadWall(int startR, int startC, int rowDir, int colDir, boolean[] deadlockMap, char[][] mapData, int width, int height, int[] targets) {
        int r = startR + rowDir;
        int c = startC + colDir;
        
        List<Integer> path = new ArrayList<>();
        boolean wallSide1Continuous = true;
        boolean wallSide2Continuous = true;
    
        int side1R = colDir; 
        int side1C = rowDir; 
        int side2R = -colDir; 
        int side2C = -rowDir;
    
        while (r > 0 && r < height - 1 && c > 0 && c < width - 1) {
            int pos = r * width + c;
            
            if (c >= mapData[r].length) return;
            if (mapData[r][c] == '#') return;
            if (Arrays.binarySearch(targets, pos) >= 0) return; // Fixed target validation
    
            if (c + side1C >= mapData[r + side1R].length || mapData[r + side1R][c + side1C] != '#') wallSide1Continuous = false;
            if (c + side2C >= mapData[r + side2R].length || mapData[r + side2R][c + side2C] != '#') wallSide2Continuous = false;
    
            if (!wallSide1Continuous && !wallSide2Continuous) return;
    
            if (deadlockMap[pos]) {
                for (int p : path) {
                    deadlockMap[p] = true;
                }
                return;
            }
    
            path.add(pos);
            r += rowDir;
            c += colDir;
        }
    }

    // Fixed: Pure 2D navigation eliminates 1D row wrap-around glitches
    private boolean[] getReachableTiles(int playerPos, int[] boxPositions, char[][] mapData, int width, int height) {
        boolean[] reachable = new boolean[width * height];
        
        boolean[] hasBox = new boolean[width * height];
        for (int box : boxPositions) {
            hasBox[box] = true;
        }

        Queue<Integer> queue = new LinkedList<>();
        queue.add(playerPos);
        reachable[playerPos] = true;

        int[] dr = {-1, 1, 0, 0};
        int[] dc = {0, 0, -1, 1};

        while (!queue.isEmpty()) {
            int curr = queue.poll();
            int cr = curr / width;
            int cc = curr % width;

            for (int i = 0; i < 4; i++) {
                int nr = cr + dr[i];
                int nc = cc + dc[i];

                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                if (nc >= mapData[nr].length) continue;

                int next = nr * width + nc;
                if (mapData[nr][nc] == '#' || hasBox[next]) continue;

                if (!reachable[next]) {
                    reachable[next] = true;
                    queue.add(next);
                }
            }
        }
        return reachable;
    }

    private int getNormalizedPlayerPos(boolean[] reachableTiles) {
        for (int i = 0; i < reachableTiles.length; i++) {
            if (reachableTiles[i]) {
                return i; 
            }
        }
        return -1;
    }

    private List<State> getSuccessors(State state, char[][] mapData, boolean[] staticDeadlockMap, int width, int height, int[] targets) {
        List<State> successors = new ArrayList<>();
        int[] boxes = state.boxPositions;
        
        boolean[] reachable = getReachableTiles(state.actualPlayerPos, boxes, mapData, width, height);
        
        boolean[] hasBox = new boolean[width * height];
        for (int b : boxes) hasBox[b] = true;
        
        int[] rowDirs = {-1, 1, 0, 0};
        int[] colDirs = {0, 0, -1, 1};
        char[] pushChars = {'u', 'd', 'l', 'r'}; 

        for (int i = 0; i < boxes.length; i++) {
            int boxPos = boxes[i];
            int br = boxPos / width;
            int bc = boxPos % width;
            
            for (int d = 0; d < 4; d++) {
                int rDir = rowDirs[d];
                int cDir = colDirs[d];
                
                int pr = br - rDir; 
                int pc = bc - cDir;
                int nr = br + rDir; 
                int nc = bc + cDir;
                
                if (pr < 0 || pr >= height || pc < 0 || pc >= width) continue;
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                if (pc >= mapData[pr].length || nc >= mapData[nr].length) continue;
                
                int playerPushPos = pr * width + pc;
                int nextBoxPos = nr * width + nc;
                
                if (!reachable[playerPushPos]) continue;
                if (mapData[nr][nc] == '#' || hasBox[nextBoxPos]) continue;
                //if (staticDeadlockMap[nextBoxPos]) continue;
                //if (creates2x2Deadlock(nextBoxPos, boxes, boxPos, mapData, width, targets)) continue;
                
                String walkPath = findWalkPath(state.actualPlayerPos, playerPushPos, boxes, mapData, width, height);
                String fullMoveSequence = walkPath + pushChars[d];
                
                int[] nextBoxes = boxes.clone();
                nextBoxes[i] = nextBoxPos;
                
                boolean[] nextReachable = getReachableTiles(boxPos, nextBoxes, mapData, width, height);
                int nextNormalizedPlayer = getNormalizedPlayerPos(nextReachable);

                // REPLACE YOUR STATE CREATION AT THE BOTTOM WITH THIS:
                int nextActualPlayer = boxPos; // The player steps into the tile the box just left
                int nextHCost = getHeuristic(nextBoxes, targets, width);
                
                successors.add(new State(nextBoxes, nextActualPlayer, nextNormalizedPlayer, state.gCost + 1, nextHCost, state.path + fullMoveSequence));
            }
        }
        return successors;
    }

    private String findWalkPath(int start, int end, int[] boxPositions, char[][] mapData, int width, int height) {
        if (start == end) return "";
        
        boolean[] hasBox = new boolean[width * height];
        for (int b : boxPositions) hasBox[b] = true;
        
        Queue<Integer> queue = new LinkedList<>();
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, Character> moveChar = new HashMap<>();
        
        queue.add(start);
        parent.put(start, -1);
        
        int[] dr = {-1, 1, 0, 0};
        int[] dc = {0, 0, -1, 1};
        char[] dirs = {'u', 'd', 'l', 'r'};
        
        boolean found = false;
        while (!queue.isEmpty()) {
            int curr = queue.poll();
            if (curr == end) {
                found = true;
                break;
            }
            
            int cr = curr / width;
            int cc = curr % width;

            for (int i = 0; i < 4; i++) {
                int nr = cr + dr[i];
                int nc = cc + dc[i];
                
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                if (nc >= mapData[nr].length) continue;

                int next = nr * width + nc;
                if (mapData[nr][nc] == '#' || hasBox[next]) continue;
                
                if (!parent.containsKey(next)) {
                    parent.put(next, curr);
                    moveChar.put(next, dirs[i]);
                    queue.add(next);
                }
            }
        }
        
        if (!found) return "";
        
        StringBuilder sb = new StringBuilder();
        int curr = end;
        while (curr != start) {
            sb.append(moveChar.get(curr));
            curr = parent.get(curr);
        }
        return sb.reverse().toString();
    }

    private int getHeuristic(int[] boxPositions, int[] targets, int width) {
        int totalDist = 0;
        for (int box : boxPositions) {
            int br = box / width;
            int bc = box % width;
            int minDist = Integer.MAX_VALUE;
            for (int target : targets) {
                int tr = target / width;
                int tc = target % width;
                int dist = Math.abs(br - tr) + Math.abs(bc - tc);
                if (dist < minDist) minDist = dist;
            }
            if (minDist != Integer.MAX_VALUE) {
                totalDist += minDist;
            }
        }
        return totalDist;
    }

    private boolean creates2x2Deadlock(int newBoxPos, int[] currentBoxes, int oldBoxPos, char[][] mapData, int width, int[] targets) {
        Set<Integer> boxes = new HashSet<>();
        for (int b : currentBoxes) {
            if (b != oldBoxPos) boxes.add(b);
        }
        boxes.add(newBoxPos);
        
        int r = newBoxPos / width;
        int c = newBoxPos % width;
        
        int[][] checks = {{-1, -1}, {-1, 0}, {0, -1}, {0, 0}};
        for (int[] offset : checks) {
            int br = r + offset[0];
            int bc = c + offset[1];
            
            if (br >= 0 && bc >= 0) {
                // Pass the targets array into the is2x2AllTargets method here
                if (is2x2Filled(br, bc, boxes, mapData, width) && !is2x2AllTargets(br, bc, mapData, width, targets)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Fixed: Changed "return true;" to "continue;" so out-of-bounds acts like a wall without breaking the loop
    private boolean is2x2Filled(int r, int c, Set<Integer> boxes, char[][] mapData, int width) {
        int height = mapData.length;
        for (int dr = 0; dr < 2; dr++) {
            for (int dc = 0; dc < 2; dc++) {
                int nr = r + dr;
                int nc = c + dc;
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue; 
                if (nc >= mapData[nr].length) continue;
                if (mapData[nr][nc] == '#') continue;
                if (boxes.contains(nr * width + nc)) continue;
                return false;
            }
        }
        return true;
    }

    private boolean is2x2AllTargets(int r, int c, char[][] mapData, int width, int[] targets) {
        int height = mapData.length;
        for (int dr = 0; dr < 2; dr++) {
            for (int dc = 0; dc < 2; dc++) {
                int nr = r + dr;
                int nc = c + dc;
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) return false;
                if (nc >= mapData[nr].length) return false;
                
                int posId = nr * width + nc;
                
                // If the position ID isn't found in the targets array, it's not a target
                if (Arrays.binarySearch(targets, posId) < 0) {
                    return false; 
                }
            }
        }
        return true;
    }
}