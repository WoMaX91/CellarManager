package fr.antoninruan.cellarmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.antoninruan.cellarmanager.model.Bottle;
import fr.antoninruan.cellarmanager.model.Compartement;
import fr.antoninruan.cellarmanager.model.CompartementInfo;
import fr.antoninruan.cellarmanager.model.Spot;
import fr.antoninruan.cellarmanager.utils.DialogUtils;
import fr.antoninruan.cellarmanager.utils.PreferencesManager;
import fr.antoninruan.cellarmanager.utils.Saver;
import fr.antoninruan.cellarmanager.utils.Updater;
import fr.antoninruan.cellarmanager.utils.mobile_sync.MobileSyncManager;
import fr.antoninruan.cellarmanager.view.CompartementDisplayController;
import fr.antoninruan.cellarmanager.view.PreferencesController;
import fr.antoninruan.cellarmanager.view.RootLayoutController;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.*;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Antonin RUAN
 */
public class MainApp extends Application {

    //FIXME position du bouton pour ajouter une étagère peu intuitive

    //TODO bind les boutons importer / exporter à des actions.
    //TODO ajouter la possibilité de modifier le nombre de ligne/colonne d'une étagère
    //TODO pouvoir changer l'ordre des étagères.
    //TODO lorsque des bouteilles sont trouvés sur étagère différent de celle courante. Afficher quelque chose qui permettent de le voir
    //TODO ajouter un bind à une appli mobile pour afficher la bouteille séléctionner sur ordinateur sur un téléphone
    //TODO ajouter un plan global de la cave ou l'on puisse placer les étagères

    /*TODO Ajouter un menu de paramètre qui permette:
            Intégrér une gestion multilingue
            Gérer le délai du double clic
            Param de connection à GitHub
            Check des updates au lancement
     */

    private static Stage primaryStage;
    private static BorderPane rootLayout;
    private static RootLayoutController controller;

    private static VBox compartementDisplay;
    private static CompartementDisplayController compartementDisplayController;

    private static PreferencesController preferencesController;

    private static File openedFile = null;
    private static File bottleFile = null;
    private static final File preferences = new File("ma_cave.preference");
    public static final DateFormat GITHUB_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public static JsonObject PREFERENCE_JSON;
    public final static Image LOGO = new Image(MainApp.class.getClassLoader().getResource("img/logo.png").toString());

    private final static ObservableMap<Integer, Compartement> compartements = FXCollections.observableHashMap();
    private final static ObservableList<Spot> spots = FXCollections.observableArrayList();
    private final static ObservableMap <Integer, Bottle> bottles = FXCollections.observableHashMap();
    private static int lastBottleId;
    private static int lastCompartementId;

    public static void main(String... args) {
        System.setProperty("file.encoding", "UTF-8");

        if(!preferences.exists()) {

            try{
                preferences.createNewFile();
            } catch (IOException e) {
                DialogUtils.sendErrorWindow(e);
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(preferences));){
                writer.write("{\"check_update\": true}");
                writer.flush();
            } catch (IOException e) {
                DialogUtils.sendErrorWindow(e);
            }

        }

        try {
            PreferencesManager.loadPreferences(JsonParser.parseReader(new FileReader(preferences)).getAsJsonObject());
        } catch (FileNotFoundException e) {
            DialogUtils.sendErrorWindow(e);
        }

