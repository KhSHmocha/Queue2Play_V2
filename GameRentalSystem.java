import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.PriorityQueue;
import java.util.Stack;

public class GameRentalSystem {

    // ---------- Settings ----------
    static final int RENTAL_DAYS = 3;
    static final int MAX_RENTAL_DAYS = 30;
    static final int MAX_COPIES_PER_GAME = 3;
    // Existing requests and rental history may contain quantities from the old limit.
    static final int MAX_SAVED_COPIES_PER_GAME = 5;
    static final int MAX_GAME_STOCK = 20;
    static final double MIN_RENTAL_PRICE = 1.00;
    static final double MAX_RENTAL_PRICE = 10.00;
    static final int NAV_CANCEL = -100;
    static final int NAV_MAIN_MENU = -101;
    static final int NAV_FINISH_SELECTION = -102;
    static final double LATE_FEE_PER_DAY = 1.00;
    static final double VIP_DISCOUNT = 0.10;
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy | hh:mm a");
    static final DateTimeFormatter TABLE_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a");
    static final DateTimeFormatter LOG_STORAGE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final DateTimeFormatter LOG_DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM dd, yyyy");
    static final DateTimeFormatter LOG_DISPLAY_TIME = DateTimeFormatter.ofPattern("hh:mm a");
    static final int GAME_PAGE_SIZE = 9;
    static final int DETAIL_PAGE_SIZE = 9;
    static final int LOG_PAGE_SIZE = 9;

    // ---------- Data structures ----------
    ConsoleUI ui = new ConsoleUI();
    final AccountService accountService;
    final RentalDataStore dataStore;
    final GameCatalogService gameCatalog;
    final RentalService rentalService;

    // Hash table maps game IDs to games; LinkedHashMap keeps inventory display order.
    LinkedHashMap<String, Game> games = new LinkedHashMap<>();

    // ArrayLists store accounts for lookup and all rentals for file saving.
    ArrayList<User> users = new ArrayList<>();

    // Doubly Linked List of pending checkout requests
    CheckoutQueue checkoutQueue = new CheckoutQueue();

    // Min-heap: peek gives the active rental with the earliest due date.
    PriorityQueue<Rental> activeRentals = new PriorityQueue<>();

    // Every rental ever made (active and completed), in rental-number order.
    ArrayList<Rental> allRentals = new ArrayList<>();

    // Stack: admin actions are displayed newest first.
    Stack<String> auditLog = new Stack<>();
    ArrayList<String> logRecords = new ArrayList<>();

    // ---------- Counters ----------
    int nextQueueNumber = 1;
    int nextRentalNumber = 1;
    boolean dashboardReturnRequested = false;
    private String selectedRentalPlatform = "";
    private String rentalSelectionDestination = "Dashboard";

    private boolean confirmRentalDashboard(LinkedHashMap<Game, Integer> cart) {
        if (cart != null && !cart.isEmpty() && !ui.confirmDiscardCart()) {
            return false;
        }
        requestDashboardReturn();
        return true;
    }

    private int parseMenuChoice(String text) {
        if (text.equalsIgnoreCase("B")) {
            return 0;
        }
        return Integer.parseInt(text);
    }

