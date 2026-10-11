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
public class TerminalUI {
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
    static final int AUDIT_TABLE_WIDTH = 87;
    static final int CHECKOUT_QUEUE_TABLE_WIDTH = 81;
    private final Scanner input = new Scanner(System.in);
    private final int terminalWidth = Math.max(40, Integer.getInteger("q2p.width", configuredWidth()));
    private final int terminalHeight = Math.max(20, Integer.getInteger("q2p.height", configuredHeight()));
    private final boolean cursorEnabled = Boolean.parseBoolean(
        System.getProperty("q2p.ansi", String.valueOf(System.console() != null)));
    private boolean outputAtLineStart = true;
    private boolean compactInput;

    // Read the configured terminal width or use the default.
    private static int configuredWidth() {
        try {
            return Integer.parseInt(System.getenv("COLUMNS"));
        }
        catch (NumberFormatException e) {
            return 120;
        }
    }
    // Read the configured terminal height or use the default.
    private static int configuredHeight() {
        try {
            return Integer.parseInt(System.getenv("LINES"));
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    // Build page boundaries from record heights while keeping each record together.
    ArrayList<Integer> pageStarts(int[] heights, int maximum, int reservedRows) {
        ArrayList<Integer> starts = new ArrayList<>();
        starts.add(0);
        int used = 0;
        int count = 0;
        int available = Math.max(1, terminalHeight - reservedRows);
        for (int i = 0; i < heights.length; i++) {
            if (count > 0 && (count == maximum || used + heights[i] > available)) {
                starts.add(i);
                used = 0;
                count = 0;
            }
            used += heights[i];
            count++;
        }
        starts.add(heights.length);
        return starts;
    }

    // Count how many terminal rows a piece of wrapped text needs.
    private int wrappedLines(String text, int width) {
        return wrapText(text, width).size();
    }

    // Get one wrapped line from a table cell.
    private String columnPart(String text, int row, int width) {
        ArrayList<String> lines = wrapText(text, width);
        if (row >= lines.size()) {
            return "";
        }
        return lines.get(row);
    }

    // Split text into fitting lines, preferring word boundaries.
    private ArrayList<String> wrapText(String text, int width) {
        ArrayList<String> lines = new ArrayList<>();
        while (text.length() > width) {
            int end = text.lastIndexOf(' ', width);
            if (end <= 0) {
                end = width;
            }
            lines.add(text.substring(0, end));
            text = text.substring(end).trim();
        }
        lines.add(text);
        return lines;
    }

    // Shrink table columns to fit the terminal width.
    private int[] displayWidths(int[] widths, int tableWidth) {
        int[] result = widths.clone();
        if (checkoutDisplay) {
            return result;
        }
        int remaining = tableWidth - terminalWidth;
        while (remaining > 0) {
            int widest = 0;
            for (int i = 1; i < result.length; i++) {
                if (result[i] > result[widest]) {
                    widest = i;
                }
            }
            if (result[widest] <= 2) {
                break;
            }
            result[widest]--;
            remaining--;
        }
        return result;
    }

    // Measure the rows needed to display a complete table record.
    private int recordHeight(String[] cells, int[] widths, int tableWidth) {
        if (!checkoutDisplay && terminalWidth < 60) {
            return wrapText(recordText(cells), terminalWidth - 2).size();
        }
        widths = displayWidths(widths, tableWidth);
        int rows = 1;
        for (int i = 0; i < cells.length; i++) {
            rows = Math.max(rows, wrappedLines(cells[i], widths[i]));
        }
        return rows;
    }

    // Join nonempty fields for compact record display.
    private String recordText(String[] cells) {
        String text = "";
        for (String cell : cells) {
            if (!cell.isEmpty()) {
                if (!text.isEmpty()) {
                    text += " | ";
                }
                text += cell;
            }
        }
        return text;
    }

    // Print a full record with wrapped cells or compact narrow-screen lines.
    private void printRecord(String format, String[] cells, int[] widths) {
        if (!checkoutDisplay && terminalWidth < 60) {
            boolean first = true;
            for (String line : wrapText(recordText(cells), terminalWidth - 2)) {
                if (first) {
                    printLine(line);
                    first = false;
                } else {
                    printLine("  " + line);
                }
            }
            return;
        }
        String[] empty = new String[cells.length];
        for (int i = 0; i < empty.length; i++) {
            empty[i] = "";
        }
        int tableWidth = String.format(format, (Object[]) empty).length();
        widths = displayWidths(widths, tableWidth);
        Matcher columns = Pattern.compile("%(-?)([0-9]+)s").matcher(format);
        String adjusted = "";
        int start = 0;
        int column = 0;
        while (columns.find()) {
            adjusted += format.substring(start, columns.start()) + "%" + columns.group(1) + widths[column] + "s";
            start = columns.end();
            column++;
        }
        format = adjusted + format.substring(start);
        int rows = 1;
        for (int i = 0; i < cells.length; i++) {
            rows = Math.max(rows, wrappedLines(cells[i], widths[i]));
        }
        for (int row = 0; row < rows; row++) {
            String[] parts = new String[cells.length];
            for (int i = 0; i < cells.length; i++) {
                parts[i] = columnPart(cells[i], row, widths[i]);
            }
            printLine(String.format(format, (Object[]) parts));
        }
    }

    // Use an array of game row heights to build pages of at most nine games.
    ArrayList<Integer> gamePages(ArrayList<Game> games) {
        int[] heights = new int[games.size()];
        for (int i = 0; i < games.size(); i++) {
            Game game = games.get(i);
            heights[i] = recordHeight(new String[] {String.valueOf(i + 1), game.gameId, game.title,
                game.platform, money(game.price), String.valueOf(game.availableCopies), "OUT OF STOCK"},
                new int[] {4, 6, 30, 10, 8, 5, 12}, GAME_TABLE_WIDTH);
        }
        int headings = recordHeight(new String[] {"NO.", "ID", "GAME TITLE", "PLATFORM", "PRICE", "STOCK", "STATUS"},
            new int[] {4, 6, 30, 10, 8, 5, 12}, GAME_TABLE_WIDTH);
        return pageStarts(heights, 9, 29 + headings - 1);
    }

    // Use rental row heights to keep each page within its record and height limits.
    ArrayList<Integer> rentalPages(ArrayList<Rental> rentals, String kind) {
        int[] heights = new int[rentals.size()];
        int width = ACTIVE_TABLE_WIDTH;
        int maximum = 9;
        int reserved = 22;
        if (kind.equals("return")) {
            width = RETURN_TABLE_WIDTH;
            maximum = 9;
            reserved = 26;
        } else if (kind.equals("history")) {
            width = HISTORY_TABLE_WIDTH;
            maximum = 3;
        }
        for (int i = 0; i < rentals.size(); i++) {
            Rental rental = rentals.get(i);
            if (kind.equals("history")) {
                heights[i] = recordHeight(new String[] {rentalLabel(rental.rentalNumber), rental.game.title,
                    String.valueOf(rental.quantity), rental.checkoutDate.format(Queue2Play.TABLE_DATE_FORMAT),
                    historyDueDate(rental), money(rental.amount), rental.status},
                    new int[] {8, 28, 3, 21, 21, 9, 10}, width);
            } else if (kind.equals("return")) {
                heights[i] = recordHeight(new String[] {String.valueOf(i + 1), rentalLabel(rental.rentalNumber),
                    rental.client.username, rental.game.title, String.valueOf(rental.quantity),
                    rental.dueDate.format(Queue2Play.TABLE_DATE_FORMAT)},
                    new int[] {4, 8, 14, 30, 3, 21}, width);
            } else {
                heights[i] = recordHeight(new String[] {rentalLabel(rental.rentalNumber), rental.client.username,
                    rental.game.title, rental.game.platform, String.valueOf(rental.quantity),
                    rental.dueDate.format(Queue2Play.TABLE_DATE_FORMAT), rental.status},
                    new int[] {8, 14, 28, 10, 3, 21, 10}, width);
            }
            if (kind.equals("history") && rental.returnDate != null) {
                String returned = "Returned: " + rental.returnDate.format(Queue2Play.TABLE_DATE_FORMAT)
                    + " | Days late: " + rental.daysLate + " | Recorded late fee: " + money(rental.lateFee);
                heights[i] += Math.max(1, (returned.length() + terminalWidth - 1) / terminalWidth);
            }
        }
        int headings = 1;
        if (kind.equals("history")) {
            headings = recordHeight(new String[] {"RECORD #", "GAME TITLE", "QTY", "RECORDED ON", "DUE ON", "AMOUNT", "STATUS"},
                new int[] {8, 28, 3, 21, 21, 9, 10}, width);
        } else if (kind.equals("return")) {
            headings = recordHeight(new String[] {"NO.", "RENTAL #", "CUSTOMER", "GAME TITLE", "QTY", "DUE DATE"},
                new int[] {4, 8, 14, 30, 3, 21}, width);
        } else {
            headings = recordHeight(new String[] {"RENTAL #", "CUSTOMER", "GAME TITLE", "PLATFORM", "QTY", "DUE DATE", "STATUS"},
                new int[] {8, 14, 28, 10, 3, 21, 10}, width);
        }
        return pageStarts(heights, maximum, reserved + headings - 1);
    }

    // Measure wrapped audit entries before assigning them to pages.
    ArrayList<Integer> auditPages(ArrayList<String> logs) {
        int[] heights = new int[logs.size()];
        for (int i = 0; i < logs.size(); i++) {
            String[] parts = logs.get(i).split("\\|", 2);
            String action = logs.get(i);
            if (parts.length == 2) {
                try {
                    LocalDateTime.parse(parts[0], Queue2Play.LOG_STORAGE_FORMAT);
                    action = parts[1];
                } catch (java.time.DateTimeException e) {
                    // Keep the complete older record visible.
                }
            }
            heights[i] = recordHeight(new String[] {"Oct 11, 2026", "12:31 AM", action},
                new int[] {13, 10, 58}, AUDIT_TABLE_WIDTH) + 1;
        }
        int headings = recordHeight(new String[] {"DATE", "TIME", "ACTION"},
            new int[] {13, 10, 58}, AUDIT_TABLE_WIDTH);
        return pageStarts(heights, 9, 22 + headings - 1);
    }

    // Measure queue records before grouping them into fitting pages.
    ArrayList<Integer> queuePages(ArrayList<RentalRequest> requests) {
        int[] heights = new int[requests.size()];
        for (int i = 0; i < requests.size(); i++) {
            RentalRequest request = requests.get(i);
            heights[i] = recordHeight(new String[] {queueLabel(request.queueNumber), request.game.title,
                request.game.platform, String.valueOf(request.quantity), "WAITING"},
                new int[] {8, 30, 10, 5, 8}, QUEUE_TABLE_WIDTH);
        }
        int headings = recordHeight(new String[] {"QUEUE #", "GAME TITLE", "PLATFORM", "QTY", "POSITION"},
            new int[] {8, 30, 10, 5, 8}, QUEUE_TABLE_WIDTH);
        return pageStarts(heights, 9, 24 + headings - 1);
    }

    // Display only the Next and Previous keys that can be used.
    void showPagination(int page, int pages) {
        ArrayList<String> choices = new ArrayList<>();
        if (page < pages - 1) {
            choices.add("[N] Next Page");
        }
        if (page > 0) {
            choices.add("[P] Previous Page");
        }
        showChoiceGroup(choices.toArray(new String[0]));
    }

    // Display the list order, page and counts in a divided status box.
    void showListStatus(String first, String second, int width) {
        width = Math.min(width, terminalWidth);
        currentWidth = width;
        lightBox = false;
        printLine(border(width, '='));
        printBoxCentered(first, width);
        printLine(border(width, '='));
        printBoxCentered(second, width);
        printLine(border(width, '='));
        flushBorder(false);
        printLine();
    }

    // Ask before discarding unsaved work and leaving the current step.
    boolean confirmDiscardTransaction(String transaction, String destination) {
        while (true) {
            printLine(border(currentWidth, '-'));
            printWrappedBoxText("Discard " + transaction + " and return to " + destination + "?", currentWidth);
            printLine(border(currentWidth, '-'));
            inputBar("CONFIRM", currentWidth, "[Y] Discard      [N] Keep Editing");
            String answer = readText(prompt());
            if (answer.equalsIgnoreCase("Y") || answer.equalsIgnoreCase("YES")) {
                return true;
            }
            if (answer.equalsIgnoreCase("N") || answer.equalsIgnoreCase("NO")) {
                return false;
            }
            error("Use Y or N.");
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
    private static final String BOLD_MUSTARD = "\u001B[1;38;2;212;160;23m";
    private static final Pattern STATUS_WORDS = Pattern.compile("\\b(VIP|AVAILABLE|OUT OF STOCK|COMPLETED|OVERDUE|PS5|Xbox|Nintendo)\\b");
    // Run with -Dq2p.ascii=true to keep plain + - | borders on terminals without UTF-8.
    private final boolean unicode = !"true".equalsIgnoreCase(System.getProperty("q2p.ascii"));
    private final boolean colorsEnabled = !"false".equalsIgnoreCase(System.getProperty("q2p.color"))
        && System.getenv("NO_COLOR") == null;
    private String borderTone = PINK;
    private String textTone = "";
    private String pendingNavigation = "";

    // Apply option colors while keeping their visible labels unchanged.
    private String colorChoices(String text) {
        Matcher choices = Pattern.compile("\\[(ENTER|[A-Z0-9]|[0-9]+–[0-9]+)\\][^\\[\\]│║]*").matcher(text);
        StringBuilder result = new StringBuilder();
        int start = 0;
        while (choices.find()) {
            result.append(text.substring(start, choices.start()));
            String choice = choices.group();
            int keyEnd = choice.indexOf(']') + 1;
            String key = choice.substring(0, keyEnd);
            String label = choice.substring(keyEnd);
            String action = label.trim().toLowerCase();
            if ((key.equals("[B]") || key.equals("[M]") || choice.contains("Main Menu")
                    || action.startsWith("next page") || action.startsWith("previous page"))
                    && !action.startsWith("discard")) {
                result.append(color(choice, SOFT_WHITE));
                start = choices.end();
                continue;
            }
            if (key.equals("[Y]") || action.startsWith("confirm") || action.startsWith("approve")
                    || action.startsWith("create account") || action.startsWith("submit") || action.startsWith("save")) {
                result.append(color(choice, "\u001B[1;92m"));
                start = choices.end();
                continue;
            }
            if (action.startsWith("logout") || action.startsWith("cancel") || action.startsWith("exit")
                    || (action.startsWith("delete") && !choice.trim().equals("[4] Delete Game"))
                    || action.startsWith("remove") || action.startsWith("discard")
                    || key.equals("[N]")) {
                result.append(color(choice, "\u001B[1;91m"));
                start = choices.end();
                continue;
            }
            String tone = SOFT_WHITE;
            if (choice.contains("All Games")) {
                tone = "\u001B[1;95m";
            } else if (choice.contains("PS5")) {
                tone = "\u001B[1;94m";
            } else if (choice.contains("Xbox")) {
                tone = "\u001B[1;92m";
            } else if (choice.contains("Nintendo")) {
                tone = "\u001B[1;91m";
            }
            result.append(color(key, BOLD_MUSTARD));
            result.append(color(label, tone));
            start = choices.end();
        }
        result.append(text.substring(start));
        return result.toString();
    }


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
    private int checkoutPage = 0;
    private int checkoutPages = 1;

    // Move to a valid checkout queue page when N or P is entered.
    boolean moveCheckoutPage(String choice) {
        if (choice.equalsIgnoreCase("N") && checkoutPage < checkoutPages - 1) {
            checkoutPage++;
            return true;
        }
        if (choice.equalsIgnoreCase("P") && checkoutPage > 0) {
            checkoutPage--;
            return true;
        }
        return false;
    }
    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("MMM dd", java.util.Locale.ENGLISH);

    // Wrap text in the selected ANSI color when colors are enabled.
    private String color(String text, String tone) {
        if (!colorsEnabled || tone.isEmpty() || text.isEmpty()) {
            return text;
        }
        return tone + text + RESET;
    }

    // Apply the existing platform and status colors to matching words.
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

    // Apply shared text colors and symbols to a displayed line.
    private String styleContent(String text) {
        if (text.matches(".*\\[(ENTER|[A-Z0-9]|[0-9]+–[0-9]+)\\].*")) {
            return colorChoices(text);
        }
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

    // Color table separators without changing their positions.
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

    // Render a styled terminal line, or a blank line when no text is supplied.
    private void printLine() {
        emitLine("");
    }

    // Write text with the screen's horizontal margin and optional newline.
    private void writeTerminal(String text, boolean newLine) {
        if (text.startsWith("\r")) {
            System.out.print("\r");
            text = text.substring(1);
            outputAtLineStart = true;
        }
        if (outputAtLineStart && !text.isEmpty()) {
            int margin = Math.max(0, (terminalWidth - currentWidth) / 2);
            System.out.print(" ".repeat(margin));
            outputAtLineStart = false;
        }
        System.out.print(text);
        if (newLine) {
            System.out.println();
            outputAtLineStart = true;
        }
    }

    // Write text without adding a newline.
    private void emit(String text) {
        flushBorder(false);
        writeTerminal(text, false);
        lastWasRow = false;
    }

    // Write text followed by a newline.
    private void emitLine(String text) {
        flushBorder(false);
        writeTerminal(text, true);
        lastWasRow = false;
    }

    // Choose the side-border character for the current box style.
    private String wall() {
        if (lightBox) {
            return color("\u2502", borderTone);
        }
        return color("\u2551", borderTone);
    }

    // Hold a border until nearby rows reveal its required shape.
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

    // Print a pending border with the correct connections.
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
        writeTerminal(text, true);
    }

    // Build a colored border using the selected style and corners.
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

    // Print a styled box row with matching side borders.
    private void printStyledRow(String styled) {
        flushBorder(true);
        writeTerminal(styled, true);
        lastWasRow = true;
    }

    // Render a styled terminal line, or a blank line when no text is supplied.
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

    // Print the plain-character version of a line.
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

    // Split formatted output into lines for shared rendering.
    private void printFormatted(String output) {
        for (String line : output.split("\\R")) {
            printLine(line);
        }
    }

    // Read and trim a text line, stopping safely when input ends.
    String readText(String prompt) {
        showPendingMessage(currentWidth);
        emit(prompt);
        outputAtLineStart = true; // ENTER finishes the line in the terminal.
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

    // Read a hidden password without trimming or printing its contents.
    String readPassword(String prompt) {
        showPendingMessage(currentWidth);
        emit("");   // draw the input panel's bottom border before the hidden prompt
        if (System.getProperty("os.name").startsWith("Windows")) {
            emit(prompt);
            outputAtLineStart = true;
            System.out.flush();
            return readWindowsInput(true);
        }
        if (System.console() != null) {
            emit(prompt);
            outputAtLineStart = true;
            char[] password = System.console().readPassword();
            if (password == null) {
                throw new InputEndedException();
            }
            String value = new String(password);
            java.util.Arrays.fill(password, '\0');
            return value;
        }
        throw new IllegalArgumentException("Run QUEUE2PLAY in an interactive terminal to enter a hidden password.");
    }

    // Capture Windows key input with optional password masking.
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

    // Read a whole-number choice and handle invalid input.
    int readNumber(String prompt) {
        String text = readText(prompt);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // Draw the input separator and any field-specific guidance.
    void inputBar(String label) {
        inputBar(label, currentWidth);
    }

    // Draw the input separator and any field-specific guidance.
    void inputBar(String label, int width) {
        inputBar(label, width, inputGuide(label));
    }

    // Draw the input separator and any field-specific guidance.
    void inputBar(String label, int width, String guide) {
        showPendingMessage(width);
        currentWidth = width;
        if (!checkoutDisplay) {
            currentWidth = Math.min(width, terminalWidth);
            width = currentWidth;
        }
        if (guide.startsWith("[")) {
            for (String row : guide.split("\n")) {
                ArrayList<String> options = new ArrayList<>();
                Matcher matcher = Pattern.compile("\\[[^\\]]+\\][^\\[]*").matcher(row);
                while (matcher.find()) {
                    options.add(matcher.group().trim());
                }
                showNavigationOptions(width, options.toArray(new String[0]));
            }
            guide = "";
        }
        String choices = pendingNavigation;
        pendingNavigation = "";
        String previousBorder = borderTone;
        String previousText = textTone;
        boolean previousLight = lightBox;
        borderTone = PINK;
        lightBox = true;
        textTone = SOFT_WHITE;
        if (!choices.isEmpty()) {
            flushBorder(false);
            lastWasRow = false;
            printLine(border(width, '-'));
            printBoxCentered("", width);
            for (String row : choices.split("\n", -1)) {
                if (row.length() > width - 2) {
                    printMessageRows(row, width);
                } else {
                    printBoxCentered(row, width);
                }
            }
            printBoxCentered("", width);
            printLine(border(width, '-'));
            flushBorder(false);
            if (!compactInput) {
                printLine();
            }
        }
        if (!guide.isEmpty()) {
            printLine();
            guide = guide.replace(". Example:", ".\nExample:");
            for (String row : guide.split("\n")) {
                String tone = BOLD_WHITE;
                if (row.toLowerCase().contains("example")) {
                    tone = "\u001B[1;90m";
                }
                for (String part : wrapText(row, width)) {
                    emitLine(color(center(part, width), tone));
                }
            }
            printLine();
        }
        String text = " " + fit(label.trim(), width - 2) + " ";
        int remaining = Math.max(0, width - text.length());
        int left = remaining / 2;
        int right = remaining - left;
        String separator = "-";
        if (unicode) {
            separator = "─";
        }
        emitLine(color(separator.repeat(left) + text + separator.repeat(right), PINK));
        textTone = previousText;
        borderTone = previousBorder;
        lightBox = previousLight;
    }

    // Choose the short helper text for the current input field.
    private String inputGuide(String label) {
        switch (label) {
            case "FULL NAME": return "5-60 characters; letters, spaces and dots.";
            case "USERNAME": return "3-20 characters; include a letter. Allowed: letters, digits, . _ -";
            case "EMAIL": return "Max 100 characters. Example: juan@gmail.com";
            case "SEARCH GAME": return "Game ID, title, or platform.";
            case "ENTER QUANTITY":
            case "QUANTITY TO ADD":
            case "QUANTITY TO DECREASE": return "Whole copies only.";
            case "RENTAL DURATION": return "1-" + Queue2Play.MAX_RENTAL_DAYS + " days.";
            case "QUEUE NUMBER TO CANCEL": return "Queue number.\nExample: 003.";
            case "DAYS LATE": return "Enter days late; use 0 if returned on time.";
            case "RENTAL PRICE": return money(Queue2Play.MIN_RENTAL_PRICE)
                + " to " + money(Queue2Play.MAX_RENTAL_PRICE) + ".";
            case "AVAILABLE COPIES": return "1-" + Queue2Play.MAX_GAME_STOCK + " copies.";
            case "ENTER Y / N": return "[Y] Yes      [N] No";
            default: return "";
        }
    }

    // Build the pink input marker with the correct screen margin.
    String prompt() {
        return prompt(currentWidth);
    }

    // Build the pink input marker with the correct screen margin.
    String prompt(int width) {
        currentWidth = width;
        return "  " + color(">", PINK) + " ";
    }

    // Start the next screen at the top when terminal clearing is enabled.
    void clearScreen() {
        pendingNavigation = "";
        pendingBorder = null;
        lastWasRow = false;
        outputAtLineStart = true;
        currentWidth = STANDARD_WIDTH;
        borderTone = PINK;
        textTone = "";
        lightBox = false;
        if (cursorEnabled) {
            System.out.print("\u001B[2J\u001B[H");
            System.out.flush();
        }
    }

    // Wait briefly for an animation unless fast mode is enabled.
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

    // Display a short progress animation for the current action.
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

    // Finish pending borders before showing result navigation.
    void finishScreen() {
        finishScreen(currentWidth);
    }

    // Finish pending borders before showing result navigation.
    void finishScreen(int width) {
        currentWidth = width;
        // Retry messages stay pending until the next input panel is drawn.
        flushBorder(false);
    }

    // Keep a completed result visible until its named destination is chosen.
    boolean waitForResultNavigation(String previousDestination, String mainDestination) {
        while (true) {
            if (previousDestination.isEmpty()) {
                showNavigationOptions(new String[] {"[M] " + mainDestination});
            } else {
                showNavigationOptions(new String[] {"[B] " + previousDestination, "[M] " + mainDestination});
            }
            inputBar("ENTER CHOICE");
            String choice = readText(prompt());
            if (choice.equalsIgnoreCase("M")) {
                return false;
            }
            if (!previousDestination.isEmpty() && choice.equalsIgnoreCase("B")) {
                return true;
            }
            clearResultRetry("Use the displayed navigation key.");
        }
    }

    // Clear the retry area before showing another navigation error.
    private void clearResultRetry(String message) {
        int width = currentWidth;
        clearScreen();
        currentWidth = width;
        error(message);
    }

    // Display or defer a success message for the current screen.
    void success(String message) {
        showPendingMessage(currentWidth);
        printMessageBox("SUCCESS: " + message, currentWidth);
    }

    // Display or defer an error message for the current screen.
    void error(String message) {
        pendingMessageType = "ERROR";
        pendingMessage = message;
    }

    // Display or defer a warning message for the current screen.
    void warning(String message) {
        pendingMessageType = "WARNING";
        pendingMessage = message;
    }

    // Display an informational line using the shared renderer.
    void info(String message) {
        if (message.startsWith("[")) {
            showNavigationOptions(new String[] {message});
            return;
        }
        printLine(message);
    }

    // Display a deferred message before the next input prompt.
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

    // Display a message in the screen's matching card style.
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
        borderTone = PINK;
        textTone = tone;
        lightBox = false;
        printLine();
        printLine(border(width, '-'));
        printMessageRows(text, width);
        printLine(border(width, '-'));
        borderTone = previousBorder;
        textTone = previousText;
        lightBox = previousLight;
    }

    // Wrap and center message text inside its card.
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

    // Keep an empty-list message visible within the current screen.
    void showEmptyState(String message) {
        printLine(border(currentWidth, '-'));
        printBoxCentered("", currentWidth);
        printMessageRows(message, currentWidth);
        printBoxCentered("", currentWidth);
        printLine(border(currentWidth, '-'));
    }

    // Prepare a platform label for the shared color renderer.
    String platformColor(String platform) {
        // Keep labels plain until after formatting so ANSI codes do not affect columns.
        return platform;
    }
    // Return the VIP or regular membership label.
    String membershipColor(boolean vip) {
        if (vip) {
            return "VIP";
        }
        return "REGULAR";
    }
    // Prepare a status label for the shared color renderer.
    String statusColor(String status) {
        return status;
    }

    // Start a screen with its title and optional path at the chosen width.
    void printHeader(String title) {
        printHeader(title, STANDARD_WIDTH);
    }

    // Start a screen with its title and optional path at the chosen width.
    void printHeader(String title, int width) {
        clearScreen();
        if (!checkoutDisplay) {
            width = Math.min(width, terminalWidth);
        }
        currentWidth = width;
        printLine(border(width, '='));
        printTitle(title, width);
        printLine(border(width, '='));
    }

    // Start a screen with its title and optional path at the chosen width.
    void printHeader(String path, String title) {
        printHeader(path, title, STANDARD_WIDTH);
    }

    // Start a screen with its title and optional path at the chosen width.
    void printHeader(String path, String title, int width) {
        clearScreen();
        if (!checkoutDisplay) {
            width = Math.min(width, terminalWidth);
        }
        currentWidth = width;
        printLine(border(width, '='));
        printTitle(path, width);
        printLine(border(width, '='));
        printTitle(title, width);
        printLine(border(width, '='));
    }

    // Build a table separator matching the requested width.
    String tableLine(int width) {
        return "-".repeat(Math.max(1, width));
    }

    // Build a box border using the requested width and fill character.
    String border(int width, char fill) {
        if (width < 2) {
            return String.valueOf(fill).repeat(Math.max(1, width));
        }
        return "+" + String.valueOf(fill).repeat(width - 2) + "+";
    }

    // Center content inside the current box borders.
    void printBoxCentered(String text, int width) {
        int inner = Math.max(0, width - 2);
        printLine("|" + center(fit(text, inner), inner) + "|");
    }

    // Print a centered screen title at the chosen width.
    private void printTitle(String title, int width) {
        String previousText = textTone;
        textTone = BOLD_WHITE;
        printBoxCentered(title, width);
        textTone = previousText;
    }

    // Display centered detail rows in a shared detail box.
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

    // Display action choices using the shared choices layout.
    private void showFeatureOptions(String[] options) {
        showNavigationOptions(options);
    }

    // Display a vertical numbered menu with padded rows.
    private void showMenuOptions(String[] options) {
        int longest = 0;
        for (String option : options) {
            longest = Math.max(longest, option.trim().length());
        }
        String group = "";
        for (String option : options) {
            if (!group.isEmpty()) {
                group += "\n";
            }
            String label = option.trim();
            group += label + " ".repeat(longest - label.length());
        }
        if (!pendingNavigation.isEmpty()) {
            pendingNavigation += "\n\n";
        }
        pendingNavigation += group;
    }

    // Shorten text only when it exceeds the available display space.
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

    // Pad text so it appears centered within the given width.
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

    // Format an amount with a dollar sign and two decimal places.
    String money(double amount) {
        return String.format("$%.2f", amount);
    }

    // Fit summary text into the available box width.
    private String summaryText(String text, int width) {
        if (text.length() > width) {
            return text.substring(0, width);
        }
        return text;
    }

    // Format a duration with the correct day or days label.
    private String rentalDurationText(int days) {
        if (days == 1) {
            return days + " day";
        }
        return days + " days";
    }
    // Format a queue number with leading zeros.
    String queueLabel(int number) {
        return String.format("#%03d", number);
    }
    // Format a rental or history number with leading zeros.
    String rentalLabel(int number) {
        return String.format("#%03d", number);
    }

    // Display grouped destination choices using the current or supplied width.
    void showNavigationOptions(String[] options) {
        showNavigationOptions(currentWidth, options);
    }

    // Display grouped destination choices using the current or supplied width.
    void showNavigationOptions(int width, String[] options) {
        currentWidth = width;
        ArrayList<String> actions = new ArrayList<>();
        ArrayList<String> cancellation = new ArrayList<>();
        ArrayList<String> navigation = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith("[C]") && (option.contains("Cancel") || option.contains("Main Menu"))) {
                cancellation.add(option);
            } else if (option.startsWith("[B]") || option.startsWith("[M]")) {
                navigation.add(option);
            } else {
                actions.add(option);
            }
        }
        showChoiceGroup(actions.toArray(new String[0]));
        showChoiceGroup(cancellation.toArray(new String[0]));
        showChoiceGroup(navigation.toArray(new String[0]));
    }

    // Center related choices and wrap them with spacing between categories.
    void showChoiceGroup(String[] options) {
        if (options.length == 0) {
            return;
        }
        int available = Math.min(currentWidth, terminalWidth) - 4;
        String group = "";
        String row = "";
        int columns = 0;
        for (String option : options) {
            if (!row.isEmpty() && (columns == 3 || row.length() + 6 + option.length() > available)) {
                if (!group.isEmpty()) {
                    group += "\n";
                }
                group += row;
                row = "";
                columns = 0;
            }
            if (!row.isEmpty()) {
                row += "      ";
            }
            row += option;
            columns++;
        }
        if (!row.isEmpty()) {
            if (!group.isEmpty()) {
                group += "\n";
            }
            group += row;
        }
        if (!pendingNavigation.isEmpty()) {
            pendingNavigation += "\n\n";
        }
        pendingNavigation += group;
    }

    // Display menu navigation inside the existing padded choices box.
    private void showBoxNavigationOptions(String[] options) {
        showNavigationOptions(STANDARD_WIDTH, options);
    }

    // Display stock and copy allowances before quantity input.
    void showSelectQuantityScreen(Game game, int inCart, int maxPerGame, int maximumToAdd) {
        showSelectQuantityScreen(game, inCart, 0, maxPerGame, maximumToAdd);
    }

    // Display stock and copy allowances before quantity input.
    void showSelectQuantityScreen(Game game, int inCart, int queued, int maxPerGame, int maximumToAdd) {
        printHeader("CLIENT > RENT GAMES", "SELECT QUANTITY");
        printCenteredDetails(new String[] {
            "Game: " + game.title,
            "Platform: " + platformColor(game.platform),
            "Already in Cart: " + inCart,
            "Already Queued: " + queued,
            "Available Stock: " + game.availableCopies,
            "Maximum Per Game: " + maxPerGame,
            "You may request up to: " + maximumToAdd
        });
        printBoxCentered("Cart quantity cannot exceed available stock.", STANDARD_WIDTH);
        showNavigationOptions(new String[] {"[B] Select Game", "[M] Discard Cart / Dashboard"});
        printLine(border(STANDARD_WIDTH, '='));
        inputBar("ENTER QUANTITY");
    }

    // Display a centered confirmation question and its Yes/No choices.
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

    // Wrap long text into complete rows inside a box.
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

    // Display the landing page with Login, Register and Exit choices.
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
        showMenuOptions(new String[] {"[1] Login\n", "[2] Register"});
        showBoxNavigationOptions(new String[] {"[Q] Exit"});
        inputBar("ENTER CHOICE");
    }

    // Display the simpler landing page for plain terminal output.
    private void showPlainMainMenu() {
        printHeader("QUEUE2PLAY");
        showMenuOptions(new String[] {"[1] Login", "[2] Register"});
        showBoxNavigationOptions(new String[] {"[Q] Exit"});
        inputBar("ENTER CHOICE");
    }

    // Print aligned logo artwork line by line.
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

    // Display the Queue2Play logo.
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

    // Display the application's exit message.
    void showGoodbye() {
        clearScreen();
        int artWidth = 0;
        for (String row : THANK_YOU_ART) {
            artWidth = Math.max(artWidth, row.length());
        }
        currentWidth = Math.min(terminalWidth, STANDARD_WIDTH);
        if (unicode && terminalWidth >= artWidth) {
            currentWidth = artWidth;
            for (String row : THANK_YOU_ART) {
                emitLine(color(row + " ".repeat(artWidth - row.length()), PINK));
            }
            printLine();
        }
        emitLine(color(center("Thank you for using QUEUE2PLAY. Goodbye!", currentWidth), PINK));
    }

    // Display username-or-email input and its navigation choices.
    void showLoginUsernameScreen() {
        printHeader("LOGIN");
        printCenteredDetails(new String[] {"Username or email"});
        showBoxNavigationOptions(new String[] {"[B] Main Menu"});
        inputBar("USERNAME OR EMAIL");
    }

    // Display password input for the selected login identifier.
    void showLoginPasswordScreen(String login) {
        printHeader("LOGIN");
        printCenteredDetails(new String[] {"Username/Email: " + fit(login, 40)});
        showBoxNavigationOptions(new String[] {"[B] Username/Email", "[C] Main Menu"});
        inputBar("PASSWORD");
    }

    // Display the current registration field and preserved draft details.
    void showRegistrationStep(int step, boolean editing, String fullName, String username, String email) {
        String[] labels = {"FULL NAME", "USERNAME", "EMAIL", "PASSWORD", "CONFIRM PASSWORD"};
        String label = labels[step - 1];
        printHeader("CREATE ACCOUNT", label);
        if (!fullName.isEmpty()) {
            printCenteredDetails(new String[] {"Name: " + fullName});
        }
        if (!username.isEmpty()) {
            printCenteredDetails(new String[] {"Username: " + username});
        }
        if (!email.isEmpty()) {
            printCenteredDetails(new String[] {"Email: " + email});
        }
        if (step == 1 && !editing) {
            showNavigationOptions(new String[] {"[B] Main Menu"});
        } else if (editing && step != 5) {
            showNavigationOptions(new String[] {"[B] Edit Information", "[C] Cancel Registration"});
        } else {
            showNavigationOptions(new String[] {"[B] " + labels[step - 2], "[C] Cancel Registration"});
        }
        if (step == 4) {
            inputBar(label, STANDARD_WIDTH, "8-20 characters; no spaces or |.");
        } else {
            inputBar(label);
        }
    }

    // Display the fields that can be edited without resetting the draft.
    void showRegistrationEditMenu(String fullName, String username, String email) {
        printHeader("CREATE ACCOUNT", "EDIT INFORMATION");
        printCenteredDetails(new String[] {"Name: " + fullName, "Username: " + username, "Email: " + email});
        showMenuOptions(new String[] {"[1] Full Name", "[2] Username", "[3] Email", "[4] Password"});
        showNavigationOptions(new String[] {"[B] Registration Summary"});
        inputBar("ENTER CHOICE");
    }

    // Ask before discarding unsaved registration details.
    boolean confirmRegistrationCancellation() {
        while (true) {
            printHeader("CREATE ACCOUNT", "CANCEL REGISTRATION");
            printBoxCentered("Discard all entered registration information?", STANDARD_WIDTH);
            inputBar("ENTER CHOICE", STANDARD_WIDTH, "[Y] Discard Registration   [N] Keep Registering");
            String answer = readText(prompt()).trim();
            if (answer.equalsIgnoreCase("Y") || answer.equalsIgnoreCase("YES")) {
                return true;
            }
            if (answer.equalsIgnoreCase("N") || answer.equalsIgnoreCase("NO")) {
                return false;
            }
            error("Use Y to discard or N to keep registering.");
        }
    }

    // Display the countdown while waiting through the login cooldown.
    void showLoginCooldown() {
        showPendingMessage(STANDARD_WIDTH);
        printMessageBox("WARNING: Three failed attempts. Wait 30 seconds before trying again.", STANDARD_WIDTH);
        flushBorder(false);
        System.out.flush();
        for (int seconds = 30; seconds > 0; seconds--) {
            showCooldownRemaining(seconds);
            waitLoginSecond();
        }
        showCooldownRemaining(0);
        emitLine("");
        printLine("You can try again now.");
    }

    // Display the failed attempt number and remaining attempts.
    void showLoginAttempt(int attempt) {
        String message = "Attempt " + attempt + "/3 — ";
        if (attempt == 3) {
            message += "Wait 30 seconds before trying again";
        } else if (attempt == 2) {
            message += "1 attempt remaining";
        } else {
            message += "2 attempts remaining";
        }
        // The next input panel displays this with the authentication error.
        if (pendingMessage == null) {
            pendingMessageType = "WARNING";
            pendingMessage = message;
        } else {
            pendingMessage += "\n" + message;
        }
    }

    // Update the countdown on the same terminal line.
    void showCooldownRemaining(int seconds) {
        currentWidth = Math.min(STANDARD_WIDTH, terminalWidth);
        emit("\r" + color(center("Try again in " + seconds + " seconds", currentWidth), YELLOW));
        System.out.flush();
    }

    // Wait one real second for the login cooldown.
    void waitLoginSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InputEndedException();
        }
    }
    // Display platform choices and the applicable navigation destinations.
    void showPlatformMenu(String heading) {
        showPlatformMenu(heading, "Dashboard");
    }

