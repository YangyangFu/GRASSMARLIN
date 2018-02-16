package iadgov.fingerprint;

import com.sun.javafx.collections.ObservableListWrapper;
import grassmarlin.Logger;
import grassmarlin.RuntimeConfiguration;
import grassmarlin.plugins.IPlugin;
import grassmarlin.plugins.internal.graph.StageAddProperties;
import grassmarlin.plugins.internal.graph.StageRecordCompletion;
import grassmarlin.session.Session;
import grassmarlin.session.pipeline.PipelineTemplate;
import grassmarlin.ui.common.SessionInterfaceController;
import grassmarlin.ui.common.menu.ActiveMenuItem;
import iadgov.fingerprint.manager.FingerPrintGui;
import iadgov.fingerprint.processor.FingerprintState;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class Plugin implements IPlugin, IPlugin.DefinesPipelineStages, IPlugin.SessionEventHooks {

    private FPDocument document;
    private RuntimeConfiguration config;
    private Collection<PipelineStage> stages;
    private BooleanProperty saveAsLeavesOld;
    private FileSystem jarFileSystem;

    public Plugin(final RuntimeConfiguration config) {
        this.config = config;
        this.saveAsLeavesOld = new SimpleBooleanProperty(true);
        FPDocument.initializeInstance(this);
        this.document = FPDocument.getInstance();

        stages = new ArrayList<>();
        stages.add(new PipelineStage(true, StageFingerprint.NAME, StageFingerprint.class, StageFingerprint.DEFAULT_OUTPUT, StageFingerprint.FINGERPRINT_PROPERTIES));

        initializeFingerprints();
    }

    /*
    * Public API
     */

    /**
     * A function that converts the XML data located at a path that conforms to the fingerprint3 XSD into a FingerprintState Object
     * @param fingerprintPath The path to the XSD conforming XML data
     * @return The Fingerprint Object representation of the XML
     */
    public FingerprintState loadFingerpint(Path fingerprintPath) {
        try {
            return this.document.load(fingerprintPath);
        } catch (JAXBException je) {
            Logger.log(Logger.Severity.ERROR, "Unable to load fingerprint at %s", fingerprintPath.toString());
            return null;
        }
    }

    /**
     * A function that makes a given Fingerprint available for use by the fingerprint processing stage
     * @param state The <code>FingerprintState</code> of the Fingerprint to make available
     */
    public boolean registerFingerprint(FingerprintState state) {
        return this.document.registerFingerprint(state);
    }

    @Override
    public Collection<PipelineStage> getPipelineStages() {
        return this.stages;
    }

    @Override
    public String getName() {
        return "Fingerprint";
    }

    private final Image iconLarge = new Image(Plugin.class.getClassLoader().getResourceAsStream("plugin.png"));
    @Override
    public Image getImageForSize(final int pixels) {
        return iconLarge;
    }

    @Override
    public Collection<MenuItem> getMenuItems() {
        return Collections.singleton(new ActiveMenuItem("Fingerprint Manager", event -> {
            FingerPrintGui editorGui = new FingerPrintGui(this);
            Stage editorWindow = new Stage();
            try {
                editorGui.start(editorWindow);
                editorWindow.show();
            } catch (Exception e) {
                e.printStackTrace();
                Logger.log(Logger.Severity.ERROR, "Error opening Fingerprint Editor");
            }
        }));
    }

    @Override
    public Serializable getConfiguration(PipelineStage stage, Serializable config) {
        if (stage.getStage().isAssignableFrom(StageFingerprint.class)) {
            if (config instanceof ArrayList) {
                FingerprintSelectionDialog dialog = new FingerprintSelectionDialog(document.getRunningFingerprints(), (List)config);
                Optional<ArrayList<FingerprintState>> newConfig = dialog.showAndWait();
                return newConfig.orElse((ArrayList)config);
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public Serializable getDefaultConfiguration(PipelineStage stage) {
        return new ArrayList<>(document.getRunningFingerprints());
    }

    @Override
    public void sessionCreated(Session session, SessionInterfaceController tabs) {
        final PipelineStage propStage = new PipelineStage(false, StageAddProperties.NAME, StageAddProperties.class, StageAddProperties.DEFAULT_OUTPUT);
        if (session.canSetPipeline().get()) {
            PipelineTemplate template = session.getSessionDefaultTemplate();
            if (!template.getStages().stream().anyMatch(stage -> StageFingerprint.class.isAssignableFrom(stage.getStage()))) {
                template.connectInputToNewStage(stage -> StageRecordCompletion.class.isAssignableFrom(stage.getStage()), StageFingerprint.DEFAULT_OUTPUT, () -> {
                    PipelineStage stage = new PipelineStage(true, StageFingerprint.NAME, StageFingerprint.class, StageFingerprint.DEFAULT_OUTPUT, StageFingerprint.FINGERPRINT_PROPERTIES);
                    template.addConnection(stage, StageFingerprint.FINGERPRINT_PROPERTIES, propStage);

                    return stage;
                });
            }
        }
    }

    @Override
    public void sessionClosed(Session session) {
        // Do Nothing
    }

    private class FingerprintSelectionDialog extends Dialog<ArrayList<FingerprintState>>{
        private ObservableList<FingerprintState> fingerprints;
        private ObservableList<FingerprintState> enabledFingerprints;

        public FingerprintSelectionDialog(List<FingerprintState> allFingerprints, List<FingerprintState> enabledFingerprints) {
            super();

            this.fingerprints = new ObservableListWrapper<>(allFingerprints);
            this.enabledFingerprints = new ObservableListWrapper<>(new ArrayList<>(enabledFingerprints));
            this.initContent();
            this.setTitle("Fingerprints");
            RuntimeConfiguration.setIcons(this);
            this.setResultConverter(this::handleButtonPress);

            this.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        }

        private void initContent() {
            this.getDialogPane().setOnKeyPressed(event -> {
                if (event.isShortcutDown()) {
                    if (KeyCode.E == event.getCode()) {
                        this.enabledFingerprints.clear();
                        this.enabledFingerprints.addAll(this.fingerprints);
                    } else if (KeyCode.D == event.getCode()) {
                        this.enabledFingerprints.clear();
                    }
                }
            });
            ListView<FingerprintState> fingerprintView = new ListView<>(this.fingerprints);
            fingerprintView.setCellFactory(this::getCellWithContextMenu);
            fingerprintView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

            this.getDialogPane().setContent(fingerprintView);
        }

        private ListCell<FingerprintState> getCellWithContextMenu(ListView<FingerprintState> view) {
            ListCell<FingerprintState> cell = new ListCell<FingerprintState>() {
                @Override
                protected void updateItem(FingerprintState item, boolean empty) {
                    super.updateItem(item, empty);

                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setText(item.getFingerprint().getHeader().getName());
                        setTooltip(new Tooltip(item.getFingerprint().getHeader().getDescription()));
                        MenuItem enableItem = new ActiveMenuItem("enable", event -> {
                            List<FingerprintState> toEnable = view.getSelectionModel().getSelectedItems().stream()
                                    .filter(state -> !enabledFingerprints.contains(state))
                                    .collect(Collectors.toList());
                            enabledFingerprints.addAll(toEnable);
                        });

                        enableItem.disableProperty().bind(new BooleanBinding() {
                            {
                                super.bind(enabledFingerprints, view.getSelectionModel().getSelectedItems());
                            }

                            @Override
                            protected boolean computeValue() {
                                return enabledFingerprints.containsAll(view.getSelectionModel().getSelectedItems());
                            }
                        });

                        MenuItem disableItem = new ActiveMenuItem("disable", event -> {
                            List<FingerprintState> toDisable = view.getSelectionModel().getSelectedItems().stream()
                                    .filter(state -> enabledFingerprints.contains(state))
                                    .collect(Collectors.toList());
                            enabledFingerprints.removeAll(toDisable);
                        });

                        disableItem.disableProperty().bind(new BooleanBinding() {
                            {
                                super.bind(enabledFingerprints, view.getSelectionModel().getSelectedItems());
                            }

                            @Override
                            protected boolean computeValue() {
                                return !view.getSelectionModel().getSelectedItems().stream()
                                        .filter(state -> enabledFingerprints.contains(state))
                                        .findAny()
                                        .isPresent();
                            }
                        });
                        setContextMenu(new ContextMenu(enableItem, disableItem));

                        Image checkImage = new Image(getClass().getResourceAsStream("/resources/images/microsoft/112_Tick_Green_64x64_72.png"));
                        ImageView enabledView = new ImageView();
                        enabledView.setFitHeight(16);
                        enabledView.setFitWidth(16);
                        enabledView.imageProperty().bind(new ObjectBinding<Image>() {
                            {
                                super.bind(enabledFingerprints);
                            }

                            @Override
                            protected Image computeValue() {
                                return enabledFingerprints.contains(item) ? checkImage : null;
                            }
                        });

                        setGraphic(enabledView);
                    }
                }
            };

            return cell;
        }

        private ArrayList<FingerprintState> handleButtonPress(ButtonType buttonPressed) {
            if (ButtonType.OK == buttonPressed) {
                return new ArrayList<>(this.enabledFingerprints);
            } else {
                return null;
            }
        }
    }

    private void initializeFingerprints() {
        Path userFingerprintPath =  this.getDefaultFingerprintDir();
        Path systemFingerprintPath = this.getSystemFingerprintDir();

        if (Files.notExists(userFingerprintPath)) {
            try {
                Files.createDirectory(userFingerprintPath);
            } catch (IOException e) {
                Logger.log(Logger.Severity.WARNING, "Unable to create non-existent user fingerprint directory " + userFingerprintPath);
            }
        }

        try {
            Files.list(userFingerprintPath).forEach(path -> {
                try {
                    document.registerFingerprint(document.load(path));
                } catch (final JAXBException | NullPointerException exc) {
                    Logger.log(Logger.Severity.WARNING, "Unable to load Fingerprint at " + path);
                }
            });
        } catch (final IOException ioe) {
            Logger.log(Logger.Severity.WARNING, "Unable to load user Fingerprints");
        }

        try {
            Files.list(systemFingerprintPath).forEach(path -> {
                try {
                    document.registerFingerprint(document.load(path));
                } catch (final JAXBException | NullPointerException exc) {
                    Logger.log(Logger.Severity.WARNING, "Unable to load Fingerprint at " + path);
                }
            });
        } catch (final IOException ioe) {
            Logger.log(Logger.Severity.WARNING, "Unable to load system Fingerprints");
        }
    }

    public Path getDefaultFingerprintDir() {
        String startDir = RuntimeConfiguration.getPersistedString(RuntimeConfiguration.PersistableFields.DIRECTORY_USER_DATA);

        return FileSystems.getDefault().getPath(startDir, "fingerprints");
    }

    public Path getSystemFingerprintDir() {
        URL classUrl = this.getClass().getResource("/" + this.getClass().getName().replace(".", "/") + ".class");
        String classPath = classUrl.toString();
        if (classPath.contains(".jar!")) {
            int stopIndex = classPath.indexOf(".jar!") + 4;
            String jarFile = classPath.substring(0, stopIndex);
            try {
                if (this.jarFileSystem == null) {
                    HashMap<String, String> props = new HashMap<>();
                    props.put("create", "false");
                    this.jarFileSystem = FileSystems.newFileSystem(new URI(jarFile), props);
                }
                Path fpDir = jarFileSystem.getPath("/fingerprints");
                return fpDir;
            } catch (URISyntaxException | IOException e) {
                Logger.log(Logger.Severity.ERROR, "Could not find default fingerprints, trying to load from " + jarFile);
            }
        } else {
            String appDir = RuntimeConfiguration.getPersistedString(RuntimeConfiguration.PersistableFields.DIRECTORY_APPLICATION);
            return Paths.get(appDir, "..", "embedded_resources", "resources", "fingerprints");
        }

        return null;
    }

    public FPDocument getDocument() {
        return this.document;
    }

    public BooleanProperty saveAsLeavesOldProperty() {
        return this.saveAsLeavesOld;
    }
}