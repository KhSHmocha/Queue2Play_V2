import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

// Run with the commands in tests/README.md. Test data never uses the real data folder.
public class BehaviorRegressionTest {
    static int checks;
    static Path testRoot;

    static class ScriptedUI extends ConsoleUI {
        String[] choices;
        int nextChoice;
        ArrayList<String> screens = new ArrayList<>();
        ArrayList<String> rows = new ArrayList<>();

        ScriptedUI(String[] choices) {
            this.choices = choices;
        }

        String readText(String prompt) {
            if (nextChoice >= choices.length) {
                throw new AssertionError("Unexpected input prompt: " + prompt);
            }
            return choices[nextChoice++];
        }

        void showCatalogState(String keyword, int sort, int page, int pages) {
            screens.add(keyword + ":" + sort + ":" + page + ":" + pages);
            super.showCatalogState(keyword, sort, page, pages);
        }

        void showGameRow(int number, Game game) {
            rows.add(number + ":" + game.gameId);
            super.showGameRow(number, game);
        }

        void waitForEnter() { }
        void waitForEnter(int width) { }
        void loading(String message) { }
        void pause(int milliseconds) { }
    }

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static GameRentalSystem freshSystem(String name) {
        System.setProperty("q2p.dataDir", testRoot.resolve(name).toString());
        return new GameRentalSystem();
    }

    static ScriptedUI script(GameRentalSystem system, String[] choices) {
        ScriptedUI ui = new ScriptedUI(choices);
        system.ui = ui;
        return ui;
    }

    static User client(String name, String membership) {
        return new User(name, "Test Client", name + "@example.com", "TestPassword123", "CLIENT", membership);
    }

    static RentalRequest enqueue(GameRentalSystem system, User user, Game game, int quantity) {
        RentalRequest request = new RentalRequest(system.nextQueueNumber++, user, game);
        request.quantity = quantity;
        request.rentalDays = 3;
        system.checkoutQueue.enqueue(request);
        return request;
    }

    static void catalogTests() {
        GameRentalSystem system = freshSystem("catalog");
        system.gameCatalog.loadStartingGames();
        ArrayList<Game> all = system.gameCatalog.getGameList("ALL");
        check(system.gameCatalog.linearSearch(all, "p001").size() == 1, "Exact ID ignores case");
        check(system.gameCatalog.linearSearch(all, "P00").isEmpty(), "Partial ID must not match");
        check(system.gameCatalog.linearSearch(all, "MINE").size() == 2, "Partial title across platforms");
        check(system.gameCatalog.linearSearch(all, "xBoX").size() == 3, "Complete platform ignores case");
        check(system.gameCatalog.linearSearch(all, "Xbo").isEmpty(), "Partial platform must not match");
        check(system.gameCatalog.linearSearch(system.gameCatalog.getGameList("PS5"), "Xbox").isEmpty(),
            "Search stays in selected platform");
        for (int sort = 1; sort <= 4; sort++) {
            ArrayList<Game> sorted = system.gameCatalog.getDisplayList(all, "", sort);
            for (int i = 1; i < sorted.size(); i++) {
                Game left = sorted.get(i - 1);
                Game right = sorted.get(i);
                if (sort == 1) check(left.title.compareToIgnoreCase(right.title) <= 0, "Title ascending");
                if (sort == 2) check(left.title.compareToIgnoreCase(right.title) >= 0, "Title descending");
                if (sort == 3) check(left.price <= right.price, "Price ascending");
                if (sort == 4) check(left.price >= right.price, "Price descending");
            }
            check(sorted.indexOf(system.games.get("P002")) < sorted.indexOf(system.games.get("X003")),
                "Equal titles and prices retain arrival order");
        }
        check(system.gameCatalog.getGameList("ALL").equals(all), "Sort leaves inventory order unchanged");
        check(system.gameCatalog.getDisplayList(all, "", 0).equals(all), "Default restores original order");
        check(system.gameCatalog.getDisplayList(new ArrayList<Game>(), "", 4).isEmpty(), "Empty sort works");
        for (int i = 0; i < 4; i++) system.gameCatalog.addGame("Extra " + i, "PS5", 2, 1);
        ScriptedUI ui = script(system, new String[] {"N", "S", "minecraft", "S", "Xbox", "O", "X",
            "N", "O", "O", "O", "O", "S", "missing", "S", "PS5", "P", "0"});
        system.viewGames("ALL");
        check(ui.screens.contains(":0:2:2"), "Next page has remaining games");
        check(ui.screens.contains("Xbox:0:1:1"), "Second search uses full list");
        check(ui.screens.contains(":1:1:2"), "Clear search retains sort and resets page");
        check(ui.screens.contains("missing:0:1:1"), "Zero results stay on the same screen");
        check(ui.screens.contains("PS5:0:1:1"), "Can search again after no match");
        check(ui.rows.contains("1:P004"), "Page two numbers start at one");
        ui = script(system, new String[] {"N", "1"});
        check(system.paginateGames(system.gameCatalog.getGameList("ALL"), "SELECT GAME", true)
            == system.games.get("P004"), "Selection resolves displayed page number");
        script(system, new String[] {"1", "0"});
        system.viewGames("ALL");
        script(system, new String[] {"M"});
        system.viewGames("PS5");
        check(system.isDashboardReturnRequested(), "M returns to dashboard");
    }

