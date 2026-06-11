package solver;

import java.util.*;

public class SokoBot {
  
    static class State implements Comparable<State> {
      int playerRow, playerCol;
      Set<String> boxPositions;
      int gCost; // Moves taken so far
      int hCost; // Heuristic cost estimated
      String path; // string path to be returned

      public State(int pRow, int pCol, Set<String> boxes, int gCost, int hCost, String path) {
        this.playerRow = pRow;
        this.playerCol = pCol;
        this.boxPositions = boxes;
        this.gCost = gCost;
        this.hCost = hCost;
        this.path = path;
      }

      public int getFCost() {
        return gCost + hCost;
      }

      @Override
      public int compareTo(State other) {
        return Integer.compare(this.getFCost(), other.getFCost());
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof State)) {
          return false;
        }
        State state = (State) o;
        return playerRow == state.playerRow &&  playerCol == state.playerCol && Objects.equals(boxPositions, state.boxPositions); 
      }

      @Override
      public int hashCode() {
        return Objects.hash(playerRow, playerCol, boxPositions);
      }
    }

    public String solveSokobanPuzzle(int width, int height, char[][] mapData, char[][] itemsData) {
      Set<String> targets = new HashSet<>();
      Set<String> startBoxes = new HashSet<>();
      int startPRow = -1, startPCol = -1;

      for (int r = 0; r < height; r++) {
          for (int c = 0; c < width; c++) {
              if (mapData[r][c] == '.') {
                  targets.add(r + "," + c);
              }
              if (itemsData[r][c] == '@') {
                  startPRow = r;
                  startPCol = c;
              } else if (itemsData[r][c] == '$') {
                  startBoxes.add(r + "," + c);
              }
          }
      }
      
      PriorityQueue<State> openList = new PriorityQueue<>();
      Set<State> visited = new HashSet<>();
      
      int initialH = calculateHeuristic(startBoxes, targets);
      State startState = new State(startPRow, startPCol, startBoxes, 0, initialH, "");
      openList.add(startState);

      while (!openList.isEmpty()) {
        State current = openList.poll();
        if (targets.equals(current.boxPositions)) {
          return current.path; 
        }

        if (visited.contains(current)) {
          continue;
        }
        visited.add(current);

        // directions are mapped directly to lowercase actions
        int[][] directions = {{-1, 0, 'u'}, {1, 0, 'd'}, {0, -1, 'l'}, {0, 1, 'r'}};

        for (int[] dir : directions) {
          int nextPRow = current.playerRow + dir[0];
          int nextPCol = current.playerCol + dir[1];
          char moveChar = (char) dir[2];

          if (nextPRow < 0 || nextPRow >= height || nextPCol < 0 || nextPCol >= width) {
            continue;
          }
          if (mapData[nextPRow][nextPCol] == '#') {
            continue;
          }

          String nextPosString = nextPRow + "," + nextPCol;
          Set<String> newBoxes = new HashSet<>(current.boxPositions);

          if (current.boxPositions.contains(nextPosString)) {
            int nextBRow = nextPRow + dir[0];
            int nextBCol = nextPCol + dir[1];
            String newBoxPosString = nextBRow + "," + nextBCol;

            if (nextBRow < 0 || nextBRow >= height || nextBCol < 0 || nextBCol >= width) continue;
            if (mapData[nextBRow][nextBCol] == '#') continue;
            if (current.boxPositions.contains(newBoxPosString)) continue;
            if (mapData[nextBRow][nextBCol] != '.' && isCornerDeadlock(nextBRow, nextBCol, mapData)) continue;
            newBoxes.remove(nextPosString);
            newBoxes.add(newBoxPosString);
          }
          int nextH = calculateHeuristic(newBoxes, targets);
          State neighbor = new State(nextPRow, nextPCol, newBoxes, current.gCost + 1, nextH, current.path + moveChar);
          if (!visited.contains(neighbor)) {
            openList.add(neighbor);
          }
        }
      }
      
      return ""; 
    }

    // Calculate distance/heuristic 
    public int calculateHeuristic(Set<String> boxPositions, Set<String> targets) {
        int totalDistance = 0;

        for (String box : boxPositions) {
            String[] bParts = box.split(","); //(1,2) 
            int br = Integer.parseInt(bParts[0]);
            int bc = Integer.parseInt(bParts[1]);
            
            int minBoxDist = -1; 
            for (String target : targets) {
                String[] tParts = target.split(","); //(1,4)
                int tr = Integer.parseInt(tParts[0]);
                int tc = Integer.parseInt(tParts[1]);
                
                int dist = Math.abs(br - tr) + Math.abs(bc - tc); // abs(1-1) + abs(2-4)
                minBoxDist = Math.min(minBoxDist, dist);
            }
            totalDistance += minBoxDist;
        }
        return totalDistance;
    }

    // deadlock checker
    private boolean isCornerDeadlock(int r, int c, char[][] mapData) {
      boolean wallUp = mapData[r - 1][c] == '#';
      boolean wallDown = mapData[r + 1][c] == '#';
      boolean wallLeft = mapData[r][c - 1] == '#';
      boolean wallRight = mapData[r][c + 1] == '#';  
      return (wallUp && wallLeft) || (wallUp && wallRight) || (wallDown && wallLeft) || (wallDown && wallRight);
    }
}
