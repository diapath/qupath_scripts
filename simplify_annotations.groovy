// a unique identifier to tag the items we will add to the toolbar
def btnId = "simplify-contours-btn"

//How much of a simplification do you need (larger = more simplified)
simplificationDistMicrons=50

// rocket borrowed from https://stackoverflow.com/questions/11537434/javafx-embedding-encoded-image-in-fxml-file
// resized, saved to a 32x32 gif with transparent background and converted using an online image to base64 encoder
String rocketImgStr = "R0lGODlhIAAgAIQAMQUCBASWvJkCBDw+PIyOjJTY2NDQz0QCBOTm5BwiJ0xHRoxKTHBqbPf492NRTy4GB/kCBPxKTLe3uEg0MJz5+IR6eAxeXPza3LsCBHICBKSkpDcsK9/f3xQaHND+/MLCwyH5BAEAAAAALAAAAAAgACAABAXoICCOZGmeaCoWmeqiDSS/tGhFMlS/zZXnu1Tsh9EFTYufrCg4ljDQqNQ5alivWCsVgEV4v1pqtht2YgebzaCBKAfXV4difnZqrJxNgNKQdAxsGk4fVhITfA0UExJsEk4cV2kGAQkJVgYGg1hpaVYIBo5Hd16ekKWCRwpsYJ4NBggOTh2rbVdthAliYHJXjE4QGbQNvLcNGUY0xxAeq8OqHx8GHkUPNTmIbbyYDQUyTTQQ1VwIkA4MfYAiB8gqD8gJHAQfkgyoIuwp7iRXFRUNkCTwpRCoguAJgygQlvhWowWNA0HEbTkSAgA7"

ByteArrayInputStream rocketInputStream = new ByteArrayInputStream(rocketImgStr.decodeBase64())
Image rocketImg = new Image(rocketInputStream,QuPathGUI.TOOLBAR_ICON_SIZE, QuPathGUI.TOOLBAR_ICON_SIZE, true, true)


// Remove all the additions made to the toolbar based on the id above
def RemoveToolItems(toolbar, id) {
    while(1) {
        hasElements = false
        for (var tbItem : toolbar.getItems()) {
            if (tbItem.getId() == id) {
                toolbar.getItems().remove(tbItem)
                hasElements = true
                break
            }
        }
        if (!hasElements) break
    }
}


def simplify_geometry(pathObject, dist) {
    def pathObjectROI = pathObject.getROI()
    def plane = pathObjectROI.getImagePlane()

    //Here we do some processing to simplify the outlines and remove small holes
    def geometry = pathObjectROI.getGeometry()
    geometry = TopologyPreservingSimplifier.simplify(geometry, dist);
    geometry = GeometryTools.refineAreas(geometry, 200, 200)

    pathObjectROI = GeometryTools.geometryToROI(geometry, plane)
    pathObject.setROI(pathObjectROI)
    return pathObject
}


def simplify_contours() {
    // Get selected objects
    def selectedObjects = getSelectedObjects()

    // Filter objects that have an ROI (contour)
    def objectsWithROI = selectedObjects.findAll { it.getROI() != null }
    

    selectedObjects.each{ pathObject ->
        simplify_geometry(pathObject, simplificationDistMicrons)        
    }
    fireHierarchyUpdate()

    Dialogs.showInfoNotification("Simplify contours", "Did simplify ${objectsWithROI.size()} annotations!")
}


Platform.runLater {
    gui = QuPathGUI.getInstance()
    toolbar = gui.getToolBar()

    // First we remove the items already in place    
    RemoveToolItems(toolbar,btnId)

    // Here we add a separator
    sepCustom = new Separator(Orientation.VERTICAL)
    sepCustom.setId(btnId)
    toolbar.getItems().add(sepCustom)    
        
    // Here we add a button
    btnCustom = new Button()
    btnCustom.setId(btnId)
    toolbar.getItems().add(btnCustom)
    
    // The button is given an icon encoded as base64 above
    ImageView imageView = new ImageView(rocketImg)
    btnCustom.setGraphic(imageView)
    btnCustom.setTooltip(new Tooltip("Simplify the selected contour(s)"))

    // This is a Toggle button, so need to know if the button was pressed or depressed
    btnCustom.setOnAction {
        simplify_contours()
    }
    
}

import javafx.application.Platform
import javafx.stage.Stage
import javafx.scene.Scene
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.geometry.Orientation
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.input.MouseEvent
import javafx.beans.value.ChangeListener
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import qupath.lib.gui.QuPathGUI
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import qupath.lib.scripting.QP