    // Display platform choices and the applicable navigation destinations.
    void showPlatformMenu(String heading, String destination) {
        showPlatformMenu(heading, destination, false);
    }

    // Display platform choices and the applicable navigation destinations.
    void showPlatformMenu(String heading, String destination, boolean hasCart) {
        printHeader(heading);
        showPlatformChoices();
        if (hasCart) {
            showChoiceGroup(new String[] {"[F] Finish Selection"});
        }
        if (!destination.equals("Dashboard")) {
            String dashboard = "[M] Dashboard";
            if (heading.contains("RENT GAMES")) {
                dashboard = "[M] Discard Cart / Dashboard";
            }
            showBoxNavigationOptions(new String[] {"[B] " + destination, dashboard});
        } else {
            String back = "[B] Dashboard";
            if (hasCart) {
                back = "[B] Discard Cart / Dashboard";
            }
            showBoxNavigationOptions(new String[] {back});
        }
        inputBar("ENTER CHOICE");
    }

    // Display the four catalog filters with their existing colors.
    private void showPlatformChoices() {
        showMenuOptions(new String[] {"[1] Browse All Games", "[2] PS5 Games", "[3] Xbox Games", "[4] Nintendo Games"});
    }

    // Display platform browsing choices for the current destination.
    void showBrowseMenu(boolean fromInventory) {
        printHeader("BROWSE GAME INVENTORY");
        showPlatformChoices();
        if (fromInventory) {
            showBoxNavigationOptions(new String[] {"[B] Inventory", "[M] Dashboard"});
        }
        else showBoxNavigationOptions(new String[] {"[B] Dashboard"});
        inputBar("ENTER CHOICE");
    }

