import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Handles terminal input and shared console display helpers.
// Format plain text first, then add ANSI styles without changing visible widths.
// Screens are still described with + - = | characters; the renderer below turns them
// into ZendCash-style box drawing (and the baby-pink palette) when text is printed.
public class ConsoleUI {
    // Box-drawing characters need UTF-8 on the console.
    static {
        try {
            System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException ignored) {
            // Keep the default stream if UTF-8 is somehow unavailable.
        }
    }

    static final String LINE = "+==========================================================+";
    static final int STANDARD_WIDTH = LINE.length();

    // Exact display widths used by the table screens.
    // Each width matches the visible length of its formatted table rows.
    static final int GAME_TABLE_WIDTH = 93;
    static final int ORDER_TABLE_WIDTH = 84;
    static final int EDIT_ORDER_TABLE_WIDTH = 58;
    static final int QUEUE_TABLE_WIDTH = 73;
    static final int HISTORY_TABLE_WIDTH = 118;
    static final int RETURN_TABLE_WIDTH = 95;
    static final int ACTIVE_TABLE_WIDTH = 112;
    static final int OVERDUE_TABLE_WIDTH = 110;
    static final int AUDIT_TABLE_WIDTH = 87;
    static final int CHECKOUT_QUEUE_TABLE_WIDTH = 81;
    private final Scanner input = new Scanner(System.in);
    private final int terminalWidth = Math.max(40, Integer.getInteger("q2p.width", configuredWidth()));

    private static int configuredWidth() {
        try {
            return Integer.parseInt(System.getenv("COLUMNS"));
        }
        catch (NumberFormatException e) {
            return 120;
        }
    }
    private int currentWidth = STANDARD_WIDTH;
    private static final String RESET = "\u001B[0m";
    private static final String PINK = "\u001B[38;5;218m"; // ZendCash baby pink
    private static final String SOFT_WHITE = "\u001B[97m";
    private static final String BOLD_WHITE = "\u001B[1;97m";
    private static final String RED = "\u001B[91m";
    private static final String YELLOW = "\u001B[93m";
    private static final String GREEN = "\u001B[92m";
    private static final String AVAILABLE_GREEN = "\u001B[38;2;21;71;52m"; // #154734
    private static final String MUSTARD = "\u001B[38;2;212;160;23m"; // #D4A017
    private static final Pattern STATUS_WORDS = Pattern.compile("\\b(VIP|AVAILABLE|OUT OF STOCK|COMPLETED|OVERDUE|PS5|Xbox|Nintendo)\\b");
    // Run with -Dq2p.ascii=true to keep plain + - | borders on terminals without UTF-8.
    private final boolean unicode = !"true".equalsIgnoreCase(System.getProperty("q2p.ascii"));
    private final boolean colorsEnabled = !"false".equalsIgnoreCase(System.getProperty("q2p.color"))
        && System.getenv("NO_COLOR") == null;
    private String borderTone = PINK;
    private String textTone = "";


    // ---- ZendCash artwork ----
    private static final String[] WELCOME_ART = {
        " __       __)",
        "(, )  |  /     /)",
        "   | /| /  _  // _  ______    _    _/_ ___",
        "   |/ |/ _(/_(/_(__(_) // (__(/_   (__(_)",
        "   /  |"
    };
    private static final String[] LOGO_QUEUE = {
        " ██████╗ ██╗   ██╗███████╗██╗   ██╗███████╗",
        "██╔═══██╗██║   ██║██╔════╝██║   ██║██╔════╝",
        "██║   ██║██║   ██║█████╗  ██║   ██║█████╗  ",
        "██║▄▄ ██║██║   ██║██╔══╝  ██║   ██║██╔══╝  ",
        "╚██████╔╝╚██████╔╝███████╗╚██████╔╝███████╗",
        " ╚══▀▀═╝  ╚═════╝ ╚══════╝ ╚═════╝ ╚══════╝"
    };
    private static final String[] LOGO_PLAY = {
        "██████╗ ██████╗ ██╗      █████╗ ██╗   ██╗",
        "╚════██╗██╔══██╗██║     ██╔══██╗╚██╗ ██╔╝",
        " █████╔╝██████╔╝██║     ███████║ ╚████╔╝ ",
        "██╔═══╝ ██╔═══╝ ██║     ██╔══██║  ╚██╔╝  ",
        "███████╗██║     ███████╗██║  ██║   ██║   ",
        "╚══════╝╚═╝     ╚══════╝╚═╝  ╚═╝   ╚═╝   "
    };
    private static final String[] THANK_YOU_ART = {
        "████████╗██╗  ██╗ █████╗ ███╗   ██╗██╗  ██╗    ██╗   ██╗ ██████╗ ██╗   ██╗",
        "╚══██╔══╝██║  ██║██╔══██╗████╗  ██║██║ ██╔╝    ╚██╗ ██╔╝██╔═══██╗██║   ██║",
        "   ██║   ███████║███████║██╔██╗ ██║█████╔╝      ╚████╔╝ ██║   ██║██║   ██║",
        "   ██║   ██╔══██║██╔══██║██║╚██╗██║██╔═██╗       ╚██╔╝  ██║   ██║██║   ██║",
        "   ██║   ██║  ██║██║  ██║██║ ╚████║██║  ██╗       ██║   ╚██████╔╝╚██████╔╝",
        "   ╚═╝   ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝  ╚═╝       ╚═╝    ╚═════╝  ╚═════╝ "
    };

    // Display validation once, below the next input guide or before an ENTER pause.
    private String pendingMessage = null;
    private String pendingMessageType = null;
    private boolean checkoutDisplay = false;
    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("MMM dd", java.util.Locale.ENGLISH);

    private String color(String text, String tone) {
        if (!colorsEnabled || tone.isEmpty() || text.isEmpty()) {
            return text;
        }
        return tone + text + RESET;
    }

    private String colorStatuses(String text) {
        Matcher matcher = STATUS_WORDS.matcher(text);
        StringBuilder styled = new StringBuilder();
        int start = 0;
        while (matcher.find()) {
            styled.append(text, start, matcher.start());
            String word = matcher.group();
            String tone = GREEN;
            if (word.equals("VIP")) {
                tone = YELLOW;
            }
            else if (word.equals("AVAILABLE")) {
                tone = AVAILABLE_GREEN;
            }
            else if (word.equals("OUT OF STOCK")) {
                tone = MUSTARD;
            }
            else if (word.equals("OVERDUE")) {
                tone = RED;
            }
            else if (word.equals("PS5")) {
                tone = "\u001B[1;94m";
            }
            else if (word.equals("Xbox")) {
                tone = "\u001B[1;92m";
            }
            else if (word.equals("Nintendo")) {
                tone = "\u001B[1;91m";
            }
            styled.append(color(word, tone));
            start = matcher.end();
        }
        styled.append(text.substring(start));
        return styled.toString();
    }

    private String styleContent(String text) {
        if (!textTone.isEmpty()) {
            return color(text, textTone);
        }
        String trimmed = text.trim();
        if (trimmed.matches("[A-Z][A-Z0-9 >/&!_-]*")) {
            return color(text, BOLD_WHITE);
        }
        int colon = text.indexOf(':');
        if (colon >= 0 && text.substring(0, colon).trim().matches("[A-Za-z][A-Za-z ]*")) {
            return color(text.substring(0, colon + 1), BOLD_WHITE)
                + colorStatuses(text.substring(colon + 1));
        }
        return colorStatuses(text);
    }

    // ---- Box renderer -------------------------------------------------------
    // A border line is held back until the next line is known, so it can become a
    // top (corner down), middle (T-junction) or bottom (corner up) edge.
    private static final int EDGE_TOP = 0, EDGE_MID = 1, EDGE_BOTTOM = 2;
    private boolean lightBox = false;      // single-line panels: input bar and message boxes
    private boolean lastWasRow = false;
    private String pendingBorder = null;
    private char pendingFill = '-';
    private boolean pendingLight = false;
    private boolean pendingAfterRow = false;
    private boolean pendingLabeled = false;
    private String pendingTone = "";

    private String styleSeparators(String text) {
        String styled = styleContent(text);
        if (!textTone.isEmpty()) {
            return styled;
        }
        String separator = "|";
        if (unicode) {
            separator = "\u2502";
        }
        return styled.replace(" | ", " " + color(separator, borderTone) + " ");
    }

    private void printLine() {
        emitLine("");
    }

    // Every direct write goes through emit so a waiting border is drawn first.
    private void emit(String text) {
        flushBorder(false);
        System.out.print(text);
        lastWasRow = false;
    }

    private void emitLine(String text) {
        flushBorder(false);
        System.out.println(text);
        lastWasRow = false;
    }

    private String wall() {
        if (lightBox) {
            return color("\u2502", borderTone);
        }
        return color("\u2551", borderTone);
    }