        launch(args);


    }

    public void start(Stage primaryStage) {
        MainApp.primaryStage = primaryStage;
        MainApp.primaryStage.setTitle("Cave");

        initRootLayout();
        //Récupération du fichier de sauvegarde

        boolean newFile = true;

        try {
            if(PreferencesManager.getSaveFilePath() != null) {
                newFile = false;
                openedFile = new File(PreferencesManager.getSaveFilePath());
            } else {
                openedFile = null;
                do {
                    try {
                        openedFile = noSaveFile();
                    } catch (URISyntaxException e) {
                        DialogUtils.sendErrorWindow(e);
                    }
                } while (openedFile == null);

                if(!openedFile.exists()) {
                    openedFile.createNewFile();
                    try(BufferedWriter writer = new BufferedWriter(new FileWriter(openedFile))) {
                        writer.write("{}");
                        writer.flush();
                    }
                    createNewCompartements(false);
                }
                PreferencesManager.setSaveFilePath(openedFile.getAbsolutePath());
            }

        }  catch (IOException e) {
            DialogUtils.sendErrorWindow(e);
        }

        //Récupération du fichier contenant l'ensemble des bouteilles

        if(PreferencesManager.getBottleFilePath() != null) {
            registerBottle(new File(PreferencesManager.getBottleFilePath()));
        } else {
            File file = new File(openedFile.getParent() + File.separator + "bottle_file.mcv");
            if(!file.exists()) {
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    DialogUtils.sendErrorWindow(e);
                }
                bottleFile = file;
                try(BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                    writer.write("{}");
                    writer.flush();
                } catch (IOException e) {
                    DialogUtils.sendErrorWindow(e);
                }
            } else {
                registerBottle(file);
            }
            PreferencesManager.setBottleFilePath(file.getAbsolutePath());
        }

        initCompartementDisplayLayout();

        if(!newFile) {
            openFile(openedFile);
        }

        MainApp.primaryStage.setTitle("Ma Cave - " + openedFile.getName());

        if(PreferencesManager.doCheckUpdateAtStart()) {
            boolean newUpdate = Updater.checkUpdate();
            if(newUpdate) {
                DialogUtils.updateAvailable(true);
            }
        }

    }

    public static void saveFiles() {
        PreferencesManager.savePreferences(preferences);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(bottleFile))){
            JsonObject bottlesJson = new JsonObject();
            if(bottles == null || bottles.isEmpty()) {
                writer.write("{}");
            } else {
                for(Bottle bottle : bottles.values()) {
                    bottlesJson.add(String.valueOf(bottle.getId()), bottle.toJson());
                }
                writer.write(bottlesJson.toString());
            }
            writer.flush();
        } catch (IOException e) {
            DialogUtils.sendErrorWindow(e);
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(openedFile))){
            JsonObject jsonObject = new JsonObject();
            if(compartements == null || compartements.isEmpty()) {
                writer.write("{}");
            } else {
                for(Compartement compartement : MainApp.compartements.values()) {
                    jsonObject.add(String.valueOf(compartement.getId()), compartement.toJson());
                }
                writer.write(jsonObject.toString());
            }
            writer.flush();
        } catch (IOException e) {
            DialogUtils.sendErrorWindow(e);
        }
    }

    public void stop() {
        if(MobileSyncManager.isActivate()) {
            MobileSyncManager.toggleState();
        }
        saveFiles();
        Saver.cancelTask();
    }

    private void initRootLayout() {

        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(MainApp.class.getClassLoader().getResource("fxml/RootLayout.fxml"));
            rootLayout = loader.load();

            controller = loader.getController();
            controller.setMainApp(this);

            Scene scene = new Scene(rootLayout);
            primaryStage.setScene(scene);
            primaryStage.setResizable(false);
            primaryStage.getIcons().add(LOGO);
            primaryStage.show();


        } catch (Exception e) {
            DialogUtils.sendErrorWindow(e);
        }

    }

    public void initCompartementDisplayLayout() {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(MainApp.class.getClassLoader().getResource("fxml/CompartementDisplayLayout.fxml"));

            VBox vBox = loader.load();
            rootLayout.setCenter(vBox);

            compartementDisplayController = loader.getController();

        } catch (IOException e) {
            DialogUtils.sendErrorWindow(e);
        }
    }

    private File noSaveFile() throws URISyntaxException {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Aucun fichier de sauvegarde détécté");
        alert.setHeaderText(null);
        alert.setContentText("Veuillez choisir le fichier pour sauvegarder votre cave");

        ((Stage) alert.getDialogPane().getScene().getWindow()).getIcons().add(LOGO);

        alert.showAndWait();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Choissisez un fichier pour sauvegarder votre cave");
        File currentJar = new File(MainApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        fileChooser.setInitialDirectory(currentJar.getParentFile());
        fileChooser.setInitialFileName("ma_cave");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Fichier MaCave", "*.mcv")
        );
        return fileChooser.showSaveDialog(primaryStage);
    }

    private void registerBottle(File file) {
        try {
            bottleFile = file;
            JsonObject bottles = JsonParser.parseReader(new FileReader(file)).getAsJsonObject();
            for(Map.Entry<String, JsonElement> entry : bottles.entrySet()) {
                int id = Integer.valueOf(entry.getKey());
                Bottle bottle = Bottle.fromJson(id, entry.getValue().getAsJsonObject());
                MainApp.bottles.put(id, bottle);
                if(MainApp.lastBottleId < id)
                    MainApp.lastBottleId = id;
            }
        } catch (FileNotFoundException e) {
            DialogUtils.sendErrorWindow(e);
        }
    }

    public void openFile(File file) {
        try {
            openedFile = file;
            JsonObject object = JsonParser.parseReader(new FileReader(file)).getAsJsonObject();

            for(Map.Entry<String, JsonElement> entry : object.entrySet()) {

                int id = Integer.valueOf(entry.getKey());
                Compartement compartement = Compartement.fromJson(entry.getValue().getAsJsonObject(), id);
                MainApp.compartements.put(id, compartement);
                if(MainApp.lastCompartementId < id)
                    MainApp.lastCompartementId = id;

            }

            lastCompartementId ++;

        } catch (FileNotFoundException e) {
            DialogUtils.sendErrorWindow(e);
        }
    }

    public void createNewCompartements(boolean cancelable) {

        Optional<CompartementInfo> result = DialogUtils.createNewCompartement(cancelable);

        result.ifPresent(compartementInfo -> {
            compartementInfo.createCompartement();
            Saver.doChange();
        });

    }

    public static File getOpenedFile() {
        return openedFile;
    }

    public static File getBottleFile() {
        return bottleFile;
    }

    public static ObservableMap <Integer, Compartement> getCompartements() {
        return compartements;
    }

    public static Compartement getCompartement(int index) {
        for(Compartement compartement : compartements.values()) {
            if(compartement.getIndex() == index) {
                return compartement;
            }
        }
        return null;
    }

    public static ObservableList <Spot> getSpots() {
        return spots;
    }

    public static ObservableMap<Integer, Bottle> getBottles() {
        return bottles;
    }

    public static RootLayoutController getController() {
        return controller;
    }

    public static CompartementDisplayController getCompartementDisplayController() {
        return compartementDisplayController;
    }

    public static PreferencesController getPreferencesController() {
        return preferencesController;
    }

    public static void setPreferencesController(PreferencesController preferencesController) {
        MainApp.preferencesController = preferencesController;
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static BorderPane getRootLayout() {
        return rootLayout;
    }

    public static JsonObject getPreferenceJson() {
        return PREFERENCE_JSON;
    }

    public static List<Spot> hasBottle(Bottle bottle) {
        List<Spot> spots = new ArrayList <>();

        for(Spot spot : MainApp.spots) {
            if (!spot.isEmpty()) {
                if (spot.getBottle().getId() == bottle.getId()) {
                    spots.add(spot);
                }
            }
        }
        return spots;
    }

    public static int nextBottleId() {
        return lastBottleId ++;
    }

    public static int nextCompartementId() {
        return lastCompartementId ++;
    }

}