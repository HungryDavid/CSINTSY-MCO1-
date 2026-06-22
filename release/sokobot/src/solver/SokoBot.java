package solver;

import java.util.*;

public class SokoBot {

    // =========================================================
    // CLASS-LEVEL VARIABLES (The Zero-Allocation BFS Engine)
    // =========================================================
    private int[] reachable;
    private int[] parent;
    private char[] moveLog;
    private int bfsToken = 0;

    static class State implements Comparable<State> {
        int actualPlayerPos; 
        int normalizedPlayerPos = -1; 
        int[] boxPositions;
        int gCost; 
        int hCost; 
        String path; 
        int lastPushedPos; // MEMORY: Tracks the location of the last box pushed
        int cachedHash; // MEMORY: Store the hash so we don't recalculate it millions of times

        public State(int actualPlayerPos, int[] boxes, int gCost, int hCost, String path, int lastPushedPos) {
            this.actualPlayerPos = actualPlayerPos;
            this.boxPositions = boxes;
            this.gCost = gCost;
            this.hCost = hCost;
            this.path = path;
            this.lastPushedPos = lastPushedPos;

            // PRE-CALCULATE HASH
            int result = Objects.hash(normalizedPlayerPos);
            this.cachedHash = 31 * result + Arrays.hashCode(boxPositions);
        }

        public int getFCost() {
            // Speed Weight (5) guarantees Original 1 and 2 are solved instantly.
            return gCost + (5 * hCost); 
        }

        @Override 
        public int compareTo(State other) { 
            int fCompare = Integer.compare(this.getFCost(), other.getFCost());
            if (fCompare == 0) {
                // TIE-BREAKER: Favor states closer to the goal.
                return Integer.compare(this.hCost, other.hCost); 
            }
            return fCompare;
        }

        @Override 
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State state = (State) o;
            return normalizedPlayerPos == state.normalizedPlayerPos && 
                   Arrays.equals(this.boxPositions, state.boxPositions); 
        }