    // Display the client's account details and dashboard choices.
    void showClientMenu(User client) {
        printHeader("CLIENT DASHBOARD");
        printCenteredDetails(new String[] {"Welcome: " + fit(client.username, 35), "Membership: " + membershipColor(client.isVip())});
        showMenuOptions(new String[] {"[1] Browse Games", "[2] Rent Games", "[3] My Queue", "[4] Rental History", "[5] VIP Membership"});
        showBoxNavigationOptions(new String[] {"[L] Logout"});
        inputBar("ENTER CHOICE");
    }

    // Display the administrator's dashboard choices.
    void showAdminMenu() {
        printHeader("ADMIN DASHBOARD");
        showMenuOptions(new String[] {"[1] Checkout", "[2] Process Returns", "[3] Inventory", "[4] Active Rentals", "[5] Audit Log"});
        showBoxNavigationOptions(new String[] {"[L] Logout"});
        inputBar("ENTER CHOICE");
    }

    // Display the active-rental list choice and Dashboard navigation.
    void showActiveRentalsMenu() {
        printHeader("ACTIVE RENTALS");
        showMenuOptions(new String[] {"[1] All Active Rentals"});
        showBoxNavigationOptions(new String[] {"[B] Dashboard"});
        inputBar("ENTER CHOICE");
    }

