import swiftbot.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * SwiftBotNavigator
 * -----------------
 * Drives the real SwiftBot using QR codes scanned via its camera.
 *
 * Flow: scan a QR code -> decode it -> validate the command(s) it contains
 * -> execute them on the real motors -> scan the next QR code, and so on,
 * until the physical X button is pressed.
 *
 * Supported commands (see coursework spec):
 *   F,speed,duration   - move Forward
 *   B,speed,duration   - move Backward
 *   R,speed,duration   - turn Right, then continue at 'speed' for 'duration'
 *   L,speed,duration   - turn Left,  then continue at 'speed' for 'duration'
 *   T,n                - reTrace the last n movement commands (F/B/R/L only)
 *   W                  - Write the command log to a file
 *
 * Multiple commands can be chained in a single QR code using ';', e.g.
 *   "F,50,2;R,60,1"
 * Up to 10 chained commands are allowed per QR code, and 'T' cannot appear
 * inside a chained sequence (it must be sent on its own).
 *
 * Extra feature: a simulated battery monitor. Every movement drains battery
 * proportional to speed x duration; the underlighting shows the battery
 * level (green/amber/red) and a warning is printed once battery is low.
 */
public class SwiftBotNavigator {

    // ---- Constants -------------------------------------------------------
    private static final int MAX_SPEED = 100;
    private static final int MAX_DURATION = 6;
    private static final int MAX_COMMANDS_PER_CODE = 10;
    private static final int MAX_SCAN_ATTEMPTS = 10; // per QR-code read attempt before giving up and retrying

    // Pivot turn tuning. The SwiftBot API only exposes wheel velocities and
    // ultrasound/camera/buttons - there is no compass or gyroscope reading
    // available, so an exact 90-degree pivot cannot be measured directly.
    // PIVOT_DURATION_MS below is a starting estimate and MUST be calibrated
    // against your own SwiftBot (place it on the floor, run a turn, and
    // adjust this value up/down until it reliably turns ~90 degrees).
    private static final int PIVOT_SPEED = 60;
    private static final int PIVOT_DURATION_MS = 350;

    private static SwiftBotAPI swiftBot;
    private static volatile boolean running = true;

    // ---- State -------------------------------------------------------------
    private static final List<ParsedCommand> movementHistory = new ArrayList<>(); // F/B/R/L only, used for retrace
    private static final List<String> fullLog = new ArrayList<>();                // every raw command received (incl. T, W)
    private static double totalExecutionSeconds = 0.0;
    private static final BatteryMonitor battery = new BatteryMonitor();

    public static void main(String[] args) throws InterruptedException {
        try {
            swiftBot = SwiftBotAPI.INSTANCE;
        } catch (Exception e) {
            System.out.println("\nI2C disabled! Let course staff know if this keeps happening.");
            System.exit(5);
        }

        // Physical X button powers the bot off, as required by the spec.
        swiftBot.enableButton(Button.X, () -> {
            System.out.println("\nX button pressed - powering off after the current action finishes.");
            running = false;
        });

        printWelcome();

        while (running) {
            String decoded = scanQrCode();
            if (!running) {
                break;
            }
            if (decoded == null) {
                System.out.println("No QR code detected. Position a QR code in front of the camera and try again.\n");
                continue;
            }
            processQrCode(decoded);
        }

        swiftBot.disableAllButtons();
        System.out.println("SwiftBot powered down. Goodbye!");
    }

    private static void printWelcome() {
        System.out.println("=================================================");
        System.out.println(" SwiftBot QR Navigation System");
        System.out.println("=================================================");
        System.out.println("Commands: F,speed,duration | B,speed,duration");
        System.out.println("          R,speed,duration | L,speed,duration");
        System.out.println("          T,n               | W");
        System.out.println("Chain commands with ';' (max " + MAX_COMMANDS_PER_CODE + " per QR code).");
        System.out.println("Press the physical X button at any time to power off.\n");
    }

    /**
     * Repeatedly captures a frame from the camera and tries to decode a QR
     * code from it, up to MAX_SCAN_ATTEMPTS times. Returns the decoded string,
     * or null if nothing was found (or the X button was pressed mid-scan).
     */
    private static String scanQrCode() {
        int attempts = 0;
        while (attempts < MAX_SCAN_ATTEMPTS && running) {
            System.out.println("Scanning for QR code...");
            try {
                BufferedImage img = swiftBot.getQRImage();
                String decoded = swiftBot.decodeQRImage(img);
                if (decoded != null && !decoded.isEmpty()) {
                    return decoded;
                }
            } catch (Exception e) {
                // No QR code in view yet - just try again.
            }
            attempts++;
        }
        return null;
    }