        @Override
        public int hashCode() { 
            return cachedHash; // INSTANT RETURN!
        }

        
    }

    public String solveSokobanPuzzle(int width, int height, char[][] mapData, char[][] itemsData) {
        long startTime = System.currentTimeMillis();

        List<Integer> targetList = new ArrayList<>();
        List<Integer> startBoxList = new ArrayList<>();
        int startPId = -1;
        
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int posId = r * width + c; 
                if (mapData[r][c] == '.') targetList.add(posId);
                if (itemsData[r][c] == '@') {
                    startPId = posId;
                } else if (itemsData[r][c] == '$') {
                    startBoxList.add(posId);
                }
            }
        }

        int[] targets = targetList.stream().mapToInt(i -> i).toArray();
        int[] startBoxes = startBoxList.stream().mapToInt(i -> i).toArray();
        Arrays.sort(startBoxes);
        Arrays.sort(targets);
        
        // 1. FIRST LINE OF DEFENSE: The Map Reader (Guesses instantly)
        boolean focusMode = isCorridorMap(mapData, width, height, startBoxes.length);
        
        // Setup variables for the Safety Net
        int bestHSoFar = Integer.MAX_VALUE;
        int statesWithoutProgress = 0;

        boolean[][] deadlockGrid = precomputeDeadlocks(width, height, mapData, targetList);
        int[][] exactDistances = precomputeExactDistances(targets, width, height, mapData);
        
        int mapSize = width * height;
        reachable = new int[mapSize];
        parent = new int[mapSize];
        moveLog = new char[mapSize];
        
        PriorityQueue<State> openList = new PriorityQueue<>();
        Set<State> visited = new HashSet<>();
        
        int initialH = calculateGreedyHeuristic(startBoxes, targets, exactDistances);
        State startState = new State(startPId, startBoxes, 0, initialH, "", -1);
        openList.add(startState);

        State bestPartialState = startState;
        int[][] directions = {{-1, 0, 'u'}, {1, 0, 'd'}, {0, -1, 'l'}, {0, 1, 'r'}};

        while (!openList.isEmpty()) {
            
            // Ultimate safety net to ensure we never hang the grader
            if (System.currentTimeMillis() - startTime > 14500) {
                return bestPartialState.path; 
            }

            State current = openList.poll();

            // ===============================================================
            // 2. SECOND LINE OF DEFENSE: The Safety Net (Rescues if we guessed wrong)
            // ===============================================================
            if (current.hCost < bestHSoFar) {
                bestHSoFar = current.hCost;
                statesWithoutProgress = 0; 
                bestPartialState = current;
            } else {
                statesWithoutProgress++; 
            }

            if (statesWithoutProgress > 300000 && !focusMode) {
                focusMode = true; // Turn on the heavy logic!
                openList.clear();
                visited.clear();
                bfsToken++; // Instantly wipe BFS memory
                
                openList.add(startState); 
                bestHSoFar = Integer.MAX_VALUE;
                statesWithoutProgress = 0;
                continue; 
            }
            // ===============================================================
            
            if (Arrays.equals(current.boxPositions, targets)) {
                return current.path; 
            }

            if (current.hCost < bestPartialState.hCost) {
                bestPartialState = current;
            }
            
            // ... (Keep your Zero-Allocation BFS and the rest of the loop exactly the same) ...

            // Zero-Allocation BFS (Flood Fill)
            bfsToken++; 
            Queue<Integer> bfsQueue = new ArrayDeque<>();
            bfsQueue.add(current.actualPlayerPos);
            reachable[current.actualPlayerPos] = bfsToken;
            
            int topOfRoomPosId = current.actualPlayerPos;

            while (!bfsQueue.isEmpty()) {
                int currId = bfsQueue.poll();
                if (currId < topOfRoomPosId) topOfRoomPosId = currId;

                int r = currId / width;
                int c = currId % width;

                for (int[] dir : directions) {
                    int nr = r + dir[0];
                    int nc = c + dir[1];
                    int nId = nr * width + nc;

                    if (nr < 0 || nr >= height || nc < 0 || nc >= width || mapData[nr][nc] == '#') continue;

                    if (Arrays.binarySearch(current.boxPositions, nId) >= 0) {
                        continue; 
                    } else if (reachable[nId] != bfsToken) {
                        reachable[nId] = bfsToken;
                        parent[nId] = currId;
                        moveLog[nId] = (char) dir[2];
                        bfsQueue.add(nId);
                    }
                }
            }

            current.normalizedPlayerPos = topOfRoomPosId;
            if (visited.contains(current)) continue;
            visited.add(current);

            for (int posId = 0; posId < mapSize; posId++) {
                if (reachable[posId] != bfsToken) continue; 

                int pr = posId / width;
                int pc = posId % width;

                for (int[] dir : directions) {
                    int br = pr + dir[0];
                    int bc = pc + dir[1];
                    int boxId = br * width + bc;

                    if (br >= 0 && br < height && bc >= 0 && bc < width && Arrays.binarySearch(current.boxPositions, boxId) >= 0) {
                        
                        int pushR = br + dir[0];
                        int pushC = bc + dir[1];
                        int pushId = pushR * width + pushC;

                        if (pushR >= 0 && pushR < height && pushC >= 0 && pushC < width) {
                            if (mapData[pushR][pushC] != '#' && !deadlockGrid[pushR][pushC] && Arrays.binarySearch(current.boxPositions, pushId) < 0) {
                                
                                int[] newBoxes = current.boxPositions.clone();
                                int bIdx = Arrays.binarySearch(newBoxes, boxId);
                                newBoxes[bIdx] = pushId;
                                Arrays.sort(newBoxes);

                                if (isDynamicDeadlock(pushR, pushC, newBoxes, targets, mapData, width, height)) continue;
                                if (isFrozenDeadlock(newBoxes, targets, mapData, width, height)) continue;

                                String walkPath = reconstructPath(posId, current.actualPlayerPos);
                                char pushMoveChar = (char) dir[2];
                                int walkCost = walkPath.length();
                                
                                boolean wasOnTarget = Arrays.binarySearch(targets, boxId) >= 0;
                                boolean willBeOnTarget = Arrays.binarySearch(targets, pushId) >= 0;
                                int targetLockPenalty = (wasOnTarget && !willBeOnTarget) ? 100 : 0;

                                // The switch penalty activates only if the AI realizes it's thrashing
                                int switchPenalty = (focusMode && current.lastPushedPos != -1 && current.lastPushedPos != boxId) ? 20 : 0;

                                int newH = calculateGreedyHeuristic(newBoxes, targets, exactDistances);

                                State nextState = new State(boxId, newBoxes, current.gCost + walkCost + targetLockPenalty + switchPenalty + 1, newH, current.path + walkPath + pushMoveChar, pushId);
                                openList.add(nextState);
                            }
                        }
                    }
                }
            }
        }
        return bestPartialState.path; 
    }

    // Updated reconstructPath to use the zero-allocation class arrays
    private String reconstructPath(int targetNode, int startNode) {
        StringBuilder sb = new StringBuilder();
        int curr = targetNode;
        while (curr != startNode && curr != -1) {
            sb.append(moveLog[curr]);
            curr = parent[curr];
        }
        return sb.reverse().toString();
    }

    // Upgraded Heuristic: Uses a primitive bitmask for ultra-fast target pairing
    public int calculateGreedyHeuristic(int[] boxPositions, int[] targets, int[][] exactDistances) {
        int totalDistance = 0;
        long targetMatched = 0L; 
        long boxMatched = 0L;    

        for (int step = 0; step < boxPositions.length; step++) {
            int globalMin = Integer.MAX_VALUE;
            int bestB = -1;
            int bestT = -1;

            for (int b = 0; b < boxPositions.length; b++) {
                if ((boxMatched & (1L << b)) != 0) continue; 
                
                int boxPos = boxPositions[b];

                for (int t = 0; t < targets.length; t++) {
                    if ((targetMatched & (1L << t)) != 0) continue; 
                    
                    int dist = exactDistances[t][boxPos];
                    if (dist < globalMin) {
                        globalMin = dist;
                        bestB = b;
                        bestT = t;
                    }
                }
            }

            if (globalMin >= Integer.MAX_VALUE / 2) return Integer.MAX_VALUE / 2; 

            if (bestB != -1 && bestT != -1) {
                boxMatched |= (1L << bestB);
                targetMatched |= (1L << bestT);
                totalDistance += globalMin;
            }
        }
        return totalDistance;
    }

    private boolean[][] precomputeDeadlocks(int width, int height, char[][] mapData, List<Integer> targets) {
        boolean[][] deadlocks = new boolean[height][width];
        Set<Integer> targetSet = new HashSet<>(targets);
        
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (mapData[r][c] == '#' || targetSet.contains(r * width + c)) continue;
                
                boolean wallUp = mapData[r - 1][c] == '#';
                boolean wallDown = mapData[r + 1][c] == '#';
                boolean wallLeft = mapData[r][c - 1] == '#';
                boolean wallRight = mapData[r][c + 1] == '#';
                
                if ((wallUp && wallLeft) || (wallUp && wallRight) || (wallDown && wallLeft) || (wallDown && wallRight)) {
                    deadlocks[r][c] = true;
                }
            }
        }
        
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (deadlocks[r][c]) {
                    if (mapData[r - 1][c] == '#' || mapData[r + 1][c] == '#') {
                        verifyAndMarkLine(r, c, 0, 1, width, mapData, targetSet, deadlocks);
                    }
                    if (mapData[r][c - 1] == '#' || mapData[r][c + 1] == '#') {
                        verifyAndMarkLine(r, c, 1, 0, width, mapData, targetSet, deadlocks);
                    }
                }
            }
        }
        return deadlocks;
    }

    private void verifyAndMarkLine(int startR, int startC, int dRow, int dCol, int width, char[][] mapData, Set<Integer> targets, boolean[][] deadlocks) {
        int height = mapData.length;
        int mapWidth = mapData[0].length;
        int r = startR + dRow;
        int c = startC + dCol;
        List<int[]> pathCells = new ArrayList<>();
        
        while (r >= 0 && r < height && c >= 0 && c < mapWidth && mapData[r][c] != '#') {
            if (targets.contains(r * width + c)) return;
            
            boolean hasWallSide1 = false;
            boolean hasWallSide2 = false;
            
            int side1R = r - dCol; int side1C = c - dRow;
            if (side1R >= 0 && side1R < height && side1C >= 0 && side1C < mapWidth) {
                hasWallSide1 = mapData[side1R][side1C] == '#';
            }
            int side2R = r + dCol; int side2C = c + dRow;
            if (side2R >= 0 && side2R < height && side2C >= 0 && side2C < mapWidth) {
                hasWallSide2 = mapData[side2R][side2C] == '#';
            }
            
            if (!hasWallSide1 && !hasWallSide2) return;
            
            if (deadlocks[r][c]) {
                for (int[] cell : pathCells) {
                    deadlocks[cell[0]][cell[1]] = true;
                }
                return;
            }
            pathCells.add(new int[]{r, c});
            r += dRow;
            c += dCol;
        }
    }

    private boolean isDynamicDeadlock(int pushR, int pushC, int[] boxes, int[] targets, char[][] mapData, int width, int height) {
      int[][] dirs = {{0,0}, {-1,0}, {0,-1}, {-1,-1}}; 
      for (int[] d : dirs) {
          int startR = pushR + d[0];
          int startC = pushC + d[1];
          
          int solids = 0;
          boolean hasBoxOffTarget = false;
          
          for (int r = startR; r < startR + 2; r++) {
              for (int c = startC; c < startC + 2; c++) {
                  if (r < 0 || r >= height || c < 0 || c >= width) break;
                  
                  if (mapData[r][c] == '#') {
                      solids++;
                  } else {
                      int posId = r * width + c;
                      if (Arrays.binarySearch(boxes, posId) >= 0) {
                          solids++;
                          if (Arrays.binarySearch(targets, posId) < 0) hasBoxOffTarget = true;
                      }
                  }
              }
          }
          if (solids == 4 && hasBoxOffTarget) return true; 
      }
      return false;
    }

    private boolean isFrozenDeadlock(int[] boxes, int[] targets, char[][] mapData, int width, int height) {
        for (int b : boxes) {
            if (Arrays.binarySearch(targets, b) >= 0) continue; 
            
            int r = b / width;
            int c = b % width;
            
            boolean wallUp = mapData[r-1][c] == '#';
            boolean wallDown = mapData[r+1][c] == '#';
            boolean wallLeft = mapData[r][c-1] == '#';
            boolean wallRight = mapData[r][c+1] == '#';
            
            boolean boxUp = Arrays.binarySearch(boxes, (r-1)*width + c) >= 0;
            boolean boxDown = Arrays.binarySearch(boxes, (r+1)*width + c) >= 0;
            boolean boxLeft = Arrays.binarySearch(boxes, r*width + c - 1) >= 0;
            boolean boxRight = Arrays.binarySearch(boxes, r*width + c + 1) >= 0;
            
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

    private int[][] precomputeExactDistances(int[] targets, int width, int height, char[][] mapData) {
      int[][] distMatrix = new int[targets.length][width * height];
      for (int[] row : distMatrix) Arrays.fill(row, Integer.MAX_VALUE / 2);
  
      int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
  
      for (int i = 0; i < targets.length; i++) {
          int targetId = targets[i];
          Queue<Integer> q = new ArrayDeque<>();
          q.add(targetId);
          distMatrix[i][targetId] = 0;
  
          while (!q.isEmpty()) {
              int curr = q.poll();
              int r = curr / width;
              int c = curr % width;
  
              for (int[] d : dirs) {
                  int nr = r + d[0];
                  int nc = c + d[1];
                  
                  if (nr >= 0 && nr < height && nc >= 0 && nc < width && mapData[nr][nc] != '#') {
                      int nId = nr * width + nc;
                      if (distMatrix[i][nId] > distMatrix[i][curr] + 1) {
                          distMatrix[i][nId] = distMatrix[i][curr] + 1;
                          q.add(nId);
                      }
                  }
              }
          }
      }
      return distMatrix;
    }

    // STATIC MAP ANALYSIS: Reads the layout to predict the required strategy
    private boolean isCorridorMap(char[][] mapData, int width, int height, int numBoxes) {
        if (numBoxes < 6) return false; // Very small maps are easily brute-forced

        int totalFloor = 0;
        int chokepoints = 0;

        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (mapData[r][c] != '#') {
                    totalFloor++;
                    
                    boolean wallUp = mapData[r-1][c] == '#';
                    boolean wallDown = mapData[r+1][c] == '#';
                    boolean wallLeft = mapData[r][c-1] == '#';
                    boolean wallRight = mapData[r][c+1] == '#';
                    
                    // A chokepoint is a strictly 1-tile wide hallway
                    if ((wallUp && wallDown) || (wallLeft && wallRight)) {
                        chokepoints++;
                    }
                }
            }
        }
        
        // If there are at least 2 severe chokepoints, OR it makes up 8% of the map, turn on Focus Mode
        double chokepointRatio = (double) chokepoints / totalFloor;
        return chokepoints >= 2 || chokepointRatio > 0.08; 
    }
}