    private int readMenuChoice(String exitKey) {
        String text = ui.readText(ui.prompt());
        if (text.equalsIgnoreCase(exitKey)) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // Empty screens wait for a named destination instead of disappearing.
    private void waitForScreenNavigation(String destination, boolean allowDashboard) {
        while (true) {
            if (allowDashboard) {
                ui.showNavigationOptions(new String[] {"[B] " + destination, "[M] Dashboard"});
            } else {
                ui.showNavigationOptions(new String[] {"[B] " + destination});
            }
            ui.inputBar("ENTER CHOICE");
            String choice = ui.readText(ui.prompt());
            if (choice.equalsIgnoreCase("B") || choice.equals("0")) {
                return;
            }
            if (allowDashboard && choice.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return;
            }
            ui.error("Use the displayed navigation key.");
        }
    }

    GameRentalSystem() {
        dataStore = new RentalDataStore(this);
        accountService = new AccountService(users, ui, dataStore);
        gameCatalog = new GameCatalogService(games);
        rentalService = new RentalService(checkoutQueue, activeRentals, allRentals, games);
    }

    void requestDashboardReturn() {
        dashboardReturnRequested = true;
    }

    boolean isDashboardReturnRequested() {
        return dashboardReturnRequested;
    }

    void clearDashboardReturnRequest() {
        dashboardReturnRequested = false;
    }

    // Start, main menu

    // Load saved data once, then keep showing the main menu until exit is chosen.
    public void start() {
        accountService.loadAccounts();
        dataStore.loadGames();
        dataStore.loadRentals();
        dataStore.loadQueue();
        dataStore.loadLogs();
        dataStore.checkpoint();
        if (accountService.embeddedAccountsChanged() || !java.nio.file.Files.exists(dataStore.file("accounts.txt"))
                || !java.nio.file.Files.exists(dataStore.file("games.txt"))
                || !java.nio.file.Files.exists(dataStore.file("rentals.txt"))
                || !java.nio.file.Files.exists(dataStore.file("queue.txt"))) {
            dataStore.saveData();
        }

        boolean running = true;
        while (running) {
            ui.showMainMenu();
            int choice = readMenuChoice("Q");

            try {
                if (choice == 1) {
                    User user = accountService.login();
                    if (user != null) {
                        if (user.isAdmin()) {
                            adminMenu(user);
                        } else {
                            clientMenu(user);
                        }
                    }
                } else if (choice == 2) {
                    accountService.register();
                } else if (choice == 0) {
                    if (ui.askYesNo("Exit QUEUE2PLAY?")) {
                        ui.showGoodbye();
                        running = false;
                    }
                } else {
                    ui.error("Invalid choice. Use 1 to log in, 2 to register, or Q to exit.");
                }
            } catch (StorageException e) {
                ui.error(e.getMessage());
            }
        }
    }

    void writeLog(String type, String message) {
        dataStore.writeLog(type, message);
    }

    // Admin action: goes on the audit log Stack AND into logs.txt
    void logAdmin(String message) {
        String timestamp = LocalDateTime.now().format(LOG_STORAGE_FORMAT);
        auditLog.push(timestamp + "|" + message);
        dataStore.writeLogRecord(timestamp, "ADMIN", message);
    }

    // Any other transaction (register, login, queue request, VIP upgrade...)
    void logEvent(String message) {
        writeLog("EVENT", message);
    }

    // Game inventory (hashmap)

    // Paginate game lists without changing their search or sort order.
    // When selectable is true, the method returns the selected Game.
    Game paginateGames(ArrayList<Game> list, String title, boolean selectable) {
        return paginateGames(list, title, selectable, null, null, null);
    }

    // Browse and rental selection share one search, sort, and pagination loop.
    private Game paginateGames(ArrayList<Game> platformGames, String title, boolean selectable,
            User client, LinkedHashMap<Game, Integer> cart, int[] selectedQuantity) {
        int page = 0;
        int sortOption = 0;
        String keyword = "";

        while (true) {
            ArrayList<Game> list = gameCatalog.getDisplayList(platformGames, keyword, sortOption);
            ArrayList<Integer> starts = ui.gamePages(list);
            int totalPages = starts.size() - 1;
            page = Math.min(page, totalPages - 1);
            int start = starts.get(page);
            int end = starts.get(page + 1);

            String heading = title;
            if (client != null) {
                heading = "CLIENT > RENT GAMES > " + selectedRentalPlatform;
            }
            ui.printHeader(heading, ConsoleUI.GAME_TABLE_WIDTH);
            if (client != null) {
                ui.showCatalogState(keyword, sortOption, page + 1, totalPages, cart);
            } else {
                ui.showCatalogState(keyword, sortOption, page + 1, totalPages);
            }
            if (list.isEmpty()) {
                ui.showEmptyState("No games found. Use S to search again or X to clear search.");
            } else {
                ui.showGameTableHeader(list.size(), start, end);
                for (int i = start; i < end; i++) {
                    ui.showGameRow(i - start + 1, list.get(i));
                }
                ui.info(ui.tableLine(ConsoleUI.GAME_TABLE_WIDTH));
            }
            ui.showGameNavigation(page, totalPages, keyword, selectable, cart != null && !cart.isEmpty(), end - start);
            ui.inputBar("ENTER CHOICE", ConsoleUI.GAME_TABLE_WIDTH);
            String choice = ui.readText(ui.prompt(ConsoleUI.GAME_TABLE_WIDTH));

            if (choice.equalsIgnoreCase("M")) {
                if (client != null && !confirmRentalDashboard(cart)) {
                    continue;
                }
                requestDashboardReturn();
                return null;
            } else if (choice.equalsIgnoreCase("F") && client != null && !cart.isEmpty()) {
                selectedQuantity[0] = NAV_FINISH_SELECTION;
                return null;
            } else if ((choice.equalsIgnoreCase("B") || choice.equals("0"))) {
                return null;
            } else if (choice.equalsIgnoreCase("N")) {
                if (page < totalPages - 1) {
                    page++;
                } else {
                    ui.warning("You are on the last page.");
                }
            } else if (choice.equalsIgnoreCase("P")) {
                if (page > 0) {
                    page--;
                } else {
                    ui.warning("You are on the first page.");
                }
            } else if (choice.equalsIgnoreCase("X")) {
                keyword = "";
                page = 0;
            } else if (choice.equalsIgnoreCase("T")) {
                sortOption = (sortOption + 1) % 5;
                page = 0;
            } else if (choice.equalsIgnoreCase("S")) {
                String dashboard = "[M] Dashboard";
                if (client != null && !cart.isEmpty()) {
                    dashboard = "[M] Discard Cart / Dashboard";
                }
                while (true) {
                    ui.showNavigationOptions(ConsoleUI.GAME_TABLE_WIDTH, new String[] {"[B] Game List", dashboard});
                    ui.inputBar("SEARCH GAME", ConsoleUI.GAME_TABLE_WIDTH);
                    String searchText = ui.readText(ui.prompt(ConsoleUI.GAME_TABLE_WIDTH));
                    if (searchText.equalsIgnoreCase("M")) {
                        if (client != null && !confirmRentalDashboard(cart)) {
                            continue;
                        }
                        requestDashboardReturn();
                        return null;
                    }
                    if (searchText.equalsIgnoreCase("B") || searchText.equals("0")) {
                        break;
                    }
                    if (searchText.isEmpty()) {
                        ui.error("Search text cannot be empty.");
                        continue;
                    }
                    keyword = searchText;
                    page = 0;
                    break;
                }
            } else if (selectable) {
                try {
                    int number = Integer.parseInt(choice);
                    if (number >= 1 && number <= end - start) {
                        Game game = list.get(start + number - 1);
                        if (cart == null) {
                            return game;
                        }
                        String error = validateRentalSelection(client, cart, game);
                        if (error != null) {
                            ui.error(error);
                            continue;
                        }
                        selectedQuantity[0] = askQuantity(game, cart.getOrDefault(game, 0), checkoutQueue.queuedCopies(client, game));
                        if (selectedQuantity[0] == 0) {
                            continue;
                        }
                        return game;
                    }
                    ui.error("Invalid game selection.");
                } catch (NumberFormatException e) {
                    ui.error("Invalid choice.");
                }
            } else {
                ui.error("Use S, X, T, N, P, B, or M.");
            }
        }
    }
    // Shows the platform menu. Returns "ALL", "PS5", "Xbox", "Nintendo" or "BACK"
    String choosePlatform(String heading) {
        return choosePlatform(heading, null);
    }

    private String choosePlatform(String heading, LinkedHashMap<Game, Integer> cart) {
        String destination = "Dashboard";
        if (heading.startsWith("ADMIN")) {
            destination = "Inventory";
        } else if (heading.contains("RENT GAMES")) {
            destination = rentalSelectionDestination;
        }
        while (true) {
            if (cart == null) {
                ui.showPlatformMenu(heading, destination);
            } else {
                ui.showPlatformMenu(heading, destination, !cart.isEmpty());
            }
            String input = ui.readText(ui.prompt());

            if (input.equalsIgnoreCase("M")) {
                if (cart != null && !confirmRentalDashboard(cart)) {
                    continue;
                }
                requestDashboardReturn();
                return "BACK";
            }
            if (input.equalsIgnoreCase("F") && cart != null && !cart.isEmpty()) {
                return "FINISH";
            }

            try {
                int choice = parseMenuChoice(input);
                if (choice == 1) {
                    return "ALL";
                }
                if (choice == 2) {
                    return "PS5";
                }
                if (choice == 3) {
                    return "Xbox";
                }
                if (choice == 4) {
                    return "Nintendo";
                }
                if (choice == 0) {
                    if (cart != null && !cart.isEmpty() && destination.equals("Dashboard") && !confirmRentalDashboard(cart)) {
                        continue;
                    }
                    return "BACK";
                }
            } catch (NumberFormatException e) {
                // handled below
            }
            ui.error("Invalid choice. Please enter 1, 2, 3, 4, B, or M.");
        }
    }

    // View one platform (or all games) with pagination and search.
    void viewGames(String platform) {
        ArrayList<Game> list = gameCatalog.getGameList(platform);
        String title = ui.platformColor(platform) + " GAMES";
        if (platform.equals("ALL")) {
            title = "ALL GAMES";
        }
        paginateGames(list, title, false);
    }

    // Client menu

    void clientMenu(User client) {
        boolean loggedIn = true;

        while (loggedIn) {
            clearDashboardReturnRequest();
            ui.showClientMenu(client);
            int choice = readMenuChoice("L");
            try {
                if (choice == 1) {
                    browseGames();
                } else if (choice == 2) {
                    placeInQueue(client);
                } else if (choice == 3) {
                    cancelMyRequest(client);
                } else if (choice == 4) {
                    viewHistory(client);
                } else if (choice == 5) {
                    upgradeToVip(client);
                } else if (choice == 0) {
                    if (ui.askYesNo("Log out of your account?")) {
                        ui.loading("Logging out");
                        ui.success("You have been logged out.");
                        ui.pause(500);
                        loggedIn = false;
                    }
                } else {
                    ui.error("Invalid choice. Choose a displayed option.");
                }
            } catch (StorageException e) {
                ui.error(e.getMessage());
            }
        }
    }

    void browseGames() {
        browseGames(false);
    }

    void browseGames(boolean fromInventory) {
        while (true) {
            ui.showBrowseMenu(fromInventory);
            String input = ui.readText(ui.prompt());

            if (input.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return;
            }

            int choice;
            try {
                choice = parseMenuChoice(input);
            } catch (NumberFormatException e) {
                ui.error("Invalid choice.");
                continue;
            }

            if (choice == 1) {
                viewGames("ALL");
            } else if (choice == 2) {
                viewGames("PS5");
            } else if (choice == 3) {
                viewGames("Xbox");
            } else if (choice == 4) {
                viewGames("Nintendo");
            } else if (choice == 0) {
                return;
            } else {
                ui.error("Invalid choice. Choose a displayed option.");
            }

            if (isDashboardReturnRequested()) {
                return;
            }
        }
    }

    // Check existing rentals, requests, and the per-game quantity limit.
    Game chooseOneGame(User client, LinkedHashMap<Game, Integer> cart, int[] selectedQuantity) {
        while (true) {
            if (selectedRentalPlatform.isEmpty()) {
                selectedRentalPlatform = choosePlatform("CLIENT > RENT GAMES > SELECT PLATFORM", cart);
            }
            String platform = selectedRentalPlatform;
            if (isDashboardReturnRequested()) {
                return null;
            }
            if (platform.equals("BACK")) {
                selectedRentalPlatform = "";
                return null;
            }

            if (platform.equals("FINISH")) {
                selectedRentalPlatform = "";
                selectedQuantity[0] = NAV_FINISH_SELECTION;
                return null;
            }

            ArrayList<Game> list = gameCatalog.getGameList(platform);
            Game game = paginateGames(list, "SELECT GAME", true, client, cart, selectedQuantity);
            if (isDashboardReturnRequested()) {
                return null;
            }
            if (game != null) {
                return game;
            }
            if (selectedQuantity[0] == NAV_FINISH_SELECTION) {
                return null;
            }
            selectedRentalPlatform = "";
        }
    }

    private String validateRentalSelection(User client, LinkedHashMap<Game, Integer> cart, Game game) {
        int inCart = cart.getOrDefault(game, 0);
        int queued = checkoutQueue.queuedCopies(client, game);
        if (rentalService.hasActiveRental(client, game)) {
            return "You are already renting this game.";
        }
        if (queued + inCart >= MAX_COPIES_PER_GAME) {
            return "You already have the maximum of "
            + MAX_COPIES_PER_GAME + " copies of this game queued or in your cart.";
        }
        if (game.availableCopies <= 0) {
            return "This game is out of stock.";
        }
        if (inCart >= game.availableCopies) {
            return "Your cart already contains all available copies of this game.";
        }
        return null;
    }

    // The combined cart quantity must fit both stock and the per-game limit.
    // Returns 0 if the client decides not to add this game after all.
    int askQuantity(Game game, int inCart) {
        return askQuantity(game, inCart, 0);
    }

    int askQuantity(Game game, int inCart, int queued) {
        int maximumToAdd = MAX_COPIES_PER_GAME - queued - inCart;
        if (game.availableCopies - inCart < maximumToAdd) {
            maximumToAdd = game.availableCopies - inCart;
        }
        if (maximumToAdd <= 0) {
            ui.error("No more copies of this game are available to add.");
            return 0;
        }

        while (true) {
            ui.showSelectQuantityScreen(game, inCart, queued, MAX_COPIES_PER_GAME, maximumToAdd);
            String input = ui.readText(ui.prompt());

            if (input.equalsIgnoreCase("C")) {
                if (ui.askYesNo("Cancel this rental transaction?")) {
                    return NAV_CANCEL;
                }
                continue;
            }
            if (input.equalsIgnoreCase("M")) {
                if (ui.confirmDiscardCart()) {
                    requestDashboardReturn();
                    return NAV_MAIN_MENU;
                }
                continue;
            }
            if ((input.equalsIgnoreCase("B") || input.equals("0"))) {
                return 0;
            }

            try {
                int quantity = Integer.parseInt(input);
                if (quantity < 1) {
                    ui.error("Quantity must be at least 1.");
                } else if (quantity > maximumToAdd) {
                    if (quantity > MAX_COPIES_PER_GAME - queued - inCart) {
                        ui.error("Maximum rental quantity is " + MAX_COPIES_PER_GAME
                            + " copies per game. You have " + queued + " queued and " + inCart + " in your cart.");
                    } else {
                        ui.error("Not enough stock. You may add up to " + maximumToAdd + " more copies.");
                    }
                } else {
                    return quantity;
                }
            } catch (NumberFormatException e) {
                ui.error("Enter a whole number, B, or M.");
            }
        }
    }

    // Game selection + quantity, repeated while the client wants to add more games.
    // The same game can be added again; its quantity is added to the cart line.
    // Returns 1 after completing selection, 0 for Back, or a navigation code.
    int addGamesToCart(User client, LinkedHashMap<Game, Integer> cart) {
        boolean adding = true;
        while (adding) {
            int[] selectedQuantity = new int[1];
            Game game = chooseOneGame(client, cart, selectedQuantity);
            if (isDashboardReturnRequested()) {
                return NAV_MAIN_MENU;
            }
            if (game == null) {
                if (selectedQuantity[0] == NAV_FINISH_SELECTION) {
                    return 1;
                }
                return 0;
            }

            int inCart = cart.getOrDefault(game, 0);
            int quantity = selectedQuantity[0];
            if (quantity == NAV_CANCEL) {
                return NAV_CANCEL;
            }
            if (quantity == NAV_MAIN_MENU) {
                return NAV_MAIN_MENU;
            }
            if (quantity == 0) {
                continue;
            }

            cart.put(game, inCart + quantity);
            ui.success(quantity + " x " + game.title + " added to your cart. Total: "
                + cart.get(game) + " copy/copies.");
            adding = ui.askYesNo("Do you want to add another game?");
        }
        if (cart.isEmpty()) {
            return 0;
        }
        return 1;
    }

    // Returns the number of rental days, or 0 if the client goes back.
    int chooseRentalDays() {
        return chooseRentalDays("Select Game");
    }

    int chooseRentalDays(String previousScreen) {
        while (true) {
            ui.printHeader("SELECT RENTAL DURATION");
            ui.showRentalDurationOptions(previousScreen);
            ui.inputBar("ENTER CHOICE");

            String input = ui.readText(ui.prompt());
            if (input.equalsIgnoreCase("C")) {
                if (ui.askYesNo("Cancel this rental transaction?")) {
                    return NAV_CANCEL;
                }
                continue;
            }
            if (input.equalsIgnoreCase("M")) {
                if (ui.confirmDiscardCart()) {
                    requestDashboardReturn();
                    return NAV_MAIN_MENU;
                }
                continue;
            }

            int choice;
            try {
                choice = parseMenuChoice(input);
            } catch (NumberFormatException e) {
                ui.error("Invalid choice.");
                continue;
            }

            if (choice == 1) {
                return 1;
            }
            if (choice == 2) {
                return 3;
            }
            if (choice == 3) {
                return 7;
            }
            if (choice == 0) {
                return 0;
            }
            if (choice == 4) {
                while (true) {
                    ui.showNavigationOptions(new String[] {"[B] Rental Duration", "[M] Discard Cart / Dashboard"});
                    ui.inputBar("RENTAL DURATION");
                    String daysInput = ui.readText(ui.prompt());
                    if (daysInput.equalsIgnoreCase("C")) {
                        if (ui.askYesNo("Cancel this rental transaction?")) {
                            return NAV_CANCEL;
                        }
                        continue;
                    }
                    if (daysInput.equalsIgnoreCase("M")) {
                        if (ui.confirmDiscardCart()) {
                            requestDashboardReturn();
                            return NAV_MAIN_MENU;
                        }
                        continue;
                    }
                    if ((daysInput.equalsIgnoreCase("B") || daysInput.equals("0"))) {
                        break;
                    }
                    try {
                        int days = Integer.parseInt(daysInput);
                        if (days < 1 || days > MAX_RENTAL_DAYS) {
                            ui.error("Rental duration must be between 1 and " + MAX_RENTAL_DAYS + " days.");
                        } else {
                            return days;
                        }
                    } catch (NumberFormatException e) {
                        ui.error("Enter a whole number from 1 to " + MAX_RENTAL_DAYS + ".");
                    }
                }
            } else {
                ui.error("Invalid choice. Choose a displayed option.");
            }
        }
    }

    void showOrderSummary(User client, LinkedHashMap<Game, Integer> cart, int days) {
        ui.showOrderSummary(client, cart, days, rentalService);
    }

    // "Continue game selection": the order stays in the cart and can be changed.
    // Back returns to the order summary even if editing emptied the cart.
    int editOrder(User client, LinkedHashMap<Game, Integer> cart, int days) {
        while (true) {
            ui.printHeader("EDIT ORDER", ConsoleUI.EDIT_ORDER_TABLE_WIDTH);
            int line = 1;
            if (cart.isEmpty()) {
                ui.showEmptyState("(Your cart is empty)");
            } else {
                ui.showEditOrderTableHeader();
                for (Game game : cart.keySet()) {
                    ui.showCartRow(cart.get(game), line, game);
                    line++;
                }
                ui.info(ui.tableLine(ConsoleUI.EDIT_ORDER_TABLE_WIDTH));
            }
            ui.showEditOrderOptions(days);
            ui.inputBar("ENTER CHOICE", ConsoleUI.EDIT_ORDER_TABLE_WIDTH);

            String input = ui.readText(ui.prompt(ConsoleUI.EDIT_ORDER_TABLE_WIDTH));
            if (input.equalsIgnoreCase("C")) {
                if (ui.askYesNo("Cancel this rental transaction and discard the cart?")) {
                    return NAV_CANCEL;
                }
                continue;
            }
            if (input.equalsIgnoreCase("M")) {
                if (ui.confirmDiscardCart()) {
                    requestDashboardReturn();
                    return NAV_MAIN_MENU;
                }
                continue;
            }

            int choice;
            try {
                choice = parseMenuChoice(input);
            } catch (NumberFormatException e) {
                ui.error("Invalid choice.");
                continue;
            }

            if (choice == 1) {
                rentalSelectionDestination = "Edit Order";
                int status = addGamesToCart(client, cart);
                if (status == NAV_CANCEL || status == NAV_MAIN_MENU) {
                    return status;
                }
            } else if (choice == 2) {
                ui.showNavigationOptions(ConsoleUI.EDIT_ORDER_TABLE_WIDTH, new String[] {"[B] Edit Order", "[M] Discard Cart / Dashboard"});
                ui.inputBar("LINE NUMBER", ConsoleUI.EDIT_ORDER_TABLE_WIDTH);
                String removeInput = ui.readText(ui.prompt(ConsoleUI.EDIT_ORDER_TABLE_WIDTH));
                while (removeInput.equalsIgnoreCase("C") || removeInput.equalsIgnoreCase("M")) {
                    if (removeInput.equalsIgnoreCase("C")) {
                        if (ui.askYesNo("Cancel this rental transaction and discard the cart?")) {
                            return NAV_CANCEL;
                        }
                    } else {
                        if (ui.confirmDiscardCart()) {
                            requestDashboardReturn();
                            return NAV_MAIN_MENU;
                        }
                    }
                    ui.showNavigationOptions(ConsoleUI.EDIT_ORDER_TABLE_WIDTH,
                        new String[] {"[B] Edit Order", "[M] Discard Cart / Dashboard"});
                    ui.inputBar("LINE NUMBER", ConsoleUI.EDIT_ORDER_TABLE_WIDTH);
                    removeInput = ui.readText(ui.prompt(ConsoleUI.EDIT_ORDER_TABLE_WIDTH));
                }
                try {
                    int number = parseMenuChoice(removeInput);
                    if (number >= 1 && number <= cart.size()) {
                        Game removed = new ArrayList<>(cart.keySet()).get(number - 1);
                        cart.remove(removed);
                        ui.success(removed.title + " removed from your cart.");
                    } else if (number != 0) {
                        ui.error("Invalid line number.");
                    }
                } catch (NumberFormatException e) {
                    ui.error("Invalid line number.");
                }
            } else if (choice == 3) {
                int newDays = chooseRentalDays("Edit Order");
                if (newDays == NAV_CANCEL || newDays == NAV_MAIN_MENU) {
                    return newDays;
                }
                if (newDays != 0) {
                    days = newDays;
                }
            } else if (choice == 0) {
                return days;
            } else {
                ui.error("Invalid choice.");
            }
        }
    }

    boolean validateCartBeforeSubmission(User client, LinkedHashMap<Game, Integer> cart) {
        String error = rentalService.validateCart(client, cart);
        if (error != null) {
            ui.error(error);
            return false;
        }
        return true;
    }

    void placeInQueue(User client) {
        selectedRentalPlatform = "";
        rentalSelectionDestination = "Dashboard";
        LinkedHashMap<Game, Integer> cart = new LinkedHashMap<>();
        int step = 1;
        int days = RENTAL_DAYS;
        while (true) {
            if (step == 1) {
                rentalSelectionDestination = "Dashboard";
                int addStatus = addGamesToCart(client, cart);
                if (addStatus == NAV_MAIN_MENU || addStatus == NAV_CANCEL || addStatus == 0) {
                    if (addStatus == NAV_CANCEL) {
                        ui.info("Rental transaction cancelled. No changes were made.");
                    }
                    return;
                }
                step = 2;
            }
            if (step == 2) {
                int selectedDays = chooseRentalDays();
                if (selectedDays == NAV_MAIN_MENU || selectedDays == NAV_CANCEL) {
                    if (selectedDays == NAV_CANCEL) {
                        ui.info("Rental transaction cancelled. No changes were made.");
                    }
                    return;
                }
                if (selectedDays == 0) {
                    step = 1;
                    continue;
                }
                days = selectedDays;
                step = 3;
            }
            showOrderSummary(client, cart, days);
            ui.showOrderConfirmationOptions();
            ui.inputBar("ENTER CHOICE", ConsoleUI.ORDER_TABLE_WIDTH);
            String choice = ui.readText(ui.prompt(ConsoleUI.ORDER_TABLE_WIDTH));

            if ((choice.equalsIgnoreCase("B") || choice.equals("0"))) {
                step = 2;
            } else if (choice.equals("1")) {
                if (!validateCartBeforeSubmission(client, cart)) {
                    ui.warning("Please edit the order before submitting.");
                    continue;
                }
                break;
            } else if (choice.equalsIgnoreCase("E") || choice.equals("2")) {
                int result = editOrder(client, cart, days);
                if (result == NAV_CANCEL) {
                    ui.info("Rental transaction cancelled. No changes were made.");
                    return;
                }
                if (result == NAV_MAIN_MENU) {
                    return;
                }
                days = result;
            } else if (choice.equalsIgnoreCase("C")) {
                if (ui.askYesNo("Cancel this rental transaction and discard the cart?")) {
                    ui.info("Rental transaction cancelled. No changes were made.");
                    return;
                }
            } else if (choice.equalsIgnoreCase("M")) {
                if (ui.confirmDiscardCart()) {
                    requestDashboardReturn();
                    return;
                }
            } else {
                ui.error("Use 1 to confirm, E to edit, B for Rental Duration, or M for Dashboard.");
            }
        }

        ui.loading("Submitting order");
        RentalDataStore.require((long) nextQueueNumber + cart.size() <= Integer.MAX_VALUE,
            "Queue number range exhausted");
        ArrayList<RentalRequest> submitted = new ArrayList<>();
        for (Game game : cart.keySet()) {
            RentalDataStore.require(nextQueueNumber < Integer.MAX_VALUE, "Queue number range exhausted");
            RentalRequest request = new RentalRequest(nextQueueNumber++, client, game);
            request.rentalDays = days;
            request.quantity = cart.get(game);
            checkoutQueue.enqueue(request);
            submitted.add(request);
            logEvent("Queue request " + ui.queueLabel(request.queueNumber) + ": "
                + client.username + " - " + request.game.title + " x" + request.quantity + " (" + days + " day(s))");
        }
        dataStore.saveQueue();
        ui.printHeader("CLIENT > RENT GAMES", "ORDER SUBMITTED", ConsoleUI.QUEUE_TABLE_WIDTH);
        ui.success("Requests saved. Stock will be checked at checkout.");
        ui.showSubmittedQueueHeader();
        ArrayList<RentalRequest> ready = checkoutQueue.previewReadyRequests();
        for (RentalRequest r : checkoutQueue.getDisplayRequests(ready)) {
            if (submitted.contains(r)) {
                ui.showQueueRequestRow(ready.indexOf(r) + 1, r);
            }
        }
        ui.info(ui.tableLine(ConsoleUI.QUEUE_TABLE_WIDTH));
        ui.finishScreen();
        ui.waitForResultNavigation("", "Dashboard");
    }
    void cancelMyRequest(User client) {
        boolean choosingRequest = false;
        int page = 0;
        while (true) {
            ArrayList<RentalRequest> myRequests = checkoutQueue.getRequestsOf(client);
            if (myRequests.isEmpty()) {
                ui.printHeader("CLIENT > QUEUE REQUESTS", "MY QUEUE REQUESTS");
                ui.showEmptyState("You have no pending queue requests.");
                waitForScreenNavigation("Dashboard", false);
                return;
            }

            ui.printHeader("CLIENT > QUEUE REQUESTS", "MY QUEUE REQUESTS", ConsoleUI.QUEUE_TABLE_WIDTH);
            ArrayList<RentalRequest> ready = checkoutQueue.previewReadyRequests();
            ArrayList<RentalRequest> displayed = new ArrayList<>();
            for (RentalRequest request : checkoutQueue.getDisplayRequests(ready)) {
                if (myRequests.contains(request)) {
                    displayed.add(request);
                }
            }
            ArrayList<Integer> starts = ui.queuePages(displayed);
            int pages = starts.size() - 1;
            page = Math.min(page, pages - 1);
            int start = starts.get(page);
            int end = starts.get(page + 1);
            ui.showListStatus("Processing positions · Page: " + (page + 1) + "/" + pages,
                "Showing " + (start + 1) + "-" + end + " of " + displayed.size(), ConsoleUI.QUEUE_TABLE_WIDTH);
            ui.showQueueRequestHeader();
            for (int i = start; i < end; i++) {
                RentalRequest request = displayed.get(i);
                ui.showQueueRequestRow(ready.indexOf(request) + 1, request);
            }
            ui.info(ui.tableLine(ConsoleUI.QUEUE_TABLE_WIDTH));
            ui.showPagination(page, pages);

            if (!choosingRequest) {
                ui.showQueueCancellationOptions();
                String choice = ui.readText(ui.prompt(ConsoleUI.QUEUE_TABLE_WIDTH));
                if (choice.equalsIgnoreCase("N") && page < pages - 1) {
                    page++;
                    continue;
                }
                if (choice.equalsIgnoreCase("P") && page > 0) {
                    page--;
                    continue;
                }
                if (choice.equalsIgnoreCase("M")) {
                    requestDashboardReturn();
                    return;
                }
                if ((choice.equalsIgnoreCase("B") || choice.equals("0"))) {
                    return;
                }
                if (!choice.equalsIgnoreCase("C") && !choice.equals("1")) {
                    ui.error("Invalid choice. Use C to cancel a request or B for Dashboard.");
                    continue;
                }
                choosingRequest = true;
                continue;
            }

            ui.showNavigationOptions(ConsoleUI.QUEUE_TABLE_WIDTH, new String[] {"[B] My Requests", "[M] Dashboard"});
            ui.inputBar("QUEUE NUMBER TO CANCEL", ConsoleUI.QUEUE_TABLE_WIDTH);
            String input = ui.readText(ui.prompt(ConsoleUI.QUEUE_TABLE_WIDTH));
            if (input.equalsIgnoreCase("N") && page < pages - 1) {
                page++;
                continue;
            }
            if (input.equalsIgnoreCase("P") && page > 0) {
                page--;
                continue;
            }
            if (input.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return;
            }
            if ((input.equalsIgnoreCase("B") || input.equals("0"))) {
                choosingRequest = false;
                continue;
            }

            int number;
            try {
                number = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                ui.error("Invalid queue number.");
                continue;
            }

            RentalRequest request = checkoutQueue.findByNumber(number);
            if (request == null || request.client != client) {
                ui.error("Invalid queue number. You can only cancel your own requests.");
                continue;
            }

            if (!ui.askYesNo("Cancel request " + ui.queueLabel(number) + " for " + request.game.title + "?")) {
                continue;
            }

            ui.loading("Cancelling request");
            checkoutQueue.remove(request);
            logEvent("Client cancelled queue request " + ui.queueLabel(number) + ": "
                + client.username + " - " + request.game.title + " x" + request.quantity);
            dataStore.saveQueue();
            ui.success("Queue request " + ui.queueLabel(number) + " has been cancelled.");

            if (checkoutQueue.getRequestsOf(client).isEmpty()) {
                ui.finishScreen(ConsoleUI.QUEUE_TABLE_WIDTH);
                ui.waitForResultNavigation("", "Dashboard");
                return;
            }
            if (!ui.askYesNo("Do you want to cancel another queue request?")) {
                return;
            }
        }
    }

    void viewHistory(User client) {
        if (client.rentalHistory.isEmpty()) {
            ui.printHeader("CLIENT > RENTAL HISTORY", "RENTAL HISTORY");
            ui.showEmptyState("You have no rental history yet.");
            waitForScreenNavigation("Dashboard", false);
            return;
        }

        ArrayList<Rental> history = new ArrayList<>();
        for (int i = client.rentalHistory.size() - 1; i >= 0; i--) {
            history.add(client.rentalHistory.get(i));
        }

        int page = 0;
        ArrayList<Integer> starts = ui.rentalPages(history, "history");
        int totalPages = starts.size() - 1;
        while (true) {
            int start = starts.get(page);
            int end = starts.get(page + 1);
            ui.printHeader("RENTAL HISTORY (Most Recent First)", ConsoleUI.HISTORY_TABLE_WIDTH);
            ui.showListStatus("Most recent first · Page: " + (page + 1) + "/" + totalPages,
                "Showing " + (start + 1) + "-" + end + " of " + history.size(), ConsoleUI.HISTORY_TABLE_WIDTH);
            ui.showHistoryTableHeader(history.size(), start, end);

            for (int i = start; i < end; i++) {
                Rental rental = history.get(i);
                ui.showHistoryRow(rental);
            }
            ui.info(ui.tableLine(ConsoleUI.HISTORY_TABLE_WIDTH));

            ui.showPagination(page, totalPages);
            ui.showBackToDashboard();
            ui.inputBar("ENTER CHOICE", ConsoleUI.HISTORY_TABLE_WIDTH);
            String choice = ui.readText(ui.prompt(ConsoleUI.HISTORY_TABLE_WIDTH));
            if (choice.equalsIgnoreCase("N") && page < totalPages - 1) {
                page++;
            }
            else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            }
            else if ((choice.equalsIgnoreCase("B") || choice.equals("0"))) {
                return;
            }
            else ui.error("Invalid choice.");
        }
    }