    /**
     * Validates and (if valid) executes an entire QR code, which may contain
     * one or more semicolon-separated commands.
     */
    private static void processQrCode(String rawCode) {
        String[] tokens = rawCode.split(";", -1);

        if (tokens.length > MAX_COMMANDS_PER_CODE) {
            System.out.println("Error: a QR code cannot contain more than " + MAX_COMMANDS_PER_CODE + " commands.");
            return;
        }

        boolean isSequence = tokens.length > 1;

        List<ParsedCommand> parsedTokens = new ArrayList<>();
        for (String token : tokens) {
            ParsedCommand parsed;
            try {
                parsed = parseCommand(token.trim());
            } catch (IllegalArgumentException ex) {
                System.out.println("Error: " + ex.getMessage());
                System.out.println("No commands were executed. Scan a corrected QR code.");
                return;
            }

            if (isSequence && parsed.type == 'T') {
                System.out.println("Error: 'T' (reTrace) cannot be used within a multi-command sequence.");
                System.out.println("No commands were executed. Scan a corrected QR code.");
                return;
            }

            parsedTokens.add(parsed);
        }

        // All commands verified as syntactically/semantically valid -> display, then execute.
        System.out.println("Command(s) received: " + rawCode);
        for (ParsedCommand p : parsedTokens) {
            fullLog.add(p.raw);
            try {
                executeCommand(p);
            } catch (InterruptedException e) {
                System.out.println("Movement interrupted.");
            }
        }
    }

    /**
     * Parses a single command token (no semicolons) into a ParsedCommand,
     * validating argument count and ranges. Throws IllegalArgumentException
     * with a human-readable message on any problem.
     */
    private static ParsedCommand parseCommand(String token) {
        if (token.isEmpty()) {
            throw new IllegalArgumentException("empty command found.");
        }

        String[] parts = token.split(",");
        char type = Character.toUpperCase(parts[0].trim().charAt(0));

        switch (type) {
            case 'F':
            case 'B':
            case 'R':
            case 'L': {
                if (parts.length != 3) {
                    throw new IllegalArgumentException(
                        "'" + type + "' requires exactly two values (speed,duration), e.g. '" + type + ",50,2'.");
                }
                int speed = parseInt(parts[1], type + " speed");
                int duration = parseInt(parts[2], type + " duration");

                if (speed <= 0 || speed > MAX_SPEED) {
                    throw new IllegalArgumentException("speed must be between 1 and " + MAX_SPEED + " (got " + speed + ").");
                }
                if (duration <= 0 || duration > MAX_DURATION) {
                    throw new IllegalArgumentException("duration must be between 1 and " + MAX_DURATION + " seconds (got " + duration + ").");
                }
                return new ParsedCommand(type, new int[]{speed, duration}, token);
            }
            case 'T': {
                if (parts.length != 2) {
                    throw new IllegalArgumentException("'T' requires exactly one value, e.g. 'T,3'.");
                }
                int n = parseInt(parts[1], "T count");
                if (n <= 0) {
                    throw new IllegalArgumentException("the reTrace count must be a positive integer.");
                }
                if (n > movementHistory.size()) {
                    throw new IllegalArgumentException(
                        "'" + n + "' exceeds the number of executed movements (" + movementHistory.size() + " so far).");
                }
                return new ParsedCommand(type, new int[]{n}, token);
            }
            case 'W': {
                if (parts.length != 1) {
                    throw new IllegalArgumentException("'W' does not take any additional values.");
                }
                return new ParsedCommand(type, new int[0], token);
            }
            default:
                throw new IllegalArgumentException("unrecognised command '" + parts[0] + "'.");
        }
    }

