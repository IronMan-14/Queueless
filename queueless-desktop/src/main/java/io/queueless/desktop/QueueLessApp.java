package io.queueless.desktop;

import io.queueless.contracts.Api;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** The actual staff workflow. All business decisions remain on the HTTP backend. */
public final class QueueLessApp extends Application {
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(24), runnable -> {
                Thread thread = new Thread(runnable, "queueless-desktop-http");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final RefreshCoordinator refresh = new RefreshCoordinator();
    private final PreferencesStore preferencesStore = new PreferencesStore();
    private final Path preferencesPath = Path.of(System.getProperty("queueless.preferences",
            Path.of(System.getProperty("user.home"), ".queueless", "operator-preferences.bin").toString()));
    private final List<Button> commandButtons = new ArrayList<>();
    private final List<Stage> historyWindows = new ArrayList<>();
    private OperatorPreferences preferences;
    private Stage stage;
    private HttpApiClient client;
    private Api.Session session;
    private Api.Snapshot snapshot;
    private Timeline polling;
    private ComboBox<Api.Service> services;
    private ComboBox<Api.Counter> counters;
    private Spinner<Integer> refreshInterval;
    private TableView<Api.Visit> visits;
    private TableView<Api.Appointment> appointments;
    private TableView<Api.Notification> notifications;
    private TableView<Api.Report> reports;
    private Label status;
    private Label current;
    private Label summary;
    private Label refreshedAt;
    private Button retry;
    private DatePicker reportFrom;
    private DatePicker reportTo;
    private boolean rendering;
    private boolean commandRunning;
    private Api.Command uncertainCommand;
    private long uncertainActorId;
    private String uncertainBackendAddress;
    private long selectedService;
    private String backendAddress = System.getProperty("queueless.backend", "http://localhost:8080/queueless");

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        preferences = preferencesStore.load(preferencesPath);
        selectedService = preferences.serviceId();
        stage.setTitle("QueueLess · Staff Console");
        stage.setMinWidth(900);
        stage.setMinHeight(650);
        showLogin(null);
        stage.setWidth(preferences.width());
        stage.setHeight(preferences.height());
        stage.show();
    }

    private void showLogin(String message) {
        List.copyOf(historyWindows).forEach(Stage::close);
        stopPolling();
        refresh.invalidate();
        session = null;
        snapshot = null;
        commandRunning = false;
        commandButtons.clear();
        Label title = new Label("QueueLess");
        title.getStyleClass().add("brand");
        Label subtitle = new Label("Staff operations console");
        subtitle.getStyleClass().add("subtitle");
        TextField backend = new TextField(uncertainCommand == null ? backendAddress : uncertainBackendAddress);
        backend.setId("backend-address");
        backend.setDisable(uncertainCommand != null);
        TextField email = new TextField();
        email.setId("login-email");
        email.setPromptText("staff@example.com");
        PasswordField password = new PasswordField();
        password.setId("login-password");
        password.setPromptText("Password");
        Label feedback = new Label(message == null ? "Sign in with an assigned staff or administrator account." : message);
        if (uncertainCommand != null) feedback.setText("An earlier action has an unknown outcome. Sign in with the same account to retry it safely.");
        feedback.setId("login-feedback");
        feedback.setWrapText(true);
        Button signIn = new Button("Sign in");
        signIn.setId("sign-in");
        signIn.getStyleClass().add("primary");
        signIn.setDefaultButton(true);
        signIn.setMaxWidth(Double.MAX_VALUE);
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(24, 24);
        progress.setVisible(false);
        signIn.setOnAction(event -> {
            if (email.getText().isBlank() || password.getText().isEmpty()) {
                feedback.setText("Enter your email and password.");
                return;
            }
            final HttpApiClient connecting;
            try { connecting = new HttpApiClient(backend.getText()); }
            catch (IllegalArgumentException invalid) { feedback.setText(invalid.getMessage()); return; }
            String enteredPassword = password.getText();
            String enteredEmail = email.getText().trim();
            password.clear();
            signIn.setDisable(true);
            progress.setVisible(true);
            feedback.setText("Connecting securely to the backend…");
            long ticket = refresh.generation();
            background(() -> connecting.login(enteredEmail, enteredPassword), authenticated -> {
                if (!refresh.isCurrent(ticket)) { connecting.clearSession(); return; }
                if (uncertainCommand != null && authenticated.user().id() != uncertainActorId) {
                    background(() -> { connecting.logout(); return true; }, ignored -> { }, ignored -> { });
                    signIn.setDisable(false);
                    progress.setVisible(false);
                    feedback.setText("An earlier action belongs to a different account. Sign in with that same account to recover its outcome.");
                    return;
                }
                if (!List.of("STAFF", "ADMIN").contains(authenticated.user().role())) {
                    background(() -> { connecting.logout(); return true; }, ignored -> { }, ignored -> { });
                    signIn.setDisable(false);
                    progress.setVisible(false);
                    feedback.setText("This console is for staff and administrators. Use the website for your user account.");
                    return;
                }
                client = connecting;
                session = authenticated;
                backendAddress = backend.getText().trim();
                showDashboard();
            }, failure -> {
                signIn.setDisable(false);
                progress.setVisible(false);
                feedback.setText(safeMessage(failure));
            });
        });
        VBox card = new VBox(12, title, subtitle, labeled("Backend", backend), labeled("Email", email),
                labeled("Password", password), signIn, progress, feedback);
        card.getStyleClass().add("card");
        card.setMaxWidth(450);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPadding(new Insets(32));
        StackPane root = new StackPane(card);
        root.setPadding(new Insets(30));
        scene(root);
    }