    private void queueBorder(String line) {
        boolean labeled = !line.matches("[+\\-=]+");
        char fill = '-';
        if (line.indexOf('=') >= 0) {
            fill = '=';
        }
        // Two plain borders in a row are drawn as one shared seam.
        if (pendingBorder != null && !labeled && !pendingLabeled && pendingLight == lightBox) {
            if (fill == '=') {
                pendingFill = '=';
            }
            return;
        }
        flushBorder(false);
        pendingBorder = line;
        pendingFill = fill;
        pendingLight = lightBox;
        pendingTone = borderTone;
        pendingAfterRow = lastWasRow;
        pendingLabeled = labeled;
        lastWasRow = false;
    }

    private void flushBorder(boolean nextIsRow) {
        if (pendingBorder == null) {
            return;
        }
        int edge = EDGE_BOTTOM;
        if (!pendingAfterRow) {
            edge = EDGE_TOP;
        } else if (nextIsRow) {
            edge = EDGE_MID;
        }
        String text = renderBorder(pendingBorder, pendingFill, pendingLight, edge, pendingTone);
        pendingBorder = null;
        System.out.println(text);
    }

    private String renderBorder(String line, char fill, boolean light, int edge, String tone) {
        String corners;
        if (light) {
            if (edge == EDGE_TOP) {
                corners = "\u250C\u2510";
            } else if (edge == EDGE_MID) {
                corners = "\u251C\u2524";
            } else {
                corners = "\u2514\u2518";
            }
        } else if (fill == '=') {
            if (edge == EDGE_TOP) {
                corners = "\u2554\u2557";
            } else if (edge == EDGE_MID) {
                corners = "\u2560\u2563";
            } else {
                corners = "\u255A\u255D";
            }
        } else {
            if (edge == EDGE_TOP) {
                corners = "\u2553\u2556";
            } else if (edge == EDGE_MID) {
                corners = "\u255F\u2562";
            } else {
                corners = "\u2559\u255C";
            }
        }
        String horizontal = "\u2500";
        if (!light && fill == '=') {
            horizontal = "\u2550";
        }
        Matcher label = Pattern.compile("^\\+(-*) (.*?) (-*)\\+$").matcher(line);
        if (label.matches()) {
            return color(corners.charAt(0) + "\u2500".repeat(label.group(1).length()) + " ", tone)
                + color(label.group(2), BOLD_WHITE)
                + color(" " + "\u2500".repeat(label.group(3).length()) + corners.charAt(1), tone);
        }
        return color(corners.charAt(0) + horizontal.repeat(Math.max(0, line.length() - 2))
            + corners.charAt(1), tone);
    }

    // One finished row (already colored) between two walls.
    private void printStyledRow(String styled) {
        flushBorder(true);
        System.out.println(styled);
        lastWasRow = true;
    }

    private void printLine(String line) {
        if (line.length() > terminalWidth && !checkoutDisplay) {
            if (line.matches("[+\\-=]+")) {
                printLine(line.substring(0, terminalWidth));
                return;
            }
            for (int start = 0; start < line.length(); start += terminalWidth) {
                printLine(line.substring(start, Math.min(start + terminalWidth, line.length())));
            }
            return;
        }
        if (!unicode) {
            printAsciiLine(line);
            return;
        }
        if (line.length() >= 2 && line.startsWith("+") && line.endsWith("+")) {
            queueBorder(line);
        } else if (line.length() >= 2 && line.startsWith("|") && line.endsWith("|")) {
            printStyledRow(wall() + styleSeparators(line.substring(1, line.length() - 1)) + wall());
        } else if (line.matches("[\\-=]+")) {
            emitLine(color(line.replace('-', '\u2500').replace('=', '\u2550'), borderTone));
        } else {
            emitLine(styleSeparators(line));
        }
    }

    // Plain ASCII layout, kept for -Dq2p.ascii=true.
    private void printAsciiLine(String line) {
        if (line.matches("[+\\-=]+")) {
            emitLine(color(line, borderTone));
        } else if (line.startsWith("+") && line.endsWith("+")) {
            Matcher label = Pattern.compile("^(\\+-* )(.*?)( -*\\+)$").matcher(line);
            if (label.matches()) {
                emitLine(color(label.group(1), borderTone)
                    + color(label.group(2), BOLD_WHITE) + color(label.group(3), borderTone));
            } else {
                emitLine(color(line, borderTone));
            }
        } else if (line.startsWith("|") && line.endsWith("|")) {
            emitLine(color("|", borderTone)
                + styleSeparators(line.substring(1, line.length() - 1))
                + color("|", borderTone));
        } else {
            emitLine(styleSeparators(line));
        }
    }

    private void printFormatted(String output) {
        for (String line : output.split("\\R")) {
            printLine(line);
        }
    }

    String readText(String prompt) {
        showPendingMessage(currentWidth);
        emit(prompt);
        if (System.console() != null && System.getProperty("os.name").startsWith("Windows")) {
            System.out.flush();
            return readWindowsInput(false).trim();
        }
        if (System.console() != null) {
            String line = System.console().readLine();
            if (line == null) {
                throw new InputEndedException();
            }
            return line.trim();
        }
        if (!input.hasNextLine()) {
            printLine("\nInput ended. Goodbye!");
            throw new InputEndedException();
        }
        return input.nextLine().trim();
    }

    // Windows reads keys without echoing them and displays one * per character.
    String readPassword(String prompt) {
        showPendingMessage(currentWidth);
        emit("");   // draw the input panel's bottom border before the hidden prompt
        if (System.getProperty("os.name").startsWith("Windows")) {
            emit(prompt);
            System.out.flush();
            return readWindowsInput(true);
        }
        if (System.console() != null) {
            char[] password = System.console().readPassword("%s", prompt);
            if (password == null) {
                throw new InputEndedException();
            }
            String value = new String(password);
            java.util.Arrays.fill(password, '\0');
            return value;
        }
        throw new IllegalArgumentException("Run QUEUE2PLAY in an interactive terminal to enter a hidden password.");
    }