    private static int parseInt(String s, String fieldName) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be a whole number (got '" + s.trim() + "').");
        }
    }

    /** Executes one already-validated command on the real robot. */
    private static void executeCommand(ParsedCommand cmd) throws InterruptedException {
        switch (cmd.type) {
            case 'F':
                moveStraight("Forward", cmd.params[0], cmd.params[1]);
                movementHistory.add(cmd);
                break;
            case 'B':
                moveStraight("Backward", cmd.params[0], cmd.params[1]);
                movementHistory.add(cmd);
                break;
            case 'R':
                turn("Right", cmd.params[0], cmd.params[1]);
                movementHistory.add(cmd);
                break;
            case 'L':
                turn("Left", cmd.params[0], cmd.params[1]);
                movementHistory.add(cmd);
                break;
            case 'T':
                retrace(cmd.params[0]);
                break;
            case 'W':
                writeLog();
                break;
        }
    }

    private static void moveStraight(String direction, int speed, int duration) throws InterruptedException {
        int velocity = direction.equals("Backward") ? -speed : speed;
        System.out.println("-> Moving " + direction + " | left=" + velocity + ", right=" + velocity
            + " | duration=" + duration + "s");

        swiftBot.move(velocity, velocity, duration * 1000);

        totalExecutionSeconds += duration;
        battery.drain(speed, duration);
    }

    /**
     * Pivots the robot ~90 degrees on the spot, then continues straight at the
     * requested post-turn speed for the requested duration.
     */
    private static void turn(String direction, int speedAfterTurn, int duration) throws InterruptedException {
        int leftPivot, rightPivot;
        if (direction.equals("Right")) {
            leftPivot = PIVOT_SPEED;
            rightPivot = -PIVOT_SPEED;
        } else { // Left
            leftPivot = -PIVOT_SPEED;
            rightPivot = PIVOT_SPEED;
        }

        System.out.println("-> Pivoting " + direction + " ~90 degrees | left=" + leftPivot + ", right=" + rightPivot);
        swiftBot.move(leftPivot, rightPivot, PIVOT_DURATION_MS);

        System.out.println("-> Continuing straight after turn | left=" + speedAfterTurn
            + ", right=" + speedAfterTurn + " | duration=" + duration + "s");
        swiftBot.move(speedAfterTurn, speedAfterTurn, duration * 1000);

        totalExecutionSeconds += duration;
        battery.drain(speedAfterTurn, duration);
    }

    /**
     * Re-executes the last n movement commands (F/B/R/L only), most recent
     * first, as described in the spec. T commands themselves are never
     * counted or repeated.
     */
    private static void retrace(int n) throws InterruptedException {
        System.out.println("-> Retracing last " + n + " movement(s):");
        int size = movementHistory.size();
        List<ParsedCommand> toRepeat = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            toRepeat.add(movementHistory.get(size - 1 - i));
        }
        for (ParsedCommand p : toRepeat) {
            executeCommand(p); // re-executes and appends to movementHistory again
        }
    }

    private static void writeLog() {
        try {
            File dir = new File("swiftbot_logs");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss");
            String writeTime = LocalTime.now().format(timeFmt);
            String fileNameTimestamp = writeTime.replace(":", "-");
            File logFile = new File(dir, "swiftbot_log_" + fileNameTimestamp + ".txt");

            try (FileWriter writer = new FileWriter(logFile)) {
                writer.write("SwiftBot Command Log\n");
                writer.write("=====================\n");
                writer.write("Commands received (in order):\n");
                for (String entry : fullLog) {
                    writer.write("  " + entry + "\n");
                }
                writer.write("\n");
                writer.write("Total execution time of movements: " + totalExecutionSeconds + " seconds\n");
                writer.write("Log written at (time of writing): " + writeTime + "\n");
            }

            System.out.println("-> Log file saved. Full path: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("Error: could not write log file - " + e.getMessage());
        }
    }

    // ---- Helper classes ----------------------------------------------------

    /** Simple immutable holder for a parsed command. */
    private static class ParsedCommand {
        final char type;
        final int[] params;
        final String raw;

        ParsedCommand(char type, int[] params, String raw) {
            this.type = type;
            this.params = params;
            this.raw = raw;
        }
    }

    /**
     * Extra feature: simulated battery monitor. Battery drains proportionally
     * to speed x duration for every movement executed, and prints a warning
     * once battery is low.
     */
    private static class BatteryMonitor {
        private double batteryPercent = 100.0;
        private boolean lowBatteryWarned = false;

        void drain(int speed, int duration) {
            // Full-speed (100), max-duration (6s) move costs 5% battery; scaled linearly.
            double cost = (speed / (double) MAX_SPEED) * (duration / (double) MAX_DURATION) * 5.0;
            batteryPercent = Math.max(0.0, batteryPercent - cost);

            if (batteryPercent <= 20.0 && !lowBatteryWarned) {
                lowBatteryWarned = true;
                System.out.println("*** WARNING: Battery low (" + String.format("%.1f", batteryPercent)
                    + "%). Consider using lower speeds to conserve power. ***");
            }
        }
    }
}
