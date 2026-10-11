import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;

class RentalManager {
    private final CheckoutQueue checkoutQueue;
    private final PriorityQueue<Rental> activeRentals;
    private final ArrayList<Rental> allRentals;
    private final Map<String, Game> catalog;

    // Use shared queue, heap, history and catalog data for rental operations.
    RentalManager(CheckoutQueue checkoutQueue, PriorityQueue<Rental> activeRentals,
        ArrayList<Rental> allRentals, Map<String, Game> catalog) {
        this.checkoutQueue = checkoutQueue;
        this.activeRentals = activeRentals;
        this.allRentals = allRentals;
        this.catalog = catalog;
    }

    // Compare clients by username, ignoring case.
    static boolean sameUser(User a, User b) {
        return a != null && b != null && a.username.equalsIgnoreCase(b.username);
    }

    // Compare games by their stable identifiers.
    static boolean sameGame(Game a, Game b) {
        return a != null && b != null && a.gameId.equals(b.gameId);
    }

    // Traverse active rentals to count the copies currently out for one game.
    int rentedCopies(Game game) {
        int count = 0;
        for (Rental rental : activeRentals) {
            if (sameGame(rental.game, game)) {
                count = Math.addExact(count, rental.quantity);
            }
        }
        return count;
    }

    // Calculate a stock adjustment while preserving the total-copy limit.
    int updatedStock(Game game, boolean adding, int quantity) {
        RentalDataStore.require(quantity >= 1, "Quantity must be positive");
        long result = game.availableCopies;
        if (adding) {
            result += quantity;
        } else {
            result -= quantity;
        }
        RentalDataStore.require(result >= 0 && result + rentedCopies(game) <= Queue2Play.MAX_GAME_STOCK,
            "Available plus rented copies must stay between zero and " + Queue2Play.MAX_GAME_STOCK);
        return (int) result;
    }

    // Calculate the base rental price with the client's VIP discount.
    double calculateRentalCost(User client, Game game) {
        RentalDataStore.require(client != null && game != null, "Client and game are required");
        GameCatalog.validate(game.title, game.platform, game.price, game.availableCopies);
        if (client.isVip()) {
            return game.price * (1 - Queue2Play.VIP_DISCOUNT);
        }
        return game.price;
    }

    // Scale the price for the chosen duration and round to cents.
    double calculateRentalCost(User client, Game game, int days) {
        RentalDataStore.require(days >= 1 && days <= Queue2Play.MAX_RENTAL_DAYS, "Invalid rental duration");
        double price = calculateRentalCost(client, game) * days / Queue2Play.RENTAL_DAYS;
        return Math.round(price * 100) / 100.0;
    }

    // Multiply the duration price by quantity and round to cents.
    double calculateRentalCost(User client, Game game, int days, int quantity) {
        RentalDataStore.require(quantity >= 1 && quantity <= Queue2Play.MAX_SAVED_COPIES_PER_GAME, "Invalid quantity");
        return Math.round(calculateRentalCost(client, game, days) * quantity * 100) / 100.0;
    }

    // Use linear search to check whether a client is renting a game.
    boolean hasActiveRental(User client, Game game) {
        for (Rental rental : activeRentals) {
            if (sameUser(rental.client, client) && sameGame(rental.game, game)) {
                return true;
            }
        }
        return false;
    }

    // Traverse active rentals to total a client's copies of one game.
    int activeCopies(User client, Game game) {
        int copies = 0;
        for (Rental rental : activeRentals) {
            if (sameUser(rental.client, client) && sameGame(rental.game, game)) {
                copies += rental.quantity;
            }
        }
        return copies;
    }

    // Use linear search to check whether any client is renting a game.
    boolean gameHasActiveRental(Game game) {
        for (Rental rental : activeRentals) {
            if (sameGame(rental.game, game)) {
                return true;
            }
        }
        return false;
    }

    // Poll a copied min-heap into due-date order without changing active rentals.
    ArrayList<Rental> getRentalsByDueDate() {
        ArrayList<Rental> sorted = new ArrayList<>();
        PriorityQueue<Rental> copy = new PriorityQueue<>(activeRentals);
        while (!copy.isEmpty()) {
            sorted.add(copy.poll());
        }
        return sorted;
    }