    private void showDashboard() {
        refresh.invalidate();
        selectedService = 0; // Load assignments before applying a preference from a previous account.
        services = new ComboBox<>();
        services.setId("service-selector");
        services.setPrefWidth(235);
        services.setPromptText("Assigned service");
        services.setConverter(converter(Api.Service::name));
        counters = new ComboBox<>();
        counters.setId("counter-selector");
        counters.setPrefWidth(260);
        counters.setPromptText("Select counter");
        counters.setConverter(converter(counter -> counter.name() + (counter.paused() ? " · paused" : "")
                + (counter.ownerId() == null ? " · unclaimed" : counter.ownerId() == session.user().id() ? " · yours" : " · occupied")));
        services.valueProperty().addListener((observable, oldValue, value) -> {
            if (!rendering && value != null && value.id() != selectedService) {
                selectedService = value.id();
                refresh.invalidate();
                snapshot = null;
                visits.getItems().clear();
                appointments.getItems().clear();
                counters.getItems().clear();
                updateActions();
                refreshSnapshot();
            }
        });
        counters.valueProperty().addListener((observable, oldValue, value) -> updateActions());
        Label brand = new Label("QueueLess");
        brand.getStyleClass().add("heading");
        Label identity = new Label(session.user().name() + " · " + session.user().role());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button logout = new Button("Sign out");
        logout.setId("sign-out");
        logout.setOnAction(event -> signOut());
        HBox top = new HBox(18, brand, identity, spacer, logout);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(18, 22, 12, 22));
        Button manualRefresh = new Button("Refresh now");
        manualRefresh.setId("refresh-now");
        manualRefresh.setOnAction(event -> refreshSnapshot());
        refreshInterval = new Spinner<>(3, 60, preferences.refreshSeconds());
        refreshInterval.setPrefWidth(82);
        refreshInterval.valueProperty().addListener((observable, oldValue, value) -> startPolling());
        HBox selectors = new HBox(10, services, counters, manualRefresh, new Label("Refresh (seconds)"), refreshInterval);
        selectors.setAlignment(Pos.CENTER_LEFT);
        selectors.setPadding(new Insets(0, 22, 14, 22));
        current = new Label("Choose a counter to begin.");
        current.setId("current-token");
        current.getStyleClass().add("current");
        summary = new Label("Loading queue…");
        refreshedAt = new Label("Periodically refreshed; all actions are checked by the backend.");
        VBox overview = new VBox(7, current, summary, refreshedAt);
        overview.getStyleClass().add("card");
        overview.setPadding(new Insets(18));
        BorderPane.setMargin(overview, new Insets(0, 22, 14, 22));

