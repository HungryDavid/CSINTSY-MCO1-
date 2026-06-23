package solver;

import java.util.*;

public class SokoBot {

    // 1. Define Your State Representation
    // This inner class keeps track of where the player and crates are in a specific timeline.
    class GameState {
        int playerR, playerC;
        List<Integer> cratePositions; // We store positions as a single number (row * width + col) to save memory
        String path; // The moves taken to get here (e.g., "lull")

        public GameState(int pr, int pc, List<Integer> crates, String path) {
            this.playerR = pr;
            this.playerC = pc;
            this.cratePositions = new ArrayList<>(crates);
            Collections.sort(this.cratePositions); // Sort so identical crate layouts match perfectly
            this.path = path;
        }

        // Creates a unique string for the Visited Memory to prevent infinite loops
        public String getHash() {
            return playerR + "," + playerC + "-" + cratePositions.toString();
        }
    }

    public String solveSokobanPuzzle(int width, int height, char[][] mapData, char[][] itemsData) {
        long startTime = System.currentTimeMillis();

        // Step A: Find the starting positions of the player and all crates
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

        // Step B: Initialize the Queue and the Visited Memory
        Queue<GameState> queue = new LinkedList<>();
        HashSet<String> visited = new HashSet<>();

        GameState initialState = new GameState(startPr, startPc, startCrates, "");
        queue.add(initialState);
        visited.add(initialState.getHash());

        // Directional mapping: Up, Down, Left, Right [cite: 102]
        int[] dr = {-1, 1, 0, 0}; // Row changes
        int[] dc = {0, 0, -1, 1}; // Col changes
        char[] dirChars = {'u', 'd', 'l', 'r'};

        // Step C: The Execution Loop
        while (!queue.isEmpty()) {
            
            // Safety switch: Break out if we approach the 15-second limit (using 14s to be safe) 
            if (System.currentTimeMillis() - startTime > 14000) {
                System.out.println("Time limit reached! Returning best effort so far.");
                return queue.peek().path; 
            }

            GameState curr = queue.poll();

            // 1. Check if solved (Are all crates standing on a '.' in mapData?)
            boolean solved = true;
            for (int cratePos : curr.cratePositions) {
                int cr = cratePos / width;
                int cc = cratePos % width;
                if (mapData[cr][cc] != '.') {
                    solved = false; // Found a crate not on a target
                    break;
                }
            }
            if (solved) {
                return curr.path; // WE WON! Return the sequence of moves.
            }

            // 2. Generate the next valid moves
            for (int i = 0; i < 4; i++) {
                int nextPr = curr.playerR + dr[i];
                int nextPc = curr.playerC + dc[i];

                // Rule 2: Player cannot walk through walls
                if (mapData[nextPr][nextPc] == '#') continue;

                int nextPlayerPos = nextPr * width + nextPc;
                List<Integer> nextCrates = new ArrayList<>(curr.cratePositions);
                boolean validMove = true;

                // Rule 3: Check if we are trying to push a crate
                if (nextCrates.contains(nextPlayerPos)) {
                    int pushR = nextPr + dr[i];
                    int pushC = nextPc + dc[i];
                    int pushPos = pushR * width + pushC;

                    // A crate cannot be pushed into a wall or another crate
                    if (mapData[pushR][pushC] == '#' || nextCrates.contains(pushPos)) {
                        validMove = false;
                    } else {
                        // Move the crate in our temporary memory
                        nextCrates.remove(Integer.valueOf(nextPlayerPos));
                        nextCrates.add(pushPos);
                    }
                }

                // 3. If the move is valid and hasn't been seen before, add it to the Queue
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

        // If the queue empties and we haven't returned a path, the puzzle is impossible.
        return ""; 
    }
}