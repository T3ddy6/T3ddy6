// ─────────────────────────────────────────────────────────────────────────────
// IMPORTS
// These lines bring in Java's built-in libraries so we can use their tools.
// Without imports, Java would not know what File, Scanner, Random, etc. are.
// ─────────────────────────────────────────────────────────────────────────────

import java.awt.image.BufferedImage; // Used for image data (referenced in comments for real hardware)
import java.io.*;                    // Gives us File, FileWriter, PrintWriter, IOException for reading/writing files
import java.time.Duration;           // Lets us measure the time gap between two moments (e.g. how long since first obstacle)
import java.time.LocalDateTime;      // Represents a date and time (used for timestamps on start, end, obstacles)
import java.time.format.DateTimeFormatter; // Formats a LocalDateTime into a readable string like "2025-04-27 14:32:05"
import java.util.*;                  // Gives us ArrayList, List, Map, LinkedHashMap, Random, Scanner all at once

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

// ─────────────────────────────────────────────────────────────────────────────
// OOP CONCEPT: CLASS
// A class is a blueprint. This one is called SearchForLight.
// It bundles together all the data (fields) and behaviour (methods) the robot needs.
// 'public' means other classes could access it.
// Everything in this program lives inside this one class.
// ─────────────────────────────────────────────────────────────────────────────
public class SearchForLight {

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTANTS
    // 'private'      = only accessible inside this class (encapsulation)
    // 'static'       = belongs to the class itself, not to any one object
    // 'final'        = the value can never be changed after it is set
    // 'double'/'int' = the data type (decimal number or whole number)
    // Using named constants avoids "magic numbers" scattered through the code.
    // If you want to change the speed, you change it in ONE place here.
    // ─────────────────────────────────────────────────────────────────────────
    private static final double LOW_SPEED_CM_PER_S   = 10.0;  // The robot always moves at exactly 10 cm per second
    private static final double MOVE_DURATION_S       = 1.0;   // Each movement lasts exactly 1 second
    private static final double TURN_DEGREES          = 30.0;  // When turning left or right, the robot rotates 30 degrees before moving forward
    private static final double OBSTACLE_THRESHOLD_CM = 50.0;  // If an object is closer than 50 cm, it is treated as an obstacle
    private static final int    MAX_OBJECTS_BEFORE_WARN = 5;   // After detecting this many obstacles, the program warns the user
    private static final double WARN_TIME_WINDOW_S    = 300.0; // The warning triggers only if those 5 obstacles happened within 5 minutes (300 seconds)
    private static final String LOG_FILE_PATH         = "swiftbot_log.txt";  // File name for the log written when the program ends
    private static final String IMAGE_SAVE_DIR        = "captured_images/"; // Folder where obstacle images are saved on disk

    // ─────────────────────────────────────────────────────────────────────────
    // OOP CONCEPT: ENUM
    // An enum (enumeration) defines a fixed set of named options.
    // Instead of using raw strings like "LEFT" or "RIGHT" (which are error-prone),
    // we use Direction.LEFT, Direction.RIGHT etc.
    // The compiler will catch any typo — you cannot accidentally write Direction.LAFT.
    // RANDOM is used when the robot cannot detect any light and wanders freely.
    // ─────────────────────────────────────────────────────────────────────────
    enum Direction { LEFT, CENTRE, RIGHT, RANDOM }

    // ─────────────────────────────────────────────────────────────────────────
    // OOP CONCEPT: INSTANCE FIELDS (STATE)
    // These are the object's memory — variables that belong to each SearchForLight object.
    // They persist for the entire lifetime of the program, updated by different methods.
    // 'private' means only methods inside this class can read or change them (encapsulation).
    // ─────────────────────────────────────────────────────────────────────────

    private double        thresholdIntensity  = 0.0;  // Baseline brightness at startup; robot only chases light brighter than this
    private double        brightestIntensity  = 0.0;  // Tracks the single brightest reading seen across the whole run
    private int           lightDetectionCount = 0;    // Counts how many times the robot detected light above the threshold
    private double        totalDistanceCm     = 0.0;  // Running total of all distance travelled, in centimetres
    private int           objectsDetected     = 0;    // Running count of obstacles encountered; triggers the warning at 5
    private LocalDateTime startTime;                  // Records the exact moment the program started (set in run())
    private LocalDateTime firstObjectTime     = null; // Records when the FIRST obstacle was seen; used to measure the 5-minute window