    void upgradeToVip(User client) {
        if (client.isVip()) {
            ui.printHeader("CLIENT > MEMBERSHIP", "VIP MEMBERSHIP");
            ui.showEmptyState("You are already a VIP member.");
            waitForScreenNavigation("Dashboard", false);
            return;
        }

        while (true) {
            ui.printHeader("CLIENT > MEMBERSHIP", "UPGRADE TO VIP");
            ui.showVipBenefits();
            ui.inputBar("ENTER CHOICE");
            String choice = ui.readText(ui.prompt());

            if (choice.equals("1")) {
                ui.loading("Upgrading membership");
                client.membership = "VIP";
                checkoutQueue.refreshMembership(client);
                logEvent("Upgraded to VIP: " + client.username);
                accountService.saveAccounts();
                ui.success("Upgrade successful! You are now a VIP member.");
                ui.finishScreen();
                ui.waitForResultNavigation("", "Dashboard");
                return;
            } else if (choice.equalsIgnoreCase("C")) {
                ui.info("VIP upgrade cancelled. No changes were made.");
                return;
            } else {
                ui.error("Invalid choice. Please enter 1 or C.");
            }
        }
    }

    // Admin menu

    void adminMenu(User admin) {
        boolean loggedIn = true;

        while (loggedIn) {
            clearDashboardReturnRequest();
            ui.showAdminMenu();
            int choice = readMenuChoice("L");
            try {
                if (choice == 1) {
                    checkoutManagement(admin.username);
                } else if (choice == 2) {
                    processReturn();
                } else if (choice == 3) {
                    manageInventory();
                } else if (choice == 4) {
                    activeRentalsMenu();
                } else if (choice == 5) {
                    viewAuditLog();
                } else if (choice == 0) {
                    if (ui.askYesNo("Log out of the admin account?")) {
                        ui.loading("Logging out");
                        ui.success("You have been logged out.");
                        ui.pause(500);
                        loggedIn = false;
                    }
                } else {
                    ui.error("Invalid choice. Choose a displayed option.");
                }
            } catch (StorageException e) {
                ui.error(e.getMessage());
            }
        }
    }

