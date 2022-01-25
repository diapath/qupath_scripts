/*
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

Script author: Egor Zindy,
for the interactive testing of the script Ki67-pipeline.groovy

Executing this script will create a new menu in which every step of the pipeline can be tested separately.

PLEASE NOTE, Ki67-pipeline.groovy must be stored in the script directory pointed to
in QuPath's Preferences -> Script directory

Details explained in https://forum.image.sc/t/splitting-a-qupath-groovy-script-across-multiple-files/61387

----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/

def tissueThreshold = 225
def minCells = 1000
def radiusMicrons = 330
def cellThreshold = 0.15
def cellThresholdPositive = 0.25

import qupath.lib.gui.prefs.PathPrefs

// A single ID for our menu (this is used so we can find the menu we add and delete it if needed).
def customId = "ki67_menu"

// Here we import the Ki67 pipeline. We will then have access to all the functions.
scriptsPath = PathPrefs.createPersistentPreference("scriptsPath", (String)null)
GroovyShell shell = new GroovyShell()
def pipeline = shell.parse(new File(scriptsPath.get(), 'Ki67-pipeline.groovy'))

def indexName(menulist,name) {
    ret = -1
    for (i=0 ; i<menulist.size(); i++) {
        if (menulist[i].getText() == name) {
            ret = i
            break
        }
    }
    return ret
}

def separatorIndex(menu) {
    items = menu.getItems()
    for (i=0 ; i<items.size(); i++) {
        println i
        item = items[i]
        //This is groovy, no need to check for the size apparently
        if (item.getStyleClass()[2] == 'separator-menu-item') {
            ret = i
            break
        }
    }
    return ret
}

def getImageData() {
    def imageData = QP.getCurrentImageData()

    if (imageData == null) {
        def gui = QuPathGUI.getInstance()

        if (gui != null) {
            def viewer = gui.getViewer()
            imageData = viewer.getImageData()
        }
    }
    return imageData
}

// Remove all the additions made to the toolbar based on the id above
def RemoveMenu(menuBar, id) {
    while(1) {
        hasElements = false
        menus = menuBar.getMenus()
        for (var menuItem : menus) {
            if (menuItem.getId() == id) {
                menus.remove(menuItem)
                hasElements = true
                break
            }
        }
        if (!hasElements) break
    }
}

Platform.runLater {
    menuTitle = "Ki-67 Analysis"
    
    def gui = QuPathGUI.getInstance()
    def viewer = gui.getViewer()
    def imageData = viewer.getImageData()
    def hierarchy = imageData.getHierarchy()
    println imageData

    menuBar = gui.menuBar
    RemoveMenu(menuBar, customId)
    
    Menu menu = new Menu(menuTitle);
    MenuItem menuItem1 = new MenuItem("Tissue detection");
    MenuItem menuItem2 = new MenuItem("Nuclei detection");
    MenuItem menuItem3 = new MenuItem("Import NDPA contours");    
    MenuItem menuItem4 = new MenuItem("Find hotspot in Tissue/ROIs or selected");
    MenuItem menuItem5 = new MenuItem("Parameters...");
    SeparatorMenuItem sep = new SeparatorMenuItem();
    
    menuItem1.setOnAction {
        QP.clearAllObjects();
        QP.createSelectAllObject(true);

        //hierarchy.clearAll();
        //hierarchy.getSelectionModel().clearSelection();

        pipeline.detect_tissue(imageData, 'BRIGHTFIELD_H_DAB', tissueThreshold)
        Dialogs.showInfoNotification(menuTitle, "Tissue detected!")

        hierarchy.fireHierarchyChangedEvent(this)
    }
    
    menuItem2.setOnAction {
        def currentObject = viewer.getSelectedObject()
        if (currentObject != null)
            Dialogs.showInfoNotification(menuTitle, "Detection only within selected contour!")

        pipeline.detect_positive(imageData, 'BRIGHTFIELD_H_DAB', cellThreshold, cellThresholdPositive, currentObject);
        Dialogs.showInfoNotification(menuTitle, "Nuclei detected!")
    }

    menuItem3.setOnAction {
        // Remove any existing ROI or TISSUE_COMBINED regions
        //def regions = hierarchy.getAnnotationObjects().findAll {it.getPathClass() == PathClassFactory.getPathClass("Region")}
        hierarchy.removeObjects(hierarchy.getAnnotationObjects().findAll{it.getName().startsWith("ROI")}, true)
        hierarchy.removeObjects(hierarchy.getAnnotationObjects().findAll{it.getName().startsWith("TISSUE_COMB")}, true)
        //hierarchy.removeObjects(regions, true)
        Dialogs.showInfoNotification(menuTitle, "Old regions removed!")

        // Import new contours
        pipeline.read_ndpa(imageData);
        pipeline.add_merged_rois(imageData, "ROI","Region")
        pipeline.add_merged_rois(imageData, "Tissue","Tissue")
        pipeline.shape_rois(imageData);
        Dialogs.showInfoNotification(menuTitle, "NDPA contours imported!")

        hierarchy.fireHierarchyChangedEvent(this)
    }

    menuItem4.setOnAction {
        def currentObject = viewer.getSelectedObject()
        if (currentObject != null)
            Dialogs.showInfoNotification(menuTitle, "Detection only within selected contour!")

        pipeline.add_hotspot(imageData, "ROI_COMBINED", "HSROI",radiusMicrons, minCells, 0, currentObject)

        //If nothing is selected, assume you want to test the pipeline (and test tissue combined)
        if (currentObject == null)
            pipeline.add_hotspot(imageData, "TISSUE_COMBINED", "HSTISSUE",radiusMicrons, minCells)
    }

    menuItem5.setOnAction {
        def params = new ParameterList()
            .addIntParameter("tissueThreshold", "Tissue Threshold", tissueThreshold, "", "the threshold value for tissue detection")
            .addDoubleParameter("cellThreshold", "Cell Threshold", cellThreshold, "", "the threshold value for cell detection")
            .addDoubleParameter("cellThresholdPositive", "Positive cell Threshold", cellThresholdPositive, "", "the threshold value for positive cell detection")
            .addIntParameter("minCells", "Minimum cell count", minCells, "cells", "Minimum number of cells in hotspot")
            .addDoubleParameter("radiusMicrons", "Distance between cells", radiusMicrons, GeneralTools.micrometerSymbol(), "Usually roughly the distance between positive cell centroids")

        if (!Dialogs.showParameterDialog("Global pipeline parameters", params))
            return

        tissueThreshold = params.getIntParameterValue("tissueThreshold")
        cellThreshold = params.getDoubleParameterValue("cellThreshold")
        cellThresholdPositive = params.getDoubleParameterValue("cellThresholdPositive")
        minCells = params.getIntParameterValue("minCells")
        radiusMicrons = params.getDoubleParameterValue("radiusMicrons")

        println("--- parameters ---")
        println("tissueThreshold = "+tissueThreshold)
        println("cellThreshold = "+cellThreshold)
        println("cellThresholdPositive = "+cellThresholdPositive)
        println("minCells = "+minCells)
        println("radiusMicrons = "+radiusMicrons)

        Dialogs.showInfoNotification(menuTitle, "New parameters registered!")
    }

    menu.getItems().add(menuItem1);
    menu.getItems().add(menuItem2);
    menu.getItems().add(menuItem3);
    menu.getItems().add(menuItem4);
    menu.getItems().add(sep);
    menu.getItems().add(menuItem5);

    menu.setId(customId)
    menus = menuBar.getMenus()
    
    // Add the menu before the "Measure" menu
    menus.add(indexName(menus,"Measure"),menu)
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

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;

import qupath.lib.plugins.parameters.ParameterList
import qupath.lib.scripting.QP
import qupath.lib.gui.scripting.QPEx
import qupath.lib.gui.QuPathGUI
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.roi.RoiTools
import qupath.lib.roi.RoiTools.CombineOp;
import qupath.lib.roi.interfaces.ROI;

import ij.plugin.filter.EDM
import ij.plugin.filter.RankFilters
import ij.process.Blitter
import ij.process.ByteProcessor
import ij.process.FloatProcessor

import qupath.imagej.processing.SimpleThresholding
import java.awt.Color
