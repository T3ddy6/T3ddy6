import swiftbot.*; // Imports the swiftbot classes to communicate and control the swiftbot

// ─────────────────────────────────────────────────────────────────────────────
// IMPORTS
// These lines bring in Java's built-in libraries so we can use their tools.
// Without imports, Java would not know what File, Scanner, Random, etc. are.
// An import allows the program to use classes that exist in another package
// ─────────────────────────────────────────────────────────────────────────────

import java.awt.image.BufferedImage //used for image data 
import java.io.File; 
import java.io.FileWriter; 
import java.io.IOException;
import java.time.LocalTime;// Represents a date and time (used for timestamps on start, end, obstacles). Used to write log time in the required HH:MM:SS
import java.time.format.DateTimeFormatter; 
import java.util.ArrayList;//to use arrayList without having to write its full package name
import java.util.List;//to use dynamic lists. I use them to store the movement history and the commands that need to be written to the log

 */**
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
 * proportional to speed x duration; a warning is printed once battery is low.
 */
// ─────────────────────────────────────────────────────────────────────────────
// OOP CONCEPT: CLASS
// A class is a blueprint. This one is called SwiftBotNavigator
// It bundles together all the data (fields) and behaviour (methods) the robot needs.
// 'public' means other classes could access it.
// Everything in this program lives inside this one class.
// The main class called SwifttBotNavigator
//An OBJECT is an actual instance of a class
// ─────────────────────────────────────────────────────────────────────────────
public class SwiftBotNavigator {

    // ---- Constants -------------------------------------------------------
    // ─────────────────────────────────────────────────────────────────────────
    // CONSTANTS
    // 'private'      = only accessible inside this class (encapsulation). Encapsulation means keeping data protected inside a class and controlling how it can be accessed or changed
    // 'static'       = belongs to the class itself, not to any one object
    // 'final'        = the value can never be changed after it is set
    private static final int MAX_SPEED = 100;                      //spec: speed must not exceed 100
    private static final int MAX_DURATION = 6;                    // spec: no movement longer than 6 seconds
    private static final int MAX_COMMANDS_PER_CODE = 10;         // spec: max 10 chained commands per QR code
    private static final int MAX_SCAN_ATTEMPTS = 10;            // per QR-code read attempt before giving up and retrying

    private static final int PIVOT_SPEED = 60;                  //Speed used when pivoting the robot
    private static final int PIVOT_DURATION_MS = 350;           //Approximate time needed to perform a 90-degree pivot
    private static SwiftBotAPI swiftBot; // volatile tells Java that this variable can be changed by another thread, such as button event
    private static volatile boolean running = true;//means the program should continue running

    // ---- State -------------------------------------------------------------
    private static final List<ParsedCommand> movementHistory = new ArrayList<>(); // F/B/R/L only, used for retrace. ArrayList is a class that implements List. T commands are NOT stored because T should not itself be repeated when retracing
    private static final List<String> fullLog = new ArrayList<>();                // every raw command received (incl. T, W)
    private static double totalExecutionSeconds = 0.0;                            // keeps track of how long the robot has actually been executing movements
    private static final BatteryMonitor battery = new BatteryMonitor();           // BatteryMonitor = class, battery = object. new BatteryMonitor() creates an instance of the class (instance)

    // main class is where a java program starts execution
    public static void main(String[] args) throws InterruptedException {
        try {
            swiftBot = SwiftBotAPI.INSTANCE;
        } catch (Exception e) {
            System.out.println("\nI2C disabled! Let course staff know if this keeps happening.");
            System.exit(5);
        } // exception handling, the program attempts to connect to the swiftbot, if something goes wrong, it catches the error. Instead of the program suddenly crashing, you display a message

        
        swiftBot.enableButton(Button.X, () -> { // Lambda expression,tells java what should happen when X button is pressed. Lambda defines behaviour that can be passed as code.
            System.out.println("\nX button pressed - powering off after the current action finishes."); //
            running = false;
        });

        printWelcome();//prints welcome information

        //main program loop
        while (running) {
            String decoded = scanQrCode(); //// The X button may have been pressed WHILE we were scanning // check again before trying to process anything.
            if (!running) {
                break;
            }
            if (decoded == null) {  // Scanning gave up after MAX_SCAN_ATTEMPTS tries with nothing found.
                System.out.println("No QR code detected. Position a QR code in front of the camera and try again.\n");
                continue; // go straight back to the top of the loop and scan again
            }
            processQrCode(decoded);
        }
        // Loop has ended (X was pressed) - clean up and shut down.

        // Disable all SwiftBot buttons before exiting
        swiftBot.disableAllButtons();
        System.out.println("SwiftBot powered down. Goodbye!");
    }


    // ================================================================
    // WELCOME MESSAGE
    // ================================================================

