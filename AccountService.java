import java.util.ArrayList;
// Accounts share the same recoverable file commit as inventory and rentals.
class AccountService {
    private final ArrayList<User> users;
    private final ConsoleUI ui;
    private final RentalDataStore dataStore;

    AccountService(ArrayList<User> users, ConsoleUI ui, RentalDataStore dataStore) {
        this.users = users;
        this.ui = ui;
        this.dataStore = dataStore;
    }

    static boolean validUsername(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{3,32}") && !value.equalsIgnoreCase("C") && !value.equals("0");
    }

    static boolean validName(String value) {
        return value != null && value.length() <= 100 && value.matches("[\\p{L}][\\p{L}\\p{M} .'-]{1,99}");
    }

    static boolean validEmail(String value) {
        return value != null && value.length() <= 254 && value.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private boolean embeddedAccountsChanged;

    boolean embeddedAccountsChanged() {
        return embeddedAccountsChanged;
    }

    void loadAccounts() {
        for (RentalDataStore.Record record : dataStore.records("accounts.txt")) {
            String[] fields = record.fields;
            try {
                RentalDataStore.require(fields.length == 6, "Expected six account fields");
                RentalDataStore.require(validUsername(fields[0]) && validName(fields[1]) && validEmail(fields[2]), "Invalid account name or login identifier");
                RentalDataStore.require(fields[4].equals("ADMIN") || fields[4].equals("CLIENT"), "Invalid role");
                RentalDataStore.require(fields[5].equals("REGULAR") || fields[5].equals("VIP"), "Invalid membership");
                RentalDataStore.require(fields[3].length() >= 8 && !GameCatalogService.containsControlCharacter(fields[3]), "Invalid stored password");
                RentalDataStore.require(findUserByLogin(fields[0]) == null && findUserByLogin(fields[2]) == null && !fields[0].equalsIgnoreCase(fields[2]), "Duplicate login identifier");
                users.add(new User(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]));
            } catch (IllegalArgumentException e) {
                throw dataStore.invalidRecord("accounts.txt", record, e);
            } catch (java.time.DateTimeException e) {
                throw dataStore.invalidRecord("accounts.txt", record, e);
            }
        }
        ensureEmbeddedAccount("admin", "System Admin", "admin@store.com", "admin123", "ADMIN", "REGULAR");
        ensureEmbeddedAccount("alex", "Alex R. Reyes", "alex@email.com", "alex12345", "CLIENT", "VIP");
        ensureEmbeddedAccount("juan", "Juan D. Cruz", "juan@email.com", "juan12345", "CLIENT", "REGULAR");
    }

    private void ensureEmbeddedAccount(String username, String name, String email, String password, String role, String membership) {
        User account = findUser(username);
        User emailOwner = findUserByLogin(email);
        RentalDataStore.require(emailOwner == null || emailOwner == account, "Embedded account email is already used: "+email);
        if (account == null) {
            User added = new User(username, name, email, password, role, membership);
            if (role.equals("ADMIN")) {
                users.add(0, added);
            } else {
                users.add(added);
            }
            embeddedAccountsChanged = true;
            return;
        }
        if (!account.username.equals(username) || !account.fullName.equals(name) || !account.email.equals(email) || !account.role.equals(role)) {
            embeddedAccountsChanged = true;
        }
        account.username = username;
        account.fullName = name;
        account.email = email;
        account.role = role;
        if (!Passwords.matches(password, account.password)) {
            account.password = Passwords.hashPlaintext(password);
            embeddedAccountsChanged = true;
        }
        // Keep saved client upgrades and rental references when restoring the embedded logins.
        if (role.equals("ADMIN") && !account.membership.equals(membership)) {
            account.membership = membership;
            embeddedAccountsChanged = true;
        }
    }

    void saveAccounts() {
        dataStore.saveData();
    }

    User findUser(String username) {
        for (User user : users) {
            if (user.username.equalsIgnoreCase(username)) {
                return user;
            }
        }
        return null;
    }

    User findUserByLogin(String login) {
        // Deterministic username lookup, with uniqueness enforced when registering/loading.
        User byName = findUser(login);
        if (byName != null) {
            return byName;
        }
        for (User user : users) {
            if (user.email.equalsIgnoreCase(login)) {
                return user;
            }
        }
        return null;
    }

    boolean emailExists(String email) {
        return findUserByLogin(email) != null;
    }

