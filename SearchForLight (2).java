import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * SwiftBot Autonomous Light-Seeking Program
 * ==========================================
 * Simulates a SwiftBot that autonomously navigates toward a light source
 * using camera-based image processing, obstacle avoidance, and logging.
 *
 * NOTE: Since real SwiftBot hardware APIs are unavailable in a simulation,
 * all hardware interactions (camera, sensors, motors, underlights) are
 * abstracted through simulation methods. Replace these with actual SwiftBot
 * SDK calls when deploying to hardware.
 */
public class SearchForLight { //Declares main class

    // ─── Constants ────────────────────────────────────────────────────────────
    private static final double LOW_SPEED_CM_PER_S   = 10.0;   // cm/s at low speed, robot always moves at 10cm per second
    private static final double MOVE_DURATION_S       = 1.0;    // seconds per move, each movement lasts exactly 1 second
    private static final double TURN_DEGREES          = 30.0;   // degrees for left/right turn, when turning left or right, the robot rotates 30 degrees before moving forward
    private static final double OBSTACLE_THRESHOLD_CM = 50.0;   // cm - obstacle detection range, if an object is closer than 50cm, its treated as an obstacle
    private static final int    MAX_OBJECTS_BEFORE_WARN = 5;    // object count trigger, after detecting 5 obstacles, the program warns the user
    private static final double WARN_TIME_WINDOW_S    = 300.0;  // 5 minutes in seconds, this warning triggers if those 5 obstacles were hit within 5 minutes(300 seconds0
    private static final String LOG_FILE_PATH         = "swiftbot_log.txt";
    private static final String IMAGE_SAVE_DIR        = "captured_images/";   // where to save the log file and obstacle images on disk

    // ─── Direction enum ────────────────────────────────────────────────────────
    enum Direction { LEFT, CENTRE, RIGHT, RANDOM }  

    // ─── State ─────────────────────────────────────────────────────────────────
    private double   thresholdIntensity    = 0.0;
    private double   brightestIntensity    = 0.0;
    private int      lightDetectionCount   = 0;
    private double   totalDistanceCm       = 0.0;
    private int      objectsDetected       = 0;
    private LocalDateTime startTime;
    private LocalDateTime firstObjectTime  = null;

    private final List<double[]>    lightReadings   = new ArrayList<>();  // {intensity, timestamp_s}
    private final List<String>      movementLog     = new ArrayList<>();
    private final List<String>      savedImagePaths = new ArrayList<>();
    private final Random            rng             = new Random();
    private final Scanner           scanner         = new Scanner(System.in);

    // ─── Entry Point ───────────────────────────────────────────────────────────
    public static void main(String[] args) {
        new SwiftBot().run();
    }

    // ─── Main Run Loop ─────────────────────────────────────────────────────────
    public void run() {
        printBanner();
        waitForButtonA();

        startTime = LocalDateTime.now();
        System.out.println("\n[SYSTEM] Program started at: " + formatTime(startTime));

        // Create image directory
        new File(IMAGE_SAVE_DIR).mkdirs();

        // Capture initial image and set threshold
        double[][] initialImage = captureImage();
        thresholdIntensity = computeOverallAverage(initialImage);
        System.out.printf("[SYSTEM] Environment threshold light intensity set to: %.2f%n", thresholdIntensity);

        // Main light-seeking loop
        boolean running = true;
        while (running) {

            // Check for termination command (non-blocking prompt check)
            if (isTerminationRequested(false)) {
                running = false;
                break;
            }

            // Check if 5 objects detected in < 5 minutes
            if (shouldPromptTermination()) {
                System.out.println("\n[WARNING] 5 objects detected in less than 5 minutes.");
                System.out.println("[SYSTEM] Enter 'TERMINATE' to stop the program, or press Enter to continue:");
                if (isTerminationRequested(true)) {
                    running = false;
                    break;
                }
            }

            // ── Light-seeking cycle ──
            double[][] image   = captureImage();
            double avgLeft     = computeColumnAverage(image, Direction.LEFT);
            double avgCentre   = computeColumnAverage(image, Direction.CENTRE);
            double avgRight    = computeColumnAverage(image, Direction.RIGHT);

            // Display intensities
            System.out.println("\n──────────────────────────────────────────");
            System.out.printf(" Left Intensity  : %.2f%n", avgLeft);
            System.out.printf(" Centre Intensity: %.2f%n", avgCentre);
            System.out.printf(" Right Intensity : %.2f%n", avgRight);

            // Check for light improvement
            double currentMax = Math.max(avgLeft, Math.max(avgCentre, avgRight));
            if (currentMax <= thresholdIntensity) {
                System.out.println("[INFO] No brighter light detected. Wandering randomly...");
                Direction wanderDir = randomDirection();
                performMove(wanderDir, avgLeft, avgCentre, avgRight, false);
                continue;
            }

            // Record light detection
            double elapsed = Duration.between(startTime, LocalDateTime.now()).toMillis() / 1000.0;
            lightReadings.add(new double[]{currentMax, elapsed});
            lightDetectionCount++;
            if (currentMax > brightestIntensity) brightestIntensity = currentMax;

            // Determine primary direction
            Direction primary = choosePrimaryDirection(avgLeft, avgCentre, avgRight);
            System.out.printf(" Direction to move: %s%n", primary);
            System.out.printf(" Speed            : %.1f cm/s%n", LOW_SPEED_CM_PER_S);
            System.out.println("──────────────────────────────────────────");

            // Obstacle check
            double distanceAhead = measureDistance();
            if (distanceAhead < OBSTACLE_THRESHOLD_CM) {
                handleObstacle(distanceAhead, primary, avgLeft, avgCentre, avgRight);
            } else {
                setUnderlights("GREEN");
                performMove(primary, avgLeft, avgCentre, avgRight, true);
            }
        }

        // Terminate
        terminateProgram();
    }

