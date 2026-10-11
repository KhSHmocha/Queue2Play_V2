import java.util.Stack;

class User {
    String username;
    String fullName;
    String email;
    String password;
    String role;        // CLIENT or ADMIN
    String membership;  // REGULAR or VIP

    // Stack stores rental history; newest rentals are pushed on top and shown first.
    Stack<Rental> rentalHistory = new Stack<>();

    User(String username, String fullName, String email,
         String password, String role, String membership) {
        this.username = username;
        this.fullName = fullName;
        this.email = email;
        this.password = PasswordSecurity.hash(password);
        this.role = role;
        this.membership = membership;
    }

    boolean isVip() {
        return membership.equals("VIP");
    }

    boolean isAdmin() {
        return role.equals("ADMIN");
    }
}