    /*
     * METHOD
     *
     * A method is a block of code that performs a particular task.
     *
     * This method displays information to the user.
     *
     * void means it does not return a value.
     */

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

    private static String scanQrCode() {
        int attempts = 0; 
        /*
         * Keep trying until:
         *
         * 1. We reach MAX_SCAN_ATTEMPTS
         * OR
         * 2. running becomes false
         */
        while (attempts < MAX_SCAN_ATTEMPTS && running) {
            System.out.println("Scanning for QR code...");  // Grab a still image from the camera (a grid of pixel data in memory)...
            try {
                BufferedImage img = swiftBot.getQRImage();  // ...then try to extract text from any QR code visible in it.
                String decoded = swiftBot.decodeQRImage(img);
                if (decoded != null && !decoded.isEmpty()) { //* Check that a QR code was actually found.
                    return decoded;  // found one - stop scanning and return the text
                }
            } catch (Exception e) {
                // No QR code in view yet - just try again.
                // If scanning fails, do not crash the program, simply try again
            }
            attempts++; //Increase number of attempts
        }
        return null; // gave up - nothing found within the attempt limit
    }

    private static void processQrCode(String rawCode) { 
        String[] tokens = rawCode.split(";", -1); // split multiple commands using;
        
        //Check maximum of 10 commands
        if (tokens.length > MAX_COMMANDS_PER_CODE) {
            System.out.println("Error: a QR code cannot contain more than " + MAX_COMMANDS_PER_CODE + " commands.");
            return;
        }
        // If there is more than one command, this is a sequence
        boolean isSequence = tokens.length > 1;

        List<ParsedCommand> parsedTokens = new ArrayList<>(); // Create a list to store the parsed commands
        for (String token : tokens) { // Loop through every command
            ParsedCommand parsed;
            try {
                parsed = parseCommand(token.trim()); 
            } catch (IllegalArgumentException ex) {
                System.out.println("Error: " + ex.getMessage()); //If the command is invalid, display an error. No movement occurs because validation happens
                System.out.println("No commands were executed. Scan a corrected QR code.");
                return;
            }
 
             /*
             * The specification says T cannot appear inside a
             * multi-command sequence.
             *
             * Example:
             *
             * F,50,2;T,2
             *
             * is invalid.
             */
            if (isSequence && parsed.type == 'T') {
                System.out.println("Error: 'T' (reTrace) cannot be used within a multi-command sequence.");
                System.out.println("No commands were executed. Scan a corrected QR code.");
                return;
            }

            parsedTokens.add(parsed); // add valid command to the list
        }

        // The commands are displayed before movement begins
        System.out.println("Command(s) received: " + rawCode);
        for (ParsedCommand p : parsedTokens) { // execute each validated command in order
            fullLog.add(p.raw);
            try {
                executeCommand(p);
            } catch (InterruptedException e) {
                System.out.println("Movement interrupted.");
            }
        }
    }

    /*
     * This method converts a String into a ParsedCommand OBJECT.
     *
     * This is an example of ABSTRACTION.
     *
     * The rest of the program does not need to worry about all the
     * details of checking the command format.
     *
     * It can simply call:
     *
     *     parseCommand(...)
     *
     * and receive a ParsedCommand object.
     */
    private static ParsedCommand parseCommand(String token) {
        if (token.isEmpty()) {  //Reject empty commands
            throw new IllegalArgumentException("empty command found.");
        }
         /*
         * Split the command using commas.
         *
         * Example:
         *
         *     F,50,2
         *
         * becomes:
         *
         *     parts[0] = F
         *     parts[1] = 50
         *     parts[2] = 2
         */

        String[] parts = token.split(",");
        char type = Character.toUpperCase(parts[0].trim().charAt(0));

        switch (type) {
            case 'F':
            case 'B':
            case 'R':
            case 'L': {

                *
                 * These commands require exactly:
                 *
                 * command,speed,duration
                 *
                 * Therefore there must be 3 parts.
                 */

                if (parts.length != 3) {
                    throw new IllegalArgumentException(
                        "'" + type + "' requires exactly two values (speed,duration), e.g. '" + type + ",50,2'.");
                }
                int speed = parseInt(parts[1], type + " speed");
                int duration = parseInt(parts[2], type + " duration");

                if (speed <= 0 || speed > MAX_SPEED) { //Speed must be between 1 and 100
                    throw new IllegalArgumentException("speed must be between 1 and " + MAX_SPEED + " (got " + speed + ").");
                }
                if (duration <= 0 || duration > MAX_DURATION) { //Duration must be between 1 and 6 seconds
                    throw new IllegalArgumentException("duration must be between 1 and " + MAX_DURATION + " seconds (got " + duration + ").");
                }
                return new ParsedCommand(type, new int[]{speed, duration}, token);
            }
            case 'T': {
                if (parts.length != 2) { // 
                    throw new IllegalArgumentException("'T' requires exactly one value, e.g. 'T,3'."); // T requires exactly one value
                }
                int n = parseInt(parts[1], "T count");
                if (n <= 0) { //Retrace value must be positive
                    throw new IllegalArgumentException("the reTrace count must be a positive integer.");
                }
                if (n > movementHistory.size()) {
                    throw new IllegalArgumentException(
                        "'" + n + "' exceeds the number of executed movements (" + movementHistory.size() + " so far).");
                }
                return new ParsedCommand(type, new int[]{n}, token);
            }
            case 'W': {
                /*
                 * W does not accept any extra values.
                 *
                 * Correct:
                 *
                 *     W
                 *
                 * Incorrect:
                 *
                 *     W,5
                 */

                if (parts.length != 1) {
                    throw new IllegalArgumentException("'W' does not take any additional values.");
                }
                return new ParsedCommand(type, new int[0], token);
            }
            default:
                /*
                 * If the command isn't F, B, R, L, T or W,
                 * reject it.
                 */
                throw new IllegalArgumentException("unrecognised command '" + parts[0] + "'.");
        }
    }
      This method decides what the SwiftBot should do based on the
     * command type.
     *
     * It receives a ParsedCommand OBJECT as a parameter.
     */