    // Display the inventory-management menu.
    void showInventoryMenu() {
        printHeader("MANAGE GAME INVENTORY");
        showMenuOptions(new String[] {"[1] Browse Inventory", "[2] Add Game", "[3] Update Stock", "[4] Delete Game"});
        showBoxNavigationOptions(new String[] {"[B] Dashboard"});
        inputBar("ENTER CHOICE");
    }

    // Display queued requests without removing them.
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

    // Read a Yes or No answer and retry invalid input.
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

    // Display the current cart, duration and calculated costs.
    void showOrderSummary(User client, LinkedHashMap<Game, Integer> cart, int days, RentalManager rentalService) {
        showCartPage(client, cart, days, rentalService, false, 0, cartPages(client, cart, days, rentalService, false));
    }

    // Copy cart entries into numbered display rows without changing the cart map.
    private ArrayList<String[]> cartRows(User client, LinkedHashMap<Game, Integer> cart, int days,
            RentalManager service, boolean editing) {
        ArrayList<String[]> rows = new ArrayList<>();
        int line = 1;
        for (Game game : cart.keySet()) {
            int quantity = cart.get(game);
            if (editing) {
                rows.add(new String[] {String.valueOf(line), game.title, game.platform, String.valueOf(quantity)});
            } else {
                rows.add(new String[] {String.valueOf(line), game.title, game.platform, String.valueOf(quantity),
                    money(service.calculateRentalCost(client, game, days)),
                    money(service.calculateRentalCost(client, game, days, quantity))});
            }
            line++;
        }
        return rows;
    }

    // Calculate full-cart totals for the summary display.
    private String[] cartSummary(User client, LinkedHashMap<Game, Integer> cart, int days,
            RentalManager service, boolean editing) {
        if (editing) {
            return new String[] {"Rental Duration: " + rentalDurationText(days)};
        }
        double total = 0;
        int totalQuantity = 0;
        for (Game game : cart.keySet()) {
            int quantity = cart.get(game);
            total += service.calculateRentalCost(client, game, days, quantity);
            totalQuantity += quantity;
        }
        String membership = client.membership;
        if (client.isVip()) {
            membership += " - 10% discount applied";
        }
        return new String[] {"Total Quantity: " + totalQuantity + " · Total Cost: " + money(total),
            "Rental Duration: " + rentalDurationText(days), "Membership: " + membership};
    }

    // Measure cart rows to build fitting order-summary pages.
    ArrayList<Integer> cartPages(User client, LinkedHashMap<Game, Integer> cart, int days,
            RentalManager service, boolean editing) {
        if (editing) {
            return transactionPages(cartRows(client, cart, days, service, true),
                new String[] {"NO.", "GAME TITLE", "PLATFORM", "QTY"}, new int[] {4, 30, 10, 5},
                EDIT_ORDER_TABLE_WIDTH, cartSummary(client, cart, days, service, true));
        }
        return transactionPages(cartRows(client, cart, days, service, false),
            new String[] {"NO.", "GAME TITLE", "PLATFORM", "QTY", "UNIT PRICE", "SUBTOTAL"},
            new int[] {4, 30, 10, 5, 10, 10}, ORDER_TABLE_WIDTH, cartSummary(client, cart, days, service, false));
    }

    // Display one cart page while keeping whole-cart line numbers.
    void showCartPage(User client, LinkedHashMap<Game, Integer> cart, int days, RentalManager service,
            boolean editing, int page, ArrayList<Integer> starts) {
        if (pendingMessage != null && !pendingMessage.isEmpty()) {
            String message = pendingMessage;
            currentWidth = Math.min(editing ? EDIT_ORDER_TABLE_WIDTH : ORDER_TABLE_WIDTH, terminalWidth);
            clearResultRetry(message);
            return;
        }
        if (editing) {
            showTransactionPage("EDIT ORDER", cartRows(client, cart, days, service, true),
                new String[] {"NO.", "GAME TITLE", "PLATFORM", "QTY"}, "%-4s | %-30s | %-10s | %5s",
                new int[] {4, 30, 10, 5}, EDIT_ORDER_TABLE_WIDTH,
                cartSummary(client, cart, days, service, true), page, starts);
        } else {
            showTransactionPage("ORDER SUMMARY", cartRows(client, cart, days, service, false),
                new String[] {"NO.", "GAME TITLE", "PLATFORM", "QTY", "UNIT PRICE", "SUBTOTAL"},
                "%-4s | %-30s | %-10s | %5s | %10s | %10s", new int[] {4, 30, 10, 5, 10, 10},
                ORDER_TABLE_WIDTH, cartSummary(client, cart, days, service, false), page, starts);
        }
    }

    // Display order actions and only usable page controls.
    void showCartActions(boolean editing, int page, int pages) {
        if (terminalHeight < 30) {
            String[] choices = {"[1] Confirm Order", "[E] Edit Order", "[B] Rental Duration", "[M] Discard Cart / Dashboard"};
            if (editing) {
                choices = new String[] {"[1] Add Game", "[2] Remove Game", "[3] Change Duration", "[B] Order Summary", "[M] Discard Cart / Dashboard"};
            }
            showTransactionNavigation(page, pages, "N", choices);
        } else {
            showPagination(page, pages);
            if (editing) {
                showFeatureOptions(new String[] {"[1] Add Game", "[2] Remove Game", "[3] Change Duration"});
                showNavigationOptions(new String[] {"[B] Order Summary", "[M] Discard Cart / Dashboard"});
            } else {
                showOrderConfirmationOptions();
            }
            inputBar("ENTER CHOICE", currentWidth);
        }
    }

    // Display the game catalog's column headings.
    void showGameTableHeader(int total, int start, int end) {
        printLine("Showing " + (start + 1) + "-" + end + " of " + total + " games");
        printLine();
        printLine(tableLine(TerminalUI.GAME_TABLE_WIDTH));
        printRecord("%-4s | %-6s | %-30s | %-10s | %8s | %5s | %-12s", new String[] {"NO.", "ID", "GAME TITLE", "PLATFORM", "PRICE", "STOCK", "STATUS"}, new int[] {4, 6, 30, 10, 8, 5, 12});
        printLine(tableLine(TerminalUI.GAME_TABLE_WIDTH));
    }

    // Display a numbered game with platform and stock status.
    void showGameRow(int shownNumber, Game game) {
        String availability = "OUT OF STOCK";
        if (game.isAvailable()) {
            availability = "AVAILABLE";
        }
        printRecord("%-4s | %-6s | %-30s | %-10s | %8s | %5s | %-12s",
            new String[] {String.valueOf(shownNumber), game.gameId, game.title, game.platform,
                money(game.price), String.valueOf(game.availableCopies), availability},
            new int[] {4, 6, 30, 10, 8, 5, 12});
    }

    // Display usable catalog controls and the correct return destinations.
    void showGameNavigation() {
        showGameNavigation(0, 1, "");
    }

    // Ask before discarding the cart and returning to Dashboard.
    boolean confirmDiscardCart() {
        while (true) {
            int width = currentWidth;
            printLine(border(width, '-'));
            printWrappedBoxText("Discard your cart and return to Dashboard?", width);
            printLine(border(width, '-'));
            inputBar("CONFIRM", width, "[Y] Discard Cart      [N] Keep Cart");
            String answer = readText(prompt());
            if (answer.equalsIgnoreCase("Y") || answer.equalsIgnoreCase("YES")) {
                return true;
            }
            if (answer.equalsIgnoreCase("N") || answer.equalsIgnoreCase("NO")) {
                return false;
            }
            error("Use Y or N.");
        }
    }

    // Display usable catalog controls and the correct return destinations.
    void showGameNavigation(int page, int pages, String keyword) {
        showGameNavigation(page, pages, keyword, false, false, 0);
    }

    // Display usable catalog controls and the correct return destinations.
    void showGameNavigation(int page, int pages, String keyword, boolean selectable, boolean hasCart, int shown) {
        currentWidth = GAME_TABLE_WIDTH;
        ArrayList<String> actions = new ArrayList<>();
        if (selectable && shown > 0) {
            String numbers = "1";
            if (shown > 1) {
                numbers += "–" + shown;
            }
            actions.add("[" + numbers + "] Select Game");
        }
        if (hasCart) {
            actions.add("[F] Finish Selection");
        }
        showChoiceGroup(actions.toArray(new String[0]));
        ArrayList<String> tools = new ArrayList<>();
        tools.add("[S] Search");
        tools.add("[T] Sort");
        if (!keyword.isEmpty()) {
            tools.add("[X] Clear Search");
        }
        showChoiceGroup(tools.toArray(new String[0]));
        ArrayList<String> pagesAvailable = new ArrayList<>();
        if (page < pages - 1) {
            pagesAvailable.add("[N] Next Page");
        }
        if (page > 0) {
            pagesAvailable.add("[P] Previous Page");
        }
        if (!pagesAvailable.isEmpty()) {
            String saved = pendingNavigation;
            pendingNavigation = "";
            showChoiceGroup(pagesAvailable.toArray(new String[0]));
            pendingNavigation = saved + "\n" + pendingNavigation;
        }
        String dashboard = "[M] Dashboard";
        if (selectable && hasCart) {
            dashboard = "[M] Discard Cart / Dashboard";
        }
        showChoiceGroup(new String[] {"[B] Select Platform", dashboard});
    }

