import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
// Parses without rewriting inputs. All business files are committed as one recoverable set.
class RentalDataStore {
    final AppStorage storage = new AppStorage();
    private final GameRentalSystem system;
    private Snapshot committed;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    RentalDataStore(GameRentalSystem system) {
        this.system = system;
    }

    Path file(String name) {
        return storage.file(name);
    }

    void checkpoint() {
        committed = new Snapshot(system);
    }

    // The line number travels with the fields so invalid data keeps its original error context.
    static class Record {
        final String[] fields;
        final int lineNumber;
        Record(String[] fields, int lineNumber) {
            this.fields = fields;
            this.lineNumber = lineNumber;
        }
    }

    List<Record> records(String name) {
        List<Record> records = new ArrayList<>();
        Path path = file(name);
        if (!Files.exists(path)) {
            return records;
        }
        try {
            List<String> lines = Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank() || line.startsWith("#") && !line.startsWith("#COUNTERS|")) {
                    continue;
                }
                records.add(new Record(line.split("\\|", -1), index + 1));
            }
        } catch (IOException e) {
            throw new StorageException("Cannot read " + path, e);
        }
        return records;
    }

    StorageException invalidRecord(String name, Record record, RuntimeException cause) {
        return new StorageException(name + " line " + record.lineNumber + ": " + cause.getMessage()
            + ". Original data was kept. Correct this line before continuing.", cause);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static int positive(String text) {
        int number = Integer.parseInt(text);
        require(number > 0 && number < Integer.MAX_VALUE, "Invalid record number");
        return number;
    }

    static int counter(String text) {
        int value = Integer.parseInt(text);
        require(value > 0, "Invalid counter");
        return value;
    }

    void saveData() {
        try {
            storage.commit(serialized());
            checkpoint();
        } catch (RuntimeException e) {
            if (committed != null) {
                committed.restore(system);
            }
            throw e;
        }
    }

    void saveGames() {
        saveData();
    }

    void saveQueue() {
        saveData();
    }

    private Map<String, String> serialized() {
        validateState();
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder accounts = new StringBuilder("# USERNAME|FULL NAME|EMAIL|PASSWORD HASH|ROLE|MEMBERSHIP\n");
        for (User user : system.users) {
            require(user.password != null && Passwords.isHash(user.password), "Administrator password setup is required");
            accounts.append(user.username).append('|').append(user.fullName).append('|').append(user.email).append('|')
                .append(user.password).append('|').append(user.role).append('|').append(user.membership).append('\n');
        }
        files.put("accounts.txt", accounts.toString());
        StringBuilder games = new StringBuilder("#COUNTERS|" + system.gameCatalog.ps5Count + "|"
            + system.gameCatalog.xboxCount + "|" + system.gameCatalog.nintendoCount + "\n"
            + "# GAME ID|TITLE|PLATFORM|PRICE|AVAILABLE COPIES\n");
        for (Game game : system.games.values()) {
            games.append(game.gameId).append('|').append(game.title).append('|')
                .append(game.platform).append('|').append(game.price).append('|').append(game.availableCopies).append('\n');
        }
        files.put("games.txt", games.toString());
        StringBuilder rentals = new StringBuilder("#COUNTERS|" + system.nextRentalNumber + "\n"
            + "# RENTAL #|USERNAME|GAME ID|TITLE|PLATFORM|CHECKOUT DATE|DUE DATE|AMOUNT|STATUS|QUANTITY|RETURN DATE|DAYS LATE|LATE FEE\n");
        for (Rental rental : system.allRentals) {
            String returnedAt = "";
            if (rental.returnDate != null) {
                returnedAt = rental.returnDate.toString();
            }
            rentals.append(rental.rentalNumber).append('|').append(rental.client.username).append('|')
                .append(rental.game.gameId).append('|').append(rental.game.title).append('|').append(rental.game.platform).append('|')
                .append(rental.checkoutDate).append('|').append(rental.dueDate).append('|').append(rental.amount).append('|')
                .append(rental.status).append('|').append(rental.quantity).append('|').append(returnedAt)
                .append('|').append(rental.daysLate).append('|').append(rental.lateFee).append('\n');
        }
        files.put("rentals.txt", rentals.toString());
        StringBuilder queue = new StringBuilder("#COUNTERS|" + system.nextQueueNumber + "|" + system.checkoutQueue.vipStreak + "\n"
            + "# QUEUE #|USERNAME|GAME ID|VIP|RENTAL DAYS|QUANTITY\n");
        for (RentalRequest request : system.checkoutQueue.getRequests()) {
            queue.append(request.queueNumber).append('|')
                .append(request.client.username).append('|').append(request.game.gameId).append('|').append(request.vip).append('|')
                .append(request.rentalDays).append('|').append(request.quantity).append('\n');
        }
        files.put("queue.txt", queue.toString());
        String logs = "# TIMESTAMP|TYPE|ACTION\n" + String.join("\n", system.logRecords);
        if (!system.logRecords.isEmpty()) {
            logs += "\n";
        }
        files.put("logs.txt", logs);
        return files;
    }

    private void validateState() {
        Set<String> logins = new HashSet<>();
        for (User user : system.users) {
            require(AccountService.validUsername(user.username) && AccountService.validName(user.fullName)
                && AccountService.validEmail(user.email), "Invalid account fields");
            require(logins.add(user.username.toLowerCase(Locale.ROOT)) && logins.add(user.email.toLowerCase(Locale.ROOT)), "Duplicate account identifier");
            require(user.role.equals("ADMIN") || user.role.equals("CLIENT"), "Invalid account role");
            require(user.membership.equals("VIP") || user.membership.equals("REGULAR"), "Invalid membership");
        }
        for (Game game : system.games.values()) {
            GameCatalogService.validate(game.title, game.platform, game.price, game.availableCopies);
            require(GameCatalogService.validId(game.gameId, game.platform), "Invalid game identifier");
            require((long) game.availableCopies + system.rentalService.rentedCopies(game) <= GameRentalSystem.MAX_GAME_STOCK, "Total game stock exceeds the limit");
        }
        Set<Integer> rentals = new HashSet<>();
        Set<Rental> active = new HashSet<>();
        for (Rental rental : system.allRentals) {
            require(rental.rentalNumber > 0 && rental.rentalNumber < system.nextRentalNumber && rentals.add(rental.rentalNumber), "Invalid rental number or counter");
            require(system.users.contains(rental.client) && !rental.client.isAdmin(), "Unknown rental client");
            require(rental.quantity >= 1 && rental.quantity <= GameRentalSystem.MAX_SAVED_COPIES_PER_GAME && Double.isFinite(rental.amount) && rental.amount >= 0, "Invalid rental amount or quantity");
            require(rental.checkoutDate != null && rental.dueDate != null && rental.dueDate.isAfter(rental.checkoutDate), "Invalid rental dates");
            require(rental.status.equals("ACTIVE") || rental.status.equals("COMPLETED"), "Invalid rental status");
            require(rental.daysLate >= 0 && Double.isFinite(rental.lateFee) && rental.lateFee >= 0, "Invalid late fee");
            if (rental.status.equals("ACTIVE")) {
                require(system.games.get(rental.game.gameId) == rental.game && system.activeRentals.contains(rental), "Active rental has no canonical game or heap entry");
                require(active.add(rental), "Duplicate active rental record");
                require(system.rentalService.activeCopies(rental.client, rental.game) <= GameRentalSystem.MAX_SAVED_COPIES_PER_GAME,
                    "Active client/game quantity exceeds the limit");
                require(rental.returnDate == null && rental.daysLate == 0 && rental.lateFee == 0, "Active rental has return fields");
            } else {
                require(!system.activeRentals.contains(rental), "Completed rental is still active");
            }
        }
        require(system.activeRentals.size() == active.size(), "Active rental heap differs from rental records");
        Set<Integer> numbers = new HashSet<>();
        for (RentalRequest request : system.checkoutQueue.getRequests()) {
            require(CheckoutQueue.validRequest(request) && request.queueNumber < system.nextQueueNumber && numbers.add(request.queueNumber), "Invalid request or queue counter");
            require(system.games.get(request.game.gameId) == request.game && system.users.contains(request.client), "Unknown request client or game");
            require(request.vip == request.client.isVip(), "Request membership is stale");
            require(system.checkoutQueue.queuedCopies(request.client, request.game)
                + system.rentalService.activeCopies(request.client, request.game) <= GameRentalSystem.MAX_SAVED_COPIES_PER_GAME,
                "Queued and active client/game quantity exceeds the limit");
        }
    }

    void loadGames() {
        if (!Files.exists(file("games.txt"))) {
            system.gameCatalog.loadStartingGames();
            return;
        }
        for (RentalDataStore.Record record : records("games.txt")) {
            String[] fields = record.fields;
            try {
                if (fields[0].equals("#COUNTERS")) {
                    require(fields.length == 4, "Invalid game counters");
                    int ps5Counter = Integer.parseInt(fields[1]);
                    int xboxCounter = Integer.parseInt(fields[2]);
                    int nintendoCounter = Integer.parseInt(fields[3]);
                    require(ps5Counter >= 0 && xboxCounter >= 0 && nintendoCounter >= 0
                        && ps5Counter < Integer.MAX_VALUE && xboxCounter < Integer.MAX_VALUE
                        && nintendoCounter < Integer.MAX_VALUE, "Invalid game counters");
                    system.gameCatalog.ps5Count = Math.max(ps5Counter, system.gameCatalog.ps5Count);
                    system.gameCatalog.xboxCount = Math.max(xboxCounter, system.gameCatalog.xboxCount);
                    system.gameCatalog.nintendoCount = Math.max(nintendoCounter, system.gameCatalog.nintendoCount);
                    continue;
                }
                require(fields.length == 5, "Expected five game fields");
                require(GameCatalogService.validId(fields[0], fields[2]), "Invalid game ID or platform");
                double price = Double.parseDouble(fields[3]);
                int stock = Integer.parseInt(fields[4]);
                GameCatalogService.validate(fields[1], fields[2], price, stock);
                require(!system.games.containsKey(fields[0]), "Duplicate game ID");
                for (Game existing : system.games.values()) {
                    require(!existing.platform.equals(fields[2]) || !existing.title.equalsIgnoreCase(fields[1]), "Duplicate title on this platform");
                }
                system.games.put(fields[0], new Game(fields[0], fields[1], fields[2], price, stock));
                system.gameCatalog.updateCounterFromId(fields[0]);
            } catch (IllegalArgumentException e) {
                throw invalidRecord("games.txt", record, e);
            } catch (java.time.DateTimeException e) {
                throw invalidRecord("games.txt", record, e);
            }
        }
    }

    void loadRentals() {
        Set<Integer> numbers = new HashSet<>();
        for (RentalDataStore.Record record : records("rentals.txt")) {
            String[] fields = record.fields;
            try {
                if (fields[0].equals("#COUNTERS")) {
                    require(fields.length == 2, "Invalid rental counter");
                    system.nextRentalNumber = Math.max(system.nextRentalNumber, counter(fields[1]));
                    continue;
                }
                require(fields.length == 9 || fields.length == 10 || fields.length == 13, "Expected 9, 10 or 13 rental fields");
                int number = positive(fields[0]);
                require(numbers.add(number), "Duplicate rental number");
                User client = system.accountService.findUser(fields[1]);
                require(client != null && !client.isAdmin(), "Unknown client");
                require(fields[8].equals("ACTIVE") || fields[8].equals("COMPLETED"), "Invalid rental status");
                Game game = system.games.get(fields[2]);
                if (game == null) {
                    require(fields[8].equals("COMPLETED"), "Active rental refers to a missing game");
                    require(GameCatalogService.validId(fields[2], fields[4]), "Invalid historical game");
                    require(GameCatalogService.validTitle(fields[3]), "Invalid historical title");
                    game = new Game(fields[2], fields[3], fields[4], 1, 0);
                }
                LocalDateTime checkout = parseDate(fields[5], false);
                LocalDateTime due = parseDate(fields[6], true);
                require(due.isAfter(checkout) && !due.isAfter(checkout.plusDays(GameRentalSystem.MAX_RENTAL_DAYS + 1L)), "Invalid rental dates");
                double amount = Double.parseDouble(fields[7]);
                require(Double.isFinite(amount) && amount >= 0, "Invalid rental amount");
                int quantity = 1;
                if (fields.length >= 10) {
                    quantity = Integer.parseInt(fields[9]);
                }
                require(quantity >= 1 && quantity <= GameRentalSystem.MAX_SAVED_COPIES_PER_GAME, "Invalid rental quantity");
                Rental rental = new Rental(number, client, game, checkout, due, amount);
                rental.quantity = quantity;
                rental.status = fields[8];
                if (fields.length == 13) {
                    if (!fields[10].isEmpty()) {
                        rental.returnDate = LocalDateTime.parse(fields[10]);
                    }
                    rental.daysLate = Integer.parseInt(fields[11]);
                    rental.lateFee = Double.parseDouble(fields[12]);
                    require(rental.daysLate >= 0 && Double.isFinite(rental.lateFee) && rental.lateFee >= 0, "Invalid return record");
                    require(rental.returnDate == null || !rental.returnDate.isBefore(checkout), "Invalid return date");
                    require(!rental.status.equals("ACTIVE") || rental.returnDate == null && rental.daysLate == 0 && rental.lateFee == 0, "Active rental has return details");
                    require(rental.returnDate != null || rental.daysLate == 0 && rental.lateFee == 0, "Fee without a return date");
                    if (rental.returnDate != null) {
                        require(rental.status.equals("COMPLETED")
                            && rental.daysLate == RentalService.daysOverdue(due, rental.returnDate)
                            && rental.lateFee == system.rentalService.calculateLateFee(rental, rental.daysLate), "Return fee does not match the date");
                    }
                }
                if (rental.status.equals("ACTIVE")) {
                    require(system.rentalService.activeCopies(client, game) <= GameRentalSystem.MAX_SAVED_COPIES_PER_GAME - quantity,
                        "Active client/game quantity exceeds the limit");
                    system.activeRentals.add(rental);
                }
                system.allRentals.add(rental);
                system.nextRentalNumber = Math.max(system.nextRentalNumber, number + 1);
            } catch (IllegalArgumentException e) {
                throw invalidRecord("rentals.txt", record, e);
            } catch (java.time.DateTimeException e) {
                throw invalidRecord("rentals.txt", record, e);
            }
        }
        for (Game game : system.games.values()) {
            require(game.availableCopies + system.rentalService.rentedCopies(game) <= GameRentalSystem.MAX_GAME_STOCK, "Total inventory exceeds the limit for " + game.gameId);
        }
        sortRentalsByNumber();
        for (User user : system.users) {
            user.rentalHistory.clear();
        }
        for (Rental rental : system.allRentals) {
            rental.client.rentalHistory.push(rental);
        }
    }

    private void sortRentalsByNumber() {
        for (int i = 1; i < system.allRentals.size(); i++) {
            Rental current = system.allRentals.get(i);
            int j = i - 1;
            while (j >= 0 && system.allRentals.get(j).rentalNumber > current.rentalNumber) {
                system.allRentals.set(j + 1, system.allRentals.get(j));
                j--;
            }
            system.allRentals.set(j + 1, current);
        }
    }

    void loadQueue() {
        Set<Integer> numbers = new HashSet<>();
        ArrayList<RentalRequest> requests = new ArrayList<>();
        for (RentalDataStore.Record record : records("queue.txt")) {
            String[] fields = record.fields;
            try {
                if (fields[0].equals("#COUNTERS")) {
                    require(fields.length == 2 || fields.length == 3, "Invalid queue counters");
                    system.nextQueueNumber = Math.max(system.nextQueueNumber, counter(fields[1]));
                    if (fields.length == 3) {
                        int streak = Integer.parseInt(fields[2]);
                        require(streak >= 0 && streak <= CheckoutQueue.VIP_BURST, "Invalid VIP counter");
                        system.checkoutQueue.vipStreak = streak;
                    }
                    continue;
                }
                require(fields.length >= 4 && fields.length <= 6, "Expected 4 to 6 request fields");
                int number = positive(fields[0]);
                require(numbers.add(number), "Duplicate queue number");
                User client = system.accountService.findUser(fields[1]);
                Game game = system.games.get(fields[2]);
                require(client != null && !client.isAdmin() && game != null, "Unknown request client or game");
                require(fields[3].equals("true") || fields[3].equals("false"), "Invalid membership flag");
                RentalRequest request = new RentalRequest(number, client, game);
                if (fields.length >= 5) {
                    request.rentalDays = Integer.parseInt(fields[4]);
                }
                if (fields.length == 6) {
                    request.quantity = Integer.parseInt(fields[5]);
                }
                require(CheckoutQueue.validRequest(request), "Request duration or quantity is outside the limits");
                int copies = system.rentalService.activeCopies(client, game) + request.quantity;
                for (RentalRequest existing : requests) {
                    if (RentalService.sameUser(existing.client, client) && RentalService.sameGame(existing.game, game)) {
                        copies += existing.quantity;
                    }
                }
                require(copies <= GameRentalSystem.MAX_SAVED_COPIES_PER_GAME, "Queued and active client/game quantity exceeds the limit");
                requests.add(request);
                system.nextQueueNumber = Math.max(system.nextQueueNumber, number + 1);
            } catch (IllegalArgumentException e) {
                throw invalidRecord("queue.txt", record, e);
            } catch (java.time.DateTimeException e) {
                throw invalidRecord("queue.txt", record, e);
            }
        }
        CheckoutQueue.sortByQueueNumber(requests);
        for (RentalRequest request : requests) {
            system.checkoutQueue.enqueue(request);
        }
    }

    private LocalDateTime parseDate(String text, boolean end) {
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeException e) {
            LocalDate date = LocalDate.parse(text);
            if (end) {
                return date.atTime(23, 59);
            }
            return date.atStartOfDay();
        }
    }

    void writeLog(String type, String message) {
        writeLogRecord(LocalDateTime.now().format(LOG_TIME), type, message);
    }

    void writeLogRecord(String stamp, String type, String message) {
        require(!stamp.contains("|") && !type.contains("|") && !message.contains("\n") && !message.contains("\r"), "Invalid log record");
        system.logRecords.add(stamp + "|" + type + "|" + message);
    }

    void loadLogs() {
        Path path = file("logs.txt");
        if (!Files.exists(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\|", 3);
                require(fields.length == 3, "Invalid log record");
                system.logRecords.add(line);
                if (fields[1].equals("ADMIN")) {
                    system.auditLog.push(fields[0] + "|" + fields[2]);
                }
            }
        } catch (IOException e) {
            throw new StorageException("Cannot load logs.txt; original file was kept.", e);
        } catch (IllegalArgumentException e) {
            throw new StorageException("Cannot load logs.txt; original file was kept.", e);
        }
    }

    // Restore the last successfully committed state if a later file write fails.
    private static class Snapshot {
        final List<User> users;
        final List<Game> games;
        final List<Rental> rentals;
        final List<RentalRequest> queue;
        final List<String> audit;
        final List<String> logs;
        // Each array index belongs to the object at the same index in its list.
        final int[] stocks;
        final String[] memberships;
        final String[] passwords;
        final String[] rentalStatuses;
        final LocalDateTime[] returnDates;
        final int[] daysLate;
        final double[] lateFees;
        final boolean[] priorities;
        final int nextQueue;
        final int nextRental;
        final int ps5Counter;
        final int xboxCounter;
        final int nintendoCounter;
        final int streak;
        Snapshot(GameRentalSystem system) {
            users = new ArrayList<>(system.users);
            games = new ArrayList<>(system.games.values());
            rentals = new ArrayList<>(system.allRentals);
            queue = system.checkoutQueue.getRequests();
            audit = new ArrayList<>(system.auditLog);
            logs = new ArrayList<>(system.logRecords);
            stocks = new int[games.size()];
            for (int i = 0; i < games.size(); i++) {
                stocks[i] = games.get(i).availableCopies;
            }
            memberships = new String[users.size()];
            passwords = new String[users.size()];
            for (int i = 0; i < users.size(); i++) {
                memberships[i] = users.get(i).membership;
                passwords[i] = users.get(i).password;
            }
            rentalStatuses = new String[rentals.size()];
            returnDates = new LocalDateTime[rentals.size()];
            daysLate = new int[rentals.size()];
            lateFees = new double[rentals.size()];
            for (int i = 0; i < rentals.size(); i++) {
                Rental rental = rentals.get(i);
                rentalStatuses[i] = rental.status;
                returnDates[i] = rental.returnDate;
                daysLate[i] = rental.daysLate;
                lateFees[i] = rental.lateFee;
            }
            priorities = new boolean[queue.size()];
            for (int i = 0; i < queue.size(); i++) {
                priorities[i] = queue.get(i).vip;
            }
            nextQueue = system.nextQueueNumber;
            nextRental = system.nextRentalNumber;
            ps5Counter = system.gameCatalog.ps5Count;
            xboxCounter = system.gameCatalog.xboxCount;
            nintendoCounter = system.gameCatalog.nintendoCount;
            streak = system.checkoutQueue.vipStreak;
        }
        void restore(GameRentalSystem system) {
            system.users.clear();
            system.users.addAll(users);
            system.games.clear();
            for (int i = 0; i < games.size(); i++) {
                Game game = games.get(i);
                game.availableCopies = stocks[i];
                system.games.put(game.gameId, game);
            }
            for (int i = 0; i < users.size(); i++) {
                User user = users.get(i);
                user.membership = memberships[i];
                user.password = passwords[i];
                user.rentalHistory.clear();
            }
            system.allRentals.clear();
            system.allRentals.addAll(rentals);
            system.activeRentals.clear();
            for (int i = 0; i < rentals.size(); i++) {
                Rental rental = rentals.get(i);
                rental.status = rentalStatuses[i];
                rental.returnDate = returnDates[i];
                rental.daysLate = daysLate[i];
                rental.lateFee = lateFees[i];
                rental.client.rentalHistory.push(rental);
                if (rental.status.equals("ACTIVE")) {
                    system.activeRentals.add(rental);
                }
            }
            system.checkoutQueue.clear();
            for (int i = 0; i < queue.size(); i++) {
                RentalRequest request = queue.get(i);
                request.vip = priorities[i];
                system.checkoutQueue.enqueue(request);
            }
            system.checkoutQueue.vipStreak = streak;
            system.auditLog.clear();
            system.auditLog.addAll(audit);
            system.logRecords.clear();
            system.logRecords.addAll(logs);
            system.nextQueueNumber = nextQueue;
            system.nextRentalNumber = nextRental;
            system.gameCatalog.ps5Count = ps5Counter;
            system.gameCatalog.xboxCount = xboxCounter;
            system.gameCatalog.nintendoCount = nintendoCounter;
        }
    }
}