    // These lists grow throughout the run and are written to the log file at the end.
    // ArrayList is a resizable array — it can hold as many items as needed.
    private final List<double[]> lightReadings   = new ArrayList<>(); // Each entry is {intensity, timestamp_in_seconds}
    private final List<String>   movementLog     = new ArrayList<>(); // Each entry is a plain-English description of one move
    private final List<String>   savedImagePaths = new ArrayList<>(); // File paths of every obstacle image saved to disk

    private final Random  rng     = new Random();           // Random number generator — used for simulating sensors and random directions
    private final Scanner scanner = new Scanner(System.in); // Reads keyboard input from the user (e.g. "A" to start, "TERMINATE" to stop)

    // ─────────────────────────────────────────────────────────────────────────
    // ENTRY POINT
    // main() is the first method Java calls when you run the program.
    // It creates one SearchForLight object (OOP: instantiation) and calls run() on it.
    // Everything from this point flows through that single object.
    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        new SearchForLight().run(); // Create the object and immediately start the main loop
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN RUN LOOP
    // This is the heart of the program. It runs continuously until the user
    // types TERMINATE or confirms stopping after too many obstacles.
    // Each iteration: capture image -> compare brightness -> check obstacles -> move.
    // ─────────────────────────────────────────────────────────────────────────
    public void run() {
        printBanner();     // Show the welcome title in the console
        waitForButtonA();  // Wait until the user presses 'A' to start

        startTime = LocalDateTime.now(); // Record the exact start time for timestamps and duration
        System.out.println("\n[SYSTEM] Program started at: " + formatTime(startTime));

        // Create the folder for saving obstacle images if it does not already exist.
        // mkdirs() creates any missing parent folders too, not just the final one.
        new File(IMAGE_SAVE_DIR).mkdirs();

        // Capture a first image to measure the ambient (background) light level.
        // This becomes thresholdIntensity — the robot only chases light ABOVE this value.
        double[][] initialImage = captureImage();
        thresholdIntensity = computeOverallAverage(initialImage);
        System.out.printf("[SYSTEM] Environment threshold light intensity set to: %.2f%n", thresholdIntensity);

        // ── Main while-loop ──
        // 'running' controls the loop. Setting it to false (plus break) exits cleanly.
        boolean running = true;
        while (running) {

            // Check if the user has typed TERMINATE without being prompted.
            // Passing 'false' means non-blocking — the loop continues if nothing was typed.
            if (isTerminationRequested(false)) {
                running = false;
                break;
            }

            // If 5 or more obstacles were detected within the last 5 minutes,
            // warn the user and give them the option to stop the program.
            if (shouldPromptTermination()) {
                System.out.println("\n[WARNING] 5 objects detected in less than 5 minutes.");
                System.out.println("[SYSTEM] Enter 'TERMINATE' to stop the program, or press Enter to continue:");
                // Passing 'true' means blocking — the loop pauses until the user responds
                if (isTerminationRequested(true)) {
                    running = false;
                    break;
                }
            }

            // ── Light-seeking cycle ──
            // Capture a fresh image from the camera (or simulation) each iteration
            double[][] image = captureImage();

            // Split the image into thirds and compute the average brightness of each third.
            // This tells us which direction has the most light.
            double avgLeft   = computeColumnAverage(image, Direction.LEFT);
            double avgCentre = computeColumnAverage(image, Direction.CENTRE);
            double avgRight  = computeColumnAverage(image, Direction.RIGHT);

            // Print the three intensity readings so the user can follow along
            System.out.println("\n──────────────────────────────────────────");
            System.out.printf(" Left Intensity  : %.2f%n", avgLeft);
            System.out.printf(" Centre Intensity: %.2f%n", avgCentre);
            System.out.printf(" Right Intensity : %.2f%n", avgRight);

            // Find the highest of the three values.
            // Math.max(a, Math.max(b, c)) is the standard way to find the max of three values.
            double currentMax = Math.max(avgLeft, Math.max(avgCentre, avgRight));

            // If the brightest reading is no better than the baseline, there is no useful light.
            // The robot wanders in a random direction and skips the rest of this loop iteration.
            // 'continue' jumps straight back to the top of the while-loop.
            if (currentMax <= thresholdIntensity) {
                System.out.println("[INFO] No brighter light detected. Wandering randomly...");
                Direction wanderDir = randomDirection();
                performMove(wanderDir, avgLeft, avgCentre, avgRight, false);
                continue; // Skip the rest of this iteration
            }

            // Light was detected above threshold — record it.
            // Duration.between().toMillis() / 1000.0 converts milliseconds to seconds.
            double elapsed = Duration.between(startTime, LocalDateTime.now()).toMillis() / 1000.0;
            lightReadings.add(new double[]{currentMax, elapsed}); // Store intensity + elapsed time
            lightDetectionCount++;
            if (currentMax > brightestIntensity) brightestIntensity = currentMax; // Update all-time brightest

            // Pick the direction with the most light as the primary movement target
            Direction primary = choosePrimaryDirection(avgLeft, avgCentre, avgRight);
            System.out.printf(" Direction to move: %s%n", primary);
            System.out.printf(" Speed            : %.1f cm/s%n", LOW_SPEED_CM_PER_S);
            System.out.println("──────────────────────────────────────────");

            // Ask the distance sensor whether anything is blocking the path ahead.
            // If something is closer than OBSTACLE_THRESHOLD_CM (50 cm), handle the obstacle.
            double distanceAhead = measureDistance();
            if (distanceAhead < OBSTACLE_THRESHOLD_CM) {
                handleObstacle(distanceAhead, primary, avgLeft, avgCentre, avgRight);
            } else {
                setUnderlights("GREEN"); // Path is clear — show green lights
                performMove(primary, avgLeft, avgCentre, avgRight, true); // Move toward the light
            }
        }

        // The loop has exited — shut the program down cleanly and write the log file
        terminateProgram();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STARTUP METHODS
    // ─────────────────────────────────────────────────────────────────────────

    // Prints the decorative welcome banner to the console when the program starts.
    // 'private' means this method can only be called from within this class.
    // 'void' means it returns nothing — it just prints and exits.
    private void printBanner() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║     SwiftBot Autonomous Light Seeker     ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("This program guides SwiftBot towards the brightest");
        System.out.println("light source using camera-based navigation.\n");
    }

