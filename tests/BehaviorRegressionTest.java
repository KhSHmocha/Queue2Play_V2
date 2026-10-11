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
        int finishes;
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

        void finishScreen() { finishes++; }
        void finishScreen(int width) { finishes++; }
        void loading(String message) { }
        void pause(int milliseconds) { }
    }

    static class AccountUI extends ScriptedUI {
        int cooldownSeconds;
        int passwordPrompts;
        ArrayList<String> loginIdentifiers = new ArrayList<>();
        String summaryName;
        String summaryUsername;
        String summaryEmail;

        AccountUI(String[] choices) {
            super(choices);
        }

        String readPassword(String prompt) {
            passwordPrompts++;
            return readText(prompt);
        }

        void waitLoginSecond() {
            cooldownSeconds++;
        }

        void showLoginPasswordScreen(String login) {
            loginIdentifiers.add(login);
            super.showLoginPasswordScreen(login);
        }

        void showRegistrationConfirmation(String name, String username, String email) {
            summaryName = name;
            summaryUsername = username;
            summaryEmail = email;
            super.showRegistrationConfirmation(name, username, email);
        }

        void useChoices(String[] choices) {
            this.choices = choices;
            nextChoice = 0;
        }
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

    static AccountService accountScript(GameRentalSystem system, AccountUI ui) {
        latestUI = ui;
        return new AccountService(system.users, ui, system.dataStore);
    }

    static void accountValidationTests() {
        check(!AccountService.validRegistrationName(null), "Null name rejected");
        check(!AccountService.validRegistrationName("A...."), "Name needs two letters");
        check(!AccountService.validRegistrationName("John"), "Name minimum is five");
        check(AccountService.validRegistrationName("Al Li"), "Five-character name accepted");
        check(AccountService.validRegistrationName("A".repeat(60)), "60-character name accepted");
        check(!AccountService.validRegistrationName("A".repeat(61)), "61-character name rejected");
        check(AccountService.validRegistrationName("Juan D. Cruz"), "Name accepts spaces and dots");
        check(AccountService.validRegistrationName("Maria"), "Single name accepted");
        check(AccountService.validRegistrationName("Jos\u00e9 Cruz"), "Name accepts Unicode letters");
        check(!AccountService.validRegistrationName("Anne-Marie"), "Name rejects hyphens");
        check(!AccountService.validRegistrationName("O'Neil"), "Name rejects apostrophes");
        check(!AccountService.validRegistrationName("Alex2"), "Name rejects digits");
        check(!AccountService.validRegistrationUsername(null), "Null username rejected");
        check(!AccountService.validRegistrationUsername("ab"), "Two-character username rejected");
        check(AccountService.validRegistrationUsername("a12"), "Three-character username accepted");
        check(AccountService.validRegistrationUsername("a".repeat(20)), "20-character username accepted");
        check(!AccountService.validRegistrationUsername("a".repeat(21)), "21-character username rejected");
        check(AccountService.validRegistrationUsername("alex_1.-"), "Username symbols accepted");
        check(!AccountService.validRegistrationUsername("123"), "Username needs a letter");
        check(!AccountService.validRegistrationUsername("..."), "Punctuation-only username rejected");
        check(!AccountService.validRegistrationUsername("a b"), "Username spaces rejected");
        check(!AccountService.validRegistrationEmail(null), "Null email rejected");
        check(AccountService.validRegistrationEmail("a.b+tag@example.com"), "Valid email accepted");
        check(AccountService.validRegistrationEmail("a".repeat(88) + "@example.com"), "100-character email accepted");
        check(!AccountService.validRegistrationEmail("a".repeat(89) + "@example.com"), "101-character email rejected");
        String[] badEmails = {"a..b@example.com", ".a@example.com", "a.@example.com", "a@example..com",
                "a@-example.com", "a@example-.com", "a@@example.com", "a b@example.com", "a@example"};
        for (String email : badEmails) {
            check(!AccountService.validRegistrationEmail(email), "Malformed email rejected: " + email);
        }
        check(!AccountService.validRegistrationPassword(null), "Null password rejected");
        check(AccountService.validRegistrationPassword("abcdefgh"), "Letters-only password accepted");
        check(AccountService.validRegistrationPassword("12345678"), "Digits-only password accepted");
        check(AccountService.validRegistrationPassword("Abc123!?"), "Mixed password and symbols accepted");
        check(AccountService.validRegistrationPassword("a".repeat(20)), "20-character password accepted");
        String[] badPasswords = {"1234567", "a".repeat(21), "        ", "abc defg", "abcdefgh ",
                " abcdefgh", "abc\tdefgh", "abc\ndefgh", "abc\u00a0defgh", "abc\u0001defgh", "abc|defgh"};
        for (String password : badPasswords) {
            check(!AccountService.validRegistrationPassword(password), "Invalid password rejected");
        }
    }

    static void registrationTests() throws Exception {
        GameRentalSystem system = freshSystem("registration-edit");
        AccountUI ui = new AccountUI(new String[] {"  Juan Cruz  ", "  juannew  ", "  juannew@example.com  ",
                "12345678", "12345678", "E", "1", "Maria Santos", "E", "2", "marianew",
                "E", "3", "maria@example.com", "E", "4", "abcdefgh", "wrongconfirm", "abcdefgh", "1"});
        AccountService accounts = accountScript(system, ui);
        accounts.register();
        check(system.users.size() == 1, "Editing creates just one account");
        User user = system.users.get(0);
        check(user.fullName.equals("Maria Santos") && user.username.equals("marianew")
                && user.email.equals("maria@example.com"), "Each edit preserves other fields");
        check(Passwords.matches("abcdefgh", user.password) && !Passwords.matches("12345678", user.password),
                "Edited password replaces the old password only after confirmation");
        check(ui.errors.contains("Passwords do not match. Please try again."), "Password mismatch retries");
        check(user.role.equals("CLIENT") && user.membership.equals("REGULAR"), "New account defaults");
        check(ui.nextChoice == ui.choices.length, "Edits return straight to summary");
        String saved = Files.readString(system.dataStore.file("accounts.txt"));
        check(!saved.contains("abcdefgh") && !saved.contains("12345678"), "Account file contains no plaintext passwords");
        check(!Files.readString(system.dataStore.file("logs.txt")).contains("abcdefgh"), "Registration log contains no password");
        GameRentalSystem reloaded = freshSystem("registration-edit");
        reloaded.accountService.loadAccounts();
        User loaded = reloaded.accountService.findUserByLogin("MARIA@EXAMPLE.COM");
        check(loaded != null && Passwords.matches("abcdefgh", loaded.password), "Registration survives restart");

        system = freshSystem("registration-back");
        ui = new AccountUI(new String[] {"  Alex Reyes  ", "alexnew", "alexnew@example.com", "12345678", "12345678",
                "E", "4", "abcdefgh", "B", "B", "B", "E", "1", "B", "B", "1"});
        accounts = accountScript(system, ui);
        accounts.register();
        user = system.users.get(0);
        check(user.fullName.equals("Alex Reyes") && user.email.equals("alexnew@example.com"), "Back preserves entered fields");
        check(Passwords.matches("12345678", user.password), "Abandoned password edit preserves confirmed password");

        system = freshSystem("registration-invalid");
        system.users.add(client("taken", "REGULAR"));
        ui = new AccountUI(new String[] {"A....", "John Doe", "TAKEN", "...", "newclient", "TAKEN@EXAMPLE.COM",
                "a..b@example.com", "newclient@example.com", "abc defgh", "12345678", "12345678", "C", "maybe", "N", "1"});
        accounts = accountScript(system, ui);
        accounts.register();
        check(system.users.size() == 2, "Invalid registration retries then succeeds");
        check(ui.errors.contains("Username is already taken. Please try another one."), "Username duplicates ignore case");
        check(ui.errors.contains("Email is already registered. Please use another email."), "Email duplicates ignore case");
        check(ui.errors.contains("Use Y to discard or N to keep registering."), "Invalid cancel answer retries");

        system = freshSystem("registration-cancel");
        ui = new AccountUI(new String[] {"John Doe", "newclient", "newclient@example.com", "12345678", "12345678", "C", "Y"});
        accountScript(system, ui).register();
        check(system.users.isEmpty(), "Summary cancellation creates no account");
        check(!Files.exists(system.dataStore.file("accounts.txt")), "Cancellation does not save an account");
        ui = new AccountUI(new String[] {"0"});
        accountScript(system, ui).register();
        check(system.users.isEmpty(), "First step main menu creates no account");
    }

    static void legacyAccountTests() {
        GameRentalSystem system = freshSystem("legacy-accounts");
        User legacy = new User("...", "Al", ".old@example..com", "old password longer than twenty", "CLIENT", "VIP");
        system.users.add(legacy);
        system.dataStore.saveData();
        GameRentalSystem loaded = freshSystem("legacy-accounts");
        loaded.accountService.loadAccounts();
        loaded.dataStore.saveData();
        User preserved = loaded.accountService.findUser("...");
        check(preserved != null && preserved.fullName.equals("Al") && preserved.membership.equals("VIP"),
                "Old account fields remain loadable and saveable");
        check(Passwords.matches("old password longer than twenty", preserved.password), "Old password rules remain usable");
        check(Passwords.matches("admin123", loaded.accountService.findUser("admin").password), "Built-in defaults unchanged");
        system = freshSystem("legacy-long-accounts");
        User longFields = new User("u".repeat(32), "N".repeat(100), "e".repeat(200) + "@example.com",
                "OldPassword123", "CLIENT", "REGULAR");
        system.users.add(longFields);
        system.dataStore.saveData();
        loaded = freshSystem("legacy-long-accounts");
        loaded.accountService.loadAccounts();
        loaded.dataStore.saveData();
        preserved = loaded.accountService.findUser(longFields.username);
        check(preserved != null && preserved.fullName.equals(longFields.fullName)
                && preserved.email.equals(longFields.email), "Long saved account fields survive stricter registration limits");
    }

    static void accountNavigationTests() throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, "UTF-8"));
        try {
            AccountUI ui = new AccountUI(new String[] {"NO"});
            ui.showLoginUsernameScreen();
            ui.info("");
            String text = output.toString("UTF-8");
            check(text.contains("[0] Main Menu") && !text.contains("[B]"), "Login identifier shows only Main Menu");
            output.reset();
            ui.clearScreen();
            ui.showLoginPasswordScreen("entered@example.com");
            ui.info("");
            text = output.toString("UTF-8");
            check(text.contains("[B] Back to Username/Email") && text.contains("[0] Main Menu"), "Login password options are explicit");
            for (int step = 1; step <= 5; step++) {
                output.reset();
                ui.showRegistrationStep(step, false, "John Doe", "johndoe", "john@example.com");
                ui.info("");
                text = output.toString("UTF-8");
                if (step == 1) {
                    check(text.contains("[0] Main Menu") && !text.contains("[B]"), "First registration screen shows Main Menu");
                } else {
                    check(text.contains("[B] Previous Step") && text.contains("[C] Cancel Registration")
                            && !text.contains("[0]"), "Later registration screens show Previous Step and Cancel");
                }
            }
            output.reset();
            ui.showRegistrationStep(1, true, "John Doe", "johndoe", "john@example.com");
            ui.info("");
            text = output.toString("UTF-8");
            check(text.contains("[B] Edit Information") && !text.contains("[0]"), "Field editing returns to Edit Information");
            output.reset();
            ui.showRegistrationConfirmation("John Doe", "johndoe", "john@example.com");
            ui.info("");
            text = output.toString("UTF-8");
            check(text.contains("[1] Create Account") && text.contains("[E] Edit Information")
                    && text.contains("[C] Cancel Registration") && !text.contains("[0]"), "Summary has only create, edit and cancel");
            output.reset();
            ui.showRegistrationEditMenu("John Doe", "johndoe", "john@example.com");
            ui.info("");
            text = output.toString("UTF-8");
            check(text.contains("[1] Full Name") && text.contains("[2] Username") && text.contains("[3] Email")
                    && text.contains("[4] Password") && text.contains("[B] Registration Summary"), "Edit menu lists all fields and Summary");
            output.reset();
            check(!ui.confirmRegistrationCancellation(), "NO keeps registration");
            ui.info("");
            text = output.toString("UTF-8");
            check(text.contains("[Y] Discard Registration") && text.contains("[N] Keep Registering"), "Cancellation options describe the outcome");
        } finally {
            System.setOut(previous);
        }
    }

    static void loginTests() throws Exception {
        GameRentalSystem system = freshSystem("login-attempts");
        User user = client("signin", "REGULAR");
        system.users.add(user);
        AccountUI ui = new AccountUI(new String[] {"  SIGNIN@EXAMPLE.COM  ", "TestPassword123"});
        AccountService accounts = accountScript(system, ui);
        check(accounts.login() == user, "Email login trims spaces and ignores case");
        check(ui.loginIdentifiers.get(0).equals("SIGNIN@EXAMPLE.COM"), "Password screen uses supplied identifier");
        ui.useChoices(new String[] {"  SIGNIN  ", "testpassword123", "0"});
        check(accounts.login() == null, "Wrong-case password fails and zero exits");
        check(ui.cooldownSeconds == 0, "First failed attempt has no cooldown");
        ui.useChoices(new String[] {"missing", "TestPassword123", "B", "signin", "badpassword", "0"});
        check(accounts.login() == null && ui.cooldownSeconds == 30, "Third failure across identifiers and menu returns waits 30 seconds");
        check(ui.loginIdentifiers.contains("missing"), "Unknown account receives password prompt");
        for (String error : ui.errors) {
            check(error.equals("Incorrect username/email or password."), "All authentication failures use the same message");
        }
        ui.useChoices(new String[] {"signin", "badpassword", "badpassword", "badpassword", "TestPassword123"});
        check(accounts.login() == user && ui.cooldownSeconds == 60, "Another three failures require another cooldown");
        ui.useChoices(new String[] {"signin", "badpassword", "badpassword", "0"});
        check(accounts.login() == null && ui.cooldownSeconds == 60, "Successful login resets the failure counter");
        ui.useChoices(new String[] {"signin", "badpassword", "TestPassword123"});
        check(accounts.login() == user && ui.cooldownSeconds == 90, "Third failure after reset requires cooldown");
        int logCount = system.logRecords.size();
        ui.useChoices(new String[] {"   ", "signin", "", "B", "0"});
        check(accounts.login() == null, "Blank input and back navigation retry safely");
        check(ui.errors.contains("Username or email cannot be empty."), "Empty identifier message");
        check(ui.errors.contains("Password cannot be empty."), "Empty password message");
        check(system.logRecords.size() == logCount && ui.cooldownSeconds == 90, "Blank fields and navigation are not authentication attempts");
        ui.useChoices(new String[] {"signin", " TestPassword123", "TestPassword123 ", "0"});
        check(accounts.login() == null, "Login never trims the password");
        String logs = Files.readString(system.dataStore.file("logs.txt"));
        check(logs.contains("Failed login attempt") && logs.contains("Login: signin"), "Login successes and failures are logged");
        check(!logs.contains("TestPassword123") && !logs.contains("badpassword"), "Login logs contain no passwords");
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
        system.games.get("P001").availableCopies = 5;
        script(system, new String[] {"1", "1", "2", "Y", "1", "1", "4", "1", "Y",
            "1", "4", "1", "N", "2", "1"});
        system.placeInQueue(regular);
        check(system.checkoutQueue.getRequests().size() == 2, "One request per distinct game");
        RentalRequest first = system.checkoutQueue.getRequests().get(0);
        check(first.quantity == 3, "Repeated selections combine and reject more than three");
        check(first.game.availableCopies == 5, "Queue confirmation does not deduct stock");
        check(first.rentalDays == 3, "Chosen duration applied to requests");
        LinkedHashMap<Game, Integer> cart = new LinkedHashMap<>();
        cart.put(first.game, 1);
        check(system.rentalService.validateCart(regular, cart) != null, "Three queued copies block another copy");
        cart.clear();
        cart.put(system.games.get("P003"), 3);
        check(system.rentalService.validateCart(regular, cart) != null, "Cart rejects quantity above current stock");
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

    static void orderStockTests() {
        GameRentalSystem system = freshSystem("stock-order");
        User regular = client("stockclient", "REGULAR");
        system.users.add(regular);
        Game game = system.gameCatalog.addGame("Limited Stock", "PS5", 5, 3);
        ScriptedUI ui = script(system, new String[] {"1", "1", "4", "2", "Y", "1", "1", "2", "1", "N", "1", "1"});
        system.placeInQueue(regular);
        RentalRequest queued = system.checkoutQueue.findByNumber(1);
        check(queued != null && queued.quantity == 3 && system.checkoutQueue.getRequests().size() == 1,
            "Repeated cart selections combine up to exact stock into one request");
        check(ui.errors.size() == 2 && ui.errors.get(0).contains("Maximum rental quantity")
            && ui.errors.get(1).contains("Maximum rental quantity"), "Initial and combined quantities above three are rejected");
        check(game.availableCopies == 3 && system.allRentals.isEmpty(), "Submitting exact stock does not deduct inventory");

        int nextQueue = system.nextQueueNumber;
        ui = script(system, new String[] {"1", "1", "0", "0"});
        system.placeInQueue(regular);
        check(ui.errors.size() == 1 && ui.errors.get(0).contains("maximum of 3"),
            "Existing three-copy request blocks another selection");
        check(queued.quantity == 3 && system.nextQueueNumber == nextQueue
            && system.checkoutQueue.getRequests().size() == 1 && system.checkoutQueue.queuedCopies(regular, game) == 3,
            "Blocked repeat order leaves the request and queue counter unchanged");
        LinkedHashMap<Game, Integer> cart = new LinkedHashMap<>();
        cart.put(game, 1);
        check(system.rentalService.validateCart(client("STOCKCLIENT", "REGULAR"), cart).contains("Maximum queued quantity"),
            "Submission counts queued copies for the same username ignoring case");
        User other = client("otherclient", "REGULAR");
        check(system.rentalService.validateCart(other, cart) == null, "Another client can request the same game");

        system.dataStore.saveData();
        GameRentalSystem loaded = freshSystem("stock-order");
        loaded.accountService.loadAccounts();
        loaded.dataStore.loadGames();
        loaded.dataStore.loadQueue();
        cart.clear();
        cart.put(loaded.games.get(game.gameId), 1);
        check(loaded.rentalService.validateCart(client("stockclient", "REGULAR"), cart).contains("Maximum queued quantity"),
            "Saved and reloaded repeat requests still enforce the total limit");

        system = freshSystem("stock-boundaries");
        game = system.gameCatalog.addGame("Stock Boundary", "PS5", 5, 0);
        ui = script(system, new String[] {"1", "1", "0", "0"});
        system.placeInQueue(regular);
        check(ui.errors.contains("This game is out of stock.") && system.checkoutQueue.isEmpty()
            && system.nextQueueNumber == 1, "Zero-stock selection is rejected before quantity input");
        cart.clear();
        cart.put(game, 1);
        check(!system.validateCartBeforeSubmission(regular, cart), "Zero-stock cart cannot be submitted");
        game.availableCopies = 2;
        cart.put(game, 3);
        check(!system.validateCartBeforeSubmission(regular, cart), "Submission rechecks stock after selection");
        cart.put(game, 2);
        check(system.validateCartBeforeSubmission(regular, cart), "Submission accepts exact stock");
        game.availableCopies = 1;
        check(!system.validateCartBeforeSubmission(regular, cart), "Reduced stock blocks an existing cart");
        check(game.availableCopies == 1 && system.checkoutQueue.isEmpty() && system.nextQueueNumber == 1
            && system.checkoutQueue.vipStreak == 0 && system.allRentals.isEmpty(), "Validation leaves checkout state unchanged");

        game.availableCopies = 3;
        cart.put(game, 3);
        ui = script(system, new String[] {"1", "1", "0", "0"});
        check(system.addGamesToCart(regular, cart) == 0 && cart.get(game) == 3,
            "Selecting a cart line already at stock does not increase its quantity");
        check(ui.errors.get(0).contains("maximum of 3"),
            "Full-limit cart line gives a clear selection error");
        script(system, new String[] {});
        check(system.askQuantity(game, 3) == 0, "No remaining stock needs no quantity prompt");
        game.availableCopies = 6;
        ui = script(system, new String[] {"4", "3"});
        check(system.askQuantity(game, 0) == 3 && ui.errors.size() == 1,
            "Three-copy limit still applies when stock is higher");
    }

    static void repeatQueueTests() throws Exception {
        GameRentalSystem system = freshSystem("repeat-orders");
        User regular = client("repeatclient", "REGULAR");
        system.users.add(regular);
        Game game = system.gameCatalog.addGame("Spider-Man 2", "PS5", 5, 5);
        for (int i = 0; i < 3; i++) {
            script(system, new String[] {"1", "1", "1", "N", String.valueOf(i % 3 + 1), "1"});
            system.placeInQueue(regular);
            check(system.checkoutQueue.queuedCopies(regular, game) == i + 1,
                "One-copy repeat order is accepted up to three copies");
            check(system.checkoutQueue.getRequests().size() == i + 1 && game.availableCopies == 5,
                "Each repeat submission has its own request and leaves stock unchanged");
        }
        int[] days = {1, 3, 7};
        for (int i = 0; i < 3; i++) {
            RentalRequest request = system.checkoutQueue.getRequests().get(i);
            check(request.queueNumber == i + 1 && request.rentalDays == days[i] && request.quantity == 1,
                "Repeat requests retain arrival order, quantity, and chosen duration");
        }
        ScriptedUI ui = script(system, new String[] {"1", "1", "0", "0"});
        system.placeInQueue(regular);
        check(ui.errors.size() == 1 && ui.errors.get(0).contains("maximum of 3")
            && system.nextQueueNumber == 4, "Fourth one-copy order is blocked before quantity input");
        system.dataStore.saveData();
        GameRentalSystem loaded = freshSystem("repeat-orders");
        loaded.accountService.loadAccounts();
        loaded.dataStore.loadGames();
        loaded.dataStore.loadQueue();
        User loadedClient = loaded.accountService.findUser("repeatclient");
        Game loadedGame = loaded.games.get(game.gameId);
        check(loaded.checkoutQueue.getRequests().size() == 3 && loaded.checkoutQueue.queuedCopies(loadedClient, loadedGame) == 3,
            "Three repeat requests survive save and reload");

        script(loaded, new String[] {"", "Y", "0"});
        loaded.checkoutManagement();
        check(loaded.rentalService.activeCopies(loadedClient, loadedGame) == 1
            && loaded.checkoutQueue.queuedCopies(loadedClient, loadedGame) == 2 && loadedGame.availableCopies == 4,
            "First repeat checkout creates one rental and leaves two requests waiting");
        GameRentalSystem partial = freshSystem("repeat-orders");
        partial.accountService.loadAccounts();
        partial.dataStore.loadGames();
        partial.dataStore.loadRentals();
        partial.dataStore.loadQueue();
        User partialClient = partial.accountService.findUser("repeatclient");
        Game partialGame = partial.games.get(game.gameId);
        check(partial.activeRentals.size() == 1 && partial.checkoutQueue.getRequests().size() == 2,
            "Active rental and remaining repeat requests reload together");
        ui = script(partial, new String[] {"A", "Y", "0"});
        partial.checkoutManagement();
        check(ui.lastApproved == 2 && partial.checkoutQueue.isEmpty() && partialGame.availableCopies == 2
            && partial.rentalService.activeCopies(partialClient, partialGame) == 3,
            "Remaining repeat requests checkout to three active copies");
        GameRentalSystem completed = freshSystem("repeat-orders");
        completed.accountService.loadAccounts();
        completed.dataStore.loadGames();
        completed.dataStore.loadRentals();
        completed.dataStore.loadQueue();
        check(completed.activeRentals.size() == 3 && completed.checkoutQueue.isEmpty(),
            "Multiple active rentals for the same game survive reload");
        LinkedHashMap<Game, Integer> activeCart = new LinkedHashMap<>();
        activeCart.put(completed.games.get(game.gameId), 1);
        check(completed.rentalService.validateCart(completed.accountService.findUser("repeatclient"), activeCart) != null,
            "Active rentals still prevent placing another order");

        system = freshSystem("cancel-repeat");
        system.users.add(regular);
        game = system.gameCatalog.addGame("Spider-Man 2", "PS5", 5, 5);
        enqueue(system, regular, game, 1);
        enqueue(system, regular, game, 2);
        ui = script(system, new String[] {"X", "2", "Y", "0"});
        system.checkoutManagement();
        check(system.checkoutQueue.queuedCopies(regular, game) == 1, "Cancelling a repeat request frees its quantity allowance");
        ui = script(system, new String[] {"1", "1", "3", "2", "N", "2", "1"});
        system.placeInQueue(regular);
        check(system.checkoutQueue.queuedCopies(regular, game) == 3 && ui.errors.size() == 1,
            "One queued copy allows exactly two more in a later order");
        ui = script(system, new String[] {"2", "1"});
        check(system.askQuantity(game, 1, 1) == 1 && ui.errors.size() == 1,
            "Quantity input counts both queued copies and the current cart line");

        String savedQueue = Files.readString(system.dataStore.file("queue.txt"));
        enqueue(system, regular, game, 3);
        boolean refusedSave = false;
        try {
            system.dataStore.saveData();
        } catch (IllegalArgumentException e) {
            refusedSave = true;
        }
        check(refusedSave && Files.readString(system.dataStore.file("queue.txt")).equals(savedQueue),
            "Save rejects more than five outstanding copies and preserves the saved queue");
        Files.writeString(system.dataStore.file("queue.txt"), savedQueue
            + "3|repeatclient|" + game.gameId + "|false|3|3\n");
        GameRentalSystem invalidData = freshSystem("cancel-repeat");
        invalidData.accountService.loadAccounts();
        invalidData.dataStore.loadGames();
        boolean refusedLoad = false;
        try {
            invalidData.dataStore.loadQueue();
        } catch (StorageException e) {
            refusedLoad = true;
        }
        check(refusedLoad && invalidData.checkoutQueue.isEmpty(), "Load rejects a combined queued quantity above five");
    }

    static void legacyQuantityTests() {
        GameRentalSystem system = freshSystem("legacy-quantity");
        User regular = client("legacyclient", "REGULAR");
        system.users.add(regular);
        Game game = system.gameCatalog.addGame("Older Order", "PS5", 5, 5);
        enqueue(system, regular, game, 5);
        system.dataStore.saveData();
        GameRentalSystem loaded = freshSystem("legacy-quantity");
        loaded.accountService.loadAccounts();
        loaded.dataStore.loadGames();
        loaded.dataStore.loadQueue();
        User loadedClient = loaded.accountService.findUser("legacyclient");
        check(loaded.checkoutQueue.findByNumber(1).quantity == 5, "Older five-copy request reloads without losing quantity");
        ScriptedUI ui = script(loaded, new String[] {"1", "1", "0", "0"});
        loaded.placeInQueue(loadedClient);
        check(ui.errors.get(0).contains("maximum of 3") && loaded.nextQueueNumber == 2,
            "Older request cannot be increased by a new order");
        script(loaded, new String[] {"", "Y", "0"});
        loaded.checkoutManagement();
        check(loaded.allRentals.size() == 1 && loaded.allRentals.get(0).quantity == 5 && loaded.checkoutQueue.isEmpty(),
            "Older accepted request can still checkout with its original quantity");
        GameRentalSystem active = freshSystem("legacy-quantity");
        active.accountService.loadAccounts();
        active.dataStore.loadGames();
        active.dataStore.loadRentals();
        check(active.activeRentals.size() == 1 && active.allRentals.get(0).quantity == 5,
            "Older five-copy active rental remains readable");
        Rental rental = active.allRentals.get(0);
        active.rentalService.completeReturn(rental, rental.checkoutDate.plusDays(1));
        active.dataStore.saveData();
        GameRentalSystem history = freshSystem("legacy-quantity");
        history.accountService.loadAccounts();
        history.dataStore.loadGames();
        history.dataStore.loadRentals();
        check(history.activeRentals.isEmpty() && history.allRentals.get(0).quantity == 5,
            "Older completed rental history keeps its original quantity");
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
        check(ui.clears == 2 && ui.queueScreens == 1 && ui.receipts == 1 && ui.finishes == 1,
            "Clears only entry and exit receipt; batch appends");

        system = sessionSystem("enter-session");
        RentalRequest next = system.checkoutQueue.findNextEligibleRequest();
        ui = script(system, new String[] {"", "Y", "", "Y", "X", "6", "Y", "R", "Q", "M"});
        system.checkoutManagement("actualadmin");
        check(system.allRentals.get(0).game == next.game, "ENTER approves the NEXT request");
        check(ui.lastApproved == 2 && ui.lastCancelled == 1 && ui.lastTotal == 9,
            "Continuous actions update session totals");
        check(ui.clears == 5 && ui.queueScreens == 3 && ui.receipts == 2 && ui.finishes == 2,
            "Ordinary actions append; only R/Q/exit redraw");
        check(system.isDashboardReturnRequested(), "M shows receipt then returns to dashboard");

        system = freshSystem("empty-session");
        ui = script(system, new String[] {"bogus", "", "A", "0"});
        system.checkoutManagement();
        check(ui.receipts == 0 && ui.finishes == 0 && ui.clears == 1, "Empty session exits directly");
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
        active.quantity = 5;
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
        String plain = positionedDrawing(drawing, 81);
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
        String plain = positionedDrawing(drawing, 81);
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
        plain = positionedDrawing(drawing, 81);
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

    static int testTerminalWidth() {
        int width = 120;
        try {
            width = Integer.parseInt(System.getenv("COLUMNS"));
        } catch (NumberFormatException e) {
            // Match the renderer's fallback when COLUMNS is unavailable.
        }
        return Math.max(40, Integer.getInteger("q2p.width", width));
    }

    // Check the terminal margin separately from each card's fixed content width.
    static String positionedDrawing(ByteArrayOutputStream drawing, int width) throws Exception {
        String plain = drawing.toString("UTF-8").replaceAll("\u001B\\[[0-9;]*[mHJ]", "");
        int margin = Math.max(0, (testTerminalWidth() - width) / 2);
        String result = "";
        for (String line : plain.split("\\R", -1)) {
            if (!line.isEmpty()) {
                check(line.startsWith(" ".repeat(margin)), "Card starts at its centered terminal margin");
                line = line.substring(margin);
            }
            result += line + "\n";
        }
        return result;
    }

    static void terminalPositionTests() throws Exception {
        String previousWidth = System.getProperty("q2p.width");
        String previousAnsi = System.getProperty("q2p.ansi");
        PrintStream previousOutput = System.out;
        int[] widths = {120, 81, 60, 40};
        try {
            System.setProperty("q2p.ansi", "true");
            for (int terminalWidth : widths) {
                System.setProperty("q2p.width", String.valueOf(terminalWidth));
                ConsoleUI ui = new ConsoleUI();
                ByteArrayOutputStream drawing = new ByteArrayOutputStream();
                System.setOut(new PrintStream(drawing, true, "UTF-8"));
                int screenWidth = Math.min(ConsoleUI.STANDARD_WIDTH, terminalWidth);
                ui.printHeader("POSITION TEST", screenWidth);
                ui.inputBar("ENTER CHOICE", screenWidth, "[0] Back");
                ui.info(ui.prompt(screenWidth));
                String styled = drawing.toString("UTF-8");
                check(styled.startsWith("\u001B[2J\u001B[H"), "Screen clears and moves to terminal top before drawing");
                String plain = styled.replaceAll("\u001B\\[[0-9;]*[mHJ]", "");
                int margin = (terminalWidth - screenWidth) / 2;
                String[] lines = plain.split("\\R");
                check(!lines[0].isEmpty(), "Screen has no leading vertical padding");
                for (int i = 0; i < lines.length - 1; i++) {
                    check(lines[i].startsWith(" ".repeat(margin)) && lines[i].length() == margin + screenWidth,
                        "Header and input panel share the centered screen margin");
                }
                check(lines[lines.length - 1].equals(" ".repeat(margin) + "  > "),
                    "Input prompt aligns with the centered input panel");
                drawing.reset();
                ui.printHeader("SECOND SCREEN", screenWidth);
                ui.info("");
                check(drawing.toString("UTF-8").startsWith("\u001B[2J\u001B[H"),
                    "Next screen also starts at terminal top");
                drawing.reset();
                ui.showGoodbye();
                check(drawing.toString("UTF-8").startsWith("\u001B[2J\u001B[H"),
                    "Closing screen also starts at terminal top");
            }
            System.setProperty("q2p.ansi", "false");
            ConsoleUI ui = new ConsoleUI();
            ByteArrayOutputStream drawing = new ByteArrayOutputStream();
            System.setOut(new PrintStream(drawing, true, "UTF-8"));
            ui.printHeader("PLAIN OUTPUT", 40);
            ui.info("");
            String plain = drawing.toString("UTF-8");
            check(!plain.startsWith("\n") && !plain.contains("\u001B[2J"),
                "Plain output has no fake blank-line clearing or cursor controls");
        } finally {
            System.setOut(previousOutput);
            if (previousWidth == null) {
                System.clearProperty("q2p.width");
            } else {
                System.setProperty("q2p.width", previousWidth);
            }
            if (previousAnsi == null) {
                System.clearProperty("q2p.ansi");
            } else {
                System.setProperty("q2p.ansi", previousAnsi);
            }
        }
    }

    static void finishScreenTests() throws Exception {
        PrintStream previousOutput = System.out;
        java.io.InputStream previousInput = System.in;
        ByteArrayOutputStream drawing = new ByteArrayOutputStream();
        try {
            System.setIn(new java.io.ByteArrayInputStream(new byte[0]));
            ConsoleUI ui = new ConsoleUI();
            System.setOut(new PrintStream(drawing, true, "UTF-8"));
            ui.inputBar("RESULT", 81, "Order complete.");
            ui.finishScreen(81);
            String plain = drawing.toString("UTF-8").replaceAll("\u001B\\[[0-9;]*[mHJ]", "");
            check(plain.contains("Order complete."), "Finishing a screen renders its result completely");
            check(!plain.contains("Press ENTER to continue"), "Finishing a screen prints no ENTER continuation text");
            check(plain.endsWith(System.lineSeparator()), "Finishing a screen prints the final border completely");
            drawing.reset();
            ui.finishScreen();
            check(drawing.size() == 0, "Finishing again has no hidden input wait or repeated message");
            ui.warning("Example result warning.");
            ui.finishScreen();
            check(drawing.size() == 0, "Finishing keeps retry messages pending for the next input panel");
            ui.showTapeInput();
            check(drawing.toString("UTF-8").contains("WARNING: Example result warning."),
                "Next input panel displays the pending warning without an ENTER pause");
            drawing.reset();
            ui.showTapeInput();
            check(!drawing.toString("UTF-8").contains("WARNING: Example result warning."),
                "Pending warning is displayed only once");
        } finally {
            System.setOut(previousOutput);
            System.setIn(previousInput);
        }
    }

    public static void main(String[] args) throws Exception {
        new ConsoleUI(); // Initialize the UTF-8 output stream before capturing TUI output.
        PrintStream originalOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, "UTF-8"));
        testRoot = Files.createTempDirectory("q2p-behavior-tests-");
        if (args.length > 0 && args[0].equals("layout")) {
            checkoutLayoutTests();
            terminalPositionTests();
            finishScreenTests();
            System.setOut(originalOutput);
            System.out.println("PASS: " + checks + " layout checks. Isolated data: " + testRoot);
            return;
        }
        if (args.length > 0 && args[0].equals("cooldown")) {
            System.setProperty("q2p.fast", "true");
            ConsoleUI ui = new ConsoleUI();
            long started = System.nanoTime();
            ui.error("Incorrect username/email or password.");
            ui.showLoginCooldown();
            long elapsed = System.nanoTime() - started;
            check(elapsed >= 30000000000L, "Cooldown really waits 30 seconds even in fast mode");
            System.setOut(originalOutput);
            System.out.println("PASS: Real cooldown lasted " + elapsed / 1000000000L + " seconds.");
            return;
        }
        accountValidationTests();
        registrationTests();
        legacyAccountTests();
        loginTests();
        accountNavigationTests();
        if (args.length > 0 && args[0].equals("accounts")) {
            System.setOut(originalOutput);
            System.out.println("PASS: " + checks + " account checks. Isolated data: " + testRoot);
            return;
        }
        catalogTests();
        orderTests();
        orderStockTests();
        repeatQueueTests();
        legacyQuantityTests();
        queueTests();
        checkoutSessionTests();
        checkoutLayoutTests();
        tapeFlowTests();
        confirmQuestionTests();
        terminalPositionTests();
        finishScreenTests();
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