        visits = new TableView<>();
        visits.setId("queue-table");
        column(visits, "Token", Api.Visit::token, 105);
        column(visits, "State", Api.Visit::state, 115);
        column(visits, "Source", item -> displaySource(item.source()), 110);
        column(visits, "Dispatch", item -> "A".equals(item.dispatchClass()) ? "Appointment" : "Walk-in", 130);
        column(visits, "Ahead", item -> item.position() == null ? "—" : Integer.toString(Math.max(0, item.position() - 1)), 75);
        column(visits, "Estimate", item -> item.estimatedMinutes() == null ? "—" : "~" + item.estimatedMinutes() + " min", 95);
        column(visits, "Joined", item -> displayTime(item.createdAt()), 180);
        visits.setPlaceholder(new Label("No queue entries for this service."));
        visits.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, value) -> updateActions());
        FlowPane actions = new FlowPane(8, 8);
        actions.getChildren().addAll(
                operation("Claim / resume", "CLAIM", this::counterCommand),
                operation("Release counter", "RELEASE", this::counterCommand),
                operation("Pause / resume", "PAUSE", this::counterCommand),
                operation("Call next", "NEXT", this::counterCommand),
                operation("Recall", "RECALL", this::visitCommand),
                operation("Start service", "START", this::visitCommand),
                operation("Complete service", "COMPLETE", this::visitCommand),
                operation("Skip called token", "SKIP", this::visitCommand),
                operation("Abort service", "ABORT", this::visitCommand),
                operation("Requeue selected", "REQUEUE", this::visitCommand));
        if ("ADMIN".equals(session.user().role())) {
            actions.getChildren().add(operation("Take over counter", "TAKEOVER", this::counterCommand));
        }
        Label actionHelp = new Label("Call, start, complete, recall and skip act on the selected counter's active token. Requeue acts on the selected row.");
        actionHelp.setWrapText(true);
        VBox queuePanel = new VBox(12, actions, actionHelp, visits);
        VBox.setVgrow(visits, Priority.ALWAYS);
        queuePanel.setPadding(new Insets(16));
        TabPane tabs = new TabPane(tab("Queue", queuePanel), tab("Appointments", appointmentPanel()),
                tab("Notifications", notificationPanel()), tab("Reports", reportPanel()));
        status = new Label("Connected. Loading assigned services…");
        if (uncertainCommand != null) status.setText("An earlier action has an unknown outcome. Use Retry to recover it before making another change.");
        status.setId("operation-status");
        status.setWrapText(true);
        retry = new Button("Retry uncertain command");
        retry.setId("retry-command");
        retry.setVisible(uncertainCommand != null);
        retry.setManaged(uncertainCommand != null);
        retry.setOnAction(event -> { if (uncertainCommand != null) executeCommand(uncertainCommand); });
        VBox bottom = new VBox(8, status, retry);
        bottom.setPadding(new Insets(14, 22, 18, 22));
        Menu history = new Menu("History");
        MenuItem queueHistory = new MenuItem("Queue history…");
        queueHistory.setOnAction(event -> showVisitHistory());
        MenuItem appointmentHistory = new MenuItem("Appointment history…");
        appointmentHistory.setOnAction(event -> showAppointmentHistory());
        history.getItems().addAll(queueHistory, appointmentHistory);
        VBox header = new VBox(new MenuBar(history), top, selectors, overview);
        BorderPane root = new BorderPane(tabs, header, null, bottom, null);
        scene(root);
        updateActions();
        startPolling();
        refreshSnapshot();
    }

    private Node appointmentPanel() {
        appointments = new TableView<>();
        column(appointments, "ID", item -> Long.toString(item.id()), 75);
        column(appointments, "Arrival window", item -> displayTime(item.startsAt()) + " – " + displayTime(item.endsAt()), 360);
        column(appointments, "State", Api.Appointment::state, 140);
        column(appointments, "Queue entry", item -> item.visitId() == null ? "—" : item.visitId().toString(), 120);
        appointments.setPlaceholder(new Label("No relevant appointments for this service."));
        Label help = new Label("Review appointment arrival windows and current state. Bookings and personal check-in are available through the website.");
        help.setWrapText(true);
        VBox content = new VBox(12, help, appointments);
        content.setPadding(new Insets(16));
        VBox.setVgrow(appointments, Priority.ALWAYS);
        return content;
    }

    private Node notificationPanel() {
        notifications = new TableView<>();
        column(notifications, "Read", item -> item.read() ? "Yes" : "New", 75);
        column(notifications, "Message", Api.Notification::message, 630);
        column(notifications, "Created", item -> displayTime(item.createdAt()), 190);
        Button markRead = new Button("Mark selected as read");
        markRead.setOnAction(event -> {
            Api.Notification selected = notifications.getSelectionModel().getSelectedItem();
            if (selected != null) executeCommand(command("READ_NOTIFICATION", selected.id(), 0, 0, 0, Map.of()));
        });
        VBox content = new VBox(12, markRead, notifications);
        content.setPadding(new Insets(16));
        VBox.setVgrow(notifications, Priority.ALWAYS);
        return content;
    }

    private Node reportPanel() {
        reports = new TableView<>();
        reports.setId("report-table");
        column(reports, "Service", Api.Report::serviceName, 200);
        column(reports, "Completed", item -> Long.toString(item.completed()), 95);
        column(reports, "Cancelled", item -> Long.toString(item.cancelled()), 90);
        column(reports, "No-shows", item -> Long.toString(item.noShows()), 90);
        column(reports, "Mean wait", item -> minutes(item.averageWaitMinutes()), 100);
        column(reports, "Mean service", item -> minutes(item.averageServiceMinutes()), 105);
        column(reports, "Appointments", item -> Long.toString(item.appointments()), 110);
        column(reports, "Fulfilled", item -> Long.toString(item.fulfilledAppointments()), 90);
        reportFrom = new DatePicker(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        reportTo = new DatePicker(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        Button load = new Button("Load report");
        load.setId("load-report");
        load.setOnAction(event -> loadReport());
        Button export = new Button("Export CSV…");
        export.setOnAction(event -> exportReport());
        HBox filters = new HBox(10, new Label("From"), reportFrom, new Label("To"), reportTo, load, export);
        filters.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("Reports use the backend's business-date rules and your authorized services. Blank averages mean no completed observations.");
        note.setWrapText(true);
        VBox content = new VBox(12, filters, note, reports);
        content.setPadding(new Insets(16));
        VBox.setVgrow(reports, Priority.ALWAYS);
        return content;
    }

    private void refreshSnapshot() {
        if (client == null || session == null || commandRunning) return;
        long ticket = refresh.begin();
        if (ticket < 0) return;
        HttpApiClient requestClient = client;
        long requestedService = selectedService;
        background(() -> requestClient.snapshot(requestedService), result -> {
            if (refresh.finish(ticket)) render(result, requestedService);
        }, failure -> {
            if (refresh.finish(ticket)) failure(failure, false);
        });
    }

    private void render(Api.Snapshot result, long requestedService) {
        snapshot = result;
        rendering = true;
        try {
            long preferredService = selectedService == 0 ? preferences.serviceId() : selectedService;
            Api.Service chosen = result.services().stream().filter(item -> item.id() == preferredService)
                    .findFirst().orElse(result.services().isEmpty() ? null : result.services().getFirst());
            services.setItems(FXCollections.observableArrayList(result.services()));
            services.setValue(chosen);
            selectedService = chosen == null ? 0 : chosen.id();
            long preferredCounter = counters.getValue() == null ? preferences.counterId() : counters.getValue().id();
            List<Api.Counter> available = result.counters().stream().filter(item -> item.serviceId() == selectedService).toList();
            Api.Counter chosenCounter = available.stream().filter(item -> item.id() == preferredCounter).findFirst()
                    .orElseGet(() -> available.stream().filter(item -> item.ownerId() != null && item.ownerId() == session.user().id())
                            .findFirst().orElse(available.isEmpty() ? null : available.getFirst()));
            counters.setItems(FXCollections.observableArrayList(available));
            counters.setValue(chosenCounter);
            long selectedVisit = visits.getSelectionModel().getSelectedItem() == null ? 0 : visits.getSelectionModel().getSelectedItem().id();
            List<Api.Visit> serviceVisits = result.visits().stream().filter(item -> item.serviceId() == selectedService).toList();
            visits.setItems(FXCollections.observableArrayList(serviceVisits));
            serviceVisits.stream().filter(item -> item.id() == selectedVisit).findFirst().ifPresent(item -> visits.getSelectionModel().select(item));
            appointments.setItems(FXCollections.observableArrayList(result.appointments().stream().filter(item -> item.serviceId() == selectedService).toList()));
            notifications.setItems(FXCollections.observableArrayList(result.notifications()));
            long waiting = serviceVisits.stream().filter(item -> "WAITING".equals(item.state())).count();
            long active = serviceVisits.stream().filter(item -> List.of("CALLED", "SERVING").contains(item.state())).count();
            summary.setText(waiting + " waiting · " + active + " active · " + available.size() + " counters"
                    + (chosen == null ? " · no assigned services" : chosen.open() ? " · admissions enabled (hours apply)" : " · admissions disabled"));
            refreshedAt.setText("Last server update: " + displayTime(result.serverTime()) + " IST · estimates are approximate");
            updateActions();
        } finally { rendering = false; }
        if (requestedService != selectedService && selectedService != 0) refreshSnapshot();
    }

    private Button operation(String label, String operation, Consumer<String> action) {
        Button button = new Button(label);
        button.setId("command-" + operation.toLowerCase(java.util.Locale.ROOT));
        button.setUserData(operation);
        button.setOnAction(event -> action.accept(operation));
        if (List.of("NEXT", "START", "COMPLETE").contains(operation)) button.getStyleClass().add("primary");
        commandButtons.add(button);
        return button;
    }

    private void counterCommand(String operation) {
        Api.Counter counter = counters.getValue();
        if (counter == null) return;
        Map<String, String> fields = Map.of();
        if ("PAUSE".equals(operation)) fields = Map.of("paused", Boolean.toString(!counter.paused()));
        if ("TAKEOVER".equals(operation)) {
            String reason = reason("Take over counter", "The active token stays with this counter. Record the reason for taking over.");
            if (reason == null) return;
            fields = Map.of("reason", reason);
        }
        executeCommand(command(operation, 0, counter.id(), 0, counter.claimVersion(), fields));
    }

    private void visitCommand(String operation) {
        Api.Counter counter = counters.getValue();
        Api.Visit visit = "REQUEUE".equals(operation) ? visits.getSelectionModel().getSelectedItem() : activeVisit();
        if (visit == null || counter == null) return;
        Map<String, String> fields = Map.of();
        if (List.of("ABORT", "SKIP").contains(operation)) {
            String reason = reason(operation.equals("ABORT") ? "Abort service" : "Skip called token", "Record the reason for " + visit.token() + ".");
            if (reason == null) return;
            fields = Map.of("reason", reason);
        }
        executeCommand(command(operation, visit.id(), counter.id(), visit.version(), counter.claimVersion(), fields));
    }

    private Api.Command command(String operation, long entityId, long counterId, long expectedVersion, long claimVersion, Map<String, String> fields) {
        return new Api.Command(UUID.randomUUID().toString(), operation, selectedService, entityId, counterId, expectedVersion, claimVersion, fields);
    }

    private void executeCommand(Api.Command request) {
        if (commandRunning || client == null || session == null) return;
        if (uncertainCommand != null && !uncertainCommand.requestId().equals(request.requestId())) {
            status.setText("Resolve the uncertain command with Retry before sending another change.");
            return;
        }
        commandRunning = true;
        refresh.invalidate();
        updateActions();
        status.setText("Processing " + request.operation().toLowerCase().replace('_', ' ') + "…");
        long ticket = refresh.generation();
        long actorId = session.user().id();
        boolean recovering = uncertainCommand != null && uncertainCommand.requestId().equals(request.requestId());
        // Retain the command even if the operator signs out while its response is still in flight.
        uncertainCommand = request;
        uncertainActorId = actorId;
        uncertainBackendAddress = backendAddress;
        HttpApiClient requestClient = client;
        background(() -> requestClient.command(request), result -> {
            if (!refresh.isCurrent(ticket)) return;
            commandRunning = false;
            uncertainCommand = null;
            uncertainActorId = 0;
            uncertainBackendAddress = null;
            retry.setVisible(false);
            retry.setManaged(false);
            status.setText(result.message() + (result.replayed() ? " (Previously completed request recovered.)" : ""));
            updateActions();
            refreshSnapshot();
        }, problem -> {
            if (!refresh.isCurrent(ticket)) return;
            commandRunning = false;
            if (problem instanceof IOException || problem instanceof InterruptedException
                    || recovering && problem instanceof ApiException expired && expired.status() == 401
                    || problem instanceof ApiException api && api.retryable()) {
                uncertainCommand = request;
                uncertainActorId = actorId;
                retry.setVisible(true);
                retry.setManaged(true);
            } else {
                uncertainCommand = null;
                uncertainActorId = 0;
                uncertainBackendAddress = null;
                retry.setVisible(false);
                retry.setManaged(false);
            }
            updateActions();
            failure(problem, uncertainCommand != null);
            refreshSnapshot();
        });
    }

    private Api.Visit activeVisit() {
        if (snapshot == null || counters == null || counters.getValue() == null || counters.getValue().visitId() == null) return null;
        long activeId = counters.getValue().visitId();
        return snapshot.visits().stream().filter(item -> item.id() == activeId).findFirst().orElse(null);
    }

    private void updateActions() {
        if (counters == null) return;
        Api.Counter counter = counters.getValue();
        Api.Visit active = activeVisit();
        Api.Visit selected = visits == null ? null : visits.getSelectionModel().getSelectedItem();
        boolean mine = counter != null && counter.ownerId() != null && session != null && counter.ownerId() == session.user().id();
        for (Button button : commandButtons) {
            String operation = (String) button.getUserData();
            boolean allowed = counter != null && switch (operation) {
                case "CLAIM" -> counter.ownerId() == null || mine;
                case "TAKEOVER" -> !mine;
                case "RELEASE" -> mine && counter.visitId() == null;
                case "PAUSE" -> mine;
                case "NEXT" -> mine && !counter.paused() && counter.visitId() == null;
                case "START", "RECALL", "SKIP" -> mine && active != null && "CALLED".equals(active.state());
                case "COMPLETE", "ABORT" -> mine && active != null && "SERVING".equals(active.state());
                case "REQUEUE" -> selected != null && "SKIPPED".equals(selected.state());
                default -> false;
            };
            button.setDisable(commandRunning || uncertainCommand != null || !allowed);
        }
        services.setDisable(commandRunning);
        counters.setDisable(commandRunning);
        if (retry != null) retry.setDisable(commandRunning);
        if (current != null) current.setText(counter == null ? "No counter selected."
                : active == null ? counter.name() + " · no active token"
                : counter.name() + " · " + active.token() + " · " + active.state());
    }

    private void loadReport() {
        LocalDate from = reportFrom.getValue(), to = reportTo.getValue();
        if (!validDates(from, to)) return;
        long ticket = refresh.generation();
        HttpApiClient requestClient = client;
        status.setText("Loading report…");
        background(() -> requestClient.reports(from, to), result -> {
            if (!refresh.isCurrent(ticket)) return;
            reports.setItems(FXCollections.observableArrayList(result));
            status.setText("Report loaded from database records.");
        }, problem -> { if (refresh.isCurrent(ticket)) failure(problem, false); });
    }

    private void showVisitHistory() {
        if (selectedService <= 0 || client == null) return;
        long serviceId = selectedService;
        HttpApiClient historyClient = client;
        TableView<Api.Visit> table = new TableView<>();
        column(table, "Token", Api.Visit::token, 230);
        column(table, "State", Api.Visit::state, 140);
        column(table, "Source", item -> displaySource(item.source()), 140);
        column(table, "Created", item -> displayTime(item.createdAt()), 250);
        historyWindow("Queue history · " + services.getValue().name(), table,
                cursor -> historyClient.visitHistory(serviceId, cursor, 50));
    }

    private void showAppointmentHistory() {
        if (selectedService <= 0 || client == null) return;
        long serviceId = selectedService;
        HttpApiClient historyClient = client;
        TableView<Api.Appointment> table = new TableView<>();
        column(table, "ID", item -> Long.toString(item.id()), 80);
        column(table, "State", Api.Appointment::state, 140);
        column(table, "Starts", item -> displayTime(item.startsAt()), 240);
        column(table, "Ends", item -> displayTime(item.endsAt()), 240);
        historyWindow("Appointment history · " + services.getValue().name(), table,
                cursor -> historyClient.appointmentHistory(serviceId, cursor, 50));
    }

    private <T> void historyWindow(String title, TableView<T> table, HistoryLoader<T> loader) {
        Stage window = new Stage();
        window.initOwner(stage);
        window.setTitle(title);
        table.setId("history-table");
        Label message = new Label("Loading history…");
        message.setWrapText(true);
        Button newest = new Button("Newest records");
        Button older = new Button("Older records");
        older.setId("history-older");
        older.setUserData(0L);
        HttpApiClient historyClient = client;
        Consumer<Long> load = before -> {
            newest.setDisable(true);
            older.setDisable(true);
            message.setText("Loading history…");
            background(() -> loader.load(before), page -> {
                if (!window.isShowing() || client != historyClient) return;
                table.setItems(FXCollections.observableArrayList(page.items()));
                older.setUserData(page.nextBeforeId());
                older.setDisable(page.nextBeforeId() == 0);
                newest.setDisable(false);
                message.setText(page.items().size() + " records, newest first." + (page.nextBeforeId() == 0 ? " End of history." : " More records are available."));
            }, problem -> {
                if (!window.isShowing() || client != historyClient) return;
                newest.setDisable(false);
                older.setDisable((long) older.getUserData() == 0);
                if (problem instanceof ApiException api && api.status() == 401) failure(problem, false);
                else message.setText(safeMessage(problem));
            });
        };
        newest.setOnAction(event -> load.accept(0L));
        older.setOnAction(event -> load.accept((Long) older.getUserData()));
        VBox content = new VBox(12, new HBox(10, newest, older), message, table);
        content.setPadding(new Insets(18));
        VBox.setVgrow(table, Priority.ALWAYS);
        Scene scene = new Scene(content, 850, 550);
        scene.getStylesheets().add(QueueLessApp.class.getResource("console.css").toExternalForm());
        window.setScene(scene);
        historyWindows.add(window);
        window.setOnHidden(event -> historyWindows.remove(window));
        window.show();
        load.accept(0L);
    }

    @FunctionalInterface private interface HistoryLoader<T> { Api.Page<T> load(long beforeId) throws Exception; }

    private void exportReport() {
        LocalDate from = reportFrom.getValue(), to = reportTo.getValue();
        if (!validDates(from, to)) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export service report");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV report", "*.csv"));
        chooser.setInitialFileName("queueless-" + from + "-" + to + ".csv");
        File destination = chooser.showSaveDialog(stage);
        if (destination == null) return;
        long ticket = refresh.generation();
        HttpApiClient requestClient = client;
        background(() -> {
            byte[] data = requestClient.reportCsv(from, to);
            Files.write(destination.toPath(), data);
            return destination.getAbsolutePath();
        }, path -> { if (refresh.isCurrent(ticket)) status.setText("CSV exported to " + path); },
                problem -> { if (refresh.isCurrent(ticket)) failure(problem, false); });
    }

    private boolean validDates(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            status.setText("Choose a valid report date range.");
            return false;
        }
        return true;
    }

    private void failure(Throwable failure, boolean uncertain) {
        if (failure instanceof ApiException api && api.status() == 401) {
            savePreferences();
            if (client != null) client.clearSession();
            client = null;
            showLogin("Your session expired. Sign in again.");
            return;
        }
        status.setText(safeMessage(failure) + (uncertain
                ? " The outcome may be unknown. Refresh, then use Retry to reuse the same request ID." : ""));
    }

    private static String safeMessage(Throwable failure) {
        if (failure instanceof ApiException || failure instanceof IllegalArgumentException) return failure.getMessage();
        if (failure instanceof IOException) return "Could not reach the backend or read its response. Check the connection and backend address.";
        if (failure instanceof InterruptedException) return "The request was interrupted.";
        if (failure instanceof RejectedExecutionException) return "The console is busy. Try again shortly.";
        return "The operation could not be completed. Refresh and try again.";
    }

    private void signOut() {
        savePreferences();
        HttpApiClient previous = client;
        client = null;
        showLogin("Signing out…");
        long ticket = refresh.generation();
        if (previous != null) background(() -> { previous.logout(); return true; },
                ignored -> { if (refresh.isCurrent(ticket)) showLogin("Signed out."); },
                ignored -> { if (refresh.isCurrent(ticket)) showLogin("Local session cleared. The backend session will expire if it could not be reached."); });
    }

    private void startPolling() {
        stopPolling();
        if (refreshInterval == null) return;
        polling = new Timeline(new KeyFrame(javafx.util.Duration.seconds(refreshInterval.getValue()), event -> refreshSnapshot()));
        polling.setCycleCount(Timeline.INDEFINITE);
        polling.play();
    }

    private void stopPolling() { if (polling != null) polling.stop(); }

    private <T> void background(Callable<T> action, Consumer<T> success, Consumer<Throwable> failure) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return action.call(); }
        };
        task.setOnSucceeded(event -> success.accept(task.getValue()));
        task.setOnFailed(event -> failure.accept(task.getException()));
        try { workers.execute(task); }
        catch (RejectedExecutionException full) { failure.accept(full); }
    }

    private void savePreferences() {
        try {
            long counterId = counters == null || counters.getValue() == null ? preferences.counterId() : counters.getValue().id();
            int seconds = refreshInterval == null ? preferences.refreshSeconds() : refreshInterval.getValue();
            preferences = new OperatorPreferences(Math.max(0, selectedService), counterId, seconds,
                    Math.min(4000, Math.max(900, stage.getWidth())), Math.min(2400, Math.max(650, stage.getHeight())));
            preferencesStore.save(preferencesPath, preferences);
        } catch (IOException | IllegalArgumentException failure) {
            if (status != null && session != null) status.setText("Preferences could not be saved; the service workflow is unaffected.");
        }
    }

    @Override public void stop() {
        stopPolling();
        refresh.close();
        savePreferences();
        if (client != null) client.clearSession();
        workers.shutdownNow();
    }

    private void scene(Region root) {
        double width = stage.getScene() == null ? preferences.width() : stage.getScene().getWidth();
        double height = stage.getScene() == null ? preferences.height() : stage.getScene().getHeight();
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(QueueLessApp.class.getResource("console.css").toExternalForm());
        stage.setScene(scene);
    }

    private static VBox labeled(String label, Node input) { return new VBox(5, new Label(label), input); }
    private static Tab tab(String title, Node content) { Tab tab = new Tab(title, content); tab.setClosable(false); return tab; }
    private static String minutes(Double value) { return value == null ? "—" : String.format(java.util.Locale.ROOT, "%.1f min", value); }
    private static String displaySource(String source) { return "WALK_IN".equals(source) ? "Walk-in" : "APPOINTMENT".equals(source) ? "Appointment" : source; }
    private static String displayTime(String timestamp) {
        if (timestamp == null) return "—";
        try { return DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata")).format(Instant.parse(timestamp)); }
        catch (java.time.DateTimeException invalid) { return timestamp; }
    }

    private static <T> StringConverter<T> converter(Function<T, String> display) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : display.apply(value); }
            @Override public T fromString(String value) { return null; }
        };
    }

    private static <T> void column(TableView<T> table, String title, Function<T, String> value, double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private String reason(String title, String prompt) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(prompt);
        dialog.setContentText("Reason:");
        return dialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
    }
}