    // Waits in a loop until the user types 'A' and presses Enter.
    // This simulates pressing the physical Button A on the SwiftBot hardware.
    // scanner.nextLine() pauses execution and waits for a line of keyboard input.
    // .trim() removes any accidental spaces or newline characters around the input.
    // equalsIgnoreCase("A") accepts both lowercase "a" and uppercase "A".
    // 'break' exits the while(true) loop once the correct input is received.
    private void waitForButtonA() {
        System.out.println("[INPUT] Press Button 'A' (type 'A' and press Enter) to start:");
        while (true) { // Loop forever until the correct key is typed
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("A")) {
                System.out.println("[SYSTEM] Button A pressed. Initialising...");
                break; // Valid input received — exit the loop and continue
            } else {
                System.out.println("[ERROR] Invalid input. Please type 'A' to start.");
                // Loop repeats and prompts the user again
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMAGE CAPTURE & PROCESSING
    // On real hardware these methods call the SwiftBot camera API.
    // In simulation they generate a fake pixel grid with a random bright spot.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Simulates capturing an image from the SwiftBot camera.
     * Returns a 2D pixel matrix where each value is a grayscale intensity (0-255).
     * Replace with actual camera API call on real hardware.
     *
     * How the simulation works:
     * - Creates a 60-row by 90-column grid of pixel values (double[][] = 2D array of decimals)
     * - Picks a random column as the centre of a simulated bright light source
     * - Each pixel gets a base brightness (30-50) plus a "glow" that falls off with distance
     *   from that column: glow = max(0, 200 - distance * 3)
     * - A little random noise (+0 to +10) is added to mimic a real camera sensor
     * - Math.min(255, ...) clamps the value so it never exceeds 255 (maximum brightness)
     */
    private double[][] captureImage() {
        int rows = 60, cols = 90;            // Image dimensions: 60 pixels tall, 90 pixels wide
        double[][] pixels = new double[rows][cols]; // Allocate the 2D array

        // Pick a random column index (0 to 89) as the simulated light source centre
        int brightColCenter = rng.nextInt(cols);

        // Fill every pixel with a calculated brightness value
        for (int r = 0; r < rows; r++) {     // Loop over each row (top to bottom)
            for (int c = 0; c < cols; c++) { // Loop over each column in that row
                double base     = 30 + rng.nextDouble() * 20;     // Base ambient brightness between 30 and 50
                double distance = Math.abs(c - brightColCenter);   // How far this column is from the light source
                double glow     = Math.max(0, 200 - distance * 3); // Brightness contribution; fades with distance, minimum 0
                pixels[r][c]    = Math.min(255, base + glow + rng.nextDouble() * 10); // Add noise, cap at 255
            }
        }
        return pixels; // Return the completed 2D pixel grid
    }

    /**
     * Splits the image into left, centre, and right thirds by column index.
     * Returns the average pixel brightness for whichever third is specified by 'dir'.
     *
     * image[0].length gives the total number of columns.
     * cols / 3 gives the width of one third.
     * The switch statement sets colStart and colEnd for the requested third.
     * The nested for-loop sums every pixel in that column range and divides by count.
     * The ternary (count == 0 ? 0 : sum/count) prevents a divide-by-zero error.
     */
    private double computeColumnAverage(double[][] image, Direction dir) {
        int cols  = image[0].length; // Total number of columns in the image
        int third = cols / 3;        // Width of each third (30 columns each for a 90-wide image)
        int colStart, colEnd;        // These will be set by the switch below

        // Use the Direction enum to select the correct column range
        switch (dir) {
            case LEFT:   colStart = 0;         colEnd = third;      break; // Columns 0-29 (left third)
            case CENTRE: colStart = third;      colEnd = 2 * third;  break; // Columns 30-59 (middle third)
            case RIGHT:  colStart = 2 * third;  colEnd = cols;       break; // Columns 60-89 (right third)
            default:     colStart = 0;          colEnd = cols;       break; // Fallback: whole image
        }

        double sum   = 0; // Accumulate total brightness of all pixels in the selected third
        int    count = 0; // Track how many pixels we have summed

        // Loop over every row, and within each row only the columns in the selected third
        for (double[] row : image) {            // 'for-each' loop: row is one row of the 2D array
            for (int c = colStart; c < colEnd; c++) {
                sum += row[c]; // Add this pixel's brightness to the running total
                count++;       // Increment the pixel counter
            }
        }

        return count == 0 ? 0 : sum / count; // Return the average; guard against divide-by-zero
    }

    /**
     * Computes the average brightness of every pixel in the entire image.
     * Called once at startup to set thresholdIntensity (the ambient light baseline).
     * Uses nested for-each loops to visit every pixel in the 2D array.
     */
    private double computeOverallAverage(double[][] image) {
        double sum = 0; // Total brightness across all pixels
        int count  = 0; // Total number of pixels counted

        for (double[] row : image) {     // Outer loop: each row of the image
            for (double val : row) {     // Inner loop: each pixel value in that row
                sum += val;
                count++;
            }
        }
        return count == 0 ? 0 : sum / count; // Return overall average; guard against divide-by-zero
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DIRECTION LOGIC
    // These methods decide which way the robot should move.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Picks the primary direction to move based on which third of the image is brightest.
     * Simple chained if-statements compare the three averages.
     * Centre is checked first — if it ties with left or right, centre wins (go straight).
     */
    private Direction choosePrimaryDirection(double left, double centre, double right) {
        if (centre >= left && centre >= right) return Direction.CENTRE; // Centre is brightest or tied — go straight
        if (left   >= centre && left >= right) return Direction.LEFT;   // Left is the brightest third
        return Direction.RIGHT;                                          // Right must be the brightest third
    }

    /**
     * Returns the second-best direction when the primary direction is blocked by an obstacle.
     * The primary direction is removed from the candidates, leaving the other two.
     * If both remaining directions have equal brightness, one is chosen at random.
     * Otherwise, the brighter of the two is returned.
     *
     * LinkedHashMap preserves insertion order, so LEFT/CENTRE/RIGHT are always
     * visited in the same sequence when we iterate over the entries.
     */
    private Direction chooseSecondaryDirection(double left, double centre, double right, Direction primary) {
        // Load all three directions and their brightness values into a map
        Map<Direction, Double> map = new LinkedHashMap<>();
        map.put(Direction.LEFT,   left);
        map.put(Direction.CENTRE, centre);
        map.put(Direction.RIGHT,  right);
        map.remove(primary); // Remove the blocked primary direction, leaving two candidates

        Direction best     = null;  // Will hold the best remaining direction
        double    bestVal  = -1;    // Will hold the highest remaining brightness
        boolean   allEqual = true;  // Assume the two remaining values are equal until proven otherwise
        double    firstVal = -1;    // Stores the first value seen, used to detect inequality

        // Iterate over the two remaining directions to find the brighter one
        for (Map.Entry<Direction, Double> e : map.entrySet()) {
            if (firstVal < 0) firstVal = e.getValue();           // Record the first value we see
            else if (e.getValue() != firstVal) allEqual = false; // A different value means they are not equal

            if (e.getValue() > bestVal) { // Keep track of the highest value and its direction
                bestVal = e.getValue();
                best    = e.getKey();
            }
        }

        if (allEqual) {
            // Both remaining directions have the same brightness — pick one at random
            List<Direction> candidates = new ArrayList<>(map.keySet());
            return candidates.get(rng.nextInt(candidates.size())); // Random index 0 or 1
        }
        return best; // Return the brighter of the two remaining directions
    }

    /**
     * Returns a random direction from LEFT, CENTRE, RIGHT.
     * Used during wandering when no light is detected above the threshold.
     * rng.nextInt(3) produces 0, 1, or 2 — used as an array index.
     */
    private Direction randomDirection() {
        Direction[] dirs = { Direction.LEFT, Direction.CENTRE, Direction.RIGHT };
        return dirs[rng.nextInt(dirs.length)]; // Pick and return a random element
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MOVEMENT METHODS
    // These methods make the robot physically move (or simulate movement).
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Moves the robot in the given direction.
     * - LEFT/RIGHT: turn 30 degrees first, then move forward 10 cm
     * - CENTRE: move straight ahead 10 cm (no turn needed)
     * - RANDOM: pick a random direction and call this method again (recursion)
     *
     * distanceCm = speed x time = 10.0 x 1.0 = 10.0 cm per call.
     * Each move is recorded as a human-readable string in movementLog.
     * pause() simulates the time it takes the robot to physically travel the distance.
     *
     * OOP note: this method calls other methods on the same object (simulateMovement, pause).
     * This is normal — a class bundles related behaviour together so methods can work as a team.
     */
    private void performMove(Direction dir, double left, double centre, double right, boolean logIntensity) {
        double distanceCm = LOW_SPEED_CM_PER_S * MOVE_DURATION_S; // Always 10.0 cm per move

        switch (dir) {
            case LEFT:
                // Turn left 30 degrees, then drive forward 10 cm
                System.out.printf("[MOVE] Turning LEFT %.0f degrees, then moving forward %.1f cm%n",
                        TURN_DEGREES, distanceCm);
                simulateMovement("Left", TURN_DEGREES, distanceCm); // Print the motor command
                movementLog.add(String.format("Left %.1f cm (turned %.0f°)", distanceCm, TURN_DEGREES));
                break;

            case RIGHT:
                // Turn right 30 degrees, then drive forward 10 cm
                System.out.printf("[MOVE] Turning RIGHT %.0f degrees, then moving forward %.1f cm%n",
                        TURN_DEGREES, distanceCm);
                simulateMovement("Right", TURN_DEGREES, distanceCm);
                movementLog.add(String.format("Right %.1f cm (turned %.0f°)", distanceCm, TURN_DEGREES));
                break;

            case CENTRE:
                // No turn needed — go straight
                System.out.printf("[MOVE] Moving STRAIGHT AHEAD %.1f cm%n", distanceCm);
                simulateMovement("Straight ahead", 0, distanceCm);
                movementLog.add(String.format("Straight ahead %.1f cm", distanceCm));
                break;

            case RANDOM:
                // Pick a random concrete direction and call performMove() again with it.
                // This is RECURSION — the method calls itself once with a real direction.
                // 'return' here skips the totalDistanceCm and pause lines below,
                // because the recursive call will handle them instead.
                Direction rand = randomDirection();
                System.out.println("[MOVE] Random direction chosen: " + rand);
                performMove(rand, left, centre, right, logIntensity);
                return; // Early return — the recursive call handles distance tracking and pause
        }

        totalDistanceCm += distanceCm; // Add this move's distance to the running total
        pause(MOVE_DURATION_S);        // Wait 1 second to simulate physical travel time
        System.out.println("[MOVE] Stopped.");
    }

    /**
     * Prints the motor command that would be sent to the SwiftBot hardware.
     * In simulation this is just a console message.
     * On real hardware: replace with the appropriate SDK method call,
     * e.g. swiftbot.move(direction, speed, duration).
     */
    private void simulateMovement(String dirLabel, double turnDeg, double distanceCm) {
        System.out.printf("[SIM] Motor command: %s | Turn: %.0f° | Distance: %.1f cm | Speed: %.1f cm/s%n",
                dirLabel, turnDeg, distanceCm, LOW_SPEED_CM_PER_S);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OBSTACLE HANDLING
    // Called when the distance sensor detects something within 50 cm.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles a detected obstacle:
     * 1. Increments the obstacle counter and records the time of the very first obstacle.
     * 2. Flashes the underlights red to signal danger.
     * 3. Saves an image of what the camera sees at this moment.
     * 4. Picks the second-best available direction and moves that way.
     *
     * The image file name includes the obstacle number and a timestamp (HHmmss)
     * so each image gets a unique name and files do not overwrite each other.
     */
    private void handleObstacle(double distanceCm, Direction primary,
                                double avgLeft, double avgCentre, double avgRight) {
        objectsDetected++; // Count this new obstacle
        if (firstObjectTime == null) firstObjectTime = LocalDateTime.now(); // Record when the first obstacle happened

        setUnderlights("RED_BLINK"); // Signal danger with blinking red underlights
        System.out.printf("%n[OBSTACLE] Object detected! Distance: %.1f cm%n", distanceCm);
        System.out.printf("[OBSTACLE] Total objects detected so far: %d%n", objectsDetected);

        // Build a unique file path using the obstacle count and current time (HH=hour, mm=minute, ss=second)
        String imagePath = IMAGE_SAVE_DIR + "obstacle_" + objectsDetected + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + ".png";
        saveObstacleImage(imagePath);   // Save (or simulate saving) the image file
        savedImagePaths.add(imagePath); // Store the path so it can be referenced in the log
        System.out.println("[OBSTACLE] Image saved to: " + imagePath);

        // Cannot move in the primary direction — choose the next best alternative
        Direction secondary = chooseSecondaryDirection(avgLeft, avgCentre, avgRight, primary);
        System.out.println("[OBSTACLE] Switching to secondary direction: " + secondary);
        setUnderlights("GREEN"); // Obstacle handled — switch back to green lights
        performMove(secondary, avgLeft, avgCentre, avgRight, false); // Move in the secondary direction
    }

    /**
     * Simulates the SwiftBot's distance sensor (ultrasonic or IR).
     * On real hardware: replace this method body with the actual sensor API call.
     *
     * Simulation:
     * - rng.nextDouble() returns a decimal between 0.0 and 1.0
     * - If the value is below 0.10, that is a 10% probability — simulate an obstacle at 10-49 cm
     * - Otherwise return a safe distance of 100-200 cm (no obstacle present)
     */
    private double measureDistance() {
        if (rng.nextDouble() < 0.10) {          // 10% chance of simulating an obstacle
            return 10 + rng.nextDouble() * 39;  // Random obstacle distance between 10 and 49 cm
        }
        return 100 + rng.nextDouble() * 100;    // Safe distance between 100 and 200 cm
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TERMINATION LOGIC
    // Methods that decide when and how the program stops.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the safety warning should be displayed to the user.
     * Condition: at least MAX_OBJECTS_BEFORE_WARN (5) obstacles have been detected
     * AND they all happened within WARN_TIME_WINDOW_S (300 seconds = 5 minutes)
     * of the very first one.
     *
     * Duration.between(a, b).toMillis() gives the time gap in milliseconds.
     * Dividing by 1000.0 converts to seconds.
     * Returns false immediately if not enough obstacles have been detected yet.
     */
    private boolean shouldPromptTermination() {
        if (objectsDetected < MAX_OBJECTS_BEFORE_WARN || firstObjectTime == null) return false; // Not enough obstacles yet
        double secondsSinceFirst = Duration.between(firstObjectTime, LocalDateTime.now()).toMillis() / 1000.0;
        return secondsSinceFirst < WARN_TIME_WINDOW_S; // True only if all 5 happened within 5 minutes
    }

    /**
     * Checks whether the user has typed "TERMINATE" to stop the program.
     *
     * Two modes controlled by the 'blocking' boolean parameter:
     *
     * blocking = true  → Waits for the user to type something and press Enter.
     *                    Used when we have explicitly asked the user to respond.
     *
     * blocking = false → Checks if input is already waiting in the buffer.
     *                    Returns false immediately if nothing has been typed.
     *                    System.in.available() > 0 tells us if bytes are ready to read.
     *                    Used in the main loop so the robot does not freeze waiting for input.
     *
     * The IOException catch silently ignores low-level I/O errors on the non-blocking check.
     */
    private boolean isTerminationRequested(boolean blocking) {
        try {
            if (blocking) {
                // Wait for the user to type something and press Enter
                String line = scanner.nextLine().trim();
                if (line.equals("TERMINATE")) {
                    System.out.println("[SYSTEM] TERMINATE command received.");
                    return true; // User confirmed they want to stop
                } else if (!line.isEmpty()) {
                    // User typed something but it was not TERMINATE — ignore it and continue
                    System.out.println("[INFO] Command ignored. Not 'TERMINATE'. Continuing...");
                }
                return false; // No valid termination command received
            } else {
                // Non-blocking check: only read if input bytes are already waiting
                if (System.in.available() > 0) {
                    String line = scanner.nextLine().trim();
                    if (line.equals("TERMINATE")) {
                        System.out.println("[SYSTEM] TERMINATE command received.");
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            // Silently ignore any I/O errors during a non-blocking check
        }
        return false; // Default: no termination command detected
    }

    /**
     * Called when the program is ready to exit.
     * Prints a goodbye message and calls writeLogFile() to save the full run summary.
     */
    private void terminateProgram() {
        System.out.println("\n[SYSTEM] Terminating program...");
        writeLogFile(); // Save the complete run summary to disk before exiting
        System.out.println("[SYSTEM] Program terminated. Goodbye!");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOGGING
    // Writes a full summary of the run to a text file when the program ends.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a plain-text log file summarising the entire run.
     *
     * try-with-resources: the PrintWriter is declared inside try() so Java
     * automatically closes it when the block exits — even if an exception occurs.
     * This prevents the file being left open on disk.
     *
     * PrintWriter wraps FileWriter so we can use println() and printf() directly.
     *
     * The log includes:
     * - Start and end times, total duration
     * - All light intensity readings with their timestamps
     * - Every movement the robot made, in order
     * - Total distance travelled
     * - Number of obstacles detected
     * - Full file paths of all saved obstacle images
     */
    private void writeLogFile() {
        LocalDateTime endTime = LocalDateTime.now(); // Capture the exact end time
        double durationSecs   = Duration.between(startTime, endTime).toMillis() / 1000.0; // Duration in seconds

        // Open the log file for writing (creates it if it does not exist)
        try (PrintWriter pw = new PrintWriter(new FileWriter(LOG_FILE_PATH))) {
            pw.println("╔══════════════════════════════════════════════╗");
            pw.println("║         SwiftBot Journey Log File            ║");
            pw.println("╚══════════════════════════════════════════════╝");
            pw.println("Start time          : " + formatTime(startTime));
            pw.println("End time            : " + formatTime(endTime));
            pw.printf( "Duration            : %.2f seconds%n", durationSecs); // %.2f = two decimal places

            pw.println();
            pw.println("── Light Data ──────────────────────────────────");
            pw.printf( "Threshold intensity (start): %.2f%n", thresholdIntensity);
            pw.printf( "Brightest intensity seen   : %.2f%n", brightestIntensity);
            pw.printf( "Light detection count      : %d%n",   lightDetectionCount);
            pw.println();

            // Write every light reading — each entry in the list is a double[] of {intensity, seconds}
            pw.println("Light detections (intensity @ time):");
            for (double[] r : lightReadings) {
                pw.printf("  Intensity: %.2f  at  %.2f s%n", r[0], r[1]); // r[0] = intensity, r[1] = time
            }
            pw.println();

            pw.println("── Movement Log ────────────────────────────────");
            pw.printf( "Total distance travelled: %.2f cm%n", totalDistanceCm);
            pw.println("Movements (in order):");

            // Write each movement with a 1-based index number
            // (i+1 because ArrayList indices start at 0 but we want to display 1, 2, 3...)
            for (int i = 0; i < movementLog.size(); i++) {
                pw.printf("  %d. %s%n", i + 1, movementLog.get(i));
            }
            pw.println();

            pw.println("── Obstacle Data ───────────────────────────────");
            pw.printf( "Total objects detected: %d%n", objectsDetected);
            pw.println();

            pw.println("── Saved Files ─────────────────────────────────");
            // getAbsolutePath() converts a relative path like "swiftbot_log.txt"
            // into a full system path like "C:\Users\...\swiftbot_log.txt" for easy navigation
            pw.println("Log file saved to   : " + new File(LOG_FILE_PATH).getAbsolutePath());

            if (savedImagePaths.isEmpty()) {
                pw.println("No obstacle images captured."); // No obstacles were hit during this run
            } else {
                pw.println("Obstacle images:");
                for (String p : savedImagePaths) {
                    pw.println("  " + new File(p).getAbsolutePath()); // Full path for each saved image
                }
            }
            pw.println("════════════════════════════════════════════════");

            System.out.println("[LOG] Log file written to: " + new File(LOG_FILE_PATH).getAbsolutePath());

        } catch (IOException e) {
            // If the file could not be written, print the reason to the console
            System.out.println("[ERROR] Failed to write log file: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HARDWARE ABSTRACTION METHODS
    // OOP CONCEPT: ABSTRACTION
    // These methods hide hardware implementation details behind clean method names.
    // The rest of the code just calls setUnderlights("GREEN") — it does not need to
    // know how that is achieved. On real hardware, replace only the body of each
    // method with the SwiftBot SDK call; nothing else in the code needs to change.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets the robot's underlight LEDs to indicate its current state.
     * In simulation: prints what the lights would do.
     * On real hardware: replace with swiftbot.setUnderlights(colour, brightness) or similar.
     *
     * A switch statement matches the 'mode' string against known cases.
     * 'default' handles any unexpected value by turning the lights off.
     */
    private void setUnderlights(String mode) {
        switch (mode) {
            case "GREEN":      // Clear path ahead — show green
                System.out.println("[LIGHTS] Underlights → GREEN");
                break;
            case "RED_BLINK":  // Obstacle detected — show blinking red
                System.out.println("[LIGHTS] Underlights → RED (blinking)");
                break;
            default:           // Unrecognised mode — turn off
                System.out.println("[LIGHTS] Underlights → OFF");
        }
    }

    /**
     * Saves an image of what the robot sees at the moment an obstacle is detected.
     * In simulation: creates an empty placeholder file at the given path.
     * On real hardware: replace the placeholder line with:
     *   ImageIO.write(capturedBufferedImage, "PNG", new File(path));
     * where capturedBufferedImage is the live frame from the SwiftBot camera.
     *
     * getParentFile().mkdirs() ensures the captured_images/ directory exists before writing.
     * new FileWriter(path).close() creates an empty file as a placeholder in simulation.
     */
    private void saveObstacleImage(String path) {
        try {
            new File(path).getParentFile().mkdirs(); // Create the directory if it does not exist
            new FileWriter(path).close();            // Placeholder: create an empty file at this path
            // On real hardware: ImageIO.write(capturedBufferedImage, "PNG", new File(path));
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to save obstacle image: " + e.getMessage());
        }
    }

    /**
     * Pauses execution for the given number of seconds.
     * Simulates the time it takes the robot to physically travel a distance.
     *
     * Thread.sleep() requires milliseconds, so we multiply seconds by 1000.
     * The cast (long) converts the double result to a whole number as required by sleep().
     * InterruptedException must be caught — it is thrown if another thread interrupts this one.
     * Thread.currentThread().interrupt() re-raises the interrupted flag so callers are aware.
     */
    private void pause(double seconds) {
        try {
            Thread.sleep((long)(seconds * 1000)); // Convert seconds to milliseconds, then sleep
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore the interrupted status for the caller
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITY METHODS
    // Small helper methods reused by multiple other methods.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts a LocalDateTime object into a formatted, human-readable string.
     * DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") defines the format:
     *   yyyy = 4-digit year
     *   MM   = 2-digit month
     *   dd   = 2-digit day
     *   HH   = 24-hour hour
     *   mm   = minutes
     *   ss   = seconds
     * Example output: "2025-04-27 14:32:05"
     * Used when printing start/end times to the console and writing them to the log file.
     */
    private String formatTime(LocalDateTime t) {
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

} // End of SearchForLight class