    // ─── Startup ───────────────────────────────────────────────────────────────
    private void printBanner() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║     SwiftBot Autonomous Light Seeker     ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("This program guides SwiftBot towards the brightest");
        System.out.println("light source using camera-based navigation.\n");
    }

    private void waitForButtonA() {
        System.out.println("[INPUT] Press Button 'A' (type 'A' and press Enter) to start:");
        while (true) {
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("A")) {
                System.out.println("[SYSTEM] Button A pressed. Initialising...");
                break;
            } else {
                System.out.println("[ERROR] Invalid input. Please type 'A' to start.");
            }
        }
    }

    // ─── Image Capture & Processing ────────────────────────────────────────────

    /**
     * Simulates capturing an image from the SwiftBot camera.
     * Returns a 2D pixel matrix where each value is a grayscale intensity (0–255).
     * Replace with actual camera API call on real hardware.
     */
    private double[][] captureImage() {
        int rows = 60, cols = 90;
        double[][] pixels = new double[rows][cols];

        // Simulate: brighter region shifts randomly to represent light sources
        int brightColCenter = rng.nextInt(cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double base     = 30 + rng.nextDouble() * 20;
                double distance = Math.abs(c - brightColCenter);
                double glow     = Math.max(0, 200 - distance * 3);
                pixels[r][c]    = Math.min(255, base + glow + rng.nextDouble() * 10);
            }
        }
        return pixels;
    }

    /** Computes average intensity for the left, centre, or right column third. */
    private double computeColumnAverage(double[][] image, Direction dir) {
        int cols    = image[0].length;
        int third   = cols / 3;
        int colStart, colEnd;

        switch (dir) {
            case LEFT:   colStart = 0;           colEnd = third;       break;
            case CENTRE: colStart = third;        colEnd = 2 * third;   break;
            case RIGHT:  colStart = 2 * third;    colEnd = cols;        break;
            default:     colStart = 0;            colEnd = cols;        break;
        }

        double sum   = 0;
        int    count = 0;
        for (double[] row : image) {
            for (int c = colStart; c < colEnd; c++) {
                sum += row[c];
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    /** Computes the overall average intensity of the entire image. */
    private double computeOverallAverage(double[][] image) {
        double sum = 0;
        int count  = 0;
        for (double[] row : image) {
            for (double val : row) { sum += val; count++; }
        }
        return count == 0 ? 0 : sum / count;
    }

    // ─── Direction Logic ───────────────────────────────────────────────────────

    private Direction choosePrimaryDirection(double left, double centre, double right) {
        if (centre >= left && centre >= right) return Direction.CENTRE;
        if (left   >= centre && left >= right)  return Direction.LEFT;
        return Direction.RIGHT;
    }

    /** Returns the second-best direction for obstacle avoidance. */
    private Direction chooseSecondaryDirection(double left, double centre, double right, Direction primary) {
        // Build sorted list excluding primary
        Map<Direction, Double> map = new LinkedHashMap<>();
        map.put(Direction.LEFT,   left);
        map.put(Direction.CENTRE, centre);
        map.put(Direction.RIGHT,  right);
        map.remove(primary);

        Direction best       = null;
        double    bestVal    = -1;
        boolean   allEqual   = true;
        double    firstVal   = -1;

        for (Map.Entry<Direction, Double> e : map.entrySet()) {
            if (firstVal < 0) firstVal = e.getValue();
            else if (e.getValue() != firstVal) allEqual = false;

            if (e.getValue() > bestVal) {
                bestVal = e.getValue();
                best    = e.getKey();
            }
        }

        if (allEqual) {
            // Pick randomly among the two
            List<Direction> candidates = new ArrayList<>(map.keySet());
            return candidates.get(rng.nextInt(candidates.size()));
        }
        return best;
    }

    private Direction randomDirection() {
        Direction[] dirs = { Direction.LEFT, Direction.CENTRE, Direction.RIGHT };
        return dirs[rng.nextInt(dirs.length)];
    }

    // ─── Movement ──────────────────────────────────────────────────────────────

    private void performMove(Direction dir, double left, double centre, double right, boolean logIntensity) {
        double distanceCm = LOW_SPEED_CM_PER_S * MOVE_DURATION_S;

        switch (dir) {
            case LEFT:
                System.out.printf("[MOVE] Turning LEFT %.0f degrees, then moving forward %.1f cm%n",
                        TURN_DEGREES, distanceCm);
                simulateMovement("Left", TURN_DEGREES, distanceCm);
                movementLog.add(String.format("Left %.1f cm (turned %.0f°)", distanceCm, TURN_DEGREES));
                break;
            case RIGHT:
                System.out.printf("[MOVE] Turning RIGHT %.0f degrees, then moving forward %.1f cm%n",
                        TURN_DEGREES, distanceCm);
                simulateMovement("Right", TURN_DEGREES, distanceCm);
                movementLog.add(String.format("Right %.1f cm (turned %.0f°)", distanceCm, TURN_DEGREES));
                break;
            case CENTRE:
                System.out.printf("[MOVE] Moving STRAIGHT AHEAD %.1f cm%n", distanceCm);
                simulateMovement("Straight ahead", 0, distanceCm);
                movementLog.add(String.format("Straight ahead %.1f cm", distanceCm));
                break;
            case RANDOM:
                Direction rand = randomDirection();
                System.out.println("[MOVE] Random direction chosen: " + rand);
                performMove(rand, left, centre, right, logIntensity);
                return;
        }
        totalDistanceCm += distanceCm;
        pause(MOVE_DURATION_S);
        System.out.println("[MOVE] Stopped.");
    }

    /** Simulates actual motor commands – replace with SwiftBot SDK calls. */
    private void simulateMovement(String dirLabel, double turnDeg, double distanceCm) {
        System.out.printf("[SIM] Motor command: %s | Turn: %.0f° | Distance: %.1f cm | Speed: %.1f cm/s%n",
                dirLabel, turnDeg, distanceCm, LOW_SPEED_CM_PER_S);
    }

    // ─── Obstacle Handling ─────────────────────────────────────────────────────

    private void handleObstacle(double distanceCm, Direction primary,
                                double avgLeft, double avgCentre, double avgRight) {
        objectsDetected++;
        if (firstObjectTime == null) firstObjectTime = LocalDateTime.now();

        setUnderlights("RED_BLINK");
        System.out.printf("%n[OBSTACLE] Object detected! Distance: %.1f cm%n", distanceCm);
        System.out.printf("[OBSTACLE] Total objects detected so far: %d%n", objectsDetected);

        // Capture and save obstacle image
        String imagePath = IMAGE_SAVE_DIR + "obstacle_" + objectsDetected + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + ".png";
        saveObstacleImage(imagePath);
        savedImagePaths.add(imagePath);
        System.out.println("[OBSTACLE] Image saved to: " + imagePath);

        // Choose secondary direction
        Direction secondary = chooseSecondaryDirection(avgLeft, avgCentre, avgRight, primary);
        System.out.println("[OBSTACLE] Switching to secondary direction: " + secondary);
        setUnderlights("GREEN");
        performMove(secondary, avgLeft, avgCentre, avgRight, false);
    }

    /** Simulates distance sensor – replace with SwiftBot ultrasonic/IR API. */
    private double measureDistance() {
        // 10% chance of simulating an obstacle between 10–49 cm
        if (rng.nextDouble() < 0.10) {
            return 10 + rng.nextDouble() * 39;
        }
        return 100 + rng.nextDouble() * 100;  // no obstacle
    }

    // ─── Termination Logic ─────────────────────────────────────────────────────

    private boolean shouldPromptTermination() {
        if (objectsDetected < MAX_OBJECTS_BEFORE_WARN || firstObjectTime == null) return false;
        double secondsSinceFirst = Duration.between(firstObjectTime, LocalDateTime.now()).toMillis() / 1000.0;
        return secondsSinceFirst < WARN_TIME_WINDOW_S;
    }

    /**
     * Checks if the user has entered TERMINATE.
     * If blocking=true, waits for input. If blocking=false, checks if input is waiting.
     */
    private boolean isTerminationRequested(boolean blocking) {
        try {
            if (blocking) {
                String line = scanner.nextLine().trim();
                if (line.equals("TERMINATE")) {
                    System.out.println("[SYSTEM] TERMINATE command received.");
                    return true;
                } else if (!line.isEmpty()) {
                    System.out.println("[INFO] Command ignored. Not 'TERMINATE'. Continuing...");
                }
                return false;
            } else {
                // Non-blocking: check if input is available (only works in terminal environments)
                if (System.in.available() > 0) {
                    String line = scanner.nextLine().trim();
                    if (line.equals("TERMINATE")) {
                        System.out.println("[SYSTEM] TERMINATE command received.");
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            // Ignore IO issues on non-blocking check
        }
        return false;
    }

    private void terminateProgram() {
        System.out.println("\n[SYSTEM] Terminating program...");
        writeLogFile();
        System.out.println("[SYSTEM] Program terminated. Goodbye!");
    }

    // ─── Logging ───────────────────────────────────────────────────────────────

    private void writeLogFile() {
        LocalDateTime endTime = LocalDateTime.now();
        double durationSecs   = Duration.between(startTime, endTime).toMillis() / 1000.0;

        try (PrintWriter pw = new PrintWriter(new FileWriter(LOG_FILE_PATH))) {
            pw.println("╔══════════════════════════════════════════════╗");
            pw.println("║         SwiftBot Journey Log File            ║");
            pw.println("╚══════════════════════════════════════════════╝");
            pw.println("Start time          : " + formatTime(startTime));
            pw.println("End time            : " + formatTime(endTime));
            pw.printf( "Duration            : %.2f seconds%n", durationSecs);
            pw.println();

            pw.println("── Light Data ──────────────────────────────────");
            pw.printf( "Threshold intensity (start): %.2f%n", thresholdIntensity);
            pw.printf( "Brightest intensity seen   : %.2f%n", brightestIntensity);
            pw.printf( "Light detection count      : %d%n",  lightDetectionCount);
            pw.println();
            pw.println("Light detections (intensity @ time):");
            for (double[] r : lightReadings) {
                pw.printf("  Intensity: %.2f  at  %.2f s%n", r[0], r[1]);
            }
            pw.println();

            pw.println("── Movement Log ────────────────────────────────");
            pw.printf( "Total distance travelled: %.2f cm%n", totalDistanceCm);
            pw.println("Movements (in order):");
            for (int i = 0; i < movementLog.size(); i++) {
                pw.printf("  %d. %s%n", i + 1, movementLog.get(i));
            }
            pw.println();

            pw.println("── Obstacle Data ───────────────────────────────");
            pw.printf( "Total objects detected: %d%n", objectsDetected);
            pw.println();

            pw.println("── Saved Files ─────────────────────────────────");
            pw.println("Log file saved to   : " + new File(LOG_FILE_PATH).getAbsolutePath());
            if (savedImagePaths.isEmpty()) {
                pw.println("No obstacle images captured.");
            } else {
                pw.println("Obstacle images:");
                for (String p : savedImagePaths) {
                    pw.println("  " + new File(p).getAbsolutePath());
                }
            }
            pw.println("════════════════════════════════════════════════");

            System.out.println("[LOG] Log file written to: " + new File(LOG_FILE_PATH).getAbsolutePath());

        } catch (IOException e) {
            System.out.println("[ERROR] Failed to write log file: " + e.getMessage());
        }
    }

    // ─── Hardware Abstractions ─────────────────────────────────────────────────

    /** Sets underlights. Replace with SwiftBot SDK: swiftbot.setUnderlights(...) */
    private void setUnderlights(String mode) {
        switch (mode) {
            case "GREEN":
                System.out.println("[LIGHTS] Underlights → GREEN");
                break;
            case "RED_BLINK":
                System.out.println("[LIGHTS] Underlights → RED (blinking)");
                break;
            default:
                System.out.println("[LIGHTS] Underlights → OFF");
        }
    }

    /** Saves obstacle image. Replace with actual BufferedImage write from camera frame. */
    private void saveObstacleImage(String path) {
        try {
            // Simulate: write a placeholder file
            new File(path).getParentFile().mkdirs();
            new FileWriter(path).close();  // placeholder
            // On real hardware: ImageIO.write(capturedBufferedImage, "PNG", new File(path));
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to save obstacle image: " + e.getMessage());
        }
    }

    /** Pauses execution for the given duration in seconds. */
    private void pause(double seconds) {
        try {
            Thread.sleep((long)(seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── Utilities ─────────────────────────────────────────────────────────────

    private String formatTime(LocalDateTime t) {
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

