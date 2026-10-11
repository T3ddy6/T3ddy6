import swiftbot.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimonSays {
    static SwiftBotAPI swiftBot;
    static int level;
    static String buttonSequence;
    static String inputSequence;

    public static void main(String[] args) {
        buttonSequence = "";
        inputSequence = "";
        level = 0;

        /*
        This program uses a string system to represent both generated button sequences and input sequences, e.g: YGR would light up Yellow-Green-Red
        Since the labels on the swiftbot buttons (A,B,X,Y) don't ALL correspond to a color, they are converted to (R,B,G,Y) respectively.
        Both sequences are stored as this converted string as opposed to using the button names for easier debugging.

        In the program, this string is processed to light up the randomly generated button sequences and input sequences as they are input, the latter for user feedback.

        The main game loop is as follows:
        1. Append buttonSequence with a random colour
        2. Get userInput. Loses if presses a button that doesn't match the correct element in the sequence at any point.
            -E.g: If buttonSequence is RGB, and user enters Y, the program will consider the run lost as soon as Y is entered.
        3. If won, check if level is a multiple of 5.
               -If is then ask to continue, if not then just continue.
           If lost, ask to retry.
        4. If at any point user doesn't want to continue, end the loop.
         */

        swiftBot = SwiftBotAPI.INSTANCE;
        Scanner scanner = new Scanner(System.in);

        //Display title screen
        titleScreen();

        //Decide whether to start the game
        while (true) {
            String ans = scanner.next();
            switch (ans) {
                case "0":
                    goodbyeMessage();
                    break;
                default:
                    startGame();
                    break;
            }
        }
    }

    public static void titleScreen() {
        clearScreen();
        System.out.println("                                                                                            \n" +
                "           ,,                                                                               \n" +
                " .M\"\"\"bgd  db                                            .M\"\"\"bgd                           \n" +
                ",MI    \"Y                                               ,MI    \"Y                           \n" +
                "`MMb.    `7MM  `7MMpMMMb.pMMMb.  ,pW\"Wq.`7MMpMMMb.      `MMb.      ,6\"Yb.`7M'   `MF',pP\"Ybd \n" +
                "  `YMMNq.  MM    MM    MM    MM 6W'   `Wb MM    MM        `YMMNq. 8)   MM  VA   ,V  8I   `\" \n" +
                ".     `MM  MM    MM    MM    MM 8M     M8 MM    MM      .     `MM  ,pm9MM   VA ,V   `YMMMa. \n" +
                "Mb     dM  MM    MM    MM    MM YA.   ,A9 MM    MM      Mb     dM 8M   MM    VVV    L.   I8 \n" +
                "P\"Ybmmd\" .JMML..JMML  JMML  JMML.`Ybmd9'.JMML  JMML.    P\"Ybmmd\"  `Moo9^Yo.  ,V     M9mmmP' \n" +
                "                                                                            ,V              \n" +
                "                                                                         OOb\"               ");

        System.out.print("Enter any key to continue... or press 0 to quit: ");
    }

    public static void startGame() {
        Scanner scanner = new Scanner(System.in);

        boolean continueGame = true;
        while (continueGame) {
            //Generates random number. 0 is A, 1 is B, 2 is X, 3 is Y
            appendButtonSequence();

            //Lights buttons and displays current level
            continueGame = lightButtonMessage();

            if (continueGame) {
                //Increments the level variable and resets inputSequence
                levelPassed();

                //Asks user if they want to continue playing if level is some multiple of 5
                if (level % 5 == 0) continueGame = levelIsSomeFifthLevel();
            }
            else continueGame = levelLost();

        }

        if (level >= 5) {
            System.out.println("You had a score of " + level + "! Victory Dance!");
            victoryDive();
        }
        goodbyeMessage();
    }

    public static void victoryDive() {
        lightRandomLights();
        rotateLeft();

        //The move method moves the bot 30cm forward or backward, accounting for variable speed depending on points scored
        move(true);
        move(false);

        rotateRight();
        rotateRight();

        move(true);
        move(false);

        rotateLeft();
        lightRandomLights();
    }

    public static void rotateLeft() {
        swiftBot.move(-50, 50, 250);
    }

    public static void rotateRight() {
        swiftBot.move(50, -50, 250);
    }

    public static void move(boolean forward) {
        //Roughly speaking, the relationship between swiftbot units and speed (on a rough, carpet-like surface) can be modelled using:
        // y=-0.35x^2+0.5x+30, where y is speed in cm/s, and x is swiftbot units.
        //In this equation, x = 0, is 100 swiftbot units, and x = 10, is 0 swiftbot units. Generally speaking, the swiftbot tends to travel 30cm/s at 100 swiftbot units (x=0).
        //In order to make the swiftbot move ~30cm every time, we need to get a speed from the above equation by inputting the correct value for x,
        //then using that speed to calculate time travelled.

        //Using f(x) = 10x+100 to determine input x

        int swiftbotUnits;
        //Assigning swiftbotUnits:
        if (level < 5) swiftbotUnits = 40;
        else if (level >= 10) swiftbotUnits = 100;
        else swiftbotUnits = level*10;

        int inputX = (int)((swiftbotUnits-100)/(-10.0));
        double speed = (int)((-0.35*Math.pow(inputX, 2)) + (0.5*inputX) + 30);
        double time = (30.0/speed)*1000;

        //Assigning swiftbotUnits to +ve or -ve depending on "forward" parameter
        swiftbotUnits = forward ? swiftbotUnits : -swiftbotUnits;

        //And finally:
        swiftBot.move(swiftbotUnits, swiftbotUnits, (int) time);
    }

    public static void lightRandomLights() {
        Random random = new Random();
        Button button = null;

        //Shuffles a list of the 4 possible colours in the game to get a random order. This is used to light the underlights in the victory dance.
        List<Character> characters = Arrays.asList('R', 'G', 'B', 'Y');
        Collections.shuffle(characters);

        //List is converted to string.
        StringBuilder string =  new StringBuilder();
        for (char c : characters) {
            string.append(c);
        }

        for (int i = 0; i < 4; i++) {
            switch (string.charAt(i)) {
                case 'R':
                    button = Button.A;
                    break;
                case 'G':
                    button = Button.X;
                    break;
                case 'B':
                    button = Button.B;
                    break;
                case 'Y':
                    button = Button.Y;
                    break;
            }

            turnOnUnderlight(button);
            //Wait so appears as lights blinking
            try {Thread.sleep(400);} catch (Exception e) {};
            swiftBot.disableUnderlights();
        }
    }

    public static void goodbyeMessage() {
        clearScreen();
        System.out.println("See you again champ!");
        try {Thread.sleep(2000);} catch (Exception e) {};
        System.exit(0);
    }

    public static boolean lightButtonMessage() {
        boolean continueGame;

        //Displays current level
        clearScreen();
        System.out.print("Level " + (level + 1));

        //Lights buttons along with its corresponding underlights depending on the generated button sequence string
        lightButtons(buttonSequence);
        continueGame = getUserInput();

        return continueGame;
    }

    public static boolean levelLost() {
        boolean continueGame = true;
        Scanner scanner  = new Scanner(System.in);

        //Print lost message.
        clearScreen();
        System.out.println("Game over!");
        System.out.println("Try again? Y/N");

        //Get user input.
        String input = scanner.nextLine();
        if (input.equalsIgnoreCase("y")) {
            level = 0;
            inputSequence = "";
            buttonSequence = "";
        } else continueGame = false;

        return continueGame;
    }

    public static boolean levelIsSomeFifthLevel() {
        Scanner scanner = new Scanner(System.in);

        //Print passed message.
        clearScreen();
        System.out.println("You've passed " + (level) + " levels. Do you wish to continue? Y/N");

        //Get user input.
        String input = scanner.nextLine();

        return input.equalsIgnoreCase("y");
    }

    public static void levelPassed() {
        level++;
        clearScreen();
        System.out.println("Level " + level + " passed!");
        inputSequence = "";

        try {Thread.sleep(3000);} catch (Exception e) {};
    }

    //Appends buttonSequence with a random new colour
    public static void appendButtonSequence() {
        Random random = new Random();
        int num = random.nextInt(4);

        switch (num) {
            case 0:
                buttonSequence = buttonSequence + "R";
                break;
            case 1:
                buttonSequence = buttonSequence + "B";
                break;
            case 2:
                buttonSequence = buttonSequence + "G";
                break;
            case 3:
                buttonSequence = buttonSequence + "Y";
                break;
        }
    }

    public static String appendInputSequence(String inputSequence, Button button) {
        switch (button) {
            case Button.A:
                inputSequence = inputSequence + "R";
                break;
            case Button.B:
                inputSequence = inputSequence + "B";
                break;
            case Button.Y:
                inputSequence = inputSequence + "Y";
                break;
            case Button.X:
                inputSequence = inputSequence + "G";
        }
        return inputSequence;
    }

    //For user feedback. Lights up corresponding color and light depending on button pressed
    public static boolean buttonPressed(Button button) {
        inputSequence = appendInputSequence(inputSequence, button);

        //For user feedback
        turnOnUnderlight(button);
        swiftBot.toggleButtonLight(button);
        try {Thread.sleep(250);} catch (Exception e) {}
        swiftBot.disableUnderlights();
        swiftBot.toggleButtonLight(button);

        //In main function, stop getting user input when:
        return (
            inputSequence.length() == buttonSequence.length()
            || !inputSequence.regionMatches(true, 0, buttonSequence, 0, inputSequence.length())
        );
    }

    //Appends inputSequence with the buttons the user inputs and compares to the button sequence, returning true if matches and false otherwise
    public static boolean getUserInput() {
        AtomicBoolean done = new AtomicBoolean(false);

        swiftBot.enableButton(Button.A, () -> {
            clearScreen();
            System.out.println("Level " +  (level + 1));
            System.out.println("Button A was pressed");

            done.set(buttonPressed(Button.A));
        });

        swiftBot.enableButton(Button.B, () -> {
            clearScreen();
            System.out.println("Level " +  (level + 1));
            System.out.println("Button B was pressed");

            done.set(buttonPressed(Button.B));
        });

        swiftBot.enableButton(Button.X, () -> {
            clearScreen();
            System.out.println("Level " +  (level + 1));
            System.out.println("Button X was pressed");

            done.set(buttonPressed(Button.X));
        });

        swiftBot.enableButton(Button.Y, () -> {
            clearScreen();
            System.out.println("Level " +  (level + 1));
            System.out.println("Button Y was pressed");

            done.set(buttonPressed(Button.Y));
        });

        while (!done.get()) {
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        swiftBot.disableAllButtons();

        return inputSequence.regionMatches(true, 0, buttonSequence, 0, inputSequence.length());
    }

    //Iterates through the buttonSequence string and lights up the corresponding underlight and color
    public static void lightButtons(String buttonSequence) {
        for (int i = 0; i < buttonSequence.length(); i++) {
            Button button = null;
            //Turning on button light and corresponding underlight
            //A is red, B is blue, X is green, Y is yellow
            switch (buttonSequence.charAt(i)) {
                case ('R'):
                    button = Button.A;
                    break;
                case ('G'):
                    button = Button.X;
                    break;
                case ('B'):
                    button = Button.B;
                    break;
                case ('Y'):
                    button = Button.Y;
                    break;
            }

            swiftBot.setButtonLight(button, true);
            turnOnUnderlight(button);

            //Wait a second
            try {Thread.sleep(1000);} catch (Exception e) {};

            //Toggling the button light and turning off underlights.
            swiftBot.toggleButtonLight(button);
            swiftBot.disableUnderlights();

            //Small wait so they're completely off.
            try {Thread.sleep(250);} catch (Exception e) {}
        }
    }

    //Defines colors as int[] and displays the correct underlight and color depending on passed button
    public static void turnOnUnderlight(Button button) {
        int[] red = new int[] { 255, 0, 0 };
        int[] blue = new int[] { 0, 0, 255 };
        int[] green = new int[] { 0, 255, 0 };
        int[] yellow = new int[] { 255, 255, 0 };

        try {
            if (button.equals(Button.A)) {
                swiftBot.setUnderlight(Underlight.MIDDLE_LEFT, red);
            }
            else if (button.equals(Button.B)) {
                swiftBot.setUnderlight(Underlight.BACK_LEFT, blue);
            }
            else if (button.equals(Button.X)) {
                swiftBot.setUnderlight(Underlight.MIDDLE_RIGHT, green);
            }
            else if (button.equals(Button.Y)) {
                swiftBot.setUnderlight(Underlight.BACK_RIGHT, yellow);
            }

        } catch (Exception e) {
            System.out.println("ERROR");
        }

    }

    public static void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

}