    void activeRentalsMenu() {
        while (true) {
            ui.showActiveRentalsMenu();
            String input = ui.readText(ui.prompt());
            if (input.equals("1")) {
                viewActiveRentals();
            } else if (input.equals("2")) {
                viewOverdueItems();
            } else if ((input.equalsIgnoreCase("B") || input.equals("0"))) {
                return;
            } else {
                ui.error("Invalid choice. Please enter 1, 2, or B.");
            }
            if (isDashboardReturnRequested()) {
                return;
            }
        }
    }

    // Session records are display-only and never enter the saved data format.
    static class CheckoutEntry {
        Rental rental;
        RentalRequest cancelled;

        CheckoutEntry(Rental rental, RentalRequest cancelled) {
            this.rental = rental;
            this.cancelled = cancelled;
        }
    }

    static class CheckoutSession {
        ArrayList<CheckoutEntry> entries = new ArrayList<>();
        String admin;
        LocalDateTime started = LocalDateTime.now();
        int approved;
        int cancelled;
        int copies;
        double total;

        CheckoutSession(String admin) {
            this.admin = admin;
        }

        void addRental(Rental rental) {
            entries.add(new CheckoutEntry(rental, null));
            approved++;
            copies += rental.quantity;
            total = Math.round((total + rental.amount) * 100) / 100.0;
        }