    // Check that a queued request can safely become an active rental.
    String validateCheckout(RentalRequest request) {
        if (request == null || checkoutQueue.findByNumber(request.queueNumber) != request) {
            return "This queue request no longer exists.";
        }
        if (!CheckoutQueue.validRequest(request)) {
            return "Invalid request client, duration or quantity.";
        }
        if (catalog.get(request.game.gameId) != request.game) {
            return "This game is no longer in the catalog.";
        }
        if (activeCopies(request.client, request.game) > Queue2Play.MAX_SAVED_COPIES_PER_GAME - request.quantity) {
            return "This checkout would exceed " + Queue2Play.MAX_SAVED_COPIES_PER_GAME
                + " active copies of this game for the client.";
        }
        if (!Double.isFinite(request.game.price) || request.game.price < Queue2Play.MIN_RENTAL_PRICE
            || request.game.price > Queue2Play.MAX_RENTAL_PRICE) {
            return "Invalid game price.";
        }
        if (request.game.availableCopies < request.quantity) {
            return "Not enough stock to process this checkout.";
        }
        return null;
    }

    // Deduct stock, add rental and history entries, then remove the queued request.
    Rental checkout(RentalRequest request, int rentalNumber) {
        boolean duplicateNumber = false;
        for (Rental existing : allRentals) {
            if (existing.rentalNumber == rentalNumber) {
                duplicateNumber = true;
                break;
            }
        }
        RentalDataStore.require(rentalNumber > 0 && rentalNumber < Integer.MAX_VALUE && !duplicateNumber,
            "Invalid or duplicate rental number");
        String error = validateCheckout(request);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        LocalDateTime now = LocalDateTime.now();
        Rental rental = new Rental(rentalNumber, request.client, request.game,
            now, now.plusDays(request.rentalDays),
            calculateRentalCost(request.client, request.game, request.rentalDays, request.quantity));
        rental.quantity = request.quantity;
        request.game.availableCopies -= request.quantity;
        allRentals.add(rental);
        activeRentals.add(rental);
        request.client.rentalHistory.push(rental);
        checkoutQueue.recordCheckout(request);
        checkoutQueue.remove(request);
        return rental;
    }

    // Calculate the per-copy fee for the recorded number of late days.
    double calculateLateFee(Rental rental, int daysLate) {
        RentalDataStore.require(daysLate >= 0, "Days late cannot be negative");
        return daysLate * Queue2Play.LATE_FEE_PER_DAY * rental.quantity;
    }

    // Count each started day after the due time as one late day.
    static int daysOverdue(LocalDateTime due, LocalDateTime now) {
        if (!now.isAfter(due)) {
            return 0;
        }
        java.time.Duration duration = java.time.Duration.between(due, now);
        long seconds = duration.getSeconds();
        long days = seconds / 86400;
        if (seconds % 86400 != 0 || duration.getNano() != 0) {
            days++;
        }
        return Math.toIntExact(days);
    }

    // Remove the active heap entry, record late fees and restore stock.
    void completeReturn(Rental rental, LocalDateTime returnedAt) {
        RentalDataStore.require(rental != null && returnedAt != null && !returnedAt.isBefore(rental.checkoutDate), "Invalid return date");
        if (!rental.status.equals("ACTIVE") || !activeRentals.contains(rental)) {
            throw new IllegalArgumentException("This rental is no longer active.");
        }
        int restored = Math.addExact(rental.game.availableCopies, rental.quantity);
        RentalDataStore.require(restored <= Queue2Play.MAX_GAME_STOCK, "Return would exceed total stock; correct inventory first");
        rental.returnDate = returnedAt;
        rental.daysLate = daysOverdue(rental.dueDate, returnedAt);
        rental.lateFee = calculateLateFee(rental, rental.daysLate);
        activeRentals.remove(rental);
        rental.status = "COMPLETED";
        rental.game.availableCopies = restored;
    }

    // Check each cart line against catalog, stock, rental and queue limits.
    String validateCart(User client, LinkedHashMap<Game, Integer> cart) {
        if (client == null || client.isAdmin()) {
            return "A client account is required.";
        }
        if (cart.isEmpty()) {
            return "Your cart is empty. Add a game before submitting.";
        }
        for (Game game : cart.keySet()) {
            if (game == null || cart.get(game) == null || catalog.get(game.gameId) != game) {
                return "This game is no longer in the catalog.";
            }
            int quantity = cart.get(game);
            if (quantity < 1 || quantity > Queue2Play.MAX_COPIES_PER_GAME) {
                return "Invalid quantity for " + game.title + ". Maximum is " + Queue2Play.MAX_COPIES_PER_GAME + ".";
            }
            if (checkoutQueue.queuedCopies(client, game) > Queue2Play.MAX_COPIES_PER_GAME - quantity) {
                return "Maximum queued quantity is " + Queue2Play.MAX_COPIES_PER_GAME
                    + " copies for " + game.title + ", including this cart.";
            }
            if (hasActiveRental(client, game)) {
                return "You are already renting " + game.title + ".";
            }
            if (quantity > game.availableCopies) {
                return "Not enough stock for " + game.title + ". Available: " + game.availableCopies + ".";
            }
        }
        return null;
    }
}
