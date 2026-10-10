import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;

class RentalService {
    private final CheckoutQueue checkoutQueue;
    private final PriorityQueue<Rental> activeRentals;
    private final ArrayList<Rental> allRentals;
    private final Map<String, Game> catalog;

    RentalService(CheckoutQueue checkoutQueue, PriorityQueue<Rental> activeRentals,
        ArrayList<Rental> allRentals, Map<String, Game> catalog) {
        this.checkoutQueue = checkoutQueue;
        this.activeRentals = activeRentals;
        this.allRentals = allRentals;
        this.catalog = catalog;
    }

    static boolean sameUser(User a, User b) {
        return a != null && b != null && a.username.equalsIgnoreCase(b.username);
    }

    static boolean sameGame(Game a, Game b) {
        return a != null && b != null && a.gameId.equals(b.gameId);
    }

    int rentedCopies(Game game) {
        int count = 0;
        for (Rental rental : activeRentals) {
            if (sameGame(rental.game, game)) {
                count = Math.addExact(count, rental.quantity);
            }
        }
        return count;
    }

    int updatedStock(Game game, boolean adding, int quantity) {
        RentalDataStore.require(quantity >= 1, "Quantity must be positive");
        long result = game.availableCopies;
        if (adding) {
            result += quantity;
        } else {
            result -= quantity;
        }
        RentalDataStore.require(result >= 0 && result + rentedCopies(game) <= GameRentalSystem.MAX_GAME_STOCK,
            "Available plus rented copies must stay between zero and " + GameRentalSystem.MAX_GAME_STOCK);
        return (int) result;
    }

    double calculateRentalCost(User client, Game game) {
        RentalDataStore.require(client != null && game != null, "Client and game are required");
        GameCatalogService.validate(game.title, game.platform, game.price, game.availableCopies);
        if (client.isVip()) {
            return game.price * (1 - GameRentalSystem.VIP_DISCOUNT);
        }
        return game.price;
    }

    double calculateRentalCost(User client, Game game, int days) {
        RentalDataStore.require(days >= 1 && days <= GameRentalSystem.MAX_RENTAL_DAYS, "Invalid rental duration");
        double price = calculateRentalCost(client, game) * days / GameRentalSystem.RENTAL_DAYS;
        return Math.round(price * 100) / 100.0;
    }

    double calculateRentalCost(User client, Game game, int days, int quantity) {
        RentalDataStore.require(quantity >= 1 && quantity <= GameRentalSystem.MAX_COPIES_PER_GAME, "Invalid quantity");
        return Math.round(calculateRentalCost(client, game, days) * quantity * 100) / 100.0;
    }

    boolean hasActiveRental(User client, Game game) {
        for (Rental rental : activeRentals) {
            if (sameUser(rental.client, client) && sameGame(rental.game, game)) {
                return true;
            }
        }
        return false;
    }

    boolean gameHasActiveRental(Game game) {
        for (Rental rental : activeRentals) {
            if (sameGame(rental.game, game)) {
                return true;
            }
        }
        return false;
    }

    ArrayList<Rental> getRentalsByDueDate() {
        ArrayList<Rental> sorted = new ArrayList<>();
        PriorityQueue<Rental> copy = new PriorityQueue<>(activeRentals);
        while (!copy.isEmpty()) {
            sorted.add(copy.poll());
        }
        return sorted;
    }

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
        if (hasActiveRental(request.client, request.game)) {
            return "This client already has an active rental for this game.";
        }
        if (!Double.isFinite(request.game.price) || request.game.price < GameRentalSystem.MIN_RENTAL_PRICE
            || request.game.price > GameRentalSystem.MAX_RENTAL_PRICE) {
            return "Invalid game price.";
        }
        if (request.game.availableCopies < request.quantity) {
            return "Not enough stock to process this checkout.";
        }
        return null;
    }

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

    double calculateLateFee(Rental rental, int daysLate) {
        RentalDataStore.require(daysLate >= 0, "Days late cannot be negative");
        return daysLate * GameRentalSystem.LATE_FEE_PER_DAY * rental.quantity;
    }

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

    void completeReturn(Rental rental, LocalDateTime returnedAt) {
        RentalDataStore.require(rental != null && returnedAt != null && !returnedAt.isBefore(rental.checkoutDate), "Invalid return date");
        if (!rental.status.equals("ACTIVE") || !activeRentals.contains(rental)) {
            throw new IllegalArgumentException("This rental is no longer active.");
        }
        int restored = Math.addExact(rental.game.availableCopies, rental.quantity);
        RentalDataStore.require(restored <= GameRentalSystem.MAX_GAME_STOCK, "Return would exceed total stock; correct inventory first");
        rental.returnDate = returnedAt;
        rental.daysLate = daysOverdue(rental.dueDate, returnedAt);
        rental.lateFee = calculateLateFee(rental, rental.daysLate);
        activeRentals.remove(rental);
        rental.status = "COMPLETED";
        rental.game.availableCopies = restored;
    }

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
            if (quantity < 1 || quantity > GameRentalSystem.MAX_COPIES_PER_GAME) {
                return "Invalid quantity for " + game.title + ". Maximum is " + GameRentalSystem.MAX_COPIES_PER_GAME + ".";
            }
            if (checkoutQueue.hasRequest(client, game)) {
                return "You already have a pending queue request for " + game.title + ".";
            }
            if (hasActiveRental(client, game)) {
                return "You are already renting " + game.title + ".";
            }
        }
        return null;
    }
}