    void register() {
        String fullName = "";
        String username = "";
        String email = "";
        String password = "";
        int step = 1;
        while (true) {
            if (step == 1) {
                ui.clearScreen();
                ui.showRegisterFullNameScreen();
                String value = ui.readText(ui.prompt());
                if (value.equals("0")) {
                    return;
                }
                if (!validName(value)) {
                    ui.showFullNameInstructions();
                    ui.waitForEnter();
                    continue;
                }
                fullName = value;
                step = 2;
            } else if (step == 2) {
                ui.clearScreen();
                ui.showRegisterUsernameScreen(fullName);
                String value = ui.readText(ui.prompt());
                if (value.equalsIgnoreCase("C")) {
                    if (ui.askYesNo("Cancel registration and discard the entered information?")) {
                        ui.info("Registration cancelled. No account was created.");
                        return;
                    }
                    continue;
                }
                if (value.equals("0")) {
                    step = 1;
                    continue;
                }
                if (value.isEmpty()) {
                    ui.error("Username cannot be empty.");
                    ui.waitForEnter();
                    continue;
                }
                if (!validUsername(value)) {
                    ui.error("Use 3-32 letters, numbers, dots, underscores or hyphens.");
                    ui.waitForEnter();
                    continue;
                }
                if (findUserByLogin(value) != null) {
                    ui.error("Username is already taken. Please try another one.");
                    ui.waitForEnter();
                    continue;
                }
                username = value;
                step = 3;
            } else if (step == 3) {
                ui.clearScreen();
                ui.showRegisterEmailScreen(fullName, username);
                String value = ui.readText(ui.prompt());
                if (value.equalsIgnoreCase("C")) {
                    if (ui.askYesNo("Cancel registration and discard the entered information?")) {
                        ui.info("Registration cancelled. No account was created.");
                        return;
                    }
                    continue;
                }
                if (value.equals("0")) {
                    step = 2;
                    continue;
                }
                if (value.isEmpty()) {
                    ui.error("Email cannot be empty.");
                    ui.waitForEnter();
                    continue;
                }
                if (!validEmail(value)) {
                    ui.error("Invalid email format. Example: juan@gmail.com");
                    ui.waitForEnter();
                    continue;
                }
                if (emailExists(value)) {
                    ui.error("Email is already registered. Please use another email.");
                    ui.waitForEnter();
                    continue;
                }
                email = value;
                step = 4;
            } else if (step == 4) {
                ui.clearScreen();
                ui.showRegisterPasswordScreen(fullName, username, email);
                String value = ui.readPassword(ui.prompt());
                if (value.equalsIgnoreCase("C")) {
                    if (ui.askYesNo("Cancel registration and discard the entered information?")) {
                        ui.info("Registration cancelled. No account was created.");
                        return;
                    }
                    continue;
                }
                if (value.equals("0")) {
                    step = 3;
                    continue;
                }
                if (value.length() < 8) {
                    ui.error("Password must be at least 8 characters.");
                    ui.waitForEnter();
                    continue;
                }
                if (value.contains("|")) {
                    ui.error("Password cannot contain the | symbol.");
                    ui.waitForEnter();
                    continue;
                }
                password = value;
                step = 5;
            } else if (step == 5) {
                ui.printHeader("CREATE ACCOUNT", "CONFIRM PASSWORD");
                ui.showConfirmPasswordPrompt();
                ui.inputBar("CONFIRM PASSWORD");
                String confirm = ui.readPassword(ui.prompt());
                if (confirm.equalsIgnoreCase("C")) {
                    if (ui.askYesNo("Cancel registration and discard the entered information?")) {
                        ui.info("Registration cancelled. No account was created.");
                        return;
                    }
                    continue;
                }
                if (confirm.equals("0")) {
                    step = 4;
                    continue;
                }
                if (!confirm.equals(password)) {
                    ui.error("Passwords do not match. Please try again.");
                    ui.waitForEnter();
                    continue;
                }
                step = 6;
            } else {
                ui.clearScreen();
                ui.showRegistrationConfirmation(fullName, username, email);
                ui.inputBar("ENTER CHOICE");
                String choice = ui.readText(ui.prompt());
                if (choice.equals("1")) {
                    break;
                }
                if (choice.equals("0")) {
                    step = 5;
                    continue;
                }
                if (choice.equals("2")) {
                    step = 1;
                    continue;
                }
                if (choice.equalsIgnoreCase("C")) {
                    ui.info("Registration cancelled. No account was created.");
                    return;
                }
                ui.error("Invalid choice. Please enter 1, 2, 0, or C.");
                ui.waitForEnter();
            }
        }
        // Finalized only after confirmation.
        ui.loading("Creating account");
        users.add(new User(username, fullName, email, Passwords.hashPlaintext(password), "CLIENT", "REGULAR"));
        dataStore.writeLog("EVENT", "Registered account: " + username);
        saveAccounts();
        ui.clearScreen();
        ui.showAccountCreated();
        ui.waitForEnter();
    }

    User login() {
        while (true) {
            ui.clearScreen();
            ui.showLoginUsernameScreen();
            String login = ui.readText(ui.prompt());
            if (login.equals("0")) {
                return null;
            }
            if (login.isEmpty()) {
                ui.error("Username or email cannot be empty.");
                ui.waitForEnter();
                continue;
            }
            User user = findUserByLogin(login);
            if (user == null) {
                dataStore.writeLog("EVENT", "Failed login attempt for account: " + login.replaceAll("[\\p{Cntrl}|]", "?"));
                dataStore.saveData();
                ui.error("Username or email was not found.");
                ui.waitForEnter();
                continue;
            }
            boolean backToLoginName = false;
            while (true) {
                ui.clearScreen();
                ui.showLoginPasswordScreen(user.username);
                String password = ui.readPassword(ui.prompt());
                if (password.equals("0")) {
                    backToLoginName = true;
                    break;
                }
                ui.loading("Verifying account");
                if (!Passwords.matches(password, user.password)) {
                    dataStore.writeLog("EVENT", "Incorrect password attempt for: " + user.username);
                    dataStore.saveData();
                    ui.error("Incorrect password. Please try again.");
                    ui.waitForEnter();
                    continue;
                }
                dataStore.writeLog("EVENT", "Login: " + user.username + " (" + user.role + ")");
                dataStore.saveData();
                ui.success("LOGIN SUCCESSFUL - Welcome, " + user.fullName + "!");
                ui.pause(500);
                ui.loading("Loading dashboard");
                return user;
            }
            if (!backToLoginName) {
                return null;
            }
        }
    }
}