        void addCancellation(RentalRequest request) {
            entries.add(new CheckoutEntry(null, request));
            cancelled++;
        }
    }

    void checkoutManagement() {
        checkoutManagement("admin");
    }

    void checkoutManagement(String admin) {
        CheckoutSession session = new CheckoutSession(admin);
        try {
            runCheckoutSession(session);
        } finally {
            ui.endCheckoutDisplay();
        }
    }

    private void runCheckoutSession(CheckoutSession session) {
        ui.showCheckoutQueue(checkoutQueue, rentalService);
        while (true) {
            String choice = ui.readText(ui.prompt(ConsoleUI.CHECKOUT_QUEUE_TABLE_WIDTH));
            if ((choice.equalsIgnoreCase("B") || choice.equals("0")) || choice.equalsIgnoreCase("M")) {
                if (!session.entries.isEmpty()) {
                    ui.showCheckoutReceipt(session, checkoutQueue);
                    ui.finishScreen(ConsoleUI.CHECKOUT_QUEUE_TABLE_WIDTH);
                    ui.waitForResultNavigation("", "Dashboard");
                }
                if (choice.equalsIgnoreCase("M")) {
                    requestDashboardReturn();
                }
                return;
            }
            if (choice.equalsIgnoreCase("V")) {
                ui.showCheckoutQueue(checkoutQueue, rentalService);
                continue;
            }
            if (ui.moveCheckoutPage(choice)) {
                ui.showCheckoutQueue(checkoutQueue, rentalService);
                continue;
            }
            if (choice.equalsIgnoreCase("R")) {
                ui.showCheckoutReceipt(session, checkoutQueue);
                ui.finishScreen(ConsoleUI.CHECKOUT_QUEUE_TABLE_WIDTH);
                waitForScreenNavigation("Checkout", true);
                if (isDashboardReturnRequested()) {
                    return;
                }
                ui.showCheckoutQueue(checkoutQueue, rentalService);
                continue;
            }
            int entriesBeforeAction = session.entries.size();
            if (choice.isEmpty()) {
                RentalRequest next = checkoutQueue.findNextEligibleRequest();
                if (next == null) {
                    ui.warning("No request is ready.");
                } else if (ui.confirmCheckout(next, rentalService)) {
                    approveSessionRequest(next, session);
                }
            } else if (choice.equalsIgnoreCase("A")) {
                approveReadyBatch(session);
            } else if (choice.equalsIgnoreCase("C")) {
                cancelSessionRequest(session);
                if (isDashboardReturnRequested()) {
                    if (!session.entries.isEmpty()) {
                        ui.showCheckoutReceipt(session, checkoutQueue);
                        ui.finishScreen(ConsoleUI.CHECKOUT_QUEUE_TABLE_WIDTH);
                        ui.waitForResultNavigation("", "Dashboard");
                    }
                    return;
                }
            } else {
                ui.error("Use ENTER, A, C, V, R, B or M.");
            }
            if (session.entries.size() > entriesBeforeAction) {
                ui.showCheckoutTapeFooter(session, checkoutQueue, rentalService);
            } else {
                ui.showTapeInput();
            }
        }
    }

    ArrayList<RentalRequest> previewReadyCheckouts() {
        return checkoutQueue.previewReadyRequests();
    }

    void approveReadyBatch(CheckoutSession session) {
        ArrayList<RentalRequest> planned = previewReadyCheckouts();
        if (planned.isEmpty()) {
            ui.warning("No request is ready.");
            return;
        }
        ui.showCheckoutBatch(planned, checkoutQueue, rentalService);
        if (!ui.confirmCheckoutBatch(planned.size())) {
            return;
        }
        for (RentalRequest request : planned) {
            if (checkoutQueue.findNextEligibleRequest() != request) {
                ui.error("Batch stopped at " + ui.queueLabel(request.queueNumber) + ": queue changed.");
                return;
            }
            if (!approveSessionRequest(request, session)) {
                return;
            }
        }
    }

    boolean approveSessionRequest(RentalRequest request, CheckoutSession session) {
        String error = rentalService.validateCheckout(request);
        if (error != null) {
            ui.error("Checkout " + ui.queueLabel(request.queueNumber) + " failed: " + error);
            return false;
        }
        try {
            Rental rental = approveCheckout(request);
            if (rental == null) {
                return false;
            }
            session.addRental(rental);
            ui.showApprovedSlip(rental, session.entries.size());
            return true;
        } catch (StorageException e) {
            ui.error("Checkout " + ui.queueLabel(request.queueNumber) + " failed: " + e.getMessage());
            return false;
        }
    }