    private String readWindowsInput(boolean mask) {
        // Only the encoded result goes through the pipe to Java; input editing stays in the terminal.
        String echo = "[Console]::Error.Write($key.KeyChar)";
        if (mask) {
            echo = "[Console]::Error.Write('*')";
        }
        String script = "$ErrorActionPreference = 'Stop'; "
            + "$text = New-Object System.Text.StringBuilder; "
            + "while ($true) { "
            + "$key = [Console]::ReadKey($true); "
            + "if ($key.Key -eq [ConsoleKey]::Enter) { [Console]::Error.WriteLine(); break }; "
            + "if ($key.Key -eq [ConsoleKey]::Backspace) { "
            + "if ($text.Length -gt 0) { $text.Length--; [Console]::Error.Write([string][char]8 + ' ' + [char]8) }; continue }; "
            + "if (-not [char]::IsControl($key.KeyChar)) { "
            + "[void]$text.Append($key.KeyChar); "
            + echo + " } }; "
            + "[Console]::Out.WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text.ToString())))";

        try {
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-Command", script);
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = builder.start();
            String encoded;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                encoded = reader.readLine();
            }
            if (process.waitFor() != 0 || encoded == null) {
                throw new IllegalArgumentException("Interactive input requires a Windows terminal. Use run.ps1 in the VS Code terminal.");
            }
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
            String password = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            java.util.Arrays.fill(bytes, (byte) 0);
            return password;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot start password masking. Windows PowerShell is required.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InputEndedException();
        }
    }

    int readNumber(String prompt) {
        String text = readText(prompt);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // One input panel owns the label and guide for each prompt.
    void inputBar(String label) {
        inputBar(label, currentWidth);
    }

    void inputBar(String label, int width) {
        inputBar(label, width, inputGuide(label));
    }

    void inputBar(String label, int width, String guide) {
        currentWidth = width;
        String previousBorder = borderTone;
        String previousText = textTone;
        boolean previousLight = lightBox;
        borderTone = PINK;
        lightBox = true;
        String text = " " + label.trim() + " ";
        int remaining = Math.max(0, width - 2 - text.length());
        int left = remaining / 2;
        int right = remaining - left;
        printLine("+" + "-".repeat(left) + text + "-".repeat(right) + "+");
        textTone = SOFT_WHITE;
        printBoxCentered("", width);
        if (checkoutDisplay && label.equals("ENTER CHOICE")) {
            printBoxCentered(guide, width);
        } else {
            printMessageRows(guide, width);
        }
        printBoxCentered("", width);
        textTone = previousText;
        printLine(border(width, '-'));
        borderTone = previousBorder;
        lightBox = previousLight;
    }

    private String inputGuide(String label) {
        switch (label) {
            case "FULL NAME": return "Enter your full name. Letters, spaces, hyphens and apostrophes are accepted.";
            case "USERNAME": return "Use 3-32 letters, numbers, dots, underscores or hyphens.";
            case "USERNAME OR EMAIL": return "Enter your registered username or email.";
            case "EMAIL": return "Enter your email address.\nExample: juancruz@gmail.com";
            case "PASSWORD": return "Enter your password, then press ENTER.";
            case "CONFIRM PASSWORD": return "Type the same password again.";
            case "SEARCH GAME": return "Enter a game ID, title, or platform.";
            case "ENTER QUANTITY":
            case "QUANTITY TO ADD":
            case "QUANTITY TO DECREASE": return "Enter a whole number of copies.";
            case "RENTAL DURATION": return "Enter 1-" + GameRentalSystem.MAX_RENTAL_DAYS + " days.";
            case "LINE NUMBER": return "Enter the cart line number to remove.";
            case "QUEUE NUMBER TO CANCEL": return "Enter the queue number of the request you want to cancel.\nExample: type 3 or 003 for queue #003.";
            case "DAYS LATE": return "Enter days late; use 0 if returned on time.";
            case "GAME TITLE": return "Enter the title of the game to add.";
            case "RENTAL PRICE": return "Enter a price from " + money(GameRentalSystem.MIN_RENTAL_PRICE)
                + " to " + money(GameRentalSystem.MAX_RENTAL_PRICE) + ".";
            case "AVAILABLE COPIES": return "Enter 1-" + GameRentalSystem.MAX_GAME_STOCK + " copies.";
            case "ENTER Y / N": return "Type Y for Yes or N for No, then press ENTER.";
            default: return "Type an option number or navigation key,\nthen press ENTER to continue.";
        }
    }

    // Typing starts at a fixed indent below the input panel.
    String prompt() {
        return prompt(currentWidth);
    }

    String prompt(int width) {
        currentWidth = width;
        return "  " + color(">", PINK) + " ";
    }

    // Fake clear-screen: pushes the previous screen out of the visible terminal area.
    // Clearing needs no ANSI cursor movement.
    void clearScreen() {
        currentWidth = STANDARD_WIDTH;
        borderTone = PINK;
        textTone = "";
        lightBox = false;
        emit("\n".repeat(60));
    }

    void pause(int milliseconds) {
        if (Boolean.getBoolean("q2p.fast")) {
            return;
        }
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Redraw each complete frame centered within the current screen width.
    void loading(String message) {
        if (Boolean.getBoolean("q2p.fast")) {
            return;
        }
        printLine();
        String label = fit(message, Math.max(1, currentWidth - 3));
        emit(color(center(label, currentWidth), YELLOW));
        try {
            for (int dots = 1; dots <= 3; dots++) {
                Thread.sleep(350);
                emit("\r" + color(center(label + ".".repeat(dots), currentWidth), YELLOW));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        printLine();
    }

    void waitForEnter() {
        waitForEnter(currentWidth);
    }

    void waitForEnter(int width) {
        showPendingMessage(width);
        printLine();
        emit(color(center("Press ENTER to continue...", width), PINK));
        if (System.console() != null) {
            if (System.console().readLine() == null) {
                throw new InputEndedException();
            }
        } else {
            if (!input.hasNextLine()) {
                throw new InputEndedException();
            }
            input.nextLine();
        }
        // If the user already stopped to read the message, do not repeat it on the next screen.
        pendingMessage = null;
        pendingMessageType = null;
    }

    void success(String message) {
        showPendingMessage(currentWidth);
        printMessageBox("SUCCESS: " + message, currentWidth);
    }

    void error(String message) {
        pendingMessageType = "ERROR";
        pendingMessage = message;
    }

    void warning(String message) {
        pendingMessageType = "WARNING";
        pendingMessage = message;
    }

    void info(String message) {
        printLine(message);
    }

    // Consume the message only at the input area, never in a screen header.
    private void showPendingMessage(int width) {
        if (pendingMessage == null || pendingMessage.isEmpty()) {
            return;
        }
        if (checkoutDisplay) {
            showMessageCard(pendingMessageType + ": " + pendingMessage);
        } else {
            printMessageBox(pendingMessageType + ": " + pendingMessage, width);
        }
        pendingMessage = null;
        pendingMessageType = null;
    }

    private void printMessageBox(String text, int width) {
        String previousBorder = borderTone;
        String previousText = textTone;
        String tone = RED;
        if (text.startsWith("WARNING:")) {
            tone = YELLOW;
        }
        else if (text.startsWith("SUCCESS:")) {
            tone = GREEN;
        }
        boolean previousLight = lightBox;
        borderTone = tone;
        textTone = tone;
        lightBox = true;
        printLine();
        printLine(border(width, '-'));
        printBoxCentered("", width);
        printMessageRows(text, width);
        printBoxCentered("", width);
        printLine(border(width, '-'));
        borderTone = previousBorder;
        textTone = previousText;
        lightBox = previousLight;
    }

    private void printMessageRows(String text, int width) {
        int contentWidth = Math.max(1, width - 4);
        for (String paragraph : text.split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > contentWidth) {
                    printBoxCentered(line.toString(), width);
                    line.setLength(0);
                }
                // Split long tokens without dropping any part of the message.
                while (word.length() > contentWidth) {
                    printBoxCentered(word.substring(0, contentWidth), width);
                    word = word.substring(contentWidth);
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            printBoxCentered(line.toString(), width);
        }
    }

    void showEmptyState(String message) {
        printLine(border(currentWidth, '-'));
        printBoxCentered("", currentWidth);
        printMessageRows(message, currentWidth);
        printBoxCentered("", currentWidth);
        printLine(border(currentWidth, '-'));
    }

    String platformColor(String platform) {
        // Keep labels plain until after formatting so ANSI codes do not affect columns.
        return platform;
    }
    String membershipColor(boolean vip) {
        if (vip) {
            return "VIP";
        }
        return "REGULAR";
    }
    String statusColor(String status) {
        return status;
    }

    // Generic header used by information screens.
    void printHeader(String title) {
        printHeader(title, STANDARD_WIDTH);
    }

    void printHeader(String title, int width) {
        clearScreen();
        currentWidth = width;
        printLine(border(width, '='));
        printBoxCentered("", width);
        printTitle(title, width);
        printBoxCentered("", width);
        printLine(border(width, '='));
    }

    void printHeader(String path, String title) {
        printHeader(path, title, STANDARD_WIDTH);
    }

    void printHeader(String path, String title, int width) {
        clearScreen();
        currentWidth = width;
        printLine(border(width, '='));
        printBoxCentered("", width);
        printTitle(path, width);
        printBoxCentered("", width);
        printLine(border(width, '='));
        printBoxCentered("", width);
        printTitle(title, width);
        printBoxCentered("", width);
        printLine(border(width, '='));
    }

    String tableLine(int width) {
        return "-".repeat(Math.max(1, width));
    }

    String border(int width, char fill) {
        if (width < 2) {
            return String.valueOf(fill).repeat(Math.max(1, width));
        }
        return "+" + String.valueOf(fill).repeat(width - 2) + "+";
    }

    void printBoxCentered(String text, int width) {
        int inner = Math.max(0, width - 2);
        printLine("|" + center(fit(text, inner), inner) + "|");
    }

    private void printTitle(String title, int width) {
        String previousText = textTone;
        textTone = BOLD_WHITE;
        printBoxCentered(title, width);
        textTone = previousText;
    }

    // Center the whole block while keeping labels and colons aligned.
    private void printCenteredDetails(String[] details) {
        int labelWidth = 0;
        for (String detail : details) {
            int colon = detail.indexOf(':');
            if (colon >= 0) {
                labelWidth = Math.max(labelWidth, detail.substring(0, colon).trim().length());
            }
        }
        String[] rows = new String[details.length];
        int blockWidth = 0;
        for (int i = 0; i < details.length; i++) {
            int colon = details[i].indexOf(':');
            if (colon >= 0) {
                rows[i] = String.format("%-" + labelWidth + "s : %s",
                    details[i].substring(0, colon).trim(), details[i].substring(colon + 1).trim());
            } else {
                rows[i] = details[i];
            }
            blockWidth = Math.max(blockWidth, rows[i].length());
        }
        blockWidth = Math.min(blockWidth, currentWidth - 4);
        printLine(border(currentWidth, '-'));
        printBoxCentered("", currentWidth);
        for (String row : rows) {
            if (row.isEmpty()) {
                printBoxCentered("", currentWidth);
            }
            while (!row.isEmpty()) {
                int length = Math.min(row.length(), blockWidth);
                String part = row.substring(0, length);
                printBoxCentered(part + " ".repeat(blockWidth - part.length()), currentWidth);
                row = row.substring(length);
            }
        }
        printBoxCentered("", currentWidth);
        printLine(border(currentWidth, '-'));
    }

    private void showFeatureOptions(String[] options) {
        printBoxCentered("", currentWidth);
        for (String option : options) {
            printBoxCentered(option, currentWidth);
        }
        printBoxCentered("", currentWidth);
    }

    String fit(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (maxLength <= 0) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 3) {
            return text.substring(0, maxLength);
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    private String center(String text, int width) {
        if (text == null) {
            text = "";
        }
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        int left = (width - text.length()) / 2;
        int right = width - text.length() - left;
        return " ".repeat(left) + text + " ".repeat(right);
    }

    String money(double amount) {
        return String.format("$%.2f", amount);
    }

    private String summaryText(String text, int width) {
        if (text.length() > width) {
            return text.substring(0, width);
        }
        return text;
    }

    private String rentalDurationText(int days) {
        if (days == 1) {
            return days + " day";
        }
        return days + " days";
    }
    String queueLabel(int number) {
        return String.format("#%03d", number);
    }
    String rentalLabel(int number) {
        return String.format("#%03d", number);
    }

    // Navigation appears in one centered footer row below the feature actions.
    void showNavigationOptions(String[] options) {
        showNavigationOptions(currentWidth, options);
    }

    void showNavigationOptions(int width, String[] options) {
        currentWidth = width;
        printLine(border(width, '-'));
        printBoxCentered("", width);
        printBoxCentered(navigationRow(width, options), width);
        printBoxCentered("", width);
        printLine(border(width, '-'));
    }

    private void showBoxNavigationOptions(String[] options) {
        printLine(border(STANDARD_WIDTH, '-'));
        printBoxCentered("", STANDARD_WIDTH);
        printBoxCentered(navigationRow(STANDARD_WIDTH, options), STANDARD_WIDTH);
        printBoxCentered("", STANDARD_WIDTH);
    }

    private String navigationRow(int width, String[] options) {
        String row = String.join("   ", options);
        if (row.length() <= width - 2) {
            return row;
        }
        return String.join(" ", options);
    }

    void showSelectQuantityScreen(Game game, int inCart, int maxPerGame, int maximumToAdd) {
        printHeader("CLIENT > RENT GAMES", "SELECT QUANTITY");
        printCenteredDetails(new String[] {
            "Game: " + game.title,
            "Platform: " + platformColor(game.platform),
            "Already in Cart: " + inCart,
            "Available Stock: " + game.availableCopies,
            "Maximum Per Game: " + maxPerGame,
            "You may request up to: " + maximumToAdd
        });
        printBoxCentered("Requests wait when stock is not enough.", STANDARD_WIDTH);
        printBoxCentered(navigationRow(STANDARD_WIDTH, new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"}), STANDARD_WIDTH);
        printLine(border(STANDARD_WIDTH, '='));
        inputBar("ENTER QUANTITY");
    }

    void showYesNoPrompt(String question) {
        int width = currentWidth;
        printLine();
        printLine(border(width, '-'));
        printBoxCentered("", width);
        printWrappedBoxText(question, width);
        printBoxCentered("", width);
        printLine(border(width, '-'));
        inputBar("ENTER Y / N", width);
    }

    private void printWrappedBoxText(String text, int width) {
        int contentWidth = Math.max(1, width - 4);
        if (text == null || text.isBlank()) {
            printBoxCentered("", width);
            return;
        }
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() == 0) {
                line.append(word);
            } else if (line.length() + 1 + word.length() <= contentWidth) {
                line.append(' ').append(word);
            } else {
                printBoxCentered(line.toString(), width);
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) {
            printBoxCentered(line.toString(), width);
        }
    }

    void showMainMenu() {
        clearScreen();
        if (!unicode) {
            showPlainMainMenu();
            return;
        }
        printLine("+==========================================================+");
        printArtBlock(WELCOME_ART);
        printLogo();
        printLine("+==========================================================+");
        printMenuButton("[1] Login");
        printMenuButton("[2] Register");
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Exit"});
        printLine("+==========================================================+");
        inputBar("ENTER CHOICE");
    }

    private void showPlainMainMenu() {
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                        QUEUE2PLAY                        |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                        [1] Login                         |");
        printLine("|                       [2] Register                       |");
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Exit"});
        printLine("+==========================================================+");
        inputBar("ENTER CHOICE");
    }

    // Pink ASCII art centered inside the current screen box.
    private void printArtBlock(String[] art) {
        int inner = STANDARD_WIDTH - 2;
        printStyledRow(wall() + " ".repeat(inner) + wall());
        int blockWidth = 0;
        for (String row : art) {
            blockWidth = Math.max(blockWidth, row.length());
        }
        for (String row : art) {
            // Pad first so the whole figure is centered as one block, not row by row.
            String padded = row + " ".repeat(blockWidth - row.length());
            printStyledRow(wall() + color(center(padded, inner), PINK) + wall());
        }
        printStyledRow(wall() + " ".repeat(inner) + wall());
    }

    // QUEUE2PLAY logo in the same block font as the ZendCash banner.
    void printLogo() {
        int inner = STANDARD_WIDTH - 2;
        for (String row : LOGO_QUEUE) {
            printStyledRow(wall() + color(center(row, inner), PINK) + wall());
        }
        printStyledRow(wall() + " ".repeat(inner) + wall());
        for (String row : LOGO_PLAY) {
            printStyledRow(wall() + color(center(row, inner), PINK) + wall());
        }
        printStyledRow(wall() + " ".repeat(inner) + wall());
    }

    // ZendCash-style menu button: a small double-line box with the option inside.
    private void printMenuButton(String label) {
        int inner = STANDARD_WIDTH - 2;
        int buttonWidth = 28;
        String blank = " ".repeat(inner);
        String top = "\u2554" + "\u2550".repeat(buttonWidth - 2) + "\u2557";
        String bottom = "\u255A" + "\u2550".repeat(buttonWidth - 2) + "\u255D";
        String side = color("\u2551", PINK);
        String middle = side + color(center(label, buttonWidth - 2), BOLD_WHITE) + side;
        int pad = (inner - buttonWidth) / 2;
        String left = " ".repeat(pad);
        String right = " ".repeat(inner - buttonWidth - pad);
        printStyledRow(wall() + blank + wall());
        printStyledRow(wall() + left + color(top, PINK) + right + wall());
        printStyledRow(wall() + left + middle + right + wall());
        printStyledRow(wall() + left + color(bottom, PINK) + right + wall());
    }

    // Closing screen shown when the program exits.
    void showGoodbye() {
        printLine();
        if (unicode && terminalWidth >= THANK_YOU_ART[0].length()) {
            for (String row : THANK_YOU_ART) {
                emitLine(color(row, PINK));
            }
            printLine();
        }
        int width = STANDARD_WIDTH;
        if (unicode) {
            width = Math.min(terminalWidth, 76);
        }
        emitLine(color(center("Thank you for using QUEUE2PLAY. Goodbye!", width), PINK));
    }

    void showLoginUsernameScreen() {
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                          LOGIN                           |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        showBoxNavigationOptions(new String[] {"[0] Back"});
        printLine("+==========================================================+");
        inputBar("USERNAME OR EMAIL");
    }

    void showLoginPasswordScreen(String username) {
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                          LOGIN                           |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printBoxCentered("Username: " + fit(username, 46), STANDARD_WIDTH);
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Back"});
        printLine("+==========================================================+");
        inputBar("PASSWORD");
    }

    void showRegisterFullNameScreen() {
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                     CREATE ACCOUNT                       |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        showBoxNavigationOptions(new String[] {"[0] Back to Main Menu"});
        printLine("+==========================================================+");
        inputBar("FULL NAME");
    }

    void showRegisterUsernameScreen(String fullName) {
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                     CREATE ACCOUNT                       |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printFormatted(String.format("| Name: %-50s |%n", summaryText(fullName, 50)));
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Back", "[C] Cancel Registration"});
        printLine("+==========================================================+");
        inputBar("USERNAME");
    }

    void showRegisterEmailScreen(String fullName, String username) {
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                     CREATE ACCOUNT                       |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printFormatted(String.format("| Name: %-50s |%n", summaryText(fullName, 50)));
        printFormatted(String.format("| Username: %-46s |%n", summaryText(username, 46)));
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Back", "[C] Cancel Registration"});
        printLine("+==========================================================+");
        inputBar("EMAIL");
    }

    void showRegisterPasswordScreen(String fullName, String username, String email) {
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                     CREATE ACCOUNT                       |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printFormatted(String.format("| Name: %-50s |%n", summaryText(fullName, 50)));
        printFormatted(String.format("| Username: %-46s |%n", summaryText(username, 46)));
        printFormatted(String.format("| Email: %-49s |%n", summaryText(email, 49)));
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Back", "[C] Cancel Registration"});
        printLine("+==========================================================+");
        inputBar("PASSWORD", STANDARD_WIDTH, "Choose a password with at least 8 characters.\nDo not use the | symbol.");
    }

    void showPlatformMenu(String heading) {
        clearScreen();
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printFormatted(String.format("|%58s|%n", center(heading, 58)));
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                  [1] View All Games                      |");
        printLine("|                  [2] PS5 Games                           |");
        printLine("|                  [3] Xbox Games                          |");
        printLine("|                  [4] Nintendo Games                      |");
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Back", "[M] Dashboard"});
        printLine("+==========================================================+");
        inputBar("ENTER CHOICE");
    }

    void showBrowseMenu(boolean fromInventory) {
        clearScreen();
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                  BROWSE GAME INVENTORY                   |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                  [1] View All Games                      |");
        printLine("|                  [2] PS5 Games                           |");
        printLine("|                  [3] Xbox Games                          |");
        printLine("|                  [4] Nintendo Games                      |");
        printLine("|                                                          |");
        if (fromInventory) {
            showBoxNavigationOptions(new String[] {"[0] Back to Inventory", "[M] Dashboard"});
        }
        else showBoxNavigationOptions(new String[] {"[0] Back to Dashboard", "[M] Dashboard"});
        printLine("+==========================================================+");
        inputBar("ENTER CHOICE");
    }

    void showClientMenu(User client) {
        clearScreen();
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                     CLIENT DASHBOARD                     |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printBoxCentered("Welcome, " + fit(client.username, 35)
            + " [" + membershipColor(client.isVip()) + "]", STANDARD_WIDTH);
        printLine("|                                                          |");
        printLine("|             [1] Browse Game Inventory                    |");
        printLine("|             [2] Rent Games / Join Checkout Queue         |");
        printLine("|             [3] My Queue Requests                        |");
        printLine("|             [4] View My Rental History                   |");
        printLine("|             [5] Upgrade to VIP Membership                |");
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Logout"});
        printLine("+==========================================================+");
        inputBar("ENTER CHOICE");
    }

    void showAdminMenu() {
        clearScreen();
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                     ADMIN DASHBOARD                      |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|              [1] Checkout Management                     |");
        printLine("|              [2] Process Game Returns & Late Fees        |");
        printLine("|              [3] Manage Game Inventory                   |");
        printLine("|              [4] Active Rentals                          |");
        printLine("|              [5] View Admin Audit Log                    |");
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Logout"});
        printLine("+==========================================================+");
        inputBar("ENTER CHOICE");
    }

    void showActiveRentalsMenu() {
        clearScreen();
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                     ACTIVE RENTALS                       |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                 [1] View All Active Rentals              |");
        printLine("|                 [2] View Overdue Rentals                 |");
        printLine("|                                                          |");
        showBoxNavigationOptions(new String[] {"[0] Back to Dashboard"});
        printLine("+==========================================================+");
        inputBar("ENTER CHOICE");
    }

    void showInventoryMenu() {
        clearScreen();
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                  MANAGE GAME INVENTORY                   |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                  [1] Browse Inventory                    |");
        printLine("|                  [2] Add Game                            |");
        printLine("|                  [3] Update Game Stock                   |");
        printLine("|                  [4] Delete Game                         |");
        printLine("|                                                          |"); 
        showBoxNavigationOptions(new String[] {"[0] Back to Dashboard"});
        printLine("+==========================================================+");
        inputBar("ENTER CHOICE");
    }

    void showQueue(CheckoutQueue queue) {
        if (queue.isEmpty()) {
            showEmptyState("The checkout queue is empty.");
            return;
        }
        printLine("VIP first; an eligible regular request gets a turn after 3 VIP checkouts.");
        printLine("Unavailable games wait. List position is not a stock reservation.");
        printLine(tableLine(CHECKOUT_QUEUE_TABLE_WIDTH));
        printFormatted(String.format("%-8s | %-8s | %-14s | %-36s | %3s%n",
            "QUEUE #", "PRIORITY", "CUSTOMER", "GAME TITLE", "QTY"));
        printLine(tableLine(CHECKOUT_QUEUE_TABLE_WIDTH));
        for (RentalRequest current : queue.getRequests()) {
            printFormatted(String.format("%-8s | %-8s | %-14s | %-36s | %3d%n",
                queueLabel(current.queueNumber),
                membershipColor(current.vip),
                fit(current.client.username, 14),
                fit(current.game.title, 36),
                current.quantity));

        }
        printLine(tableLine(CHECKOUT_QUEUE_TABLE_WIDTH));
    }

    boolean askYesNo(String question) {
        while (true) {
            showYesNoPrompt(question);
            String answer = readText(prompt());
            if (answer.equalsIgnoreCase("Y") || answer.equalsIgnoreCase("YES")) {
                return true;
            }
            if (answer.equalsIgnoreCase("N") || answer.equalsIgnoreCase("NO")) {
                return false;
            }
            error("Please enter Y or N.");
        }
    }

    void showOrderSummary(User client, LinkedHashMap<Game, Integer> cart, int days, RentalService rentalService) {
        printHeader("ORDER SUMMARY", ConsoleUI.ORDER_TABLE_WIDTH);
        printBoxCentered("Game requests are processed separately. Stock is checked at checkout.", ORDER_TABLE_WIDTH);
        if (cart.isEmpty()) {
            showEmptyState("Your cart is empty. Add a game before submitting.");
            return;
        }

        double total = 0;
        int totalQuantity = 0;
        int line = 1;
        printLine(border(ORDER_TABLE_WIDTH, '-'));
        printBoxCentered("", ORDER_TABLE_WIDTH);
        printBoxCentered(String.format("%-4s | %-26s | %-10s | %5s | %10s | %10s",
            "NO.", "GAME TITLE", "PLATFORM", "QTY", "UNIT PRICE", "SUBTOTAL"), ORDER_TABLE_WIDTH);
        printBoxCentered("", ORDER_TABLE_WIDTH);
        printLine(border(ORDER_TABLE_WIDTH, '-'));
        printBoxCentered("", ORDER_TABLE_WIDTH);
        for (Game game : cart.keySet()) {
            int quantity = cart.get(game);
            double unitPrice = rentalService.calculateRentalCost(client, game, days);
            double subtotal = rentalService.calculateRentalCost(client, game, days, quantity);
            total += subtotal;
            totalQuantity += quantity;
            printBoxCentered(String.format("%-4d | %-26s | %-10s | %5d | %10s | %10s",
                line, fit(game.title, 26),
                fit(platformColor(game.platform), 10), quantity, money(unitPrice), money(subtotal)), ORDER_TABLE_WIDTH);
            line++;
        }
        printBoxCentered("", ORDER_TABLE_WIDTH);
        String membership = membershipColor(client.isVip());
        if (client.isVip()) {
            membership += " - 10% discount applied";
        }
        printCenteredDetails(new String[] {
            "Total Quantity : " + totalQuantity,
            "Rental Duration: " + rentalDurationText(days),
            "Membership: " + membership,
            "Total Cost: " + money(total)
        });
    }

    void showGameTableHeader(int total, int start, int end) {
        printLine("Showing " + (start + 1) + "-" + end + " of " + total + " games");
        printLine();
        printLine(tableLine(ConsoleUI.GAME_TABLE_WIDTH));
        printFormatted(String.format("%-4s | %-6s | %-30s | %-10s | %8s | %5s | %-12s%n",
            "NO.", "ID", "GAME TITLE", "PLATFORM", "PRICE", "STOCK", "STATUS"));
        printLine(tableLine(ConsoleUI.GAME_TABLE_WIDTH));
    }

    void showGameRow(int shownNumber, Game game) {
        String availability = "OUT OF STOCK";
        if (game.isAvailable()) {
            availability = "AVAILABLE";
        }
        printFormatted(String.format("%-4d | %-6s | %-30s | %-10s | %8s | %5d | %-12s%n",
            shownNumber, game.gameId,
            fit(game.title, 30),
            platformColor(game.platform), money(game.price), game.availableCopies,
            availability));
    }

    void showGameNavigation() {
        printLine("[S] Search  [X] Clear search  [O] Change sort");
        printLine("[N] Next page  [P] Previous page");
        showNavigationOptions(GAME_TABLE_WIDTH, new String[] {"[0] Back", "[M] Dashboard"});
    }

    void showCatalogState(String keyword, int sortOption, int page, int totalPages) {
        String[] sortNames = {"Default order", "Title A-Z", "Title Z-A", "Price low-high", "Price high-low"};
        String searchLabel = "None";
        if (!keyword.isEmpty()) {
            searchLabel = keyword;
        }
        printLine("Search: " + searchLabel);
        printLine("Sort: " + sortNames[sortOption] + " | Page " + page + " of " + totalPages);
    }

    void showRentalDurationOptions() {
        showFeatureOptions(new String[] {
            "[1] 1 Day",
            "[2] 3 Days",
            "[3] 7 Days",
            "[4] Custom Duration (1-" + GameRentalSystem.MAX_RENTAL_DAYS + " days)"
        });
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    void showEditOrderTableHeader() {
        printLine(tableLine(ConsoleUI.EDIT_ORDER_TABLE_WIDTH));
        printFormatted(String.format("%-4s | %-30s | %-10s | %5s%n",
            "NO.", "GAME TITLE", "PLATFORM", "QTY"));
        printLine(tableLine(ConsoleUI.EDIT_ORDER_TABLE_WIDTH));
    }

    void showCartRow(int quantity, int line, Game game) {
        printFormatted(String.format("%-4d | %-30s | %-10s | %5d%n",
            line, fit(game.title, 30),
            platformColor(game.platform), quantity));
    }

    void showEditOrderOptions(int days) {
        printCenteredDetails(new String[] {"Rental Duration: " + rentalDurationText(days)});
        showFeatureOptions(new String[] {"[1] Add a game", "[2] Remove a game from the cart", "[3] Change rental duration"});
        showNavigationOptions(EDIT_ORDER_TABLE_WIDTH, new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    void showOrderConfirmationOptions() {
        showFeatureOptions(new String[] {"[1] Confirm Order", "[2] Edit Order"});
        showNavigationOptions(ORDER_TABLE_WIDTH, new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    void showSubmittedQueueHeader() {
        printLine();
        printLine(tableLine(ConsoleUI.QUEUE_TABLE_WIDTH));
        printFormatted(String.format("%-8s | %-30s | %-10s | %5s | %8s%n",
            "QUEUE #", "GAME TITLE", "PLATFORM", "QTY", "LIST POS"));
        printLine(tableLine(ConsoleUI.QUEUE_TABLE_WIDTH));
    }

    void showQueueRequestRow(int position, RentalRequest request) {
        printFormatted(String.format("%-8s | %-30s | %-10s | %5d | %8d%n",
            queueLabel(request.queueNumber),
            fit(request.game.title, 30),
            platformColor(request.game.platform), request.quantity, position));
    }

    void showQueueRequestHeader() {
        printLine(tableLine(ConsoleUI.QUEUE_TABLE_WIDTH));
        printFormatted(String.format("%-8s | %-30s | %-10s | %5s | %8s%n",
            "QUEUE #", "GAME TITLE", "PLATFORM", "QTY", "POSITION"));
        printLine(tableLine(ConsoleUI.QUEUE_TABLE_WIDTH));
    }

    void showHistoryTableHeader(int total, int start, int end) {
        printLine("Showing " + (start + 1) + "-" + end + " of " + total + " rentals");
        printLine();
        printLine(tableLine(ConsoleUI.HISTORY_TABLE_WIDTH));
        printFormatted(String.format("%-8s | %-28s | %3s | %-21s | %-21s | %9s | %-10s%n",
            "RENTAL #", "GAME TITLE", "QTY", "RENTED ON", "DUE ON", "AMOUNT", "STATUS"));
        printLine(tableLine(ConsoleUI.HISTORY_TABLE_WIDTH));
    }

    void showHistoryRow(Rental rental) {
        printFormatted(String.format("%-8s | %-28s | %3d | %-21s | %-21s | %9s | %-10s%n",
            rentalLabel(rental.rentalNumber),
            fit(rental.game.title, 28),
            rental.quantity, rental.checkoutDate.format(GameRentalSystem.TABLE_DATE_FORMAT),
            rental.dueDate.format(GameRentalSystem.TABLE_DATE_FORMAT), money(rental.amount), statusColor(rental.status)));
        if (rental.returnDate != null) {
            printLine("Returned: " + rental.returnDate.format(GameRentalSystem.TABLE_DATE_FORMAT)
            + " | Days late: " + rental.daysLate + " | Recorded late fee: " + money(rental.lateFee));
        }
    }

    void showBackOptions() {
        showNavigationOptions(new String[] {"[0] Back", "[M] Dashboard"});
    }

    void showBackToDashboard() {
        showNavigationOptions(new String[] {"[0] Back to Dashboard"});
    }

    // Format plain text to 81 columns before the shared renderer applies colors.
    private void checkoutRow(String text) {
        printBoxCentered(" " + String.format("%-77s", fit(text, 77)) + " ", CHECKOUT_QUEUE_TABLE_WIDTH);
    }

    boolean checkoutReady(RentalRequest request) {
        return CheckoutQueue.validRequest(request) && request.game.availableCopies >= request.quantity;
    }

    String checkoutStatus(RentalRequest request) {
        if (!CheckoutQueue.validRequest(request)) {
            return "WAIT invalid";
        }
        if (checkoutReady(request)) {
            return "READY";
        }
        if (request.game.availableCopies == 0) {
            return "WAIT need " + request.quantity;
        }
        return "WAIT short " + (request.quantity - request.game.availableCopies);
    }

    String checkoutStock(RentalRequest request) {
        if (request.game == null) {
            return "-";
        }
        int stock = request.game.availableCopies;
        if (checkoutReady(request)) {
            return stock + "→" + (stock - request.quantity);
        }
        return String.valueOf(stock);
    }

    String checkoutStreak(int streak) {
        String dots = "";
        for (int i = 0; i < CheckoutQueue.VIP_BURST; i++) {
            if (i < streak) {
                dots += "●";
            } else {
                dots += "○";
            }
        }
        return dots;
    }

    String checkoutNext(CheckoutQueue queue, RentalService service) {
        RentalRequest next = queue.findNextEligibleRequest();
        if (next == null) {
            return "NEXT  none ready";
        }
        return "NEXT  " + queueLabel(next.queueNumber) + " " + fit(next.client.username, 10)
            + " · " + fit(next.game.title, 22) + " x" + next.quantity + " · " + next.rentalDays
            + " days · " + money(service.calculateRentalCost(next.client, next.game, next.rentalDays, next.quantity));
    }

    void showCheckoutQueue(CheckoutQueue queue, RentalService service) {
        clearScreen();
        checkoutDisplay = true;
        currentWidth = CHECKOUT_QUEUE_TABLE_WIDTH;
        ArrayList<RentalRequest> requests = queue.getRequests();
        int ready = 0;
        for (RentalRequest request : requests) {
            if (checkoutReady(request)) {
                ready++;
            }
        }
        printLine(border(currentWidth, '='));
        checkoutRow("ADMIN > CHECKOUT           QUEUE " + requests.size() + "  READY " + ready
            + "  WAITING " + (requests.size() - ready));
        printLine(border(currentWidth, '='));
        if (requests.isEmpty()) {
            showEmptyState("The checkout queue is empty.");
        } else {
            checkoutRow(String.format("%-7s%-5s%-10s%-18s%3s  %4s  %-7s%s",
                "#", "PRI", "CUSTOMER", "GAME", "QTY", "DAYS", "STOCK", "STATUS"));
            printLine(border(currentWidth, '-'));
            for (RentalRequest request : requests) {
                String priority = "REG";
                if (request.vip) {
                    priority = membershipColor(true);
                }
                String customer = "-";
                String title = "-";
                if (request.client != null) {
                    customer = request.client.username;
                }
                if (request.game != null) {
                    title = request.game.title;
                }
                checkoutRow(String.format("%-7s%-5s%-10s%-18s%3d  %4d  %-7s%s",
                    queueLabel(request.queueNumber), priority, fit(customer, 9),
                    fit(title, 17), request.quantity, request.rentalDays,
                    checkoutStock(request), checkoutStatus(request)));
            }
        }
        printLine(border(currentWidth, '-'));
        checkoutRow("VIP streak " + checkoutStreak(queue.vipStreak) + " " + queue.vipStreak + "/" + CheckoutQueue.VIP_BURST);
        printLine(border(currentWidth, '='));
        checkoutRow(checkoutNext(queue, service));
        printLine(border(currentWidth, '='));
        flushBorder(false);
        lastWasRow = false;
        showTapeInput();
    }

    void showCheckoutTapeFooter(GameRentalSystem.CheckoutSession session, CheckoutQueue queue, RentalService service) {
        showSessionStatus(session, queue, service);
        showTapeInput();
    }

    // Finish each card before starting the next, so shared borders do not join them.
    private void beginCheckoutCard(char fill) {
        flushBorder(false);
        lastWasRow = false;
        currentWidth = CHECKOUT_QUEUE_TABLE_WIDTH;
        lightBox = false;
        borderTone = PINK;
        textTone = "";
        printLine(border(currentWidth, fill));
    }

    private void endCheckoutCard(char fill) {
        printLine(border(currentWidth, fill));
        flushBorder(false);
        lastWasRow = false;
        textTone = "";
    }

    private void showSlipTitle(String title, int number, LocalDateTime timestamp, String tone) {
        textTone = tone;
        String label = title + "  #" + String.format("%02d", number);
        String time = timestamp.format(DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH));
        checkoutRow(String.format("%-69s%8s", fit(label, 69), time));
        textTone = "";
        printLine(border(currentWidth, '-'));
    }

    void showApprovedSlip(Rental rental, int number) {
        beginCheckoutCard('-');
        showSlipTitle("✔ APPROVED", number, rental.checkoutDate, GREEN);
        int days = (int) java.time.Duration.between(rental.checkoutDate, rental.dueDate).toDays();
        checkoutRow(String.format("%-11s %-10s %-24s x%d %2d days %9s",
            rentalLabel(rental.rentalNumber), fit(rental.client.username, 10), fit(rental.game.title, 24),
            rental.quantity, days, money(rental.amount)));
        checkoutRow("Out " + rental.checkoutDate.format(RECEIPT_DATE) + "     Due " + rental.dueDate.format(RECEIPT_DATE));
        endCheckoutCard('-');
    }

    void showCancelledSlip(RentalRequest request, int number) {
        beginCheckoutCard('-');
        showSlipTitle("✘ CANCELLED", number, LocalDateTime.now(), RED);
        checkoutRow(String.format("%-11s %-10s %-24s x%d %2d days",
            queueLabel(request.queueNumber), fit(request.client.username, 10), fit(request.game.title, 24),
            request.quantity, request.rentalDays));
        endCheckoutCard('-');
    }

    void showSessionStatus(GameRentalSystem.CheckoutSession session, CheckoutQueue queue, RentalService service) {
        beginCheckoutCard('=');
        String counts = "SESSION   APPROVED " + session.approved + "   CANCELLED " + session.cancelled
            + "   TOTAL " + money(session.total);
        checkoutRow(String.format("%-70s%7s", fit(counts, 70), "VIP " + checkoutStreak(queue.vipStreak)));
        printLine(border(currentWidth, '='));
        checkoutRow(checkoutNext(queue, service));
        endCheckoutCard('=');
    }

    void showTapeInput() {
        showPendingMessage(CHECKOUT_QUEUE_TABLE_WIDTH);
        flushBorder(false);
        lastWasRow = false;
        inputBar("ENTER CHOICE", CHECKOUT_QUEUE_TABLE_WIDTH,
            "[ENTER] Approve  [A] All  [X] Cancel #  [Q] Queue  [R] Receipt  [0] Back");
        flushBorder(false);
        lastWasRow = false;
    }

    void showMessageCard(String message) {
        beginCheckoutCard('-');
        textTone = RED;
        if (message.startsWith("WARNING:")) {
            textTone = YELLOW;
        }
        printMessageRows(message, CHECKOUT_QUEUE_TABLE_WIDTH);
        endCheckoutCard('-');
    }

    void showConfirmQuestion(String text) {
        showPendingMessage(CHECKOUT_QUEUE_TABLE_WIDTH);
        flushBorder(false);
        lastWasRow = false;
        String separator = "─".repeat(CHECKOUT_QUEUE_TABLE_WIDTH);
        emitLine(color(separator, PINK));
        emitLine(center(text, CHECKOUT_QUEUE_TABLE_WIDTH));
        emitLine(color(separator, PINK));
    }

    private boolean confirmCheckoutAction(String question) {
        while (true) {
            showConfirmQuestion(question);
            String answer = readText(prompt(CHECKOUT_QUEUE_TABLE_WIDTH));
            if (answer.equalsIgnoreCase("Y") || answer.equalsIgnoreCase("YES")) {
                return true;
            }
            if (answer.equalsIgnoreCase("N") || answer.equalsIgnoreCase("NO")) {
                return false;
            }
            error("Use Y or N.");
        }
    }

    boolean confirmCheckout(RentalRequest request, RentalService service) {
        double cost = service.calculateRentalCost(request.client, request.game, request.rentalDays, request.quantity);
        String prefix = "Approve " + queueLabel(request.queueNumber) + " " + fit(request.client.username, 10) + " · ";
        String suffix = " x" + request.quantity + " · " + money(cost) + " ?   [Y] Yes   [N] No";
        int titleWidth = CHECKOUT_QUEUE_TABLE_WIDTH - prefix.length() - suffix.length();
        return confirmCheckoutAction(prefix + fit(request.game.title, titleWidth) + suffix);
    }

    boolean confirmCheckoutCancellation(RentalRequest request) {
        String prefix = "Cancel " + queueLabel(request.queueNumber) + " " + fit(request.client.username, 10) + " · ";
        String suffix = " x" + request.quantity + " ?   [Y] Yes   [N] No";
        int titleWidth = CHECKOUT_QUEUE_TABLE_WIDTH - prefix.length() - suffix.length();
        return confirmCheckoutAction(prefix + fit(request.game.title, titleWidth) + suffix);
    }

    boolean confirmCheckoutBatch(int count) {
        return confirmCheckoutAction("Confirm batch? [Y] Approve " + count + " [N] Cancel");
    }

    void showCheckoutBatch(ArrayList<RentalRequest> planned, CheckoutQueue queue, RentalService service) {
        beginCheckoutCard('=');
        printTitle("APPROVE ALL READY (" + planned.size() + ")", CHECKOUT_QUEUE_TABLE_WIDTH);
        printLine(border(currentWidth, '='));
        double total = 0;
        int streak = queue.vipStreak;
        int number = 1;
        for (RentalRequest request : planned) {
            String priority = "";
            if (request.vip) {
                streak = Math.min(CheckoutQueue.VIP_BURST, streak + 1);
                priority = "VIP (streak " + streak + "/" + CheckoutQueue.VIP_BURST + ")";
            } else {
                streak = 0;
            }
            double cost = service.calculateRentalCost(request.client, request.game, request.rentalDays, request.quantity);
            total += cost;
            checkoutRow(String.format("%3d %-7s %-10s %-18s x%d %9s %s", number++,
                queueLabel(request.queueNumber), fit(request.client.username, 10),
                fit(request.game.title, 18), request.quantity, money(cost), priority));
        }
        printLine(border(currentWidth, '-'));
        checkoutRow("TOTAL " + planned.size() + " rentals · " + money(total));
        String skipped = "Skipped:";
        for (RentalRequest request : queue.getRequests()) {
            if (!planned.contains(request)) {
                String label = queueLabel(request.queueNumber) + " (stock)";
                if (!CheckoutQueue.validRequest(request)) {
                    label = queueLabel(request.queueNumber) + " (invalid)";
                }
                if (skipped.length() + label.length() + 1 > 77) {
                    checkoutRow(skipped);
                    skipped = "Skipped:";
                }
                skipped += " " + label;
            }
        }
        if (skipped.equals("Skipped:")) {
            skipped += " none";
        }
        checkoutRow(skipped);
        endCheckoutCard('=');
    }

    void showCheckoutReceipt(GameRentalSystem.CheckoutSession session, CheckoutQueue queue) {
        printHeader("CHECKOUT SESSION RECEIPT", CHECKOUT_QUEUE_TABLE_WIDTH);
        checkoutRow(session.started.format(DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a"))
            + " · " + fit(session.admin, 32));
        printLine(border(currentWidth, '='));
        checkoutRow(String.format("%-8s%-11s%-24s%3s %10s  %s", "RENTAL", "CUSTOMER", "GAME", "QTY", "AMOUNT", "DUE"));
        for (GameRentalSystem.CheckoutEntry entry : session.entries) {
            if (entry.rental != null) {
                Rental rental = entry.rental;
                checkoutRow(String.format("%-8s%-11s%-24s%3d %10s  %s",
                    rentalLabel(rental.rentalNumber), fit(rental.client.username, 10),
                    fit(rental.game.title, 23), rental.quantity, money(rental.amount), rental.dueDate.format(RECEIPT_DATE)));
            } else {
                RentalRequest request = entry.cancelled;
                checkoutRow(String.format("%-8s%-11s%-24s%3d %10s  %s",
                    queueLabel(request.queueNumber), fit(request.client.username, 10),
                    fit(request.game.title, 23), request.quantity, "-", "cancelled"));
            }
        }
        printLine(border(currentWidth, '-'));
        checkoutRow(session.approved + " rentals · " + session.copies + " copies · " + session.cancelled
            + " cancelled                 TOTAL " + money(session.total));
        int waiting = 0;
        for (RentalRequest request : queue.getRequests()) {
            if (!checkoutReady(request)) {
                waiting++;
            }
        }
        checkoutRow("Left in queue: " + queue.getRequests().size() + " (" + waiting + " waiting)");
        printLine(border(currentWidth, '='));
    }

    void endCheckoutDisplay() {
        checkoutDisplay = false;
    }

    void showQueueCancellationOptions() {
        printLine(border(currentWidth, '-'));
        showFeatureOptions(new String[] {"[1] Cancel a Request"});
        showNavigationOptions(currentWidth, new String[] {"[0] Back", "[M] Dashboard"});
        inputBar("ENTER CHOICE", currentWidth);
    }

    void showVipBenefits() {
        printCenteredDetails(new String[] {
            "Price           : Free project membership",
            "Rental Discount : 10%",
            "Queue Priority  : VIP first with a regular turn after 3 VIPs",
            "Membership      : " + membershipColor(true)
        });
        showFeatureOptions(new String[] {"[1] Confirm Upgrade"});
        showNavigationOptions(new String[] {"[C] Cancel Transaction"});
    }

    void showNextCheckout(double rentalCost, RentalRequest next) {
        printCenteredDetails(new String[] {
            "Customer        : " + next.client.username,
            "Membership      : " + membershipColor(next.vip),
            "Game            : " + next.game.title,
            "Platform        : " + platformColor(next.game.platform),
            "Queue Number    : " + queueLabel(next.queueNumber),
            "Quantity        : " + next.quantity,
            "Rental Duration : " + next.rentalDays + " day(s)",
            "Rental Price    : " + money(rentalCost)
        });
    }

    void showReturnTableHeader(int total, int start, int end) {
        printLine("Showing " + (start + 1) + "-" + end + " of " + total + " active rentals\n");
        printLine(tableLine(ConsoleUI.RETURN_TABLE_WIDTH));
        printFormatted(String.format("%-4s | %-8s | %-14s | %-30s | %3s | %-21s%n",
            "NO.", "RENTAL #", "CUSTOMER", "GAME TITLE", "QTY", "DUE DATE"));
        printLine(tableLine(ConsoleUI.RETURN_TABLE_WIDTH));
    }

    void showReturnRow(int shown, Rental rental) {
        printFormatted(String.format("%-4d | %-8s | %-14s | %-30s | %3d | %-21s%n",
            shown, rentalLabel(rental.rentalNumber),
            fit(rental.client.username, 14),
            fit(rental.game.title, 30),
            rental.quantity, rental.dueDate.format(GameRentalSystem.TABLE_DATE_FORMAT)));
    }

    void showReturnDetails(Rental found) {
        printCenteredDetails(new String[] {
            "Rental   : " + rentalLabel(found.rentalNumber),
            "Customer : " + found.client.username,
            "Game     : " + found.game.title,
            "Platform : " + platformColor(found.game.platform),
            "Quantity : " + found.quantity,
            "Due On   : " + found.dueDate.format(GameRentalSystem.DATE_FORMAT)
        });
        showNavigationOptions(new String[] {"[C] Cancel Transaction", "[M] Dashboard"});
    }

    void showReturnConfirmation(Rental found, int daysLate, double lateFee) {
        printCenteredDetails(new String[] {
            "Rental            : " + rentalLabel(found.rentalNumber),
            "Customer          : " + found.client.username,
            "Game              : " + found.game.title,
            "Quantity Returned : " + found.quantity,
            "Days Overdue      : " + daysLate,
            "Late Fee          : " + money(lateFee)
        });
        printBoxCentered("Fee is $1 per copy for each started day overdue.", currentWidth);
        showFeatureOptions(new String[] {"[1] Confirm Return"});
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    void showReturnResult(Rental found, int daysLate, double lateFee) {
        printCenteredDetails(new String[] {
            "Customer          : " + found.client.username,
            "Game              : " + found.game.title,
            "Quantity Returned : " + found.quantity,
            "Days Overdue      : " + daysLate,
            "Late Fee          : " + money(lateFee),
            "Current Stock     : " + found.game.availableCopies
        });
    }

    void showActiveRentalHeader(int total, int start, int end) {
        printLine("Showing " + (start + 1) + "-" + end + " of " + total + " rentals");
        printLine();
        printLine(tableLine(ConsoleUI.ACTIVE_TABLE_WIDTH));
        printFormatted(String.format("%-8s | %-14s | %-28s | %-10s | %3s | %-21s | %-10s%n",
            "RENTAL #", "CUSTOMER", "GAME TITLE", "PLATFORM", "QTY", "DUE DATE", "STATUS"));
        printLine(tableLine(ConsoleUI.ACTIVE_TABLE_WIDTH));
    }

    void showActiveRentalRow(Rental rental) {
        printFormatted(String.format("%-8s | %-14s | %-28s | %-10s | %3d | %-21s | %-10s%n",
            rentalLabel(rental.rentalNumber),
            fit(rental.client.username, 14),
            fit(rental.game.title, 28),
            platformColor(rental.game.platform), rental.quantity,
            rental.dueDate.format(GameRentalSystem.TABLE_DATE_FORMAT), statusColor(rental.status)));
    }

    void showOverdueRentalHeader(int total, int start, int end) {
        printLine("Showing " + (start + 1) + "-" + end + " of " + total + " overdue rentals");
        printLine();
        printLine(tableLine(ConsoleUI.OVERDUE_TABLE_WIDTH));
        printFormatted(String.format("%-8s | %-14s | %-28s | %3s | %-21s | %9s | %9s%n",
            "RENTAL #", "CUSTOMER", "GAME TITLE", "QTY", "DUE DATE", "DAYS LATE", "LATE FEE"));
        printLine(tableLine(ConsoleUI.OVERDUE_TABLE_WIDTH));
    }

    void showOverdueRentalRow(double lateFee, Rental rental, long daysOverdue) {
        printFormatted(String.format("%-8s | %-14s | %-28s | %3d | %-21s | %9d | %9s%n",
            rentalLabel(rental.rentalNumber),
            fit(rental.client.username, 14),
            fit(rental.game.title, 28),
            rental.quantity, rental.dueDate.format(GameRentalSystem.TABLE_DATE_FORMAT), daysOverdue,
            money(lateFee)));
    }

    void showEarliestDueRental(Rental top) {
        printLine("Highest Priority: Rental " + rentalLabel(top.rentalNumber)
            + " | Due: " + top.dueDate.format(GameRentalSystem.DATE_FORMAT));
    }

    void showAuditHeader(int total, int start, int end) {
        printLine("Showing " + (start + 1) + "-" + end + " of " + total + " records");
        printFormatted(String.format("%-13s | %-10s | %-58s%n", "DATE", "TIME", "ACTION"));
        printLine(tableLine(ConsoleUI.AUDIT_TABLE_WIDTH));
    }

    void showAuditRow(LocalDateTime stamp, String[] parts) {
        printFormatted(String.format("%-13s | %-10s | %-58s%n",
            stamp.format(GameRentalSystem.LOG_DISPLAY_DATE), stamp.format(GameRentalSystem.LOG_DISPLAY_TIME), fit(parts[1], 58)));
    }

    void showLegacyAuditRow(String entry) {
        printFormatted(String.format("%-13s | %-10s | %-58s%n", "", "", fit(entry, 58)));
    }

    void showAddGameInstructions() {
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    void showPlatformOptions() {
        showFeatureOptions(new String[] {
            "[1] " + platformColor("PS5"),
            "[2] " + platformColor("Xbox"),
            "[3] " + platformColor("Nintendo")
        });
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    void showPriceInstructions() {
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
        inputBar("RENTAL PRICE");
    }

    void showStockInstructions() {
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
        inputBar("AVAILABLE COPIES");
    }

    void showNewGameConfirmation(String title, String platform, double price, int copies) {
        printCenteredDetails(new String[] {
            "Title    : " + title,
            "Platform : " + platformColor(platform),
            "Price    : " + money(price),
            "Stock    : " + copies
        });
        showFeatureOptions(new String[] {"[1] Confirm Add", "[2] Edit Information"});
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    void showGameAdded(Game game) {
        printCenteredDetails(new String[] {
            "Game ID  : " + game.gameId,
            "Title    : " + game.title,
            "Platform : " + platformColor(game.platform),
            "Stock    : " + game.availableCopies,
            "Price    : " + money(game.price)
        });
    }

    void showDeleteGameDetails(Game game, int pending) {
        printCenteredDetails(new String[] {
            "Game ID          : " + game.gameId,
            "Title            : " + game.title,
            "Platform         : " + platformColor(game.platform),
            "Current Stock    : " + game.availableCopies,
            "Pending Requests : " + pending
        });
    }

    void showStockUpdateOptions(Game game) {
        printCenteredDetails(new String[] {
            "Game          : " + game.title,
            "Platform      : " + platformColor(game.platform),
            "Current Stock : " + game.availableCopies,
            "Stock Limit   : 0-" + GameRentalSystem.MAX_GAME_STOCK
        });
        showFeatureOptions(new String[] {"[1] Add Stock", "[2] Decrease Stock"});
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    void showStockQuantityPrompt(boolean adding) {
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
        if (adding) {
            inputBar("QUANTITY TO ADD");
        } else {
            inputBar("QUANTITY TO DECREASE");
        }
    }

    void showStockConfirmation(Game game, int previous, int newStock) {
        printCenteredDetails(new String[] {
            "Game           : " + game.title,
            "Previous Stock : " + previous,
            "New Stock      : " + newStock
        });
    }

    void showStockUpdated(Game game, int previous) {
        printCenteredDetails(new String[] {
            "Game           : " + game.title,
            "Previous Stock : " + previous,
            "Current Stock  : " + game.availableCopies
        });
    }

    void showFullNameInstructions() {
        error("Enter a full name of 2-100 characters using letters, spaces, periods, hyphens or apostrophes.");
    }

    void showConfirmPasswordPrompt() {
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Registration"});
    }

    void showRegistrationConfirmation(String fullName, String username, String email) {
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                  CONFIRM REGISTRATION                    |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printCenteredDetails(new String[] {
            "Name       : " + fullName,
            "Username   : " + username,
            "Email      : " + email,
            "Membership : REGULAR"
        });
        showFeatureOptions(new String[] {"[1] Create Account", "[2] Edit Information"});
        showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Registration"});
    }

    void showAccountCreated() {
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|                   ACCOUNT CREATED!                       |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
        printLine("|                                                          |");
        printLine("|              Your account is ready to use.               |");
        printLine("|                                                          |");
        printLine("+==========================================================+");
    }
}
