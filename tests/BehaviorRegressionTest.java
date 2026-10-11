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
    static ScriptedUI latestUI;

    static class ScriptedUI extends ConsoleUI {
        String[] choices;
        int nextChoice;
        ArrayList<String> screens = new ArrayList<>();
        ArrayList<String> rows = new ArrayList<>();
        int clears;
        int receipts;
        int waits;
        int queueScreens;
        int lastApproved;
        int lastCancelled;
        double lastTotal;
        ArrayList<Integer> tapeRentals = new ArrayList<>();
        ArrayList<Integer> tapeCancellations = new ArrayList<>();
        ArrayList<Integer> batchNumbers = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        ArrayList<String> tapeEvents = new ArrayList<>();
        int statusBoxes;
        int inputPanels;
        ArrayList<Integer> slipNumbers = new ArrayList<>();

        void clearScreen() {
            clears++;
            super.clearScreen();
        }

        void error(String message) {
            errors.add(message);
            super.error(message);
        }

        void showCheckoutQueue(CheckoutQueue queue, RentalService service) {
            queueScreens++;
            super.showCheckoutQueue(queue, service);
        }

        void showApprovedSlip(Rental rental, int number) {
            tapeRentals.add(rental.rentalNumber);
            slipNumbers.add(number);
            tapeEvents.add("approved:" + number);
            super.showApprovedSlip(rental, number);
        }

        void showCancelledSlip(RentalRequest request, int number) {
            tapeCancellations.add(request.queueNumber);
            slipNumbers.add(number);
            tapeEvents.add("cancelled:" + number);
            super.showCancelledSlip(request, number);
        }

        void showSessionStatus(GameRentalSystem.CheckoutSession session, CheckoutQueue queue, RentalService service) {
            statusBoxes++;
            tapeEvents.add("status");
            super.showSessionStatus(session, queue, service);
        }

        void showTapeInput() {
            super.showTapeInput();
            inputPanels++;
            tapeEvents.add("input");
        }

        void showMessageCard(String message) {
            tapeEvents.add("message:" + message);
            super.showMessageCard(message);
        }

        void showCheckoutTapeFooter(GameRentalSystem.CheckoutSession session, CheckoutQueue queue, RentalService service) {
            lastApproved = session.approved;
            lastCancelled = session.cancelled;
            lastTotal = session.total;
            super.showCheckoutTapeFooter(session, queue, service);
        }

        void showCheckoutReceipt(GameRentalSystem.CheckoutSession session, CheckoutQueue queue) {
            receipts++;
            super.showCheckoutReceipt(session, queue);
        }

        void showCheckoutBatch(ArrayList<RentalRequest> planned, CheckoutQueue queue, RentalService service) {
            for (RentalRequest request : planned) {
                batchNumbers.add(request.queueNumber);
            }
            super.showCheckoutBatch(planned, queue, service);
        }

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

        void waitForEnter() { waits++; }
        void waitForEnter(int width) { waits++; }
        void loading(String message) { }
        void pause(int milliseconds) { }
    }

    // Simulate a failed second approval while keeping the first real saved checkout.
    static class FailingApprovalSystem extends GameRentalSystem {
        int attempts;

        Rental approveCheckout(RentalRequest request) {
            attempts++;
            if (attempts == 2) {
                ui.error("Checkout " + ui.queueLabel(request.queueNumber) + " failed: validation rejected.");
                return null;
            }
            return super.approveCheckout(request);
        }
    }

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            if (latestUI != null && !latestUI.errors.isEmpty()) {
                System.err.println("Scripted UI errors: " + latestUI.errors);
            }
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
        latestUI = ui;
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
        ScriptedUI cancellationUI = script(system, new String[] {"", "N", "X", String.valueOf(waiting.queueNumber), "N",
            "X", String.valueOf(waiting.queueNumber), "Y", "M"});
        system.checkoutManagement();
        check(system.checkoutQueue.findByNumber(waiting.queueNumber) == null,
            "Cancel any waiting request from shared queue screen. Errors: " + cancellationUI.errors);
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
        script(loaded, new String[] {"", "Y", "0"});
        loaded.checkoutManagement();
        check(loaded.checkoutQueue.isEmpty() && loaded.activeRentals.size() == 5, "Approve from shared screen saves checkout");
        script(loaded, new String[] {"", "X", "0", "0"});
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

    static GameRentalSystem sessionSystem(String name) {
        GameRentalSystem system = freshSystem(name);
        User vip = client("vipone", "VIP");
        User regular = client("regularone", "REGULAR");
        system.users.add(vip);
        system.users.add(regular);
        Game shared = system.gameCatalog.addGame("Shared Game", "PS5", 5, 3);
        Game unavailable = system.gameCatalog.addGame("Unavailable Game", "Xbox", 5, 0);
        enqueue(system, regular, shared, 2);
        for (int i = 0; i < 4; i++) {
            Game game = system.gameCatalog.addGame("Batch VIP " + i, "Nintendo", 5, 1);
            enqueue(system, vip, game, 1);
        }
        enqueue(system, regular, unavailable, 1);
        enqueue(system, vip, shared, 2);
        return system;
    }

    static void checkoutSessionTests() throws Exception {
        GameRentalSystem system = sessionSystem("batch-session");
        ArrayList<RentalRequest> before = system.checkoutQueue.getRequests();
        ArrayList<RentalRequest> planned = system.previewReadyCheckouts();
        int[] expected = {2, 3, 4, 1, 5};
        check(planned.size() == expected.length, "Batch simulates shared stock and skips shortages");
        for (int i = 0; i < expected.length; i++) {
            check(planned.get(i).queueNumber == expected[i], "Preview follows three-VIP fairness rule");
        }
        check(system.games.get("P001").availableCopies == 3 && system.checkoutQueue.vipStreak == 0,
            "Preview changes neither live stock nor streak");
        check(system.checkoutQueue.getRequests().equals(before) && system.nextRentalNumber == 1,
            "Preview changes neither queue nor rental numbers");
        ScriptedUI ui = script(system, new String[] {"A", "Y", "X", "6", "Y", "0"});
        system.checkoutManagement("actualadmin");
        check(ui.batchNumbers.size() == 5 && ui.tapeRentals.size() == 5,
            "One tape line per batch approval. Slips: " + ui.tapeRentals.size() + "; errors: " + ui.errors);
        for (int i = 0; i < expected.length; i++) {
            check(ui.batchNumbers.get(i) == expected[i], "Displayed batch equals preview order");
            check(system.allRentals.get(i).game == before.get(before.indexOf(planned.get(i))).game,
                "Real approval order equals preview order");
        }
        check(ui.lastApproved == 5 && ui.lastCancelled == 1 && ui.lastTotal == 28,
            "Session counters and price exclude cancellation");
        check(ui.tapeCancellations.size() == 1 && ui.tapeCancellations.get(0) == 6,
            "Cancellation appends queue number");
        check(system.checkoutQueue.findByNumber(6) == null && system.checkoutQueue.getRequests().size() == 1,
            "Cancelled request removed; shared-stock shortage remains");
        check(system.auditLog.size() == 6 && system.auditLog.peek().contains("Cancelled queue request #006"),
            "Every action has an audit entry");
        String savedLogs = Files.readString(system.dataStore.file("logs.txt"));
        check(savedLogs.contains("Cancelled queue request #006"), "Cancellation audit saved to isolated data");
        check(ui.clears == 2 && ui.queueScreens == 1 && ui.receipts == 1 && ui.waits == 1,
            "Clears only entry and exit receipt; batch appends");

        system = sessionSystem("enter-session");
        RentalRequest next = system.checkoutQueue.findNextEligibleRequest();
        ui = script(system, new String[] {"", "Y", "", "Y", "X", "6", "Y", "R", "Q", "M"});
        system.checkoutManagement("actualadmin");
        check(system.allRentals.get(0).game == next.game, "ENTER approves the NEXT request");
        check(ui.lastApproved == 2 && ui.lastCancelled == 1 && ui.lastTotal == 9,
            "Continuous actions update session totals");
        check(ui.clears == 5 && ui.queueScreens == 3 && ui.receipts == 2 && ui.waits == 2,
            "Ordinary actions append; only R/Q/exit redraw");
        check(system.isDashboardReturnRequested(), "M shows receipt then returns to dashboard");

        system = freshSystem("empty-session");
        ui = script(system, new String[] {"bogus", "", "A", "0"});
        system.checkoutManagement();
        check(ui.receipts == 0 && ui.waits == 0 && ui.clears == 1, "Empty session exits directly");
        check(ui.errors.contains("Use ENTER, A, X, Q, R, 0 or M.") && system.nextRentalNumber == 1,
            "Invalid keys change nothing");
        ui = script(system, new String[] {"M"});
        system.checkoutManagement();
        check(ui.receipts == 0 && system.isDashboardReturnRequested(), "Empty M returns directly");

        system = sessionSystem("declined-session");
        ui = script(system, new String[] {"", "N", "A", "N", "X", "0", "X", "abc", "X", "999",
            "X", "6", "N", "0"});
        system.checkoutManagement();
        check(system.allRentals.isEmpty() && system.checkoutQueue.getRequests().size() == 7,
            "Declined actions and invalid cancellations preserve state");
        check(ui.receipts == 0 && ui.tapeRentals.isEmpty() && ui.tapeCancellations.isEmpty(),
            "Declined actions create no session entries");
        check(ui.clears == 1 && ui.queueScreens == 1, "Declined batch does not clear or redraw table");

        system = sessionSystem("invalid-midbatch");
        RentalRequest invalid = system.checkoutQueue.findByNumber(3);
        Rental active = new Rental(1, invalid.client, invalid.game, java.time.LocalDateTime.now(),
            java.time.LocalDateTime.now().plusDays(3), 4.5);
        system.activeRentals.add(active);
        system.allRentals.add(active);
        invalid.client.rentalHistory.push(active);
        system.nextRentalNumber = 2;
        // Call the batch coordinator directly: this deliberately inconsistent fixture must not be saved.
        GameRentalSystem.CheckoutSession session = new GameRentalSystem.CheckoutSession("admin");
        ui = script(system, new String[] {"Y"});
        system.checkoutQueue.remove(system.checkoutQueue.findByNumber(2));
        system.approveReadyBatch(session);
        check(session.approved == 0 && system.checkoutQueue.findByNumber(3) == invalid,
            "Batch stops on real checkout validation failure");
        check(ui.errors.get(0).contains("#003"), "Batch validation failure identifies request");

        System.setProperty("q2p.dataDir", testRoot.resolve("partial-batch").toString());
        FailingApprovalSystem failure = new FailingApprovalSystem();
        User regular = client("failureclient", "REGULAR");
        failure.users.add(regular);
        for (int i = 0; i < 3; i++) {
            Game game = failure.gameCatalog.addGame("Failure Test " + i, "PS5", 5, 1);
            enqueue(failure, regular, game, 1);
        }
        ui = script(failure, new String[] {"A", "Y", "0"});
        failure.checkoutManagement();
        check(failure.attempts == 2 && failure.allRentals.size() == 1,
            "Mid-batch failure stops before later requests");
        check(ui.tapeRentals.size() == 1 && ui.lastApproved == 1 && ui.lastTotal == 5,
            "Mid-batch failure retains earlier tape line and session total");
        check(failure.checkoutQueue.getRequests().size() == 2 && ui.errors.get(0).contains("#002"),
            "Failed and later requests stay queued; error names failed request");
        GameRentalSystem reloaded = freshSystem("partial-batch");
        reloaded.accountService.loadAccounts();
        reloaded.dataStore.loadGames();
        reloaded.dataStore.loadRentals();
        reloaded.dataStore.loadQueue();
        check(reloaded.activeRentals.size() == 1 && reloaded.checkoutQueue.getRequests().size() == 2,
            "Earlier successful approval remains saved after batch failure");
    }

    static void checkoutLayoutTests() throws Exception {
        GameRentalSystem system = sessionSystem("layout-session");
        ConsoleUI ui = new ConsoleUI();
        RentalRequest ready = system.checkoutQueue.findByNumber(1);
        RentalRequest waiting = system.checkoutQueue.findByNumber(6);
        check(ui.checkoutStatus(ready).equals("READY") && ui.checkoutStock(ready).equals("3→1"),
            "READY stock shows request alone");
        check(ui.checkoutStatus(waiting).equals("WAIT need 1") && ui.checkoutStock(waiting).equals("0"),
            "Zero stock shows need label");
        waiting.game.availableCopies = 1;
        waiting.quantity = 2;
        check(ui.checkoutStatus(waiting).equals("WAIT short 1"), "Insufficient stock shows short label");
        waiting.quantity = 0;
        check(ui.checkoutStatus(waiting).equals("WAIT invalid"), "Invalid request is WAIT");
        waiting.quantity = 2;
        system.users.get(0).username = "ExtremelyLongAdminCustomerName";
        ready.game.title = "An Extremely Long Game Title For Testing Width In Every Screen";
        PrintStream previous = System.out;
        ByteArrayOutputStream drawing = new ByteArrayOutputStream();
        System.setOut(new PrintStream(drawing, true, "UTF-8"));
        try {
            ui.showCheckoutQueue(system.checkoutQueue, system.rentalService);
            GameRentalSystem.CheckoutSession session = new GameRentalSystem.CheckoutSession("testadmin");
            Rental rental = new Rental(1, ready.client, ready.game, java.time.LocalDateTime.now(),
                java.time.LocalDateTime.now().plusDays(3), 5);
            session.addRental(rental);
            session.addCancellation(waiting);
            ui.showApprovedSlip(rental, 1);
            ui.showCancelledSlip(waiting, 2);
            ui.showCheckoutTapeFooter(session, system.checkoutQueue, system.rentalService);
            ui.warning("No request is ready.");
            ui.showTapeInput();
            ui.showCheckoutBatch(system.previewReadyCheckouts(), system.checkoutQueue, system.rentalService);
            ui.showCheckoutReceipt(session, system.checkoutQueue);
            ui.info(String.format("%-81s", "")); // Flush the receipt's delayed bottom border.
        } finally {
            System.setOut(previous);
            ui.endCheckoutDisplay();
        }
        String plain = drawing.toString("UTF-8").replaceAll("\u001B\\[[0-9;]*m", "");
        for (String line : plain.split("\\R")) {
            if (!line.isEmpty()) {
                check(line.length() == 81, "Checkout rendered row is exactly 81 columns: " + line.length());
            }
            if (line.contains("✔ APPROVED") || line.contains("✘ CANCELLED")) {
                check(line.substring(71, 79).matches("[0-9]{2}:[0-9]{2} [AP]M"),
                    "Slip timestamp is right-aligned");
            }
            if (line.contains("SESSION   APPROVED")) {
                check(line.substring(72, 79).startsWith("VIP "), "Session VIP indicator is right-aligned");
            }
        }
        check(plain.contains("WAIT short 1") && plain.contains("READY"), "Rendered status labels");
        check(plain.contains("✔") && plain.contains("✘") && plain.contains("TOTAL $5.00"), "Tape and receipt render totals");
        check(!plain.contains("◀ NEXT") && !plain.contains("▶"), "Rows have no NEXT marker");
        check(plain.contains("✔ APPROVED  #01") && plain.contains("✘ CANCELLED  #02"),
            "Boxed results include session numbers");
        check(plain.contains("Out ") && plain.contains("Due ") && plain.contains("3 days"),
            "Approved slip includes duration and dates");
        check(plain.contains("WARNING: No request is ready."), "Warning renders in a separate message card");
        check(plain.contains("[ENTER] Approve  [A] All  [X] Cancel #  [Q] Queue  [R] Receipt  [0] Back"),
            "Input panel contains the tape keys");
        check(!plain.contains("[M] Dashboard"), "M remains hidden from tape keys");
        if (!"true".equalsIgnoreCase(System.getProperty("q2p.ascii"))) {
            check(plain.contains("╓") && plain.contains("╖") && plain.contains("╟"), "Detail-card slip borders");
            check(plain.contains("╔") && plain.contains("╠") && plain.contains("╗"), "Divided session-status borders");
            check(plain.contains("┌") && plain.contains("┐") && plain.contains("ENTER CHOICE"), "Input-panel borders");
        }
        if (System.getenv("NO_COLOR") == null && !"false".equals(System.getProperty("q2p.color"))) {
            String styled = drawing.toString("UTF-8");
            check(styled.contains("\u001B[92m ✔ APPROVED"), "Approved slip title is green");
            check(styled.contains("\u001B[91m ✘ CANCELLED"), "Cancelled slip title is red");
            boolean yellowWarning = false;
            boolean pinkInput = false;
            for (String line : styled.split("\\R")) {
                if (line.contains("WARNING:") && line.contains("\u001B[93m")) {
                    yellowWarning = true;
                }
                if (line.contains("ENTER CHOICE") && line.contains("\u001B[38;5;218m")) {
                    pinkInput = true;
                }
            }
            check(yellowWarning, "Warning card text is yellow");
            check(pinkInput, "Tape input panel is pink");
        }
    }

    static void tapeFlowTests() {
        GameRentalSystem system = freshSystem("blocked-tape");
        User regular = client("blockedclient", "REGULAR");
        system.users.add(regular);
        Game game = system.gameCatalog.addGame("Blocked", "PS5", 5, 0);
        RentalRequest waiting = enqueue(system, regular, game, 1);
        int nextQueue = system.nextQueueNumber;
        ScriptedUI ui = script(system, new String[] {"", "", "bad", "A", "0"});
        system.checkoutManagement();
        check(ui.statusBoxes == 0 && ui.slipNumbers.isEmpty(), "Blocked and invalid input prints no status or slip");
        check(ui.inputPanels == 5 && ui.tapeEvents.size() == 9, "Each retry prints only message and input panel");
        check(ui.tapeEvents.get(0).equals("input"), "Entry ends with input panel");
        for (int i = 1; i < ui.tapeEvents.size(); i += 2) {
            check(ui.tapeEvents.get(i).startsWith("message:"), "Retry starts with message card");
            check(ui.tapeEvents.get(i + 1).equals("input"), "Retry message is immediately followed by input panel");
        }
        check(ui.tapeEvents.get(1).equals("message:WARNING: No request is ready."), "Empty ENTER warning text");
        check(ui.lastApproved == 0 && ui.lastCancelled == 0 && ui.lastTotal == 0,
            "Blocked input leaves session counters unchanged");
        check(system.checkoutQueue.findByNumber(waiting.queueNumber) == waiting && game.availableCopies == 0
            && system.allRentals.isEmpty() && system.nextRentalNumber == 1 && system.nextQueueNumber == nextQueue
            && system.checkoutQueue.vipStreak == 0, "Retries do not change queue, stock, rentals, or VIP state");
        check(ui.clears == 1, "Retries never clear screen");

        system = sessionSystem("numbered-tape");
        ui = script(system, new String[] {"", "Y", "X", "6", "Y", "", "Y", "0"});
        system.checkoutManagement();
        String[] expectedEvents = {"input", "approved:1", "status", "input", "cancelled:2", "status", "input",
            "approved:3", "status", "input"};
        check(ui.tapeEvents.size() == expectedEvents.length, "Successful actions append one slip, status, and input panel");
        for (int i = 0; i < expectedEvents.length; i++) {
            check(ui.tapeEvents.get(i).equals(expectedEvents[i]), "Slip/status/input render order");
        }
        check(ui.slipNumbers.get(0) == 1 && ui.slipNumbers.get(1) == 2 && ui.slipNumbers.get(2) == 3,
            "Slip numbering includes cancellations");
        check(ui.lastApproved == 2 && ui.lastCancelled == 1 && ui.lastTotal == 9,
            "Boxed tape preserves session counters and costs");
        check(ui.statusBoxes == 3 && ui.inputPanels == 4 && ui.clears == 2,
            "Successful actions do not clear or repeat controls");

        system = sessionSystem("batch-tape");
        ui = script(system, new String[] {"A", "Y", "0"});
        system.checkoutManagement();
        check(ui.tapeRentals.size() == 5 && ui.statusBoxes == 1 && ui.inputPanels == 2,
            "Batch prints five slips and one final status/input panel");
        check(ui.clears == 2, "Batch clears only at entry and exit receipt");
        for (int i = 0; i < 5; i++) {
            check(ui.tapeEvents.get(i + 1).equals("approved:" + (i + 1)), "Batch numbers consecutive result slips");
        }
        check(ui.tapeEvents.get(6).equals("status") && ui.tapeEvents.get(7).equals("input"),
            "Batch status and input follow all slips");
    }

    static void confirmQuestionTests() throws Exception {
        GameRentalSystem system = sessionSystem("confirm-layout");
        RentalRequest request = system.checkoutQueue.findByNumber(1);
        request.client.username = "alex";
        request.game.title = "Mario Kart 8";
        ScriptedUI ui = script(system, new String[] {"maybe", "y", "", "nO"});
        PrintStream previous = System.out;
        ByteArrayOutputStream drawing = new ByteArrayOutputStream();
        System.setOut(new PrintStream(drawing, true, "UTF-8"));
        try {
            ui.showCheckoutQueue(system.checkoutQueue, system.rentalService);
            drawing.reset();
            check(ui.confirmCheckout(request, system.rentalService), "Invalid approval answer retries then accepts lowercase y");
            check(!ui.confirmCheckoutCancellation(request), "Invalid cancellation answer retries then accepts mixed-case NO");
        } finally {
            System.setOut(previous);
            ui.endCheckoutDisplay();
        }
        String styled = drawing.toString("UTF-8");
        String plain = styled.replaceAll("\u001B\\[[0-9;]*m", "");
        String separator = "─".repeat(81);
        String[] lines = plain.split("\\R");
        int questions = 0;
        int errors = 0;
        int separators = 0;
        for (int i = 0; i < lines.length; i++) {
            check(lines[i].length() == 81, "Confirmation and error-card lines are exactly 81 columns");
            if (lines[i].equals(separator)) {
                separators++;
            }
            if (lines[i].contains("?   [Y] Yes   [N] No")) {
                questions++;
                String text = lines[i].trim();
                check(lines[i].indexOf(text) == (81 - text.length()) / 2, "Confirmation question is centered");
                check(i > 0 && i + 1 < lines.length && lines[i - 1].equals(separator)
                    && lines[i + 1].equals(separator), "Question has full-width separators above and below");
            }
            if (lines[i].contains("ERROR: Use Y or N.")) {
                errors++;
                check(lines[i].indexOf("ERROR: Use Y or N.") == (81 - "ERROR: Use Y or N.".length()) / 2,
                    "Invalid-answer message is centered");
                check(i + 4 < lines.length && lines[i + 2].equals(separator)
                    && lines[i + 3].contains("?   [Y] Yes   [N] No") && lines[i + 4].equals(separator),
                    "Error card is immediately followed by the redrawn question");
            }
        }
        check(questions == 4 && errors == 2 && separators == 8, "Each invalid answer repeats only message card and question");
        check(plain.contains("Approve #001 alex · Mario Kart 8 x2 · $10.00 ?")
            && plain.contains("Cancel #001 alex · Mario Kart 8 x2 ?"), "Both confirmation questions retain action details");
        check(ui.statusBoxes == 0 && ui.slipNumbers.isEmpty() && ui.inputPanels == 1,
            "Confirmation retries do not repeat slips, status, or tape input panel");
        check(system.allRentals.isEmpty() && request.game.availableCopies == 3
            && system.checkoutQueue.findByNumber(1) == request, "Rendering confirmations does not change checkout state");
        if (System.getenv("NO_COLOR") == null && !"false".equals(System.getProperty("q2p.color"))) {
            check(styled.contains("\u001B[38;5;218m" + separator + "\u001B[0m"), "Confirmation separators are pink");
        }

        request.game.title = "A Very Long Game Title That Must Be Shortened Without Losing Price Or Choices";
        ui = script(system, new String[] {"YES", "N"});
        drawing.reset();
        System.setOut(new PrintStream(drawing, true, "UTF-8"));
        try {
            check(ui.confirmCheckout(request, system.rentalService), "Approval accepts YES");
            check(!ui.confirmCheckoutCancellation(request), "Cancellation accepts N");
        } finally {
            System.setOut(previous);
        }
        plain = drawing.toString("UTF-8").replaceAll("\u001B\\[[0-9;]*m", "");
        for (String line : plain.split("\\R")) {
            check(line.length() == 81, "Long-title confirmation remains exactly 81 columns");
        }
        check(plain.contains("... x2 · $10.00 ?   [Y] Yes   [N] No")
            && plain.contains("... x2 ?   [Y] Yes   [N] No"), "Only long game titles are shortened");

        system = sessionSystem("confirm-actions");
        ui = script(system, new String[] {"", "bad", "YES", "X", "6", "bad", "yes", "0"});
        system.checkoutManagement();
        check(ui.lastApproved == 1 && ui.lastCancelled == 1 && ui.slipNumbers.size() == 2,
            "Approval and cancellation still commit after invalid-answer retries");
        check(system.allRentals.size() == 1 && system.checkoutQueue.findByNumber(6) == null,
            "Confirmed actions still create rental and remove cancelled request");
        system = sessionSystem("confirm-aborts");
        ui = script(system, new String[] {"", "bad", "n", "X", "6", "bad", "NO", "0"});
        system.checkoutManagement();
        check(system.allRentals.isEmpty() && system.checkoutQueue.getRequests().size() == 7
            && system.games.get("P001").availableCopies == 3 && system.checkoutQueue.vipStreak == 0,
            "N and NO abort approval and cancellation without changing checkout state");
        check(ui.slipNumbers.isEmpty() && ui.statusBoxes == 0, "Declined confirmations produce no slip or status box");
    }

    public static void main(String[] args) throws Exception {
        new ConsoleUI(); // Initialize the UTF-8 output stream before capturing TUI output.
        PrintStream originalOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, "UTF-8"));
        testRoot = Files.createTempDirectory("q2p-behavior-tests-");
        catalogTests();
        orderTests();
        queueTests();
        checkoutSessionTests();
        checkoutLayoutTests();
        tapeFlowTests();
        confirmQuestionTests();
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