    void cancelSessionRequest(CheckoutSession session) {
        ui.inputBar("QUEUE NUMBER TO CANCEL", ConsoleUI.CHECKOUT_QUEUE_TABLE_WIDTH, "[B] Checkout   [M] Dashboard");
        String input = ui.readText(ui.prompt(ConsoleUI.CHECKOUT_QUEUE_TABLE_WIDTH));
        if (input.equalsIgnoreCase("M")) {
            requestDashboardReturn();
            return;
        }
        if ((input.equalsIgnoreCase("B") || input.equals("0"))) {
            return;
        }
        RentalRequest request;
        try {
            request = checkoutQueue.findByNumber(Integer.parseInt(input));
        } catch (NumberFormatException e) {
            ui.error("Enter a valid queue number.");
            return;
        }
        if (request == null) {
            ui.error("Request not found.");
            return;
        }
        if (ui.confirmCheckoutCancellation(request)) {
            try {
                checkoutQueue.remove(request);
                logAdmin("Cancelled queue request " + ui.queueLabel(request.queueNumber)
                    + ": " + request.client.username);
                dataStore.saveQueue();
            } catch (StorageException e) {
                ui.error("Cancellation " + ui.queueLabel(request.queueNumber) + " failed: " + e.getMessage());
                return;
            }
            session.addCancellation(request);
            ui.showCancelledSlip(request, session.entries.size());
        }
    }

    Rental approveCheckout(RentalRequest request) {
        String error = rentalService.validateCheckout(request);
        if (error != null) {
            ui.error(error);
            return null;
        }
        Rental rental = rentalService.checkout(request, nextRentalNumber);
        nextRentalNumber++;
        logAdmin("Processed checkout: " + request.client.username
            + " - " + request.game.title + " x" + request.quantity);

        dataStore.saveData();
        return rental;
    }

    ArrayList<Rental> searchReturnRentals(String keyword) {
        ArrayList<Rental> matches = new ArrayList<>();
        String search = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        for (Rental rental : rentalService.getRentalsByDueDate()) {
            if (search.isEmpty() || rental.client.username.toLowerCase(java.util.Locale.ROOT).contains(search)
                    || rental.client.fullName.toLowerCase(java.util.Locale.ROOT).contains(search)
                    || rental.game.gameId.equalsIgnoreCase(search)
                    || rental.game.title.toLowerCase(java.util.Locale.ROOT).contains(search)) {
                matches.add(rental);
            }
        }
        return matches;
    }

    ArrayList<Rental> parseReturnSelection(ArrayList<Rental> rentals, int start, int end, String input) {
        boolean[] chosen = new boolean[end - start];
        String[] numbers = input.split(",", -1);
        for (String text : numbers) {
            int number;
            try {
                number = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                ui.error("Enter a row number or comma-separated numbers, such as 1,3,5.");
                return null;
            }
            if (number < 1 || number > chosen.length) {
                ui.error("Choose only row numbers displayed on this page.");
                return null;
            }
            if (chosen[number - 1]) {
                ui.error("Select each rental only once.");
                return null;
            }
            chosen[number - 1] = true;
        }
        ArrayList<Rental> selected = new ArrayList<>();
        // Read rows in their displayed due-date order, regardless of input order.
        for (int i = 0; i < chosen.length; i++) {
            if (chosen[i]) {
                selected.add(rentals.get(start + i));
            }
        }
        return selected;
    }

    ArrayList<Rental> selectActiveRentalsForReturn() {
        int page = 0;
        String keyword = "";
        while (true) {
            if (activeRentals.isEmpty()) {
                ui.printHeader("ADMIN > RETURNS", "ACTIVE RENTALS", ConsoleUI.RETURN_TABLE_WIDTH);
                ui.showEmptyState("No active rentals.");
                waitForScreenNavigation("Dashboard", false);
                return null;
            }
            ArrayList<Rental> rentals = searchReturnRentals(keyword);
            ArrayList<Integer> starts = ui.rentalPages(rentals, "return");
            int totalPages = starts.size() - 1;
            page = Math.min(page, totalPages - 1);
            int start = starts.get(page);
            int end = starts.get(page + 1);
            ui.showReturnSelectionList(rentals, start, end, page, totalPages, keyword);
            String choice = ui.readText(ui.prompt(ConsoleUI.RETURN_TABLE_WIDTH)).trim();
            if ((choice.equalsIgnoreCase("B") || choice.equals("0"))) {
                return null;
            }
            if (choice.equalsIgnoreCase("S")) {
                ui.showReturnSearchPrompt();
                String search = ui.readText(ui.prompt(ConsoleUI.RETURN_TABLE_WIDTH)).trim();
                if (search.equalsIgnoreCase("M")) {
                    requestDashboardReturn();
                    return null;
                }
                if (search.equalsIgnoreCase("B")) {
                    continue;
                }
                keyword = search;
                page = 0;
            } else if (choice.equalsIgnoreCase("X")) {
                keyword = "";
                page = 0;
            } else if (choice.equalsIgnoreCase("N")) {
                if (page < totalPages - 1) {
                    page++;
                } else {
                    ui.error("There is no next page.");
                }
            } else if (choice.equalsIgnoreCase("P")) {
                if (page > 0) {
                    page--;
                } else {
                    ui.error("There is no previous page.");
                }
            } else {
                ArrayList<Rental> selected = parseReturnSelection(rentals, start, end, choice);
                if (selected != null) {
                    return selected;
                }
            }
        }
    }

    void completeSelectedReturns(ArrayList<Rental> selected, LocalDateTime returnedAt) {
        RentalDataStore.require(selected != null && !selected.isEmpty() && returnedAt != null, "Select an active rental to return.");
        LinkedHashMap<Game, Integer> restoredStocks = new LinkedHashMap<>();
        ArrayList<Rental> checked = new ArrayList<>();
        // Validate the whole selection before changing any rental or stock.
        for (Rental rental : selected) {
            RentalDataStore.require(rental != null && rental.status.equals("ACTIVE") && activeRentals.contains(rental)
                    && !checked.contains(rental), "A selected rental is no longer active or was selected twice.");
            RentalDataStore.require(!returnedAt.isBefore(rental.checkoutDate), "Invalid return date.");
            int stock = rental.game.availableCopies;
            if (restoredStocks.containsKey(rental.game)) {
                stock = restoredStocks.get(rental.game);
            }
            int restored = Math.addExact(stock, rental.quantity);
            RentalDataStore.require(restored <= MAX_GAME_STOCK, "Return would exceed total stock; correct inventory first.");
            restoredStocks.put(rental.game, restored);
            checked.add(rental);
        }
        for (Rental rental : selected) {
            rentalService.completeReturn(rental, returnedAt);
            logAdmin("Processed return: " + rental.client.username + " - " + rental.game.title
                    + " x" + rental.quantity + "; days late " + rental.daysLate + "; fee " + ui.money(rental.lateFee));
        }
        // One recoverable commit saves the entire selected batch.
        dataStore.saveData();
    }