    private static int parseInt(String s, String fieldName) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) { //NumberFormatException happens when Java cannot convert the string into an integer
            throw new IllegalArgumentException(fieldName + " must be a whole number (got '" + s.trim() + "').");
        }
    }


    private static void executeCommand(ParsedCommand cmd) throws InterruptedException {
        switch (cmd.type) {
            case 'F': //FORWARD
                moveStraight("Forward", cmd.params[0], cmd.params[1]); // Store the movement in history. This is required for T/reTrace
                movementHistory.add(cmd);
                break;
            case 'B': //BACKWARD
                moveStraight("Backward", cmd.params[0], cmd.params[1]);
                movementHistory.add(cmd);
                break;
            case 'R': //RIGHT
                turn("Right", cmd.params[0], cmd.params[1]);
                movementHistory.add(cmd);
                break;
            case 'L': //LEFT
                turn("Left", cmd.params[0], cmd.params[1]);
                movementHistory.add(cmd);
                break;
            case 'T': //RETRACE
                retrace(cmd.params[0]);
                break;
            case 'W': //WRITE LOG
                writeLog();
                break;
        }
    }

     //* This method controls straight movement.
     *
     * ABSTRACTION:
     *
     * The rest of the program only needs to call:
     *
     *     moveStraight("Forward", 50, 3);
     *
     * It doesn't need to know the lower-level SwiftBot API details.
     *//

    private static void moveStraight(String direction, int speed, int duration) throws InterruptedException {
        int velocity = direction.equals("Backward") ? -speed : speed;
        System.out.println("-> Moving " + direction + " | left=" + velocity + ", right=" + velocity
            + " | duration=" + duration + "s");

            /*
         * Send the movement command to the SwiftBot.
         *
         * Duration is converted from seconds to milliseconds.
         *
         * Example:
         *
         *     3 seconds × 1000 = 3000 milliseconds
         */

        swiftBot.move(velocity, velocity, duration * 1000);

        // Add movement duration to total execution time
        totalExecutionSeconds += duration;
        battery.drain(speed, duration);
    }
// ================================================================
    // TURNING
    // ================================================================

    /*
     * Turns the robot approximately 90 degrees.
     *
     * The robot has two wheels:
     *
     *     LEFT WHEEL
     *     RIGHT WHEEL
     *
     * To pivot:
     *
     * Right turn:
     *
     *     left  = positive
     *     right = negative
     *
     * Left turn:
     *
     *     left  = negative
     *     right = positive
     *//
    private static void turn(String direction, int speedAfterTurn, int duration) throws InterruptedException {
        int leftPivot, rightPivot;
        if (direction.equals("Right")) { //Rigth turn
            leftPivot = PIVOT_SPEED;
            rightPivot = -PIVOT_SPEED;
        } else { // Left  //left turn
            leftPivot = -PIVOT_SPEED;
            rightPivot = PIVOT_SPEED;
        }

        System.out.println("-> Pivoting " + direction + " ~90 degrees | left=" + leftPivot + ", right=" + rightPivot);
        swiftBot.move(leftPivot, rightPivot, PIVOT_DURATION_MS); //Perform the pivot. PIVOT_DURATION_MS determines how long the robot pivots.

        System.out.println("-> Continuing straight after turn | left=" + speedAfterTurn
            + ", right=" + speedAfterTurn + " | duration=" + duration + "s");
        swiftBot.move(speedAfterTurn, speedAfterTurn, duration * 1000);

        totalExecutionSeconds += duration; //Update total movement time
        battery.drain(speedAfterTurn, duration); //Update simulated battery
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