    // Display search, sort, page and optional cart totals.
    void showCatalogState(String keyword, int sortOption, int page, int totalPages) {
        showCatalogState(keyword, sortOption, page, totalPages, null);
    }

    // Display search, sort, page and optional cart totals.
    void showCatalogState(String keyword, int sortOption, int page, int totalPages, LinkedHashMap<Game, Integer> cart) {
        String[] sortNames = {"Default order", "Title A-Z", "Title Z-A", "Price low-high", "Price high-low"};
        String searchLabel = "None";
        if (!keyword.isEmpty()) {
            searchLabel = keyword;
        }
        int width = Math.min(GAME_TABLE_WIDTH, terminalWidth);
        currentWidth = width;
        lightBox = false;
        printLine(border(width, '='));
        printLeftRightBox("Search: " + searchLabel, "Page: " + page + "/" + totalPages, width);
        printLine(border(width, '='));
        String cartText = "";
        if (cart != null) {
            int copies = 0;
            for (int quantity : cart.values()) {
                copies += quantity;
            }
            cartText = "Cart: " + cart.size() + " games · " + copies + " copies";
        }
        printLeftRightBox("Sort: " + sortNames[sortOption], cartText, width);
        printLine(border(width, '='));
        flushBorder(false);
        printLine();
    }

    // Align two status values at opposite sides of a box.
    private void printLeftRightBox(String left, String right, int width) {
        int inner = width - 4;
        right = fit(right, inner);
        left = fit(left, Math.max(0, inner - right.length() - 1));
        printLine("| " + left + " ".repeat(inner - left.length() - right.length()) + right + " |");
    }

    // Display preset and custom durations with the correct Back destination.
    void showRentalDurationOptions() {
        showRentalDurationOptions("Select Game");
    }

    // Display preset and custom durations with the correct Back destination.
    void showRentalDurationOptions(String previousScreen) {
        showFeatureOptions(new String[] {
            "[1] 1 Day",
            "[2] 3 Days",
            "[3] 7 Days",
            "[4] Custom Duration (1-" + Queue2Play.MAX_RENTAL_DAYS + " days)"
        });
        showNavigationOptions(new String[] {"[B] " + previousScreen, "[M] Discard Cart / Dashboard"});
    }

    // Display the editable cart's column headings.
    void showEditOrderTableHeader() {
        printLine(tableLine(TerminalUI.EDIT_ORDER_TABLE_WIDTH));
        printFormatted(String.format("%-4s | %-30s | %-10s | %5s%n",
            "NO.", "GAME TITLE", "PLATFORM", "QTY"));
        printLine(tableLine(TerminalUI.EDIT_ORDER_TABLE_WIDTH));
    }

    // Display one numbered cart line with its quantity and price.
    void showCartRow(int quantity, int line, Game game) {
        printFormatted(String.format("%-4d | %-30s | %-10s | %5d%n",
            line, fit(game.title, 30),
            platformColor(game.platform), quantity));
    }

    // Display add, remove and duration-edit choices.
    void showEditOrderOptions(int days) {
        printCenteredDetails(new String[] {"Rental Duration: " + rentalDurationText(days)});
        showFeatureOptions(new String[] {"[1] Add Game", "[2] Remove Game", "[3] Change Duration"});
        showNavigationOptions(EDIT_ORDER_TABLE_WIDTH, new String[] {"[B] Order Summary", "[M] Discard Cart / Dashboard"});
    }

    // Display submit, edit and discard order choices.
    void showOrderConfirmationOptions() {
        showFeatureOptions(new String[] {"[1] Confirm Order", "[E] Edit Order"});
        showNavigationOptions(ORDER_TABLE_WIDTH, new String[] {"[B] Rental Duration", "[M] Discard Cart / Dashboard"});
    }

    // Display headings for newly submitted requests.
    void showSubmittedQueueHeader() {
        printLine();
        printLine(tableLine(TerminalUI.QUEUE_TABLE_WIDTH));
        printRecord("%-8s | %-30s | %-10s | %5s | %8s", new String[] {"QUEUE #", "GAME TITLE", "PLATFORM", "QTY", "POSITION"}, new int[] {8, 30, 10, 5, 8});
        printLine(tableLine(TerminalUI.QUEUE_TABLE_WIDTH));
    }

    // Display a request's predicted ready position or waiting status.
    void showQueueRequestRow(int position, RentalRequest request) {
        String positionText = "WAITING";
        if (position > 0) {
            positionText = String.valueOf(position);
        }
        printRecord("%-8s | %-30s | %-10s | %5s | %8s",
            new String[] {queueLabel(request.queueNumber), request.game.title,
                request.game.platform, String.valueOf(request.quantity), positionText},
            new int[] {8, 30, 10, 5, 8});
    }

    // Display the customer's queued-request column headings.
    void showQueueRequestHeader() {
        printLine(tableLine(TerminalUI.QUEUE_TABLE_WIDTH));
        printRecord("%-8s | %-30s | %-10s | %5s | %8s", new String[] {"QUEUE #", "GAME TITLE", "PLATFORM", "QTY", "POSITION"}, new int[] {8, 30, 10, 5, 8});
        printLine(tableLine(TerminalUI.QUEUE_TABLE_WIDTH));
    }

    // Display history columns for rental and cancellation records.
    void showHistoryTableHeader(int total, int start, int end) {
        printLine(tableLine(TerminalUI.HISTORY_TABLE_WIDTH));
        printRecord("%-8s | %-28s | %3s | %-21s | %-21s | %9s | %-10s", new String[] {"RECORD #", "GAME TITLE", "QTY", "RECORDED ON", "DUE ON", "AMOUNT", "STATUS"}, new int[] {8, 28, 3, 21, 21, 9, 10});
        printLine(tableLine(TerminalUI.HISTORY_TABLE_WIDTH));
    }

    // Display one history record with any recorded return details.
    void showHistoryRow(Rental rental) {
        printRecord("%-8s | %-28s | %3s | %-21s | %-21s | %9s | %-10s", new String[] {rentalLabel(rental.rentalNumber), rental.game.title, String.valueOf(rental.quantity),
                rental.checkoutDate.format(Queue2Play.TABLE_DATE_FORMAT),
                historyDueDate(rental), money(rental.amount), rental.status}, new int[] {8, 28, 3, 21, 21, 9, 10});
        if (rental.returnDate != null) {
            printLine("Returned: " + rental.returnDate.format(Queue2Play.TABLE_DATE_FORMAT)
                + " | Days late: " + rental.daysLate + " | Recorded late fee: " + money(rental.lateFee));
        }
    }

    // Display the Dashboard return choice.
    void showBackToDashboard() {
        showNavigationOptions(new String[] {"[B] Dashboard"});
    }

    // Pad a checkout card row to the fixed 81-column width.
    private void checkoutRow(String text) {
        printBoxCentered(" " + String.format("%-77s", fit(text, 77)) + " ", CHECKOUT_QUEUE_TABLE_WIDTH);
    }

    // Check whether a request is valid and has enough stock for display.
    boolean checkoutReady(RentalRequest request) {
        return CheckoutQueue.validRequest(request) && request.game.availableCopies >= request.quantity;
    }

    // Return the request's ready or blocked display status.
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

    // Format the available and requested stock for a queue row.
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

    // Build the three-marker VIP streak display.
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

    // Describe the next eligible request or report that none is ready.
    String checkoutNext(CheckoutQueue queue, RentalManager service) {
        RentalRequest next = queue.findNextEligibleRequest();
        if (next == null) {
            return "NEXT  none ready";
        }
        return "NEXT  " + queueLabel(next.queueNumber) + " " + fit(next.client.username, 10)
            + " · " + fit(next.game.title, 22) + " x" + next.quantity + " · " + next.rentalDays
            + " days · " + money(service.calculateRentalCost(next.client, next.game, next.rentalDays, next.quantity));
    }

    // Measure checkout queue rows to build its page boundaries.
    private ArrayList<Integer> checkoutPageStarts(ArrayList<RentalRequest> requests) {
        int[] heights = new int[requests.size()];
        for (int i = 0; i < requests.size(); i++) {
            RentalRequest request = requests.get(i);
            String title = "-";
            String customer = "-";
            if (request.game != null) {
                title = request.game.title;
            }
            if (request.client != null) {
                customer = request.client.username;
            }
            heights[i] = Math.max(wrappedLines(title, 17), wrappedLines(customer, 10));
        }
        return pageStarts(heights, 9, 31);
    }

    // Show no due date for cancelled requests; show it for rentals.
    String historyDueDate(Rental rental) {
        if (rental.status.equals("CANCELLED")) {
            return "-";
        }
        return rental.dueDate.format(Queue2Play.TABLE_DATE_FORMAT);
    }