    void processReturn() {
        while (true) {
            ArrayList<Rental> selected = selectActiveRentalsForReturn();
            if (selected == null) {
                return;
            }
            LocalDateTime returnedAt = LocalDateTime.now();
            if (!ui.confirmSelectedReturns(selected, returnedAt, rentalService)) {
                continue;
            }
            completeSelectedReturns(selected, returnedAt);
            ui.showSelectedReturnResults(selected);
            String previousDestination = "";
            if (!activeRentals.isEmpty()) {
                previousDestination = "Return List";
            }
            if (!ui.waitForResultNavigation(previousDestination, "Dashboard")) {
                return;
            }
        }
    }
    void viewActiveRentals() {
        ArrayList<Rental> rentals = rentalService.getRentalsByDueDate();
        if (rentals.isEmpty()) {
            ui.printHeader("ADMIN > ACTIVE RENTALS", "ACTIVE RENTALS");
            ui.showEmptyState("There are no active rentals.");
            waitForScreenNavigation("Active Rentals", true);
            return;
        }

        int page = 0;
        ArrayList<Integer> starts = ui.rentalPages(rentals, "active");
        int totalPages = starts.size() - 1;
        while (true) {
            int start = starts.get(page);
            int end = starts.get(page + 1);
            ui.printHeader("ACTIVE RENTALS - Earliest Due Date First", ConsoleUI.ACTIVE_TABLE_WIDTH);
            ui.showListStatus("Earliest due date first · Page: " + (page + 1) + "/" + totalPages,
                "Showing " + (start + 1) + "-" + end + " of " + rentals.size(), ConsoleUI.ACTIVE_TABLE_WIDTH);
            ui.showActiveRentalHeader(rentals.size(), start, end);

            for (int i = start; i < end; i++) {
                Rental rental = rentals.get(i);
                ui.showActiveRentalRow(rental);
            }
            ui.info(ui.tableLine(ConsoleUI.ACTIVE_TABLE_WIDTH));

            ui.showPagination(page, totalPages);
            ui.showNavigationOptions(new String[] {"[B] Active Rentals", "[M] Dashboard"});
            ui.inputBar("ENTER CHOICE", ConsoleUI.ACTIVE_TABLE_WIDTH);
            String choice = ui.readText(ui.prompt(ConsoleUI.ACTIVE_TABLE_WIDTH));
            if (choice.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return;
            } else if (choice.equalsIgnoreCase("N") && page < totalPages - 1) {
                page++;
            }
            else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            }
            else if ((choice.equalsIgnoreCase("B") || choice.equals("0"))) {
                return;
            }
            else ui.error("Invalid choice.");
        }
    }

    void viewOverdueItems() {
        ArrayList<Rental> rentals = rentalService.getRentalsByDueDate();
        LocalDateTime now = LocalDateTime.now();
        for (int i = rentals.size() - 1; i >= 0; i--) {
            if (!now.isAfter(rentals.get(i).dueDate)) {
                rentals.remove(i);
            }
        }

        if (rentals.isEmpty()) {
            ui.printHeader("ADMIN > ACTIVE RENTALS", "OVERDUE RENTALS");
            ui.showEmptyState("There are no overdue rentals.");
            waitForScreenNavigation("Active Rentals", true);
            return;
        }

        int page = 0;
        ArrayList<Integer> starts = ui.rentalPages(rentals, "overdue");
        int totalPages = starts.size() - 1;
        while (true) {
            int start = starts.get(page);
            int end = starts.get(page + 1);
            ui.printHeader("OVERDUE RENTALS - Earliest Due Date First", ConsoleUI.OVERDUE_TABLE_WIDTH);
            ui.showListStatus("Earliest due date first · Page: " + (page + 1) + "/" + totalPages,
                "Showing " + (start + 1) + "-" + end + " of " + rentals.size(), ConsoleUI.OVERDUE_TABLE_WIDTH);
            ui.showOverdueRentalHeader(rentals.size(), start, end);

            for (int i = start; i < end; i++) {
                Rental rental = rentals.get(i);
                long daysOverdue = RentalService.daysOverdue(rental.dueDate, now);
                ui.showOverdueRentalRow(daysOverdue * LATE_FEE_PER_DAY * rental.quantity, rental, daysOverdue);
            }
            ui.info(ui.tableLine(ConsoleUI.OVERDUE_TABLE_WIDTH));

            Rental top = rentals.get(0);
            ui.showEarliestDueRental(top);
            ui.showPagination(page, totalPages);
            ui.showNavigationOptions(new String[] {"[B] Active Rentals", "[M] Dashboard"});
            ui.inputBar("ENTER CHOICE", ConsoleUI.OVERDUE_TABLE_WIDTH);

            String choice = ui.readText(ui.prompt(ConsoleUI.OVERDUE_TABLE_WIDTH));
            if (choice.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return;
            } else if (choice.equalsIgnoreCase("N") && page < totalPages - 1) {
                page++;
            }
            else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            }
            else if ((choice.equalsIgnoreCase("B") || choice.equals("0"))) {
                return;
            }
            else ui.error("Invalid choice.");
        }
    }

    void viewAuditLog() {
        if (auditLog.isEmpty()) {
            ui.printHeader("ADMIN > AUDIT LOG", "ADMIN AUDIT LOG");
            ui.showEmptyState("No actions have been recorded yet.");
            waitForScreenNavigation("Dashboard", false);
            return;
        }

        ArrayList<String> logs = new ArrayList<>();
        for (int i = auditLog.size() - 1; i >= 0; i--) {
            logs.add(auditLog.get(i));
        }

        int page = 0;
        ArrayList<Integer> starts = ui.auditPages(logs);
        int totalPages = starts.size() - 1;
        while (true) {
            int start = starts.get(page);
            int end = starts.get(page + 1);
            ui.printHeader("ADMIN AUDIT LOG (Most Recent First)", ConsoleUI.AUDIT_TABLE_WIDTH);
            ui.showListStatus("Most recent first · Page: " + (page + 1) + "/" + totalPages,
                "Showing " + (start + 1) + "-" + end + " of " + logs.size(), ConsoleUI.AUDIT_TABLE_WIDTH);
            ui.showAuditHeader(logs.size(), start, end);

            for (int i = start; i < end; i++) {
                String entry = logs.get(i);
                String[] parts = entry.split("\\|", 2);
                if (parts.length == 2) {
                    try {
                        LocalDateTime stamp = LocalDateTime.parse(parts[0], LOG_STORAGE_FORMAT);
                        ui.showAuditRow(stamp, parts);
                    } catch (DateTimeException e) {
                        ui.showLegacyAuditRow(entry);
                    }
                } else {
                    ui.showLegacyAuditRow(entry);
                }
            }

            ui.info(ui.tableLine(ConsoleUI.AUDIT_TABLE_WIDTH));
            ui.showPagination(page, totalPages);
            ui.showBackToDashboard();
            ui.inputBar("ENTER CHOICE", ConsoleUI.AUDIT_TABLE_WIDTH);
            String choice = ui.readText(ui.prompt(ConsoleUI.AUDIT_TABLE_WIDTH));
            if (choice.equalsIgnoreCase("N") && page < totalPages - 1) {
                page++;
            }
            else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            }
            else if ((choice.equalsIgnoreCase("B") || choice.equals("0"))) {
                return;
            }
            else ui.error("Invalid choice.");
        }
    }

    // Manage inventory (admin)

    void manageInventory() {
        while (true) {
            ui.showInventoryMenu();
            String input = ui.readText(ui.prompt());


            int choice;
            try {
                choice = parseMenuChoice(input);
            } catch (NumberFormatException e) {
                ui.error("Invalid choice.");
                continue;
            }

            if (choice == 1) {
                browseGames(true);
            } else if (choice == 2) {
                addGame();
            } else if (choice == 3) {
                updateGameStock();
            } else if (choice == 4) {
                deleteGame();
            } else if (choice == 0) {
                return;
            } else {
                ui.error("Invalid choice. Please enter 1, 2, 3, 4, or B.");
            }

            if (isDashboardReturnRequested()) {
                return;
            }
        }
    }

    private boolean gameAlreadyExists(String title, String platform) {
        for (Game game : games.values()) {
            if (game.title.equalsIgnoreCase(title) && game.platform.equals(platform)) {
                return true;
            }
        }
        return false;
    }

    void addGame() {
        while (true) {
            String title = "";
            String platform = "";
            double price = 0;
            int copies = 0;
            int step = 1;
            boolean editing = false;

            while (true) {
                if (step == 6) {
                    ui.showGameEditMenu(title, platform, price, copies);
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("B") || input.equals("0")) {
                        step = 5;
                    } else if (input.equals("1") || input.equals("2") || input.equals("3") || input.equals("4")) {
                        step = Integer.parseInt(input);
                        editing = true;
                    } else {
                        ui.error("Choose 1, 2, 3, 4, or B for Game Summary.");
                    }
                    continue;
                }
                if (step == 1) {
                    if (editing) {
                        ui.showGameEditField(step, title, platform, price, copies);
                    } else {
                        ui.printHeader("ADMIN > MANAGE INVENTORY > ADD GAME", "ADD GAME");
                        ui.showAddGameInstructions();
                        ui.inputBar("GAME TITLE");
                    }
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Dashboard")) {
                            continue;
                        }
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Inventory")) {
                            continue;
                        }
                        ui.info("Add-game transaction cancelled. No changes were made.");
                        return;
                    }
                    if (input.equalsIgnoreCase("B") || input.equals("0")) {
                        if (editing) {
                            editing = false;
                            step = 5;
                            continue;
                        }
                        if (!title.isEmpty() && !ui.confirmDiscardTransaction("this add-game transaction", "Inventory")) {
                            continue;
                        }
                        return;
                    }
                    if (input.isEmpty()) {
                        ui.error("Title cannot be empty.");
                        continue;
                    }
                    if (!GameCatalogService.validTitle(input)) {
                        ui.error("Use a title of 1-100 characters without control characters or the | symbol.");
                        continue;
                    }
                    if (editing && gameAlreadyExists(input, platform)) {
                        ui.error("That game already exists on " + platform + ". Update its stock instead.");
                        continue;
                    }
                    title = input;
                    step = 2;
                    if (editing) {
                        editing = false;
                        step = 5;
                    }
                } else if (step == 2) {
                    if (editing) {
                        ui.showGameEditField(step, title, platform, price, copies);
                    } else {
                        ui.printHeader("ADD GAME", "SELECT PLATFORM");
                        ui.showPlatformOptions();
                        ui.inputBar("ENTER CHOICE");
                    }
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Dashboard")) {
                            continue;
                        }
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Inventory")) {
                            continue;
                        }
                        ui.info("Add-game transaction cancelled. No changes were made.");
                        return;
                    }
                    if ((input.equalsIgnoreCase("B") || input.equals("0"))) {
                        step = 1;
                        if (editing) {
                            editing = false;
                            step = 5;
                        }
                        continue;
                    }
                    String selectedPlatform;
                    if (input.equals("1")) {
                        selectedPlatform = "PS5";
                    }
                    else if (input.equals("2")) {
                        selectedPlatform = "Xbox";
                    }
                    else if (input.equals("3")) {
                        selectedPlatform = "Nintendo";
                    }
                    else {
                        ui.error("Invalid platform choice.");
                        continue;
                    }

                    if (gameAlreadyExists(title, selectedPlatform)) {
                        ui.error("That game already exists on " + selectedPlatform + ". Update its stock instead.");
                        if (!editing) {
                            step = 1;
                        }
                        continue;
                    }
                    platform = selectedPlatform;
                    step = 3;
                    if (editing) {
                        editing = false;
                        step = 5;
                    }
                } else if (step == 3) {
                    if (editing) {
                        ui.showGameEditField(step, title, platform, price, copies);
                    } else {
                        ui.printHeader("ADD GAME", "RENTAL PRICE");
                        ui.showPriceInstructions();
                    }
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Dashboard")) {
                            continue;
                        }
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Inventory")) {
                            continue;
                        }
                        ui.info("Add-game transaction cancelled. No changes were made.");
                        return;
                    }
                    if ((input.equalsIgnoreCase("B") || input.equals("0"))) {
                        step = 2;
                        if (editing) {
                            editing = false;
                            step = 5;
                        }
                        continue;
                    }
                    double enteredPrice;
                    try {
                        enteredPrice = Double.parseDouble(input);
                    } catch (NumberFormatException e) {
                        ui.error("Enter a valid price.");
                        continue;
                    }
                    if (!Double.isFinite(enteredPrice) || enteredPrice < MIN_RENTAL_PRICE || enteredPrice > MAX_RENTAL_PRICE) {
                        ui.error("Rental price must be between " + ui.money(MIN_RENTAL_PRICE)
                            + " and " + ui.money(MAX_RENTAL_PRICE) + ".");
                        continue;
                    }
                    price = enteredPrice;
                    step = 4;
                    if (editing) {
                        editing = false;
                        step = 5;
                    }
                } else if (step == 4) {
                    if (editing) {
                        ui.showGameEditField(step, title, platform, price, copies);
                    } else {
                        ui.printHeader("ADD GAME", "AVAILABLE COPIES");
                        ui.showStockInstructions();
                    }
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Dashboard")) {
                            continue;
                        }
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Inventory")) {
                            continue;
                        }
                        ui.info("Add-game transaction cancelled. No changes were made.");
                        return;
                    }
                    if ((input.equalsIgnoreCase("B") || input.equals("0"))) {
                        step = 3;
                        if (editing) {
                            editing = false;
                            step = 5;
                        }
                        continue;
                    }
                    int enteredCopies;
                    try {
                        enteredCopies = Integer.parseInt(input);
                    } catch (NumberFormatException e) {
                        ui.error("Enter a whole number of copies.");
                        continue;
                    }
                    if (enteredCopies < 1 || enteredCopies > MAX_GAME_STOCK) {
                        ui.error("Stock must be between 1 and " + MAX_GAME_STOCK + " copies.");
                        continue;
                    }
                    copies = enteredCopies;
                    editing = false;
                    step = 5;
                } else {
                    ui.printHeader("ADMIN > MANAGE INVENTORY > ADD GAME", "CONFIRM NEW GAME");
                    ui.showNewGameConfirmation(title, platform, price, copies);
                    ui.inputBar("ENTER CHOICE");
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Dashboard")) {
                            continue;
                        }
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        if (!ui.confirmDiscardTransaction("this add-game transaction", "Inventory")) {
                            continue;
                        }
                        ui.info("Add-game transaction cancelled. No changes were made.");
                        return;
                    }
                    if (input.equalsIgnoreCase("E") || input.equals("2")) {
                        step = 6;
                        continue;
                    }
                    if ((input.equalsIgnoreCase("B") || input.equals("0"))) {
                        step = 4;
                        continue;
                    }
                    if (!input.equals("1")) {
                        ui.error("Invalid choice.");
                        continue;
                    }

                    ui.loading("Saving game");
                    Game game = gameCatalog.addGame(title, platform, price, copies);
                    logAdmin("Added game: " + title + " (" + platform + ")");
                    dataStore.saveGames();

                    ui.printHeader("ADMIN > MANAGE INVENTORY > ADD GAME", "GAME ADDED");
                    ui.success("Game added successfully.");
                    ui.showGameAdded(game);
                    break;
                }
            }

            if (!ui.askYesNo("Do you want to add another game?")) {
                return;
            }
        }
    }

    void deleteGame() {
        while (true) {
            String platform = choosePlatform("ADMIN > MANAGE INVENTORY > DELETE GAME");
            if (isDashboardReturnRequested()) {
                return;
            }
            if (platform.equals("BACK")) {
                return;
            }

            while (true) {
                ArrayList<Game> list = gameCatalog.getGameList(platform);
                Game game = paginateGames(list, "SELECT GAME TO DELETE", true);
                if (isDashboardReturnRequested()) {
                    return;
                }
                if (game == null) {
                    break;
                }

                if (rentalService.gameHasActiveRental(game)) {
                    ui.error("Cannot delete this game because it currently has an active rental.");
                    continue;
                }

                int pending = checkoutQueue.countRequestsForGame(game);
                ui.printHeader("ADMIN > MANAGE INVENTORY > DELETE GAME", "CONFIRM DELETE");
                ui.showDeleteGameDetails(game, pending);
                if (pending > 0) {
                    ui.warning("Deleting this game will also cancel its pending queue request(s).");
                }

                if (!ui.askYesNo("Permanently delete this game?")) {
                    continue;
                }

                ui.loading("Deleting game");
                int cancelled = checkoutQueue.removeRequestsForGame(game);
                games.remove(game.gameId);
                logAdmin("Deleted game: " + game.title + " (" + game.platform + ")");
                dataStore.saveData();

                ui.printHeader("ADMIN > MANAGE INVENTORY > DELETE GAME", "GAME DELETED");
                ui.success(game.title + " was deleted successfully.");
                if (cancelled > 0) {
                    ui.warning(cancelled + " pending queue request(s) were cancelled.");
                }

                if (!ui.askYesNo("Do you want to delete another game?")) {
                    return;
                }
                break;
            }
        }
    }

    void updateGameStock() {
        while (true) {
            String platform = choosePlatform("ADMIN > MANAGE INVENTORY > UPDATE STOCK");
            if (isDashboardReturnRequested()) {
                return;
            }
            if (platform.equals("BACK")) {
                return;
            }

            while (true) {
                Game game = paginateGames(gameCatalog.getGameList(platform), "SELECT GAME TO UPDATE", true);
                if (isDashboardReturnRequested()) {
                    return;
                }
                if (game == null) {
                    break;
                }

                while (true) {
                    ui.printHeader("ADMIN > MANAGE INVENTORY > UPDATE STOCK", "UPDATE STOCK");
                    ui.showStockUpdateOptions(game);
                    ui.inputBar("ENTER CHOICE");
                    String input = ui.readText(ui.prompt());

                    if (input.equalsIgnoreCase("M")) {
                        if (!ui.confirmDiscardTransaction("this stock update", "Dashboard")) {
                            continue;
                        }
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        if (!ui.confirmDiscardTransaction("this stock update", "Inventory")) {
                            continue;
                        }
                        ui.info("Stock update cancelled. No changes were made.");
                        return;
                    }
                    if ((input.equalsIgnoreCase("B") || input.equals("0"))) {
                        break;
                    }
                    if (!input.equals("1") && !input.equals("2")) {
                        ui.error("Invalid choice.");
                        continue;
                    }

                    boolean adding = input.equals("1");
                    ui.showStockQuantityPrompt(adding);
                    String qtyInput = ui.readText(ui.prompt());
                    while (qtyInput.equalsIgnoreCase("M") || qtyInput.equalsIgnoreCase("C")) {
                        String destination = "Inventory";
                        if (qtyInput.equalsIgnoreCase("M")) {
                            destination = "Dashboard";
                        }
                        if (ui.confirmDiscardTransaction("this stock update", destination)) {
                            break;
                        }
                        ui.showStockQuantityPrompt(adding);
                        qtyInput = ui.readText(ui.prompt());
                    }
                    if (qtyInput.equalsIgnoreCase("M")) {
                        requestDashboardReturn();
                        return;
                    }
                    if (qtyInput.equalsIgnoreCase("C")) {
                        ui.info("Stock update cancelled. No changes were made.");
                        return;
                    }
                    if ((qtyInput.equalsIgnoreCase("B") || qtyInput.equals("0"))) {
                        continue;
                    }

                    int quantity;
                    try {
                        quantity = Integer.parseInt(qtyInput);
                    } catch (NumberFormatException e) {
                        ui.error("Quantity must be a whole number.");
                        continue;
                    }
                    if (quantity < 1) {
                        ui.error("Quantity must be at least 1.");
                        continue;
                    }

                    int previous = game.availableCopies;
                    int newStock;
                    try {
                        newStock = rentalService.updatedStock(game, adding, quantity);
                    }
                    catch (IllegalArgumentException e) {
                        ui.error(e.getMessage());
                        continue;
                    }

                    ui.printHeader("ADMIN > MANAGE INVENTORY > UPDATE STOCK", "CONFIRM STOCK UPDATE");
                    ui.showStockConfirmation(game, previous, newStock);
                    if (!ui.askYesNo("Confirm this stock update?")) {
                        continue;
                    }

                    game.availableCopies = newStock;
                    String action = "Decreased stock";
                    if (adding) {
                        action = "Added stock";
                    }
                    logAdmin(action + ": " + game.title + " (" + game.platform + ") "
                        + previous + " -> " + game.availableCopies);
                    dataStore.saveGames();

                    ui.loading("Updating stock");
                    ui.printHeader("ADMIN > MANAGE INVENTORY > UPDATE STOCK", "STOCK UPDATED");
                    ui.success("Stock updated successfully.");
                    ui.showStockUpdated(game, previous);

                    if (!ui.askYesNo("Do you want to update another game's stock?")) {
                        return;
                    }
                    break;
                }
                if (isDashboardReturnRequested()) {
                    return;
                }
            }
        }
    }
}

