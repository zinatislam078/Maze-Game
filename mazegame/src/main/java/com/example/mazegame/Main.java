package com.example.mazegame;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.*;

public class Main extends Application {

    // --- Game settings ---
    private static final int CELL_SIZE = 28; // pixel size per cell
    private int cols = 21; // must be odd for nice mazes
    private int rows = 21; // must be odd
    private MazeGenerator.Pattern chosenPattern = MazeGenerator.Pattern.RECURSIVE_BACKTRACKER;

    // --- Game state ---
    private Maze maze;
    private Player player;
    private List<Enemy> enemies = new ArrayList<>();
    private List<Collectible> collectibles = new ArrayList<>();
    private boolean up, down, left, right;
    private int score = 0;
    private long startTime;
    private boolean gameOver = false;
    private boolean levelComplete = false;
    private Canvas canvas;
    private int level = 1;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        // Let the player choose a pattern
        MazeGenerator.Pattern pattern = choosePatternDialog();
        if (pattern != null) chosenPattern = pattern;

        setupGame();

        BorderPane root = new BorderPane();
        canvas = new Canvas(cols * CELL_SIZE, rows * CELL_SIZE + 60);
        root.setCenter(canvas);

        Scene scene = new Scene(root);
        setupInput(scene);

        primaryStage.setTitle("Eco Maze — Level " + level + " (" + chosenPattern + ")");
        primaryStage.setScene(scene);
        primaryStage.show();