    // Display predicted queue order with separate status and choices.
    void showCheckoutQueue(CheckoutQueue queue, RentalManager service) {
        clearScreen();
        checkoutDisplay = true;
        currentWidth = CHECKOUT_QUEUE_TABLE_WIDTH;
        ArrayList<RentalRequest> planned = queue.previewReadyRequests();
        ArrayList<RentalRequest> requests = queue.getDisplayRequests(planned);
        int ready = planned.size();
        ArrayList<Integer> starts = checkoutPageStarts(requests);
        checkoutPages = starts.size() - 1;
        checkoutPage = Math.min(checkoutPage, checkoutPages - 1);
        int start = starts.get(checkoutPage);
        int end = starts.get(checkoutPage + 1);
        java.util.LinkedHashMap<Game, Integer> stocks = new java.util.LinkedHashMap<>();
        printLine(border(currentWidth, '='));
        checkoutRow("ADMIN > CHECKOUT");
        printLine(border(currentWidth, '='));
        flushBorder(false);
        printLine();
        printLine(border(currentWidth, '='));
        checkoutRow("QUEUE " + requests.size() + "  READY " + ready
            + "  WAITING " + (requests.size() - ready) + "   VIP streak "
            + checkoutStreak(queue.vipStreak) + " " + queue.vipStreak + "/" + CheckoutQueue.VIP_BURST);
        printLine(border(currentWidth, '='));
        checkoutRow("Page: " + (checkoutPage + 1) + "/" + checkoutPages + " · Showing "
            + (requests.isEmpty() ? 0 : start + 1) + "-" + end);
        printLine(border(currentWidth, '='));
        flushBorder(false);
        printLine();
        if (requests.isEmpty()) {
            showEmptyState("The checkout queue is empty.");
        } else {
            printLine(tableLine(currentWidth));
            printLine(String.format("%-5s%-7s%-5s%-10s%-17s%3s %4s %-7s%-21s",
                "POS", "#", "PRI", "CUSTOMER", "GAME", "QTY", "DAYS", "STOCK", "STATUS"));
            printLine(tableLine(currentWidth));
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
                int position = planned.indexOf(request) + 1;
                String positionText = "-";
                String status = "WAITING";
                String stockText = "-";
                if (request.game != null) {
                    if (!stocks.containsKey(request.game)) {
                        stocks.put(request.game, request.game.availableCopies);
                    }
                    int stock = stocks.get(request.game);
                    stockText = String.valueOf(stock);
                    if (position > 0) {
                        stockText = stock + "→" + (stock - request.quantity);
                        stocks.put(request.game, stock - request.quantity);
                    }
                }
                if (position > 0) {
                    positionText = String.valueOf(position);
                    status = "READY";
                }
                int index = requests.indexOf(request);
                if (index >= start && index < end) {
                    printRecord("%-5s%-7s%-5s%-10s%-17s%3s %4s %-7s%-21s",
                        new String[] {positionText, queueLabel(request.queueNumber), priority, customer,
                            title, String.valueOf(request.quantity), String.valueOf(request.rentalDays), stockText, status},
                        new int[] {5, 7, 5, 10, 17, 3, 4, 7, 21});
                }
            }
        }
        printLine(tableLine(currentWidth));
        printLine(String.format("%-81s", fit(checkoutNext(queue, service), CHECKOUT_QUEUE_TABLE_WIDTH)));
        flushBorder(false);
        lastWasRow = false;
        showTapeInput();
    }

    // Display session status and input after successful tape actions.
    void showCheckoutTapeFooter(Queue2Play.CheckoutSession session, CheckoutQueue queue, RentalManager service) {
        checkoutPages = checkoutPageStarts(queue.getDisplayRequests(queue.previewReadyRequests())).size() - 1;
        checkoutPage = Math.min(checkoutPage, checkoutPages - 1);
        showSessionStatus(session, queue, service);
        showTapeInput();
    }

    // Start a fixed-width result or message card.
    private void beginCheckoutCard(char fill) {
        flushBorder(false);
        lastWasRow = false;
        currentWidth = CHECKOUT_QUEUE_TABLE_WIDTH;
        lightBox = false;
        borderTone = PINK;
        textTone = "";
        printLine(border(currentWidth, fill));
    }

    // Close the current checkout card.
    private void endCheckoutCard(char fill) {
        printLine(border(currentWidth, fill));
        flushBorder(false);
        lastWasRow = false;
        textTone = "";
    }

    // Align a slip's action, session number and timestamp.
    private void showSlipTitle(String title, int number, LocalDateTime timestamp, String tone) {
        textTone = tone;
        String label = title + "  #" + String.format("%02d", number);
        String time = timestamp.format(DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH));
        checkoutRow(String.format("%-69s%8s", fit(label, 69), time));
        textTone = "";
        printLine(border(currentWidth, '-'));
    }

    // Display one numbered approval slip with rental cost and dates.
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

    // Display one numbered cancellation slip without a rental charge.
    void showCancelledSlip(RentalRequest request, int number) {
        beginCheckoutCard('-');
        showSlipTitle("✘ CANCELLED", number, LocalDateTime.now(), RED);
        checkoutRow(String.format("%-11s %-10s %-24s x%d %2d days",
            queueLabel(request.queueNumber), fit(request.client.username, 10), fit(request.game.title, 24),
            request.quantity, request.rentalDays));
        endCheckoutCard('-');
    }

    // Display session totals, VIP streak and the next eligible request.
    void showSessionStatus(Queue2Play.CheckoutSession session, CheckoutQueue queue, RentalManager service) {
        beginCheckoutCard('=');
        String counts = "SESSION   APPROVED " + session.approved + "   CANCELLED " + session.cancelled
            + "   TOTAL " + money(session.total);
        checkoutRow(String.format("%-70s%7s", fit(counts, 70), "VIP " + checkoutStreak(queue.vipStreak)));
        printLine(border(currentWidth, '='));
        checkoutRow(checkoutNext(queue, service));
        endCheckoutCard('=');
    }

    // Display the checkout actions and input prompt area.
    void showTapeInput() {
        showPendingMessage(CHECKOUT_QUEUE_TABLE_WIDTH);
        flushBorder(false);
        lastWasRow = false;
        currentWidth = CHECKOUT_QUEUE_TABLE_WIDTH;
        showChoiceGroup(new String[] {"[ENTER] Approve", "[A] All"});
        showChoiceGroup(new String[] {"[V] Queue", "[R] Receipt"});
        showPagination(checkoutPage, checkoutPages);
        showChoiceGroup(new String[] {"[C] Cancel #"});
        showChoiceGroup(new String[] {"[B] Dashboard"});
        inputBar("ENTER CHOICE", CHECKOUT_QUEUE_TABLE_WIDTH, "");
        flushBorder(false);
        lastWasRow = false;
    }

    // Display a separate centered warning or error card.
    void showMessageCard(String message) {
        beginCheckoutCard('-');
        textTone = RED;
        if (message.startsWith("WARNING:")) {
            textTone = YELLOW;
        }
        printMessageRows(message, CHECKOUT_QUEUE_TABLE_WIDTH);
        endCheckoutCard('-');
    }

    // Center a question between full-width pink separators.
    void showConfirmQuestion(String text) {
        showPendingMessage(CHECKOUT_QUEUE_TABLE_WIDTH);
        flushBorder(false);
        currentWidth = CHECKOUT_QUEUE_TABLE_WIDTH;
        lastWasRow = false;
        String separator = "─".repeat(CHECKOUT_QUEUE_TABLE_WIDTH);
        emitLine(color(separator, PINK));
        emitLine(colorChoices(center(text, CHECKOUT_QUEUE_TABLE_WIDTH)));
        emitLine(color(separator, PINK));
    }

    // Read Yes/No and redraw only the question after an invalid answer.
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

    // Ask before approving the selected queue request.
    boolean confirmCheckout(RentalRequest request, RentalManager service) {
        double cost = service.calculateRentalCost(request.client, request.game, request.rentalDays, request.quantity);
        String prefix = "Approve " + queueLabel(request.queueNumber) + " " + fit(request.client.username, 10) + " · ";
        String suffix = " x" + request.quantity + " · " + money(cost) + " ?   [Y] Yes   [N] No";
        int titleWidth = CHECKOUT_QUEUE_TABLE_WIDTH - prefix.length() - suffix.length();
        return confirmCheckoutAction(prefix + fit(request.game.title, titleWidth) + suffix);
    }

    // Ask before cancelling the selected queue request.
    boolean confirmCheckoutCancellation(RentalRequest request) {
        String prefix = "Cancel " + queueLabel(request.queueNumber) + " " + fit(request.client.username, 10) + " · ";
        String suffix = " x" + request.quantity + " ?   [Y] Yes   [N] No";
        int titleWidth = CHECKOUT_QUEUE_TABLE_WIDTH - prefix.length() - suffix.length();
        return confirmCheckoutAction(prefix + fit(request.game.title, titleWidth) + suffix);
    }

    // Ask before approving the displayed ready batch, with paging when needed.
    boolean confirmCheckoutBatch(int count) {
        return confirmCheckoutAction("Confirm batch? [Y] Approve " + count + " [N] Cancel");
    }

    // These pages contain display copies only. They never process a transaction.
    // Choose compact record display for short or narrow terminals.
    private boolean compactTransactionRows() {
        return terminalHeight < 30 || terminalWidth < CHECKOUT_QUEUE_TABLE_WIDTH;
    }

    // Join field names and values for readable compact records.
    private String labelledRecord(String[] headings, String[] cells) {
        String text = "";
        for (int i = 0; i < cells.length; i++) {
            if (!cells[i].isEmpty()) {
                if (!text.isEmpty()) {
                    text += " | ";
                }
                text += headings[i] + ": " + cells[i];
            }
        }
        return text;
    }

    // Print a complete transaction in table or compact form.
    private void printTransactionRecord(String format, String[] headings, String[] cells, int[] widths, int width) {
        if (compactTransactionRows()) {
            boolean first = true;
            for (String line : wrapText(labelledRecord(headings, cells), Math.min(width, terminalWidth) - 2)) {
                printLine(first ? line : "  " + line);
                first = false;
            }
        } else {
            printRecord(format, cells, widths);
        }
    }

    // Measure transaction rows and reserve room for details and navigation.
    private ArrayList<Integer> transactionPages(ArrayList<String[]> rows, String[] headings,
            int[] widths, int width, String[] summary) {
        return transactionPages(rows, headings, widths, width, summary, new String[0]);
    }

    // Measure transaction rows and reserve room for details and navigation.
    private ArrayList<Integer> transactionPages(ArrayList<String[]> rows, String[] headings,
            int[] widths, int width, String[] summary, String[] details) {
        boolean previous = checkoutDisplay;
        checkoutDisplay = false;
        int[] heights = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            if (compactTransactionRows()) {
                heights[i] = wrappedLines(labelledRecord(headings, rows.get(i)), Math.min(width, terminalWidth) - 2);
            } else {
                heights[i] = recordHeight(rows.get(i), widths, width);
            }
        }
        int summaryHeight = 0;
        for (String line : summary) {
            summaryHeight += wrappedLines(line, Math.min(width, terminalWidth));
        }
        int reserved = 12 + summaryHeight;
        if (terminalHeight >= 30) {
            reserved = 19 + summaryHeight + recordHeight(headings, widths, width);
        }
        for (String line : details) {
            reserved += wrappedLines(line, Math.min(width, terminalWidth) - 4);
        }
        if (details.length > 0 && terminalHeight >= 30) {
            reserved += 4;
        }
        ArrayList<Integer> starts = pageStarts(heights, 9, reserved);
        checkoutDisplay = previous;
        return starts;
    }

    // Display one transaction page with separate metadata and record rows.
    private void showTransactionPage(String title, ArrayList<String[]> rows, String[] headings,
            String format, int[] widths, int width, String[] summary, int page, ArrayList<Integer> starts) {
        showTransactionPage(title, rows, headings, format, widths, width, summary, page, starts, new String[0]);
    }

    // Display one transaction page with separate metadata and record rows.
    private void showTransactionPage(String title, ArrayList<String[]> rows, String[] headings,
            String format, int[] widths, int width, String[] summary, int page, ArrayList<Integer> starts,
            String[] details) {
        boolean previous = checkoutDisplay;
        checkoutDisplay = false;
        if (terminalHeight < 30) {
            clearScreen();
            currentWidth = Math.min(width, terminalWidth);
            printLine(fit(title, currentWidth - 12) + "  " + (page + 1) + "/" + (starts.size() - 1));
        } else {
            printHeader(title, width);
        }
        if (details.length > 0) {
            if (terminalHeight >= 30) {
                printLine(border(currentWidth, '='));
                for (String line : details) {
                    printWrappedBoxText(line, currentWidth);
                }
                printLine(border(currentWidth, '='));
                for (String line : summary) {
                    printWrappedBoxText(line, currentWidth);
                }
                printLine(border(currentWidth, '='));
                flushBorder(false);
            } else {
                for (String line : details) {
                    for (String part : wrapText(line, currentWidth)) {
                        printLine(part);
                    }
                }
            }
        }
        int start = starts.get(page);
        int end = starts.get(page + 1);
        String state = "Page " + (page + 1) + "/" + (starts.size() - 1) + " · Rows "
                + (rows.isEmpty() ? 0 : start + 1) + "-" + end + "/" + rows.size();
        if (terminalHeight >= 30) {
            for (String part : wrapText(state, currentWidth)) {
                printLine(String.format("%-" + currentWidth + "s", part));
            }
        }
        if (terminalHeight >= 30 && !compactTransactionRows()) {
            printLine(tableLine(currentWidth));
            printRecord(format, headings, widths);
        }
        for (int i = start; i < end; i++) {
            printTransactionRecord(format, headings, rows.get(i), widths, width);
        }
        if (rows.isEmpty()) {
            String message = "No transactions in this session.";
            if (title.equals("EDIT ORDER") || title.equals("ORDER SUMMARY")) {
                message = "Your cart is empty.";
            }
            printLine(center(message, currentWidth));
        }
        if (terminalHeight >= 30) {
            printLine(tableLine(currentWidth));
        }
        if (details.length == 0 || terminalHeight < 30) {
            for (String line : summary) {
                for (String part : wrapText(line, currentWidth)) {
                    printLine(String.format("%-" + currentWidth + "s", part));
                }
            }
        }
        flushBorder(false);
        checkoutDisplay = previous;
    }

    // Display usable page keys and named return destinations.
    private void showTransactionNavigation(int page, int pages, String nextKey, String[] destinations) {
        ArrayList<String> choices = new ArrayList<>();
        if (page < pages - 1) {
            choices.add("[" + nextKey + "] " + (terminalHeight < 30 ? "Next" : "Next Page"));
        }
        if (page > 0) {
            choices.add(terminalHeight < 30 ? "[P] Previous" : "[P] Previous Page");
        }
        for (String destination : destinations) {
            choices.add(destination);
        }
        showChoiceGroup(choices.toArray(new String[0]));
        compactInput = terminalHeight < 30;
        inputBar("ENTER CHOICE", currentWidth, "");
        compactInput = false;
    }

    // Copy session approvals and cancellations into receipt display rows.
    private ArrayList<String[]> receiptRows(Queue2Play.CheckoutSession session) {
        ArrayList<String[]> rows = new ArrayList<>();
        for (Queue2Play.CheckoutEntry entry : session.entries) {
            if (entry.rental != null) {
                Rental rental = entry.rental;
                rows.add(new String[] {rentalLabel(rental.rentalNumber), rental.client.username,
                    rental.game.title, String.valueOf(rental.quantity), money(rental.amount), rental.dueDate.format(RECEIPT_DATE), "APPROVED"});
            } else {
                RentalRequest request = entry.cancelled;
                rows.add(new String[] {queueLabel(request.queueNumber), request.client.username,
                    request.game.title, String.valueOf(request.quantity), "-", "-", "CANCELLED"});
            }
        }
        return rows;
    }

    // Build session totals and predicted queue waiting counts.
    private String[] receiptSummary(Queue2Play.CheckoutSession session, CheckoutQueue queue) {
        int queued = queue.getRequests().size();
        int waiting = queued - queue.previewReadyRequests().size();
        return new String[] {"Approved " + session.approved + " · " + session.copies + " copies · Cancelled " + session.cancelled
            + " · TOTAL " + money(session.total), "Left in queue: " + queued + " (" + waiting + " waiting)"};
    }

    // Measure receipt rows to keep the prompt and navigation visible.
    ArrayList<Integer> receiptPages(Queue2Play.CheckoutSession session, CheckoutQueue queue) {
        return transactionPages(receiptRows(session), new String[] {"RECORD", "CUSTOMER", "GAME", "QTY", "AMOUNT", "DUE", "STATUS"},
            new int[] {7, 10, 20, 3, 7, 7, 9}, CHECKOUT_QUEUE_TABLE_WIDTH, receiptSummary(session, queue), receiptDetails(session));
    }

    // Build separate administrator and timestamp details for the receipt.
    private String[] receiptDetails(Queue2Play.CheckoutSession session) {
        return new String[] {"ADMIN " + session.admin + " · "
            + session.started.format(DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a"))};
    }

    // Display one receipt page without changing checkout state.
    void showCheckoutReceiptPage(Queue2Play.CheckoutSession session, CheckoutQueue queue,
            int page, ArrayList<Integer> starts) {
        showTransactionPage("CHECKOUT SESSION RECEIPT", receiptRows(session),
            new String[] {"RECORD", "CUSTOMER", "GAME", "QTY", "AMOUNT", "DUE", "STATUS"},
            "%-7s | %-10s | %-20s | %3s | %7s | %-7s | %-9s", new int[] {7, 10, 20, 3, 7, 7, 9},
            CHECKOUT_QUEUE_TABLE_WIDTH, receiptSummary(session, queue), page, starts, receiptDetails(session));
    }

    // Keep the receipt visible while handling page and destination choices.
    boolean waitForReceiptNavigation(Queue2Play.CheckoutSession session, CheckoutQueue queue, String previousDestination) {
        ArrayList<Integer> starts = receiptPages(session, queue);
        int pages = starts.size() - 1;
        if (pages == 1 && previousDestination.isEmpty()) {
            return waitForResultNavigation("", "Dashboard");
        }
        int page = 0;
        String[] destinations = {"[M] Dashboard"};
        if (!previousDestination.isEmpty()) {
            destinations = new String[] {"[B] " + previousDestination, "[M] Dashboard"};
        }
        while (true) {
            showTransactionNavigation(page, pages, "N", destinations);
            String choice = readText(prompt());
            if (choice.equalsIgnoreCase("M")) {
                return false;
            }
            if (!previousDestination.isEmpty() && (choice.equalsIgnoreCase("B") || choice.equals("0"))) {
                return true;
            }
            if (choice.equalsIgnoreCase("N") && page < pages - 1) {
                page++;
            } else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            } else {
                clearResultRetry("Use the displayed navigation keys.");
                continue;
            }
            showCheckoutReceiptPage(session, queue, page, starts);
        }
    }

    // Build predicted batch rows from copied display data.
    private ArrayList<String[]> batchRows(ArrayList<RentalRequest> planned, CheckoutQueue queue, RentalManager service) {
        ArrayList<String[]> rows = new ArrayList<>();
        int streak = queue.vipStreak;
        for (int i = 0; i < planned.size(); i++) {
            RentalRequest request = planned.get(i);
            String priority = "REG";
            if (request.vip) {
                streak = Math.min(CheckoutQueue.VIP_BURST, streak + 1);
                priority = "VIP " + streak + "/3";
            } else {
                streak = 0;
            }
            rows.add(new String[] {String.valueOf(i + 1), queueLabel(request.queueNumber), request.client.username,
                request.game.title, "x" + request.quantity,
                money(service.calculateRentalCost(request.client, request.game, request.rentalDays, request.quantity)), priority});
        }
        return rows;
    }

    // Calculate the displayed batch counts and total cost.
    private String[] batchSummary(ArrayList<RentalRequest> planned, CheckoutQueue queue, RentalManager service) {
        double total = 0;
        for (RentalRequest request : planned) {
            total += service.calculateRentalCost(request.client, request.game, request.rentalDays, request.quantity);
        }
        return new String[] {"TOTAL " + planned.size() + " rentals · " + money(total),
            "Skipped: " + (queue.getRequests().size() - planned.size()) + " waiting"};
    }

    // Measure predicted batch records before building preview pages.
    ArrayList<Integer> batchPages(ArrayList<RentalRequest> planned, CheckoutQueue queue, RentalManager service) {
        return transactionPages(batchRows(planned, queue, service),
            new String[] {"NO.", "QUEUE", "CUSTOMER", "GAME", "QTY", "AMOUNT", "PRIORITY"},
            new int[] {3, 7, 10, 24, 3, 9, 7}, CHECKOUT_QUEUE_TABLE_WIDTH, batchSummary(planned, queue, service));
    }

    // Display one batch-preview page without processing requests.
    void showCheckoutBatchPage(ArrayList<RentalRequest> planned, CheckoutQueue queue, RentalManager service,
            int page, ArrayList<Integer> starts) {
        showTransactionPage("APPROVE ALL READY", batchRows(planned, queue, service),
            new String[] {"NO.", "QUEUE", "CUSTOMER", "GAME", "QTY", "AMOUNT", "PRIORITY"},
            "%3s | %-7s | %-10s | %-24s | %3s | %9s | %-7s", new int[] {3, 7, 10, 24, 3, 9, 7},
            CHECKOUT_QUEUE_TABLE_WIDTH, batchSummary(planned, queue, service), page, starts);
    }

    // Ask before approving the displayed ready batch, with paging when needed.
    boolean confirmCheckoutBatch(ArrayList<RentalRequest> planned, CheckoutQueue queue, RentalManager service) {
        ArrayList<Integer> starts = batchPages(planned, queue, service);
        int pages = starts.size() - 1;
        if (pages == 1 && terminalWidth >= CHECKOUT_QUEUE_TABLE_WIDTH) {
            return confirmCheckoutBatch(planned.size());
        }
        int page = 0;
        while (true) {
            showTransactionNavigation(page, pages, "F", new String[] {"[Y] Approve All", "[N] Cancel"});
            String choice = readText(prompt());
            if (choice.equalsIgnoreCase("Y") || choice.equalsIgnoreCase("YES")) {
                return true;
            }
            if (choice.equalsIgnoreCase("N") || choice.equalsIgnoreCase("NO")) {
                return false;
            }
            if (choice.equalsIgnoreCase("F") && page < pages - 1) {
                page++;
            } else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            } else {
                clearResultRetry("Use Y or N, or the displayed page keys.");
                continue;
            }
            showCheckoutBatchPage(planned, queue, service, page, starts);
        }
    }

    // Display the ready batch preview without approving it.
    void showCheckoutBatch(ArrayList<RentalRequest> planned, CheckoutQueue queue, RentalManager service) {
        ArrayList<Integer> starts = batchPages(planned, queue, service);
        if (starts.size() > 2 || terminalWidth < CHECKOUT_QUEUE_TABLE_WIDTH) {
            showCheckoutBatchPage(planned, queue, service, 0, starts);
            return;
        }
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
        checkoutRow("Skipped: " + (queue.getRequests().size() - planned.size()) + " waiting (see Queue)");
        endCheckoutCard('=');
    }

    // Display the first page of the checkout session receipt.
    void showCheckoutReceipt(Queue2Play.CheckoutSession session, CheckoutQueue queue) {
        showCheckoutReceiptPage(session, queue, 0, receiptPages(session, queue));
    }

    // Reset checkout-only layout and page settings.
    void endCheckoutDisplay() {
        checkoutDisplay = false;
        checkoutPage = 0;
        checkoutPages = 1;
    }

    // Display customer cancellation and navigation choices.
    void showQueueCancellationOptions() {
        printLine(border(currentWidth, '-'));
        showFeatureOptions(new String[] {"[C] Cancel a Request"});
        showNavigationOptions(currentWidth, new String[] {"[B] Dashboard"});
        inputBar("ENTER CHOICE", currentWidth);
    }

    // Display the free membership, discount and queue-priority description.
    void showVipBenefits() {
        printCenteredDetails(new String[] {
            "Price           : Free lifetime membership",
            "Rental Discount : 10%",
            "Queue Priority  : VIP first with a regular turn after 3 VIPs",
            "Membership      : " + membershipColor(true)
        });
        showFeatureOptions(new String[] {"[1] Confirm Upgrade"});
        showNavigationOptions(new String[] {"[C] Cancel Transaction"});
    }

    // Display the next request's customer, game and rental cost.
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

    // Display due-date-ordered return choices with search and page controls.
    void showReturnSelectionList(ArrayList<Rental> rentals, int start, int end, int page, int pages, String keyword) {
        printHeader("ADMIN > RETURNS", "EARLIEST DUE DATE FIRST", RETURN_TABLE_WIDTH);
        String search = "None";
        if (!keyword.isEmpty()) {
            search = keyword;
        }
        showListStatus("Search: " + search + " · Page: " + (page + 1) + "/" + pages,
            "Showing " + (rentals.isEmpty() ? 0 : start + 1) + "-" + end + " of " + rentals.size(),
            RETURN_TABLE_WIDTH);
        if (rentals.isEmpty()) {
            showEmptyState("No matching active rentals.");
        } else {
            showReturnTableHeader(rentals.size(), start, end);
            for (int i = start; i < end; i++) {
                showReturnRow(i - start + 1, rentals.get(i));
            }
            printLine(tableLine(RETURN_TABLE_WIDTH));
        }
        showNavigationOptions(new String[] {"[S] Search"});
        if (!keyword.isEmpty()) {
            showNavigationOptions(new String[] {"[X] Clear Search"});
        }
        showPagination(page, pages);
        showNavigationOptions(new String[] {"[B] Dashboard"});
        inputBar("SELECT RETURNS", RETURN_TABLE_WIDTH,
                "Current page; full quantities.\nExample: 1,3,5");
    }

    // Display the input field for finding an active rental.
    void showReturnSearchPrompt() {
        showNavigationOptions(new String[] {"[B] Return List", "[M] Dashboard"});
        inputBar("SEARCH RETURNS", RETURN_TABLE_WIDTH,
                "Customer, game ID, or title; blank shows all.");
    }

    // Display the first confirmation page for selected returns.
    void showSelectedReturnSummary(ArrayList<Rental> selected, LocalDateTime returnedAt, RentalManager service) {
        showReturnConfirmationPage(selected, returnedAt, service, 0, returnConfirmationPages(selected, returnedAt, service));
    }

    // Build selected-return rows with due dates and calculated late fees.
    private ArrayList<String[]> returnConfirmationRows(ArrayList<Rental> selected, LocalDateTime returnedAt, RentalManager service) {
        ArrayList<String[]> rows = new ArrayList<>();
        for (Rental rental : selected) {
            int daysLate = RentalManager.daysOverdue(rental.dueDate, returnedAt);
            rows.add(new String[] {rentalLabel(rental.rentalNumber), rental.client.username, rental.game.title,
                String.valueOf(rental.quantity), rental.dueDate.format(DateTimeFormatter.ofPattern("MMM dd yyyy HH:mm")),
                String.valueOf(daysLate), money(service.calculateLateFee(rental, daysLate))});
        }
        return rows;
    }

    // Calculate counts and late fees for the full return selection.
    private String[] returnConfirmationSummary(ArrayList<Rental> selected, LocalDateTime returnedAt, RentalManager service) {
        double total = 0;
        for (Rental rental : selected) {
            int daysLate = RentalManager.daysOverdue(rental.dueDate, returnedAt);
            total += service.calculateLateFee(rental, daysLate);
        }
        return new String[] {"Selected rentals: " + selected.size() + " · Total late fees: " + money(total)};
    }

    // Measure return-confirmation rows before building pages.
    ArrayList<Integer> returnConfirmationPages(ArrayList<Rental> selected, LocalDateTime returnedAt, RentalManager service) {
        return transactionPages(returnConfirmationRows(selected, returnedAt, service),
            new String[] {"RENTAL #", "CUSTOMER", "GAME", "QTY", "DUE DATE", "LATE", "LATE FEE"},
            new int[] {8, 12, 24, 3, 17, 4, 9}, RETURN_TABLE_WIDTH,
            returnConfirmationSummary(selected, returnedAt, service));
    }

    // Display one confirmation page for the selected return batch.
    void showReturnConfirmationPage(ArrayList<Rental> selected, LocalDateTime returnedAt, RentalManager service,
            int page, ArrayList<Integer> starts) {
        showTransactionPage("CONFIRM SELECTED RETURNS", returnConfirmationRows(selected, returnedAt, service),
            new String[] {"RENTAL #", "CUSTOMER", "GAME", "QTY", "DUE DATE", "LATE", "LATE FEE"},
            "%-8s | %-12s | %-24s | %3s | %-17s | %4s | %9s", new int[] {8, 12, 24, 3, 17, 4, 9},
            RETURN_TABLE_WIDTH, returnConfirmationSummary(selected, returnedAt, service), page, starts);
    }

    // Page the preview and read Yes/No for the entire selection.
    boolean confirmSelectedReturns(ArrayList<Rental> selected, LocalDateTime returnedAt, RentalManager service) {
        showSelectedReturnSummary(selected, returnedAt, service);
        ArrayList<Integer> starts = returnConfirmationPages(selected, returnedAt, service);
        int pages = starts.size() - 1;
        int page = 0;
        while (true) {
            showTransactionNavigation(page, pages, "F", new String[] {"[Y] Confirm Returns", "[N] Back to List"});
            String answer = readText(prompt(RETURN_TABLE_WIDTH)).trim();
            if (answer.equalsIgnoreCase("Y") || answer.equalsIgnoreCase("YES")) {
                return true;
            }
            if (answer.equalsIgnoreCase("N") || answer.equalsIgnoreCase("NO")) {
                return false;
            }
            if (answer.equalsIgnoreCase("F") && page < pages - 1) {
                page++;
            } else if (answer.equalsIgnoreCase("P") && page > 0) {
                page--;
            } else {
                clearResultRetry("Use Y to confirm or N to return to the list.");
                continue;
            }
            showReturnConfirmationPage(selected, returnedAt, service, page, starts);
        }
    }

    // Display the first page of saved return results.
    void showSelectedReturnResults(ArrayList<Rental> selected) {
        showReturnResultPage(selected, 0, returnResultPages(selected));
    }

    // Build result rows with recorded late fees and restored stock.
    private ArrayList<String[]> returnResultRows(ArrayList<Rental> selected) {
        ArrayList<String[]> rows = new ArrayList<>();
        for (Rental rental : selected) {
            rows.add(new String[] {rentalLabel(rental.rentalNumber), rental.client.username, rental.game.title,
                String.valueOf(rental.quantity), String.valueOf(rental.daysLate), money(rental.lateFee),
                String.valueOf(rental.game.availableCopies)});
        }
        return rows;
    }

    // Calculate result totals for all processed returns.
    private String[] returnResultSummary(ArrayList<Rental> selected) {
        double total = 0;
        for (Rental rental : selected) {
            total += rental.lateFee;
        }
        return new String[] {"Saved " + selected.size() + " returns · Late fees " + money(total)};
    }

    // Measure processed-return rows before building result pages.
    ArrayList<Integer> returnResultPages(ArrayList<Rental> selected) {
        return transactionPages(returnResultRows(selected),
            new String[] {"RENTAL #", "CUSTOMER", "GAME", "QTY", "LATE", "LATE FEE", "STOCK"},
            new int[] {8, 14, 30, 3, 4, 9, 9}, RETURN_TABLE_WIDTH, returnResultSummary(selected));
    }

    // Display one page of processed returns without processing them again.
    void showReturnResultPage(ArrayList<Rental> selected, int page, ArrayList<Integer> starts) {
        showTransactionPage("RETURNS PROCESSED", returnResultRows(selected),
            new String[] {"RENTAL #", "CUSTOMER", "GAME", "QTY", "LATE", "LATE FEE", "STOCK"},
            "%-8s | %-14s | %-30s | %3s | %4s | %9s | %9s", new int[] {8, 14, 30, 3, 4, 9, 9},
            RETURN_TABLE_WIDTH, returnResultSummary(selected), page, starts);
    }

    // Keep return results visible while paging or choosing a destination.
    boolean waitForReturnResultNavigation(ArrayList<Rental> selected, String previousDestination) {
        ArrayList<Integer> starts = returnResultPages(selected);
        int pages = starts.size() - 1;
        if (pages == 1) {
            return waitForResultNavigation(previousDestination, "Dashboard");
        }
        int page = 0;
        String[] destinations = {"[M] Dashboard"};
        if (!previousDestination.isEmpty()) {
            destinations = new String[] {"[B] " + previousDestination, "[M] Dashboard"};
        }
        while (true) {
            showTransactionNavigation(page, pages, "N", destinations);
            String choice = readText(prompt());
            if (choice.equalsIgnoreCase("M")) {
                return false;
            }
            if (!previousDestination.isEmpty() && choice.equalsIgnoreCase("B")) {
                return true;
            }
            if (choice.equalsIgnoreCase("N") && page < pages - 1) {
                page++;
            } else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            } else {
                clearResultRetry("Use the displayed navigation keys.");
                continue;
            }
            showReturnResultPage(selected, page, starts);
        }
    }

    // Display the return selection's numbered column headings.
    void showReturnTableHeader(int total, int start, int end) {
        printLine(tableLine(TerminalUI.RETURN_TABLE_WIDTH));
        printRecord("%-4s | %-8s | %-14s | %-30s | %3s | %-21s", new String[] {"NO.", "RENTAL #", "CUSTOMER", "GAME TITLE", "QTY", "DUE DATE"}, new int[] {4, 8, 14, 30, 3, 21});
        printLine(tableLine(TerminalUI.RETURN_TABLE_WIDTH));
    }

    // Display one active rental with its current-page selection number.
    void showReturnRow(int shown, Rental rental) {
        printRecord("%-4s | %-8s | %-14s | %-30s | %3s | %-21s", new String[] {String.valueOf(shown), rentalLabel(rental.rentalNumber), rental.client.username, rental.game.title,
                String.valueOf(rental.quantity), rental.dueDate.format(Queue2Play.TABLE_DATE_FORMAT)}, new int[] {4, 8, 14, 30, 3, 21});
    }

    // Display the active-rental column headings.
    void showActiveRentalHeader(int total, int start, int end) {
        printLine(tableLine(TerminalUI.ACTIVE_TABLE_WIDTH));
        printRecord("%-8s | %-14s | %-28s | %-10s | %3s | %-21s | %-10s", new String[] {"RENTAL #", "CUSTOMER", "GAME TITLE", "PLATFORM", "QTY", "DUE DATE", "STATUS"}, new int[] {8, 14, 28, 10, 3, 21, 10});
        printLine(tableLine(TerminalUI.ACTIVE_TABLE_WIDTH));
    }

    // Display one active rental without changing its heap entry.
    void showActiveRentalRow(Rental rental) {
        printRecord("%-8s | %-14s | %-28s | %-10s | %3s | %-21s | %-10s", new String[] {rentalLabel(rental.rentalNumber), rental.client.username, rental.game.title, rental.game.platform,
                String.valueOf(rental.quantity), rental.dueDate.format(Queue2Play.TABLE_DATE_FORMAT), rental.status}, new int[] {8, 14, 28, 10, 3, 21, 10});
    }

    // Display the audit log's date, time and action headings.
    void showAuditHeader(int total, int start, int end) {
        printLine(tableLine(AUDIT_TABLE_WIDTH));
        printRecord("%-13s | %-10s | %-58s", new String[] {"DATE", "TIME", "ACTION"}, new int[] {13, 10, 58});
        printLine(tableLine(TerminalUI.AUDIT_TABLE_WIDTH));
    }

    // Display the parsed date, time and action of an audit record.
    void showAuditRow(LocalDateTime stamp, String[] parts) {
        printRecord("%-13s | %-10s | %-58s",
            new String[] {stamp.format(Queue2Play.LOG_DISPLAY_DATE),
                stamp.format(Queue2Play.LOG_DISPLAY_TIME), parts[1]}, new int[] {13, 10, 58});
        printLine();
    }

    // Display an older audit record without losing its text.
    void showLegacyAuditRow(String entry) {
        printRecord("%-13s | %-10s | %-58s", new String[] {"", "", entry}, new int[] {13, 10, 58});
        printLine();
    }

    // Display useful guidance for entering a game title.
    void showAddGameInstructions() {
        showNavigationOptions(new String[] {"[B] Inventory", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    // Display supported platforms for adding or editing a game.
    void showPlatformOptions() {
        showFeatureOptions(new String[] {
            "[1] " + platformColor("PS5"),
            "[2] " + platformColor("Xbox"),
            "[3] " + platformColor("Nintendo")
        });
        showNavigationOptions(new String[] {"[B] Game Title", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    // Display the allowed rental price range.
    void showPriceInstructions() {
        showNavigationOptions(new String[] {"[B] Select Platform", "[C] Cancel Transaction", "[M] Dashboard"});
        inputBar("RENTAL PRICE");
    }

    // Display the allowed starting-stock range.
    void showStockInstructions() {
        showNavigationOptions(new String[] {"[B] Rental Price", "[C] Cancel Transaction", "[M] Dashboard"});
        inputBar("AVAILABLE COPIES");
    }

    // Display draft game details and confirmation choices.
    void showNewGameConfirmation(String title, String platform, double price, int copies) {
        showNewGameDetails(title, platform, price, copies);
        showFeatureOptions(new String[] {"[1] Confirm Add", "[E] Edit Information"});
        showNavigationOptions(new String[] {"[B] Available Copies", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    // Display the current draft's title, platform, price and stock.
    private void showNewGameDetails(String title, String platform, double price, int copies) {
        printCenteredDetails(new String[] {
            "Title    : " + title,
            "Platform : " + platformColor(platform),
            "Price    : " + money(price),
            "Stock    : " + copies
        });
    }

    // Display independent draft fields that can be edited.
    void showGameEditMenu(String title, String platform, double price, int copies) {
        printHeader("ADMIN > ADD GAME", "EDIT INFORMATION");
        showNewGameDetails(title, platform, price, copies);
        showMenuOptions(new String[] {"[1] Game Title", "[2] Platform", "[3] Rental Price", "[4] Available Copies"});
        showNavigationOptions(new String[] {"[B] Game Summary"});
        inputBar("ENTER CHOICE");
    }

    // Display one editable game field while preserving other details.
    void showGameEditField(int step, String title, String platform, double price, int copies) {
        String[] labels = {"GAME TITLE", "PLATFORM", "RENTAL PRICE", "AVAILABLE COPIES"};
        printHeader("ADMIN > ADD GAME", "EDIT " + labels[step - 1]);
        showNewGameDetails(title, platform, price, copies);
        if (step == 2) {
            showFeatureOptions(new String[] {"[1] PS5", "[2] Xbox", "[3] Nintendo"});
        }
        showNavigationOptions(new String[] {"[C] Cancel Transaction", "[B] Game Summary", "[M] Dashboard"});
        inputBar(labels[step - 1]);
    }

    // Display the saved game's identifier and details.
    void showGameAdded(Game game) {
        printCenteredDetails(new String[] {
            "Game ID  : " + game.gameId,
            "Title    : " + game.title,
            "Platform : " + platformColor(game.platform),
            "Stock    : " + game.availableCopies,
            "Price    : " + money(game.price)
        });
    }

    // Display the game and affected request count before deletion.
    void showDeleteGameDetails(Game game, int pending) {
        printCenteredDetails(new String[] {
            "Game ID          : " + game.gameId,
            "Title            : " + game.title,
            "Platform         : " + platformColor(game.platform),
            "Current Stock    : " + game.availableCopies,
            "Pending Requests : " + pending
        });
    }

    // Display stock details and increase or decrease choices.
    void showStockUpdateOptions(Game game) {
        printCenteredDetails(new String[] {
            "Game          : " + game.title,
            "Platform      : " + platformColor(game.platform),
            "Current Stock : " + game.availableCopies,
            "Stock Limit   : 0-" + Queue2Play.MAX_GAME_STOCK
        });
        showFeatureOptions(new String[] {"[1] Add Stock", "[2] Decrease Stock"});
        showNavigationOptions(new String[] {"[B] Select Game", "[C] Cancel Transaction", "[M] Dashboard"});
    }

    // Display quantity input for the chosen stock adjustment.
    void showStockQuantityPrompt(boolean adding) {
        showNavigationOptions(new String[] {"[B] Update Stock", "[C] Cancel Transaction", "[M] Dashboard"});
        if (adding) {
            inputBar("QUANTITY TO ADD");
        } else {
            inputBar("QUANTITY TO DECREASE");
        }
    }

    // Display old and proposed stock before confirmation.
    void showStockConfirmation(Game game, int previous, int newStock) {
        printCenteredDetails(new String[] {
            "Game           : " + game.title,
            "Previous Stock : " + previous,
            "New Stock      : " + newStock
        });
    }

    // Display the saved stock adjustment result.
    void showStockUpdated(Game game, int previous) {
        printCenteredDetails(new String[] {
            "Game           : " + game.title,
            "Previous Stock : " + previous,
            "Current Stock  : " + game.availableCopies
        });
    }

    // Display the allowed full-name length and characters.
    void showFullNameInstructions() {
        error("Use 5-60 characters with at least two letters. Only letters, spaces and dots are allowed.");
    }

    // Display the account draft before creating it.
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
        showFeatureOptions(new String[] {"[1] Create Account", "[E] Edit Information"});
        showNavigationOptions(new String[] {"[C] Cancel Registration"});
    }

    // Display the successful registration result and next destination.
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