    static void orderTests() {
        GameRentalSystem system = freshSystem("order");
        system.gameCatalog.loadStartingGames();
        User regular = client("regular", "REGULAR");
        system.users.add(regular);
        script(system, new String[] {"1", "1", "2", "Y", "1", "1", "4", "3", "Y",
            "1", "4", "1", "N", "2", "1"});
        system.placeInQueue(regular);
        check(system.checkoutQueue.getRequests().size() == 2, "One request per distinct game");
        RentalRequest first = system.checkoutQueue.getRequests().get(0);
        check(first.quantity == 5, "Repeated selections combine and reject more than five");
        check(first.game.availableCopies == 2, "Queue confirmation does not deduct or limit to stock");
        check(first.rentalDays == 3, "Chosen duration applied to requests");
        LinkedHashMap<Game, Integer> cart = new LinkedHashMap<>();
        cart.put(first.game, 1);
        check(system.rentalService.validateCart(regular, cart) != null, "Queued game cannot be requested again");
        cart.clear();
        cart.put(system.games.get("P003"), 5);
        check(system.rentalService.validateCart(regular, cart) == null, "Cart accepts quantity above current stock");
        script(system, new String[] {"2", "1", "3", "3", "0"});
        check(system.editOrder(regular, cart, 3) == 7 && cart.isEmpty(), "Remove whole line and edit duration");
        int[] durations = {1, 3, 7};
        for (int i = 0; i < durations.length; i++) {
            script(system, new String[] {String.valueOf(i + 1)});
            check(system.chooseRentalDays() == durations[i], "Preset duration");
        }
        script(system, new String[] {"4", "31", "-1", "abc", "30"});
        check(system.chooseRentalDays() == 30, "Custom duration rejects invalid inputs and accepts 30");
        script(system, new String[] {"4", "1"});
        check(system.chooseRentalDays() == 1, "Custom duration accepts 1");
        GameRentalSystem cancelled = freshSystem("cancelled-order");
        cancelled.gameCatalog.loadStartingGames();
        script(cancelled, new String[] {"1", "1", "1", "N", "1", "C", "Y"});
        cancelled.placeInQueue(regular);
        check(cancelled.checkoutQueue.isEmpty() && cancelled.games.get("P001").availableCopies == 2,
            "Cancelled order leaves queue and stock untouched");
    }