        startTime = System.currentTimeMillis();
        AnimationTimer timer = new AnimationTimer() {
            private long last = 0;
            @Override
            public void handle(long now) {
                if (last == 0) last = now;
                double dt = (now - last) / 1e9;
                update(dt);
                render();
                last = now;
            }
        };
        timer.start();
    }

    private MazeGenerator.Pattern choosePatternDialog() {
        List<MazeGenerator.Pattern> choices = Arrays.asList(MazeGenerator.Pattern.values());
        ChoiceDialog<MazeGenerator.Pattern> dialog = new ChoiceDialog<>(choices.get(0), choices);
        dialog.setTitle("Choose Maze Pattern");
        dialog.setHeaderText("Select a maze generation pattern:");
        dialog.setContentText("Pattern:");
        Optional<MazeGenerator.Pattern> result = dialog.showAndWait();
        return result.orElse(null);
    }

    private void setupGame() {
        // create maze
        maze = new Maze(cols, rows);
        MazeGenerator.generate(maze, chosenPattern);

        // place player in top-left open cell (first pass)
        player = new Player(maze.getStartX(), maze.getStartY());

        // spawn collectibles
        collectibles.clear();
        spawnCollectibles(maze, Math.max(5, (cols * rows) / 40));

        // spawn a few enemies
        enemies.clear();
        spawnEnemies(maze, Math.max(1, level / 1));

        score = 0;
        gameOver = false;
        levelComplete = false;
    }

    private void spawnCollectibles(Maze m, int count) {
        Random rnd = new Random();
        int attempts = 0;
        while (collectibles.size() < count && attempts < count * 30) {
            attempts++;
            int cx = rnd.nextInt(m.cols);
            int cy = rnd.nextInt(m.rows);
            if (!m.isWall(cx, cy) && (cx != player.x || cy != player.y)) {
                // 70% eco (good), 30% trash (bad)
                boolean eco = rnd.nextDouble() < 0.7;
                collectibles.add(new Collectible(cx, cy, eco));
            }
        }
    }

    private void spawnEnemies(Maze m, int count) {
        Random rnd = new Random();
        int attempts = 0;
        while (enemies.size() < count && attempts < count * 30) {
            attempts++;
            int ex = rnd.nextInt(m.cols);
            int ey = rnd.nextInt(m.rows);
            if (!m.isWall(ex, ey) && (ex != player.x || ey != player.y)) {
                enemies.add(new Enemy(ex, ey));
            }
        }
    }

    private void setupInput(Scene scene) {
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.W) up = true;
            if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.S) down = true;
            if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.A) left = true;
            if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.D) right = true;
            if (e.getCode() == KeyCode.R) {
                level = 1;
                setupGame();
            }
            if (e.getCode() == KeyCode.N && levelComplete) {
                level++;
                // increase maze size slowly
                cols = Math.min(41, cols + 2);
                rows = Math.min(41, rows + 2);
                maze = new Maze(cols, rows);
                MazeGenerator.generate(maze, chosenPattern);
                canvas.setWidth(cols * CELL_SIZE);
                canvas.setHeight(rows * CELL_SIZE + 60);
                setupGame();
            }
        });
        scene.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.W) up = false;
            if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.S) down = false;
            if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.A) left = false;
            if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.D) right = false;
        });
    }

    private void update(double dt) {
        if (gameOver) return;
        // player movement (grid based, smoothish)
        int dx = 0, dy = 0;
        if (up) dy = -1;
        if (down) dy = 1;
        if (left) dx = -1;
        if (right) dx = 1;
        if (dx != 0 && dy != 0) { dx = 0; } // no diagonal

        if (dx != 0 || dy != 0) {
            int nx = player.x + dx;
            int ny = player.y + dy;
            if (!maze.isWall(nx, ny)) {
                player.x = nx;
                player.y = ny;
                // collect items
                collectAt(nx, ny);
                // check exit
                if (maze.isExit(nx, ny)) {
                    levelComplete = true;
                }
            }
        }

        // enemies move (simple chase or patrol)
        for (Enemy e : enemies) {
            e.update(dt, maze, player);
            // collision with player
            if (e.x == player.x && e.y == player.y) {
                gameOver = true;
            }
        }

        // win condition: collect all eco items (only eco count)
        boolean anyEcoLeft = collectibles.stream().anyMatch(c -> c.eco);
        if (!anyEcoLeft) {
            // Optionally open exit
            maze.openExit();
        }
    }

    private void collectAt(int x, int y) {
        Iterator<Collectible> it = collectibles.iterator();
        while (it.hasNext()) {
            Collectible c = it.next();
            if (c.x == x && c.y == y) {
                if (c.eco) score += 10;
                else score -= 5;
                it.remove();
            }
        }
    }

    private void render() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        // background
        g.setFill(Color.web("#dff3e3"));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // draw maze
        for (int y = 0; y < maze.rows; y++) {
            for (int x = 0; x < maze.cols; x++) {
                double px = x * CELL_SIZE;
                double py = y * CELL_SIZE;
                if (maze.isWall(x, y)) {
                    g.setFill(Color.web("#2e2e2e"));
                    g.fillRect(px, py, CELL_SIZE, CELL_SIZE);
                } else {
                    g.setFill(Color.web("#f7fff7"));
                    g.fillRect(px, py, CELL_SIZE, CELL_SIZE);
                }
            }
        }

        // draw exit
        int ex = maze.exitX, ey = maze.exitY;
        g.setFill(Color.web("#ffec99"));
        g.fillOval(ex * CELL_SIZE + 6, ey * CELL_SIZE + 6, CELL_SIZE - 12, CELL_SIZE - 12);

        // draw collectibles
        for (Collectible c : collectibles) {
            double px = c.x * CELL_SIZE;
            double py = c.y * CELL_SIZE;
            if (c.eco) {
                // leaf-like shape
                g.setFill(Color.web("#2e8b57"));
                g.fillOval(px + 6, py + 6, CELL_SIZE - 12, CELL_SIZE - 12);
                g.setFill(Color.web("#0b6623"));
                g.fillOval(px + 8, py + 8, CELL_SIZE - 16, CELL_SIZE - 16);
            } else {
                // trash
                g.setFill(Color.web("#a9a9a9"));
                g.fillRect(px + 8, py + 8, CELL_SIZE - 16, CELL_SIZE - 16);
            }
        }

        // draw enemies
        for (Enemy e : enemies) {
            double px = e.x * CELL_SIZE;
            double py = e.y * CELL_SIZE;
            g.setFill(Color.web("#ff6b6b"));
            g.fillOval(px + 4, py + 4, CELL_SIZE - 8, CELL_SIZE - 8);
            g.setFill(Color.BLACK);
            g.fillOval(px + CELL_SIZE/2 - 3, py + CELL_SIZE/2 - 3, 6, 6);
        }

        // draw player
        double px = player.x * CELL_SIZE;
        double py = player.y * CELL_SIZE;
        g.setFill(Color.web("#1b9aaa"));
        g.fillOval(px + 4, py + 4, CELL_SIZE - 8, CELL_SIZE - 8);
        g.setFill(Color.WHITE);
        g.fillOval(px + CELL_SIZE/2 - 4, py + CELL_SIZE/2 - 6, 8, 8);

        // HUD
        g.setFill(Color.web("#0b3d2e"));
        g.setFont(Font.font(16));
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        g.fillText("Level: " + level + "   Score: " + score + "   Time: " + elapsed + "s   Items left: " + collectibles.size(), 10, rows * CELL_SIZE + 24);

        if (gameOver) {
            g.setFill(new Color(0,0,0,0.6));
            g.fillRect(0, rows * CELL_SIZE / 4, cols * CELL_SIZE, rows * CELL_SIZE / 2);
            g.setFill(Color.WHITE);
            g.setFont(Font.font(36));
            g.fillText("Game Over! Press R to restart", 30, rows * CELL_SIZE / 2);
        } else if (levelComplete) {
            g.setFill(new Color(0,0,0,0.6));
            g.fillRect(0, rows * CELL_SIZE / 4, cols * CELL_SIZE, rows * CELL_SIZE / 2);
            g.setFill(Color.WHITE);
            g.setFont(Font.font(32));
            g.fillText("Level Complete! Press N for next level", 20, rows * CELL_SIZE / 2);
        }
    }

    // ------------------ Maze representation ------------------
    static class Maze {
        final int cols, rows;
        final boolean[][] wall;
        int startX, startY;
        int exitX, exitY;
        boolean exitOpen = false;

        Maze(int cols, int rows) {
            this.cols = cols;
            this.rows = rows;
            wall = new boolean[cols][rows];
            // fill walls by default
            for (int x = 0; x < cols; x++) {
                Arrays.fill(wall[x], true);
            }
            // default start top-left, exit bottom-right
            startX = 1; startY = 1;
            exitX = cols - 2; exitY = rows - 2;
        }

        boolean isWall(int x, int y) {
            if (x < 0 || y < 0 || x >= cols || y >= rows) return true;
            return wall[x][y];
        }

        void setOpen(int x, int y) {
            if (x < 0 || y < 0 || x >= cols || y >= rows) return;
            wall[x][y] = false;
        }

        boolean isExit(int x, int y) {
            return x == exitX && y == exitY && exitOpen;
        }

        void openExit() {
            exitOpen = true;
            setOpen(exitX, exitY);
        }

        int getStartX() { return startX; }
        int getStartY() { return startY; }
    }

    // ------------------ Maze generator (multiple patterns) ------------------
    static class MazeGenerator {

        enum Pattern { RECURSIVE_BACKTRACKER, PRIM_LIKE, BINARY_TREE }

        static void generate(Maze m, Pattern pattern) {
            switch (pattern) {
                case RECURSIVE_BACKTRACKER: backtracker(m); break;
                case PRIM_LIKE: primLike(m); break;
                case BINARY_TREE: binaryTree(m); break;
                default: backtracker(m);
            }
            // ensure start/exit open
            m.setOpen(m.startX, m.startY);
            m.setOpen(m.exitX, m.exitY);
        }

        // Recursive backtracker (depth-first)
        private static void backtracker(Maze m) {
            boolean[][] visited = new boolean[m.cols][m.rows];
            Random rnd = new Random();
            int sx = m.startX, sy = m.startY;
            Deque<int[]> stack = new ArrayDeque<>();
            visited[sx][sy] = true;
            m.setOpen(sx, sy);
            stack.push(new int[]{sx, sy});
            while (!stack.isEmpty()) {
                int[] cur = stack.peek();
                int x = cur[0], y = cur[1];
                List<int[]> neighbors = new ArrayList<>();
                int[][] dirs = {{2,0},{-2,0},{0,2},{0,-2}};
                for (int[] d : dirs) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx > 0 && ny > 0 && nx < m.cols-1 && ny < m.rows-1 && !visited[nx][ny]) {
                        neighbors.add(new int[]{nx, ny, d[0], d[1]});
                    }
                }
                if (neighbors.isEmpty()) {
                    stack.pop();
                } else {
                    int[] sel = neighbors.get(rnd.nextInt(neighbors.size()));
                    int nx = sel[0], ny = sel[1];
                    int bx = x + sel[2]/2, by = y + sel[3]/2; // break wall between
                    m.setOpen(bx, by);
                    m.setOpen(nx, ny);
                    visited[nx][ny] = true;
                    stack.push(new int[]{nx, ny});
                }
            }
        }

        // Prim-like randomized
        private static void primLike(Maze m) {
            Random rnd = new Random();
            boolean[][] inMaze = new boolean[m.cols][m.rows];
            List<int[]> frontier = new ArrayList<>();
            int sx = m.startX, sy = m.startY;
            inMaze[sx][sy] = true;
            m.setOpen(sx, sy);
            addFrontier(sx, sy, m, frontier);
            while (!frontier.isEmpty()) {
                int idx = rnd.nextInt(frontier.size());
                int[] cell = frontier.remove(idx);
                int x = cell[0], y = cell[1];
                // find neighbors in maze
                List<int[]> neighborsIn = new ArrayList<>();
                int[][] dirs = {{2,0},{-2,0},{0,2},{0,-2}};
                for (int[] d : dirs) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx > 0 && ny > 0 && nx < m.cols-1 && ny < m.rows-1 && inMaze[nx][ny]) {
                        neighborsIn.add(new int[]{nx, ny, d[0], d[1]});
                    }
                }
                if (!neighborsIn.isEmpty()) {
                    int[] n = neighborsIn.get(rnd.nextInt(neighborsIn.size()));
                    int bx = x - n[2]/2, by = y - n[3]/2;
                    m.setOpen(bx, by);
                    m.setOpen(x, y);
                    inMaze[x][y] = true;
                    addFrontier(x, y, m, frontier);
                }
            }
        }

        private static void addFrontier(int x, int y, Maze m, List<int[]> frontier) {
            int[][] dirs = {{2,0},{-2,0},{0,2},{0,-2}};
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1];
                if (nx > 0 && ny > 0 && nx < m.cols-1 && ny < m.rows-1) {
                    boolean already = false;
                    for (int[] f : frontier) {
                        if (f[0] == nx && f[1] == ny) { already = true; break; }
                    }
                    if (!already) frontier.add(new int[]{nx, ny});
                }
            }
        }

        // Binary tree: simple, biased corridors
        private static void binaryTree(Maze m) {
            for (int y = 1; y < m.rows - 1; y += 2) {
                for (int x = 1; x < m.cols - 1; x += 2) {
                    m.setOpen(x, y);
                    // randomly carve east or south
                    boolean carveEast = (Math.random() < 0.5);
                    if (x + 1 < m.cols - 1 && carveEast) {
                        m.setOpen(x + 1, y);
                    } else if (y + 1 < m.rows - 1) {
                        m.setOpen(x, y + 1);
                    }
                }
            }
        }
    }

    // ------------------ Player ------------------
    static class Player {
        int x, y;
        Player(int x, int y) { this.x = x; this.y = y; }
    }

    // ------------------ Collectible ------------------
    static class Collectible {
        int x, y;
        boolean eco; // good or bad
        Collectible(int x, int y, boolean eco) { this.x = x; this.y = y; this.eco = eco; }
    }

    // ------------------ Enemy (simple) ------------------
    static class Enemy {
        int x, y;
        private double cooldown = 0;

        Enemy(int x, int y) {
            this.x = x; this.y = y;
        }

        void update(double dt, Maze maze, Player player) {
            cooldown -= dt;
            if (cooldown <= 0) {
                cooldown = 0.25 + Math.random() * 0.35; // move every ~0.25-0.6s
                // basic chase: move towards player if path open else random neighbor
                int dx = Integer.compare(player.x, x);
                int dy = Integer.compare(player.y, y);
                // try horizontal then vertical
                if (dx != 0 && !maze.isWall(x + dx, y)) {
                    x += dx;
                } else if (dy != 0 && !maze.isWall(x, y + dy)) {
                    y += dy;
                } else {
                    // random available direction
                    List<int[]> avail = new ArrayList<>();
                    int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
                    for (int[] d : dirs) {
                        if (!maze.isWall(x + d[0], y + d[1])) avail.add(d);
                    }
                    if (!avail.isEmpty()) {
                        int[] d = avail.get(new Random().nextInt(avail.size()));
                        x += d[0]; y += d[1];
                    }
                }
            }
        }
    }
}
