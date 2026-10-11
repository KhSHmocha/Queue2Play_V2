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
    static final int MAX_COPIES_PER_GAME = 5;
    static final int MAX_GAME_STOCK = 20;
    static final double MIN_RENTAL_PRICE = 1.00;
    static final double MAX_RENTAL_PRICE = 10.00;
    static final int NAV_CANCEL = -100;
    static final int NAV_MAIN_MENU = -101;
    static final double LATE_FEE_PER_DAY = 1.00;
    static final double VIP_DISCOUNT = 0.10;
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy | hh:mm a");
    static final DateTimeFormatter TABLE_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a");
    static final DateTimeFormatter LOG_STORAGE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final DateTimeFormatter LOG_DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM dd, yyyy");
    static final DateTimeFormatter LOG_DISPLAY_TIME = DateTimeFormatter.ofPattern("hh:mm a");
    static final int GAME_PAGE_SIZE = 9;
    static final int DETAIL_PAGE_SIZE = 3;
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
            int choice = ui.readNumber(ui.prompt());

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
                    ui.error("Invalid choice. Please enter 1, 2, or 0.");
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
            int totalPages = Math.max(1, (list.size() + GAME_PAGE_SIZE - 1) / GAME_PAGE_SIZE);
            int start = page * GAME_PAGE_SIZE;
            int end = Math.min(start + GAME_PAGE_SIZE, list.size());

            ui.printHeader(title, ConsoleUI.GAME_TABLE_WIDTH);
            ui.showCatalogState(keyword, sortOption, page + 1, totalPages);
            if (list.isEmpty()) {
                ui.showEmptyState("No games found. Use S to search again or X to clear search.");
            } else {
                ui.showGameTableHeader(list.size(), start, end);
                for (int i = start; i < end; i++) {
                    ui.showGameRow(i - start + 1, list.get(i));
                }
                ui.info(ui.tableLine(ConsoleUI.GAME_TABLE_WIDTH));
            }
            ui.showGameNavigation();
            if (selectable) {
                ui.info("Enter a displayed game number to select it.");
            }
            ui.inputBar("ENTER CHOICE", ConsoleUI.GAME_TABLE_WIDTH);
            String choice = ui.readText(ui.prompt(ConsoleUI.GAME_TABLE_WIDTH));

            if (choice.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return null;
            } else if (choice.equals("0")) {
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
            } else if (choice.equalsIgnoreCase("O")) {
                sortOption = (sortOption + 1) % 5;
                page = 0;
            } else if (choice.equalsIgnoreCase("S")) {
                ui.showNavigationOptions(ConsoleUI.GAME_TABLE_WIDTH, new String[] {"[0] Back", "[M] Dashboard"});
                ui.inputBar("SEARCH GAME", ConsoleUI.GAME_TABLE_WIDTH);
                String searchText = ui.readText(ui.prompt(ConsoleUI.GAME_TABLE_WIDTH));
                if (searchText.equalsIgnoreCase("M")) {
                    requestDashboardReturn();
                    return null;
                }
                if (searchText.equals("0")) {
                    continue;
                }
                if (searchText.isEmpty()) {
                    ui.error("Search text cannot be empty.");
                    continue;
                }
                keyword = searchText;
                page = 0;
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
                        selectedQuantity[0] = askQuantity(game, cart.getOrDefault(game, 0));
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
                ui.error("Use S, X, O, N, P, 0, or M.");
            }
        }
    }
    // Shows the platform menu. Returns "ALL", "PS5", "Xbox", "Nintendo" or "BACK"
    String choosePlatform(String heading) {
        while (true) {
            ui.showPlatformMenu(heading);
            String input = ui.readText(ui.prompt());

            if (input.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return "BACK";
            }

            try {
                int choice = Integer.parseInt(input);
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
                    return "BACK";
                }
            } catch (NumberFormatException e) {
                // handled below
            }
            ui.error("Invalid choice. Please enter 1, 2, 3, 4, 0, or M.");
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
            int choice = ui.readNumber(ui.prompt());
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
                    ui.error("Invalid choice. Please enter a number from the menu.");
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
                choice = Integer.parseInt(input);
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
                ui.error("Invalid choice. Please enter a number from the menu.");
            }

            if (isDashboardReturnRequested()) {
                return;
            }
        }
    }

    // Check existing rentals, requests, and the per-game quantity limit.
    Game chooseOneGame(User client, LinkedHashMap<Game, Integer> cart, int[] selectedQuantity) {
        while (true) {
            String platform = choosePlatform("CLIENT > RENT GAMES > SELECT PLATFORM");
            if (isDashboardReturnRequested()) {
                return null;
            }
            if (platform.equals("BACK")) {
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
        }
    }

    private String validateRentalSelection(User client, LinkedHashMap<Game, Integer> cart, Game game) {
        int inCart = cart.getOrDefault(game, 0);
        if (checkoutQueue.hasRequest(client, game)) {
            return "You already have a pending request for this game.";
        }
        if (rentalService.hasActiveRental(client, game)) {
            return "You are already renting this game.";
        }
        if (inCart >= MAX_COPIES_PER_GAME) {
            return "You already have the maximum of "
            + MAX_COPIES_PER_GAME + " copies of this game in your cart.";
        }
        return null;
    }

    // Requests can wait for stock, but the per-game quantity limit still applies.
    // Returns 0 if the client decides not to add this game after all.
    int askQuantity(Game game, int inCart) {
        int maximumToAdd = MAX_COPIES_PER_GAME - inCart;

        while (true) {
            ui.showSelectQuantityScreen(game, inCart, MAX_COPIES_PER_GAME, maximumToAdd);
            String input = ui.readText(ui.prompt());

            if (input.equalsIgnoreCase("C")) {
                if (ui.askYesNo("Cancel this rental transaction?")) {
                    return NAV_CANCEL;
                }
                continue;
            }
            if (input.equalsIgnoreCase("M")) {
                if (ui.askYesNo("Return to Dashboard and discard this rental transaction?")) {
                    requestDashboardReturn();
                    return NAV_MAIN_MENU;
                }
                continue;
            }
            if (input.equals("0")) {
                return 0;
            }

            try {
                int quantity = Integer.parseInt(input);
                if (quantity < 1) {
                    ui.error("Quantity must be at least 1.");
                } else if (quantity > maximumToAdd) {
                    if (inCart + quantity > MAX_COPIES_PER_GAME) {
                        ui.error("Maximum rental quantity is " + MAX_COPIES_PER_GAME
                            + " copies per game. You currently have " + inCart + " in your cart.");
                    } else {
                        ui.error("The request exceeds the per-game quantity limit.");
                    }
                } else {
                    return quantity;
                }
            } catch (NumberFormatException e) {
                ui.error("Enter a whole number, 0, C, or M.");
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
        while (true) {
            ui.printHeader("SELECT RENTAL DURATION");
            ui.showRentalDurationOptions();
            ui.inputBar("ENTER CHOICE");

            String input = ui.readText(ui.prompt());
            if (input.equalsIgnoreCase("C")) {
                if (ui.askYesNo("Cancel this rental transaction?")) {
                    return NAV_CANCEL;
                }
                continue;
            }
            if (input.equalsIgnoreCase("M")) {
                if (ui.askYesNo("Return to Dashboard and discard this rental transaction?")) {
                    requestDashboardReturn();
                    return NAV_MAIN_MENU;
                }
                continue;
            }

            int choice;
            try {
                choice = Integer.parseInt(input);
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
                    ui.showNavigationOptions(new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
                    ui.inputBar("RENTAL DURATION");
                    String daysInput = ui.readText(ui.prompt());
                    if (daysInput.equalsIgnoreCase("C")) {
                        if (ui.askYesNo("Cancel this rental transaction?")) {
                            return NAV_CANCEL;
                        }
                        continue;
                    }
                    if (daysInput.equalsIgnoreCase("M")) {
                        if (ui.askYesNo("Return to Dashboard and discard this rental transaction?")) {
                            requestDashboardReturn();
                            return NAV_MAIN_MENU;
                        }
                        continue;
                    }
                    if (daysInput.equals("0")) {
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
                ui.error("Invalid choice. Please enter a number from the menu.");
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
                if (ui.askYesNo("Return to Dashboard and discard this rental transaction?")) {
                    requestDashboardReturn();
                    return NAV_MAIN_MENU;
                }
                continue;
            }

            int choice;
            try {
                choice = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                ui.error("Invalid choice.");
                continue;
            }

            if (choice == 1) {
                int status = addGamesToCart(client, cart);
                if (status == NAV_CANCEL || status == NAV_MAIN_MENU) {
                    return status;
                }
            } else if (choice == 2) {
                ui.showNavigationOptions(ConsoleUI.EDIT_ORDER_TABLE_WIDTH, new String[] {"[0] Back", "[C] Cancel Transaction", "[M] Dashboard"});
                ui.inputBar("LINE NUMBER", ConsoleUI.EDIT_ORDER_TABLE_WIDTH);
                String removeInput = ui.readText(ui.prompt(ConsoleUI.EDIT_ORDER_TABLE_WIDTH));
                if (removeInput.equalsIgnoreCase("C")) {
                    if (ui.askYesNo("Cancel this rental transaction and discard the cart?")) {
                        return NAV_CANCEL;
                    }
                    continue;
                }
                if (removeInput.equalsIgnoreCase("M")) {
                    if (ui.askYesNo("Return to Dashboard and discard this rental transaction?")) {
                        requestDashboardReturn();
                        return NAV_MAIN_MENU;
                    }
                    continue;
                }
                try {
                    int number = Integer.parseInt(removeInput);
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
                int newDays = chooseRentalDays();
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
        LinkedHashMap<Game, Integer> cart = new LinkedHashMap<>();
        int step = 1;
        int days = RENTAL_DAYS;
        while (true) {
            if (step == 1) {
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

            if (choice.equals("0")) {
                step = 2;
            } else if (choice.equals("1")) {
                if (!validateCartBeforeSubmission(client, cart)) {
                    ui.warning("Please edit the order before submitting.");
                    continue;
                }
                break;
            } else if (choice.equals("2")) {
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
                if (ui.askYesNo("Return to Dashboard and discard this rental transaction?")) {
                    requestDashboardReturn();
                    return;
                }
            } else {
                ui.error("Invalid choice. Please enter 1, 2, 0, C, or M.");
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
        for (RentalRequest r : submitted) {
            ui.showQueueRequestRow(checkoutQueue.getPosition(r), r);
        }
        ui.info(ui.tableLine(ConsoleUI.QUEUE_TABLE_WIDTH));
        ui.waitForEnter();
    }
    void cancelMyRequest(User client) {
        boolean choosingRequest = false;
        while (true) {
            ArrayList<RentalRequest> myRequests = checkoutQueue.getRequestsOf(client);
            if (myRequests.isEmpty()) {
                ui.printHeader("CLIENT > QUEUE REQUESTS", "MY QUEUE REQUESTS");
                ui.showEmptyState("You have no pending queue requests.");
                ui.waitForEnter();
                return;
            }

            ui.printHeader("CLIENT > QUEUE REQUESTS", "MY QUEUE REQUESTS", ConsoleUI.QUEUE_TABLE_WIDTH);
            ui.showQueueRequestHeader();
            for (RentalRequest request : myRequests) {
                ui.showQueueRequestRow(checkoutQueue.getPosition(request), request);
            }
            ui.info(ui.tableLine(ConsoleUI.QUEUE_TABLE_WIDTH));

            if (!choosingRequest) {
                ui.showQueueCancellationOptions();
                String choice = ui.readText(ui.prompt(ConsoleUI.QUEUE_TABLE_WIDTH));
                if (choice.equalsIgnoreCase("M")) {
                    requestDashboardReturn();
                    return;
                }
                if (choice.equals("0")) {
                    return;
                }
                if (!choice.equals("1")) {
                    ui.error("Invalid choice. Please enter 1, 0, or M.");
                    continue;
                }
                choosingRequest = true;
                continue;
            }

            ui.showNavigationOptions(ConsoleUI.QUEUE_TABLE_WIDTH, new String[] {"[0] Back to My Requests", "[M] Dashboard"});
            ui.inputBar("QUEUE NUMBER TO CANCEL", ConsoleUI.QUEUE_TABLE_WIDTH);
            String input = ui.readText(ui.prompt(ConsoleUI.QUEUE_TABLE_WIDTH));
            if (input.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return;
            }
            if (input.equals("0")) {
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
                ui.waitForEnter(ConsoleUI.QUEUE_TABLE_WIDTH);
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
            ui.waitForEnter();
            return;
        }

        ArrayList<Rental> history = new ArrayList<>();
        for (int i = client.rentalHistory.size() - 1; i >= 0; i--) {
            history.add(client.rentalHistory.get(i));
        }

        int page = 0;
        int totalPages = (history.size() + DETAIL_PAGE_SIZE - 1) / DETAIL_PAGE_SIZE;
        while (true) {
            int start = page * DETAIL_PAGE_SIZE;
            int end = Math.min(start + DETAIL_PAGE_SIZE, history.size());
            ui.printHeader("RENTAL HISTORY (Most Recent First)", ConsoleUI.HISTORY_TABLE_WIDTH);
            ui.showHistoryTableHeader(history.size(), start, end);

            for (int i = start; i < end; i++) {
                Rental rental = history.get(i);
                ui.showHistoryRow(rental);
            }
            ui.info(ui.tableLine(ConsoleUI.HISTORY_TABLE_WIDTH));

            if (page < totalPages - 1) {
                ui.info("[N] Next Page");
            }
            if (page > 0) {
                ui.info("[P] Previous Page");
            }
            ui.showBackToDashboard();
            ui.inputBar("ENTER CHOICE", ConsoleUI.HISTORY_TABLE_WIDTH);
            String choice = ui.readText(ui.prompt(ConsoleUI.HISTORY_TABLE_WIDTH));
            if (choice.equalsIgnoreCase("N") && page < totalPages - 1) {
                page++;
            }
            else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            }
            else if (choice.equals("0")) {
                return;
            }
            else ui.error("Invalid choice.");
        }
    }

    void upgradeToVip(User client) {
        if (client.isVip()) {
            ui.printHeader("CLIENT > MEMBERSHIP", "VIP MEMBERSHIP");
            ui.showEmptyState("You are already a VIP member.");
            ui.waitForEnter();
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
                ui.waitForEnter();
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
            int choice = ui.readNumber(ui.prompt());
            try {
                if (choice == 1) {
                    checkoutManagement();
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
                    ui.error("Invalid choice. Please enter a number from the menu.");
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
            } else if (input.equals("0")) {
                return;
            } else {
                ui.error("Invalid choice. Please enter 1, 2, or 0.");
            }
            if (isDashboardReturnRequested()) {
                return;
            }
        }
    }

    // Queue list, eligible request, approval, and cancellation stay on one screen.
    void checkoutManagement() {
        boolean cancelling = false;
        while (true) {
            ui.printHeader("ADMIN > CHECKOUT MANAGEMENT", "CHECKOUT QUEUE", ConsoleUI.CHECKOUT_QUEUE_TABLE_WIDTH);
            ui.showQueue(checkoutQueue);
            RentalRequest next = checkoutQueue.findNextEligibleRequest();
            if (next != null) {
                ui.showNextCheckout(rentalService.calculateRentalCost(next.client, next.game,
                    next.rentalDays, next.quantity), next);
            } else if (!checkoutQueue.isEmpty()) {
                ui.info("All requests are waiting for enough stock.");
            }

            if (cancelling) {
                ui.info("Enter a queue number to cancel. [0] Back  [M] Dashboard");
                ui.inputBar("QUEUE NUMBER TO CANCEL", ConsoleUI.CHECKOUT_QUEUE_TABLE_WIDTH);
            } else {
                ui.showCheckoutActions();
            }
            String choice = ui.readText(ui.prompt(ConsoleUI.CHECKOUT_QUEUE_TABLE_WIDTH));
            if (choice.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return;
            }
            if (choice.equals("0")) {
                if (cancelling) {
                    cancelling = false;
                    continue;
                }
                return;
            }
            if (cancelling) {
                RentalRequest request;
                try {
                    request = checkoutQueue.findByNumber(Integer.parseInt(choice));
                } catch (NumberFormatException e) {
                    ui.error("Enter a valid queue number.");
                    continue;
                }
                if (request == null) {
                    ui.error("Request not found.");
                    continue;
                }
                if (ui.askYesNo("Cancel request " + ui.queueLabel(request.queueNumber)
                        + " for " + request.client.username + "?")) {
                    checkoutQueue.remove(request);
                    logAdmin("Cancelled queue request " + ui.queueLabel(request.queueNumber)
                        + ": " + request.client.username);
                    dataStore.saveQueue();
                    ui.success("Request cancelled and saved.");
                }
                cancelling = false;
            } else if (choice.equals("1")) {
                if (next == null) {
                    ui.warning("No request has enough stock for checkout.");
                    continue;
                }
                if (ui.askYesNo("Approve checkout " + ui.queueLabel(next.queueNumber)
                        + " for " + next.client.username + "?")) {
                    approveCheckout(next);
                }
            } else if (choice.equals("2")) {
                if (checkoutQueue.isEmpty()) {
                    ui.warning("The checkout queue is empty.");
                } else {
                    cancelling = true;
                }
            } else {
                ui.error("Use 1, 2, 0, or M.");
            }
        }
    }
    void approveCheckout(RentalRequest request) {
        String error = rentalService.validateCheckout(request);
        if (error != null) {
            ui.error(error);
            return;
        }
        Rental rental = rentalService.checkout(request, nextRentalNumber);
        nextRentalNumber++;
        logAdmin("Processed checkout: " + request.client.username
            + " - " + request.game.title + " x" + request.quantity);

        dataStore.saveData();
        ui.showCheckoutApproved(rental);
    }

    Rental selectActiveRentalForReturn() {
        ArrayList<Rental> rentals = rentalService.getRentalsByDueDate();
        if (rentals.isEmpty()) {
            return null;
        }

        int page = 0;
        int pageSize = 10;
        int totalPages = (rentals.size() + pageSize - 1) / pageSize;

        while (true) {
            int start = page * pageSize;
            int end = Math.min(start + pageSize, rentals.size());
            ui.printHeader("ADMIN > RETURNS", "SELECT RENTAL TO RETURN", ConsoleUI.RETURN_TABLE_WIDTH);
            ui.showReturnTableHeader(rentals.size(), start, end);

            for (int i = start; i < end; i++) {
                Rental rental = rentals.get(i);
                int shown = i - start + 1;
                ui.showReturnRow(shown, rental);
            }
            ui.info(ui.tableLine(ConsoleUI.RETURN_TABLE_WIDTH));

            ui.info("");
            if (page < totalPages - 1) {
                ui.info("[N] Next Page");
            }
            if (page > 0) {
                ui.info("[P] Previous Page");
            }
            ui.showBackToDashboard();
            ui.inputBar("ENTER CHOICE", ConsoleUI.RETURN_TABLE_WIDTH);
            String choice = ui.readText(ui.prompt(ConsoleUI.RETURN_TABLE_WIDTH));

            if (choice.equalsIgnoreCase("N") && page < totalPages - 1) {
                page++;
            } else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            } else if (choice.equals("0")) {
                return null;
            } else {
                try {
                    int number = Integer.parseInt(choice);
                    if (number >= 1 && number <= end - start) {
                        return rentals.get(start + number - 1);
                    }
                    ui.error("Invalid rental selection.");
                } catch (NumberFormatException e) {
                    ui.error("Invalid choice.");
                }
            }
        }
    }

    void processReturn() {
        while (true) {
            Rental found = selectActiveRentalForReturn();
            if (found == null) {
                if (activeRentals.isEmpty()) {
                    ui.printHeader("RETURNS");
                    ui.showEmptyState("No active rentals.");
                    ui.waitForEnter();
                }
                return;
            }
            LocalDateTime returnedAt = LocalDateTime.now();
            int daysLate = RentalService.daysOverdue(found.dueDate, returnedAt);
            double lateFee = rentalService.calculateLateFee(found, daysLate);
            ui.printHeader("ADMIN > RETURNS", "CONFIRM RETURN");
            ui.showReturnDetails(found);
            ui.showReturnConfirmation(found, daysLate, lateFee);
            String confirm = ui.readText(ui.prompt());
            if (confirm.equals("0")) {
                continue;
            }
            if (confirm.equalsIgnoreCase("C")) {
                return;
            }
            if (confirm.equalsIgnoreCase("M")) {
                requestDashboardReturn();
                return;
            }
            if (!confirm.equals("1")) {
                ui.error("Enter 1, 0, C, or M.");
                continue;
            }
            rentalService.completeReturn(found, returnedAt);
            logAdmin("Processed return: " + found.client.username + " - " + found.game.title
                + " x" + found.quantity + "; days late " + daysLate + "; fee " + ui.money(lateFee));
            dataStore.saveData();
            ui.printHeader("ADMIN > RETURNS", "RETURN PROCESSED");
            ui.success("Return and late fee saved. Payment collection is handled separately.");
            ui.showReturnResult(found, daysLate, lateFee);
            if (!ui.askYesNo("Process another return?")) {
                return;
            }
        }
    }

    void viewActiveRentals() {
        ArrayList<Rental> rentals = rentalService.getRentalsByDueDate();
        if (rentals.isEmpty()) {
            ui.printHeader("ADMIN > ACTIVE RENTALS", "ACTIVE RENTALS");
            ui.showEmptyState("There are no active rentals.");
            ui.waitForEnter();
            return;
        }

        int page = 0;
        int totalPages = (rentals.size() + DETAIL_PAGE_SIZE - 1) / DETAIL_PAGE_SIZE;
        while (true) {
            int start = page * DETAIL_PAGE_SIZE;
            int end = Math.min(start + DETAIL_PAGE_SIZE, rentals.size());
            ui.printHeader("ACTIVE RENTALS - Earliest Due Date First", ConsoleUI.ACTIVE_TABLE_WIDTH);
            ui.showActiveRentalHeader(rentals.size(), start, end);

            for (int i = start; i < end; i++) {
                Rental rental = rentals.get(i);
                ui.showActiveRentalRow(rental);
            }
            ui.info(ui.tableLine(ConsoleUI.ACTIVE_TABLE_WIDTH));

            if (page < totalPages - 1) {
                ui.info("[N] Next Page");
            }
            if (page > 0) {
                ui.info("[P] Previous Page");
            }
            ui.showBackOptions();
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
            else if (choice.equals("0")) {
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
            ui.waitForEnter();
            return;
        }

        int page = 0;
        int totalPages = (rentals.size() + DETAIL_PAGE_SIZE - 1) / DETAIL_PAGE_SIZE;
        while (true) {
            int start = page * DETAIL_PAGE_SIZE;
            int end = Math.min(start + DETAIL_PAGE_SIZE, rentals.size());
            ui.printHeader("OVERDUE RENTALS - Earliest Due Date First", ConsoleUI.OVERDUE_TABLE_WIDTH);
            ui.showOverdueRentalHeader(rentals.size(), start, end);

            for (int i = start; i < end; i++) {
                Rental rental = rentals.get(i);
                long daysOverdue = RentalService.daysOverdue(rental.dueDate, now);
                ui.showOverdueRentalRow(daysOverdue * LATE_FEE_PER_DAY * rental.quantity, rental, daysOverdue);
            }
            ui.info(ui.tableLine(ConsoleUI.OVERDUE_TABLE_WIDTH));

            Rental top = rentals.get(0);
            ui.showEarliestDueRental(top);
            if (page < totalPages - 1) {
                ui.info("[N] Next Page");
            }
            if (page > 0) {
                ui.info("[P] Previous Page");
            }
            ui.showBackOptions();
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
            else if (choice.equals("0")) {
                return;
            }
            else ui.error("Invalid choice.");
        }
    }

    void viewAuditLog() {
        if (auditLog.isEmpty()) {
            ui.printHeader("ADMIN > AUDIT LOG", "ADMIN AUDIT LOG");
            ui.showEmptyState("No actions have been recorded yet.");
            ui.waitForEnter();
            return;
        }

        ArrayList<String> logs = new ArrayList<>();
        for (int i = auditLog.size() - 1; i >= 0; i--) {
            logs.add(auditLog.get(i));
        }

        int page = 0;
        int totalPages = (logs.size() + LOG_PAGE_SIZE - 1) / LOG_PAGE_SIZE;
        while (true) {
            int start = page * LOG_PAGE_SIZE;
            int end = Math.min(start + LOG_PAGE_SIZE, logs.size());
            ui.printHeader("ADMIN AUDIT LOG (Most Recent First)", ConsoleUI.AUDIT_TABLE_WIDTH);
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
            if (page < totalPages - 1) {
                ui.info("[N] Next Page");
            }
            if (page > 0) {
                ui.info("[P] Previous Page");
            }
            ui.showBackToDashboard();
            ui.inputBar("ENTER CHOICE", ConsoleUI.AUDIT_TABLE_WIDTH);
            String choice = ui.readText(ui.prompt(ConsoleUI.AUDIT_TABLE_WIDTH));
            if (choice.equalsIgnoreCase("N") && page < totalPages - 1) {
                page++;
            }
            else if (choice.equalsIgnoreCase("P") && page > 0) {
                page--;
            }
            else if (choice.equals("0")) {
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
                choice = Integer.parseInt(input);
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
                ui.error("Invalid choice. Please enter 1, 2, 3, 4, or 0.");
            }

            if (isDashboardReturnRequested()) {
                return;
            }
        }
    }

    void addGame() {
        while (true) {
            String title = "";
            String platform = "";
            double price = 0;
            int copies = 0;
            int step = 1;

            while (true) {
                if (step == 1) {
                    ui.printHeader("ADMIN > MANAGE INVENTORY > ADD GAME", "ADD GAME");
                    ui.showAddGameInstructions();
                    ui.inputBar("GAME TITLE");
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C") || input.equals("0")) {
                        ui.info("Add-game transaction cancelled. No changes were made.");
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
                    title = input;
                    step = 2;
                } else if (step == 2) {
                    ui.printHeader("ADD GAME", "SELECT PLATFORM");
                    ui.showPlatformOptions();
                    ui.inputBar("ENTER CHOICE");
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        ui.info("Add-game transaction cancelled. No changes were made.");
                        return;
                    }
                    if (input.equals("0")) {
                        step = 1;
                        continue;
                    }
                    if (input.equals("1")) {
                        platform = "PS5";
                    }
                    else if (input.equals("2")) {
                        platform = "Xbox";
                    }
                    else if (input.equals("3")) {
                        platform = "Nintendo";
                    }
                    else {
                        ui.error("Invalid platform choice.");
                        continue;
                    }

                    boolean duplicate = false;
                    for (Game game : games.values()) {
                        if (game.title.equalsIgnoreCase(title) && game.platform.equals(platform)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) {
                        ui.error("That game already exists on " + platform + ". Update its stock instead.");
                        step = 1;
                        continue;
                    }
                    step = 3;
                } else if (step == 3) {
                    ui.printHeader("ADD GAME", "RENTAL PRICE");
                    ui.showPriceInstructions();
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        ui.info("Add-game transaction cancelled. No changes were made.");
                        return;
                    }
                    if (input.equals("0")) {
                        step = 2;
                        continue;
                    }
                    try {
                        price = Double.parseDouble(input);
                    } catch (NumberFormatException e) {
                        ui.error("Enter a valid price.");
                        continue;
                    }
                    if (!Double.isFinite(price) || price < MIN_RENTAL_PRICE || price > MAX_RENTAL_PRICE) {
                        ui.error("Rental price must be between " + ui.money(MIN_RENTAL_PRICE)
                            + " and " + ui.money(MAX_RENTAL_PRICE) + ".");
                        continue;
                    }
                    step = 4;
                } else if (step == 4) {
                    ui.printHeader("ADD GAME", "AVAILABLE COPIES");
                    ui.showStockInstructions();
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        ui.info("Add-game transaction cancelled. No changes were made.");
                        return;
                    }
                    if (input.equals("0")) {
                        step = 3;
                        continue;
                    }
                    try {
                        copies = Integer.parseInt(input);
                    } catch (NumberFormatException e) {
                        ui.error("Enter a whole number of copies.");
                        continue;
                    }
                    if (copies < 1 || copies > MAX_GAME_STOCK) {
                        ui.error("Stock must be between 1 and " + MAX_GAME_STOCK + " copies.");
                        continue;
                    }
                    step = 5;
                } else {
                    ui.printHeader("ADMIN > MANAGE INVENTORY > ADD GAME", "CONFIRM NEW GAME");
                    ui.showNewGameConfirmation(title, platform, price, copies);
                    ui.inputBar("ENTER CHOICE");
                    String input = ui.readText(ui.prompt());
                    if (input.equalsIgnoreCase("M")) {
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        ui.info("Add-game transaction cancelled. No changes were made.");
                        return;
                    }
                    if (input.equals("2")) {
                        step = 1;
                        continue;
                    }
                    if (input.equals("0")) {
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
                        requestDashboardReturn();
                        return;
                    }
                    if (input.equalsIgnoreCase("C")) {
                        ui.info("Stock update cancelled. No changes were made.");
                        return;
                    }
                    if (input.equals("0")) {
                        break;
                    }
                    if (!input.equals("1") && !input.equals("2")) {
                        ui.error("Invalid choice.");
                        continue;
                    }

                    boolean adding = input.equals("1");
                    ui.showStockQuantityPrompt(adding);
                    String qtyInput = ui.readText(ui.prompt());
                    if (qtyInput.equalsIgnoreCase("M")) {
                        requestDashboardReturn();
                        return;
                    }
                    if (qtyInput.equalsIgnoreCase("C")) {
                        ui.info("Stock update cancelled. No changes were made.");
                        return;
                    }
                    if (qtyInput.equals("0")) {
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