    static void queueTests() {
        GameRentalSystem system = freshSystem("queue");
        User vip = client("vipclient", "VIP");
        User regular = client("regclient", "REGULAR");
        system.users.add(vip);
        system.users.add(regular);
        Game waitingGame = system.gameCatalog.addGame("Waiting", "PS5", 5, 0);
        Game game = system.gameCatalog.addGame("Ready", "Xbox", 5, 20);
        RentalRequest waiting = enqueue(system, vip, waitingGame, 2);
        RentalRequest regularRequest = enqueue(system, regular, game, 1);
        // Different games allow the same VIP to hold several distinct requests.
        RentalRequest[] vipRequests = new RentalRequest[4];
        for (int i = 0; i < 4; i++) {
            Game vipGame = system.gameCatalog.addGame("VIP Game " + i, "Nintendo", 5, 2);
            vipRequests[i] = enqueue(system, vip, vipGame, 1);
        }
        for (int i = 0; i < 3; i++) {
            check(system.checkoutQueue.findNextEligibleRequest() == vipRequests[i], "VIP FIFO skips unavailable request");
            system.rentalService.checkout(vipRequests[i], system.nextRentalNumber++);
        }
        check(system.checkoutQueue.findNextEligibleRequest() == regularRequest, "Regular gets turn after three VIP checkouts");
        Rental rental = system.rentalService.checkout(regularRequest, system.nextRentalNumber++);
        check(system.checkoutQueue.vipStreak == 0, "Regular checkout resets streak");
        check(game.availableCopies == 19 && rental.quantity == 1 && rental.amount == 5,
            "Checkout records quantity and price and deducts stock");
        check(rental.dueDate.equals(rental.checkoutDate.plusDays(3)), "Checkout records duration and dates");
        check(system.activeRentals.contains(rental) && regular.rentalHistory.peek() == rental,
            "Checkout adds active rental and history");
        check(system.checkoutQueue.findByNumber(regularRequest.queueNumber) == null, "Checkout removes request");
        check(system.rentalService.validateCheckout(regularRequest) != null, "Double processing rejected");
        check(system.checkoutQueue.findByNumber(waiting.queueNumber) == waiting, "Unavailable request stays queued");
        waitingGame.availableCopies = 2;
        check(system.checkoutQueue.findNextEligibleRequest() == waiting, "Earlier waiting VIP regains priority when stocked");
        waitingGame.availableCopies = 0;
        LinkedHashMap<Game, Integer> cart = new LinkedHashMap<>();
        cart.put(game, 1);
        check(system.rentalService.validateCart(regular, cart) != null, "Active game cannot be requested again");
        check(vip.rentalHistory.peek().amount == 4.5, "VIP discount recorded");
        system.dataStore.saveData();
        script(system, new String[] {"1", "N", "2", String.valueOf(waiting.queueNumber), "N",
            "2", String.valueOf(waiting.queueNumber), "Y", "M"});
        system.checkoutManagement();
        check(system.checkoutQueue.findByNumber(waiting.queueNumber) == null, "Cancel any waiting request from shared queue screen");
        check(system.checkoutQueue.vipStreak == 0, "Declined approval and cancellation do not count as checkout");
        check(system.isDashboardReturnRequested(), "Queue screen supports M");
        GameRentalSystem loaded = freshSystem("queue");
        loaded.accountService.loadAccounts();
        loaded.dataStore.loadGames();
        loaded.dataStore.loadRentals();
        loaded.dataStore.loadQueue();
        check(loaded.activeRentals.size() == 4 && loaded.allRentals.size() == 4, "Saved checkout reloads rentals");
        check(loaded.games.get(game.gameId).availableCopies == 19, "Saved checkout reloads stock");
        check(loaded.checkoutQueue.getRequests().size() == 1, "Saved cancellation reloads queue");
        script(loaded, new String[] {"1", "Y", "0"});
        loaded.checkoutManagement();
        check(loaded.checkoutQueue.isEmpty() && loaded.activeRentals.size() == 5, "Approve from shared screen saves checkout");
        script(loaded, new String[] {"1", "2", "0"});
        loaded.checkoutManagement();
        check(loaded.checkoutQueue.isEmpty(), "Empty queue controls stay usable");
        CheckoutQueue queue = new CheckoutQueue();
        RentalRequest unavailableRegular = new RentalRequest(1, regular, waitingGame);
        RentalRequest eligibleRegular = new RentalRequest(2, regular, game);
        queue.enqueue(unavailableRegular);
        queue.enqueue(eligibleRegular);
        queue.vipStreak = 3;
        check(queue.findNextEligibleRequest() == eligibleRegular, "Fairness skips unavailable regular requests");
        queue.remove(eligibleRegular);
        RentalRequest availableVip = new RentalRequest(3, vip, game);
        queue.enqueue(availableVip);
        check(queue.findNextEligibleRequest() == availableVip, "VIP can proceed when regular requests cannot");
        check(queue.vipStreak == 3, "Viewing next request does not change streak");
    }

    public static void main(String[] args) throws Exception {
        new ConsoleUI(); // Initialize the UTF-8 output stream before capturing TUI output.
        PrintStream originalOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, "UTF-8"));
        testRoot = Files.createTempDirectory(Path.of(".build"), "behavior-tests-");
        catalogTests();
        orderTests();
        queueTests();
        String terminal = output.toString("UTF-8");
        if (System.getenv("NO_COLOR") != null || "false".equals(System.getProperty("q2p.color"))) {
            check(!terminal.contains("\u001B[1;94m"), "NO_COLOR disables platform colors");
            check(terminal.contains("PS5") && terminal.contains("Xbox") && terminal.contains("Nintendo"),
                "Plain platform labels remain visible");
        } else {
            check(terminal.contains("\u001B[1;94mPS5\u001B[0m"), "PS5 bright blue");
            check(terminal.contains("\u001B[1;92mXbox\u001B[0m"), "Xbox bright green");
            check(terminal.contains("\u001B[1;91mNintendo\u001B[0m"), "Nintendo bright red");
        }
        System.setOut(originalOutput);
        System.out.println("PASS: " + checks + " checks. Isolated data: " + testRoot);
    }
}
