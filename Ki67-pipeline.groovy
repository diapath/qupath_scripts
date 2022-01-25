/*
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

Script author: Egor Zindy,
Based in parts on scripts by Pierre Caron for DIAPath 2019.

Hotspot code by Pete Bankhead, additions by Mike Nelson
https://forum.image.sc/t/discussion-and-script-what-is-a-hotspot/34054

Executing the pipeline on a QuPath project image does the following operations:
  * Tissue detection
  * Importing the NDPA annotations for the image, with ROI the tumour region and ROI2 the hotspot region
  * Detecting and counting negatively and positively stained nuclei.
  * Hotspot search in the "ROI union ROI2" region

  * Automatically save and concatenate all the results in a single CSV file in:
      annotation_results/Combined_Results.csv 

This script can be run automatically on all the images of a project
                        
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/

def tissueThreshold = 225
def minCells = 1000
def radiusMicrons = 330

import qupath.lib.scripting.QP
import qupath.lib.gui.scripting.QPEx
import qupath.lib.regions.RegionRequest
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.objects.PathObjectTools
import qupath.lib.objects.classes.PathClassTools
import qupath.lib.regions.ImagePlane
import qupath.lib.objects.PathObjects
import qupath.lib.objects.PathAnnotationObject

import qupath.lib.roi.*
import qupath.lib.roi.RoiTools
import qupath.lib.roi.RoiTools.CombineOp;
import qupath.lib.roi.interfaces.ROI;

import qupath.lib.geom.Point2
import qupath.lib.images.servers.ImageServer

import qupath.lib.common.GeneralTools
import qupath.lib.common.ColorTools;

import java.io.*
import java.awt.*
import java.awt.image.BufferedImage
import static java.awt.RenderingHints.*
import javax.imageio.ImageIO

import ij.plugin.filter.EDM
import ij.plugin.filter.RankFilters
import ij.process.Blitter
import ij.process.ByteProcessor
import ij.process.FloatProcessor
import qupath.imagej.processing.SimpleThresholding
import qupath.lib.objects.classes.PathClass
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.plugins.parameters.ParameterList
import qupath.lib.scripting.QP
import qupath.lib.objects.PathDetectionObject


import java.awt.Color

// https://groups.google.com/forum/#!searchin/qupath-users/ndpa%7Csort:date/qupath-users/xhCx_nhbWQQ/QoUOQB24CQAJ
// Script to find highest density of positive cells in QuPath v0.2.0-m5.
// Assumes cell detection has already been calculated.
// Algorithm runs at a specified resolution (here 20 um/px).

/*     
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

                        Combine two regions into one (ROI union ROI2 -> ROI_COMBINED)
                        
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/

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

def add_merged_rois(imageData, parentPrefix, parentClass) {
    def hierarchy = imageData.getHierarchy()

    // Here we do the union of ALL the ROI and ROI2, that's probably the easiest way
    def regions = hierarchy.getAnnotationObjects().findAll {it.getPathClass() == PathClassFactory.getPathClass(parentClass)}

    def roiNew = null
    for (regionPath in regions) {
        //println("Merging... "+regionPath.getName())
        if (regionPath.getName().startsWith(parentPrefix)) {
            if (roiNew == null)
                roiNew = regionPath.getROI()
            else
                roiNew = RoiTools.combineROIs(regionPath.getROI(), roiNew, CombineOp.ADD);
        }
    }

    def annotation = new PathAnnotationObject(roiNew)
    annotation.setName(parentPrefix.toUpperCase()+"_COMBINED")
    annotation.setPathClass(PathClassFactory.getPathClass(parentClass))
    hierarchy.addPathObject(annotation) //, false)
}


/*     
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

                        Automatic hotspot detection

Based on code by Pete Bankhead @petebankhead and clean-up by Mike Nelson @Mike_Nelson
https://forum.image.sc/t/find-highest-staining-region-of-a-slide/31251/6
https://forum.image.sc/t/discussion-and-script-what-is-a-hotspot/34054

----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/

def add_hotspot(imageData, String parentName, String hsName, double radiusMicrons=330.0, int minCells=1000, int minNegCells=0, PathAnnotationObject selectedROI=null) {
    double pixelSizeMicrons=20.0
    boolean tumorOnly=false

    def hierarchy = imageData.getHierarchy()

    def hasHotspot = false

    //remove hotspots
    hierarchy.removeObjects(hierarchy.getAnnotationObjects().findAll{it.getName().startsWith(hsName)},true)

    //Find the tissue with the most positive cells
    for (parent in hierarchy.getAnnotationObjects()) {
        if (selectedROI == null)
        {
            if (parentName == "" && !parent.getName().startsWith("Tissue"))
                continue
            else if (parentName != "" && parent.getName() != parentName)
                continue
        } else {
            if (parent != selectedROI)
                continue
        }

        def cells = hierarchy.getObjectsForROI(null, parent.getROI()).findAll { it.isDetection() }
        def pcells = cells.findAll {it.getPathClass() == PathClassFactory.getPathClass("Positive")}

        if (cells.size < minCells)
            continue

        QP.logger.info("Searching for a hotspot in {} - {} cells (of which {} positive)",parent.getName(),cells.size(), pcells.size())

        hasHotspot = true
        def filterClass = null
        def server = imageData.getServer()

        double downsample = pixelSizeMicrons / server.getPixelCalibration().getAveragedPixelSizeMicrons()
        int w = Math.ceil(server.getWidth() / downsample)
        int h = Math.ceil(server.getHeight() / downsample)

        // Create centroid map
        def fpNegative = new FloatProcessor(w, h)
        def fpPositive = new FloatProcessor(w, h)
        def unknownClasses = new HashSet<PathClass>()
        for (cell in cells) {
            def roi = PathObjectTools.getROI(cell, true)
            if (roi.isEmpty())
                continue
            def pathClass = cell.getPathClass()
            if (pathClass == null || (filterClass != null && !pathClass.isDerivedFrom(filterClass)))
                continue
            int x = (roi.getCentroidX() / downsample) as int
            int y = (roi.getCentroidY() / downsample) as int
            if (PathClassTools.isPositiveClass(pathClass))
                fpPositive.setf(x, y, fpPositive.getf(x, y) + 1f as float)
            else if (PathClassTools.isNegativeClass(pathClass))
                fpNegative.setf(x, y, fpNegative.getf(x, y) + 1f as float)
            else
                unknownClasses.add(pathClass)
        }
        if (!unknownClasses.isEmpty())
            print('Unknown classes: ' + unknownClasses)

        // Compute sum of filter elements
        def rf = new RankFilters()
        double radius = radiusMicrons / pixelSizeMicrons
        int dim = Math.ceil(radius * 2 + 5)
        def fpTemp = new FloatProcessor(dim, dim)
        fpTemp.setf(dim/2 as int, dim/2 as int, radius * radius as float)
        rf.rank(fpTemp, radius, RankFilters.MEAN)
        def pixels = fpTemp.getPixels() as float[]
        double n = Arrays.stream(pixels).filter({f -> f > 0}).count()

        // Compute sums
        rf.rank(fpPositive, radius, RankFilters.MEAN)
        fpPositive.multiply(n)
        rf.rank(fpNegative, radius, RankFilters.MEAN)
        fpNegative.multiply(n)

        // Update valid mask
        ByteProcessor bpValid
        def annotations = hierarchy.getAnnotationObjects()
        if (annotations) {
            def imgMask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
            def g2d = imgMask.createGraphics()
            g2d.scale(1.0/downsample, 1.0/downsample)
            g2d.setColor(Color.WHITE)
            for (annotation in annotations) {
                def shape = annotation.getROI().getShape()
                g2d.fill(shape)
            }
            g2d.dispose()
            bpValid = new ByteProcessor(imgMask)
            bpValid = SimpleThresholding.thresholdAbove(new EDM().makeFloatEDM(bpValid, 0, true), radius as float)
        }

        // Compute local densities
        def fpDensity = new FloatProcessor(w, h)
        def fpDensityAll = new FloatProcessor(w, h)
        def fpSum = fpNegative.duplicate()
        fpSum.copyBits(fpPositive,0, 0, Blitter.ADD)
        int nPixels = w * h
        float maxDensity = Float.NEGATIVE_INFINITY
        int maxInd = -1
        int maxCells = 0
        for (int i = 0; i < nPixels; i++) {
            float total = fpSum.getf(i)
            if (total == 0f)
                continue
            if (bpValid.getf(i) == 0f)
                continue
            float density = fpPositive.getf(i) / total
            fpDensityAll.setf(i, density)
            if (total >= minCells && fpNegative.getf(i) >= minNegCells) {
                fpDensity.setf(i, density)
                if (density > maxDensity) {
                    maxDensity = density
                    maxInd = i
                }
            }
            if (total > maxCells) maxCells = total;
        }
        if (maxInd < 0) {
            println parent.getName() + ': No region found! Max total cells='+maxCells
            continue
        }

        double x = downsample * (maxInd % w)
        double y = downsample * (maxInd / w)

        double fullRadius = radiusMicrons / server.getPixelCalibration().getAveragedPixelSizeMicrons()
        def roi = ROIs.createEllipseROI(x - fullRadius, y - fullRadius, fullRadius*2, fullRadius*2, ImagePlane.getDefaultPlane())
        def hotspot = PathObjects.createAnnotationObject(roi)

        if (selectedROI != null) 
            hotspot.setName(hsName+"_"+selectedROI.getName())
        else if (parentName == "")
            hotspot.setName(parent.getName().replace("Tissue",hsName))
        else
            hotspot.setName(parent.getName().replace(parentName,hsName))

        hotspot.setPathClass(PathClassFactory.getPathClass("Region"))
        hotspot.setColorRGB(ColorTools.makeRGB(0, 255, 0))
        hierarchy.addPathObject(hotspot)

        cells = hierarchy.getObjectsForROI(null, hotspot.getROI()).findAll { it.isDetection() }
        pcells = cells.findAll {it.getPathClass() == PathClassFactory.getPathClass("Positive")}
        ncells = cells.findAll {it.getPathClass() == PathClassFactory.getPathClass("Negative")}

        QP.logger.info("Parent class:{} - found {} cells, {} positive, {} negative", parent.getName(), cells.size(), pcells.size(), ncells.size())
        return hasHotspot
    }
    //new ImagePlus("Density", fpDensity).show()
}

// it is possible to start with a larger radius hotspot and decrease its size until a hotspot is found.
def add_adaptative_hotspot(imageData, radiusMicrons=[1000, 500, 330], minCellList=[1000,500,500]) {
    hasHSR = false
    hasHST = false

    // The idea here is, can we find a large hotspot first (hasHSR becomes true) and
    // if not, look for hotspots of smallest size.
    [minCellList,radiusMicrons].transpose().each { m,r ->
        if (!hasHSR)
            hasHSR = add_hotspot(imageData, "ROI_COMBINED","HSROI",r, m) ? true : false
        if (!hasHST)
            hasHST = add_hotspot(imageData, "TISSUE_COMBINED","HSTISSUE",r, m) ? true : false
    }
}


/*     
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

                        Automatic tissue detection
                        
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/

def detect_tissue(imageData, imageType='BRIGHTFIELD_H_DAB',threshold=220)
{
    QP.logger.info("detecting tissue {} {} {}",imageData, imageType, threshold);
    QP.setImageType(imageType);
    QP.setColorDeconvolutionStains('{"Name" : "H-DAB default", "Stain 1" : "Hematoxylin", "Values 1" : "0.767 0.570 0.288 ", "Stain 2" : "DAB", "Values 2" : "0.243 0.490 0.833 ", "Background" : " 255 255 255 "}');

    QP.runPlugin('qupath.imagej.detect.tissue.SimpleTissueDetection2', imageData, '{"threshold": '+threshold+',  "requestedPixelSizeMicrons": 20.0,  "minAreaMicrons": 10000,  "maxHoleAreaMicrons": 1000000.0,  "darkBackground": false,  "smoothImage": true,  "medianCleanup": true,  "dilateBoundaries": false,  "smoothCoordinates": true,  "excludeOnBoundary": false,  "singleAnnotation": false}');

    def double areaMax = 0
    def hierarchy = imageData.getHierarchy()

    for (annotation in hierarchy.getAnnotationObjects()) {
        roi = annotation.getROI()  
        area = roi.getArea()
        if (area > areaMax){
            areaMax = area
        } 
    }

    //Minimum area is 5% of areaMax
    areaMin = Math.round(areaMax * .05)

    def int i = 1
    for (annotation in hierarchy.getAnnotationObjects()) {
        roi = annotation.getROI()  
        area = roi.getArea()
        if (area >= areaMin){
            annotation.setName("Tissue"+i)
            annotation.setPathClass(PathClassFactory.getPathClass("Tissue"))
            i += 1
        }
        else {
            hierarchy.removeObject(annotation,true)
        }  
    }

    hierarchy.getSelectionModel().setSelectedObject(null);
}

/* 
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

              Automatic nuclei detection using QuPath's cell detection method
    
             Detection counts are recorded in a text file in the project directory

----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/

def detect_positive(imageData, imageType='BRIGHTFIELD_H_DAB')
{
    //Parametres positive cell detection
    def requestedPixelSizeMicrons = 0.5
    def backgroundRadiusMicrons = 8.0
    def medianRadiusMicrons = 0.0
    def sigmaMicrons = 1.5
    def minAreaMicrons = 10.0
    def maxAreaMicrons = 400.0
    def threshold = 0.15
    def thresholdPositive1 = 0.25


    QP.logger.info("detecting positive {} {}",imageData, imageType);
    QP.setImageType(imageType)

    def hierarchy = imageData.getHierarchy()
    def tissues = hierarchy.getAnnotationObjects().findAll {it.getPathClass() == PathClassFactory.getPathClass("Tissue")}

    //First, detect for each tissues...
    tissues.each{
        hierarchy.getSelectionModel().setSelectedObject(it)
        QP.runPlugin('qupath.imagej.detect.cells.PositiveCellDetection', imageData, '{"detectionImageBrightfield": "Optical density sum",  "requestedPixelSizeMicrons": '+requestedPixelSizeMicrons+',  "backgroundRadiusMicrons": '+backgroundRadiusMicrons+',  "medianRadiusMicrons": '+medianRadiusMicrons+',  "sigmaMicrons": '+sigmaMicrons+',  "minAreaMicrons": '+minAreaMicrons+',  "maxAreaMicrons": '+maxAreaMicrons+',  "threshold": '+threshold+',  "maxBackground": 2.0,  "watershedPostProcess": true,  "excludeDAB": false,  "cellExpansionMicrons": 5.0,  "includeNuclei": true,  "smoothBoundaries": true,  "makeMeasurements": true,  "thresholdCompartment": "Nucleus: DAB OD mean",  "thresholdPositive1": '+thresholdPositive1+',  "thresholdPositive2": 0.4,  "thresholdPositive3": 0.6,  "singleThreshold": true}');
    }

    //Deselect everything
    hierarchy.getSelectionModel().setSelectedObject(null);
}

/* 
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

              NDPA annotations import
    
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/
def convertPointMicrons(point, pixelsPerMicron_X=1000, pixelsPerMicron_Y=1000 ) {
    point.x = point.x /1000 * pixelsPerMicron_X  + X_Reference /1000 * pixelsPerMicron_X 
    point.y = point.y /1000 * pixelsPerMicron_Y + Y_Reference /1000 * pixelsPerMicron_Y 
}

//Here we attempt to recover some possible class names by which the annotations are called.
def sanitizeName(details) {
    def ret = details.trim()
    
    while (ret.endsWith(".")) {
        ret = ret.substring(0, ret.length() - 1)
    }
    return ret
}

def convertName(details) {
    def ret = details.trim().toLowerCase()

    if (ret.startsWith("roi") || ret.startsWith("zoi")) {
        ret = "Region"
        return ret
    }
    
    while (ret.endsWith(".")) {
        ret = ret.substring(0, ret.length() - 1)
    }
    

    switch(ret) {
        case "tumour" : case "tumeur":
            ret = "tumor"
            break
        case "ignorer":
            ret = "ignore"
            break
        case "necrose":
            ret = "necrosis"
            break
        case "autre":
            ret = "other"
            break
    }
    return ret.capitalize()
}
    
def read_ndpa(imageData)
{
    def server = imageData.getServer()

    // We need the pixel size
    def cal = server.getPixelCalibration()
    if (!cal.hasPixelSizeMicrons()) {
        Dialogs.showMessageDialog("Metadata check", "No pixel information for this image!");
        return
    }

    // Here we get the pixel size
    def md = server.getMetadata()
    def pixelsPerMicron_X = 1 / cal.getPixelWidthMicrons() //md["pixelWidthMicrons"]
    def pixelsPerMicron_Y = 1 / cal.getPixelHeightMicrons() //md["pixelHeightMicrons"]

    //Aperio Image Scope displays images in a different orientation
    //TODO Is this in the metadatata? Is this likely to be a problem?
    //print(server.dumpMetadata())
    def rotated = false

    def h = server.getHeight()
    def w = server.getWidth()
    def ImageCenter_X = (w/2)*1000/pixelsPerMicron_X
    def ImageCenter_Y = (h/2)*1000/pixelsPerMicron_Y 

    // need to add annotations to hierarchy so qupath sees them
    def hierarchy = imageData.getHierarchy()
        
    //*********Get NDPA automatically based on naming scheme 
    def path = GeneralTools.toPath(server.getURIs()[0]).toString()+".ndpa";

    def NDPAfile = new File(path)
    if (!NDPAfile.exists()) {
        println "No NDPA file for this image..."
        return
    }

    //Get X Reference from OPENSLIDE data
    //The Open slide numbers are actually offset from IMAGE center (not physical slide center). 
    //This is annoying, but you can calculate the value you need -- Offset from top left in Nanometers. 

    def map = server.osr.getProperties()
    map.each { k, v ->
        if(k.equals("hamamatsu.XOffsetFromSlideCentre")) {
            OffSet_From_Image_Center_X = v
            OffSet_From_Top_Left_X = ImageCenter_X.toDouble() - OffSet_From_Image_Center_X.toDouble()
            X_Reference =  OffSet_From_Top_Left_X
        }
        if(k.equals("hamamatsu.YOffsetFromSlideCentre")) {
            OffSet_From_Image_Center_Y = v
            OffSet_From_Top_Left_Y = ImageCenter_Y.toDouble() - OffSet_From_Image_Center_Y.toDouble() 
            Y_Reference =  OffSet_From_Top_Left_Y
        }
    }


    //Read files
    def text = NDPAfile.getText()
    def list = new XmlSlurper().parseText(text)

    list.ndpviewstate.each { ndpviewstate ->
        def annotationName = sanitizeName(ndpviewstate.title.toString())
        def annotationClassName = convertName(annotationName)
        def annotationType = ndpviewstate.annotation.@type.toString().toUpperCase()
        def annotationColor = ndpviewstate.annotation.@color.toString().toUpperCase()
        def details = ndpviewstate.details.toString()
    
        roi = null
        
        if (annotationType == "CIRCLE") {
            //special case
            def X = ndpviewstate.annotation.x.toDouble()
            def Y = ndpviewstate.annotation.y.toDouble()
            def point = new Point2(X, Y)
            convertPointMicrons(point, pixelsPerMicron_X, pixelsPerMicron_Y)

            def rx = ndpviewstate.annotation.radius.toDouble() / 1000 * pixelsPerMicron_X
            def ry = ndpviewstate.annotation.radius.toDouble() / 1000 * pixelsPerMicron_Y
            roi = new EllipseROI(point.x-rx,point.y-ry,rx*2,ry*2,null);
        }
        
        if (annotationType == "LINEARMEASURE") {
            //special case
            def X = ndpviewstate.annotation.x1.toDouble()
            def Y = ndpviewstate.annotation.y1.toDouble()
            def pt1 = new Point2(X, Y)
            convertPointMicrons(pt1, pixelsPerMicron_X, pixelsPerMicron_Y)
            X = ndpviewstate.annotation.x2.toDouble()
            Y = ndpviewstate.annotation.y2.toDouble()
            def pt2 = new Point2(X, Y)
            convertPointMicrons(pt2, pixelsPerMicron_X, pixelsPerMicron_Y)
            roi = new LineROI(pt1.x,pt1.y,pt2.x,pt2.y);
        }

        if (annotationType == "PIN") {
            def X = ndpviewstate.annotation.x.toDouble()
            def Y = ndpviewstate.annotation.y.toDouble()
            def point = new Point2(X, Y)
            convertPointMicrons(point, pixelsPerMicron_X, pixelsPerMicron_Y)
            roi = new PointsROI(point.x,point.y);
        }
            
        // All that's left if FREEHAND which handles polygons, polylines, rectangles
        ndpviewstate.annotation.pointlist.each { pointlist ->
            def tmp_points_list = []
            pointlist.point.each{ point ->
                if (rotated) {
                    X = point.x.toDouble()
                    Y = h - point.y.toDouble()
                } else {
                    X = point.x.toDouble()
                    Y =  point.y.toDouble()
                }
                tmp_points_list.add(new Point2(X, Y))
            } 
    
            //Adjust each point relative to SLIDECENTER coordinates and adjust for pixelsPerMicron
            for ( point in tmp_points_list) {
                    convertPointMicrons(point, pixelsPerMicron_X, pixelsPerMicron_Y)
            }
            
            if (annotationType == "FREEHAND") {
                isClosed = 1 //ndpviewstate.annotation.closed.toBoolean() //XXX PLEASE PLEASE PLEASE USE THE POLYGON TOOL!!!!!
                isRectangle = (ndpviewstate.annotation.specialtype.toString() == "rectangle")
                if (isRectangle) {
                    x1 = tmp_points_list[0].x
                    y1 = tmp_points_list[0].y
                    x3 = tmp_points_list[2].x
                    y3 = tmp_points_list[2].y
                    roi = new RectangleROI(x1,y1,x3-x1,y3-y1);
                }
                else if (isClosed)
                    roi = new PolygonROI(tmp_points_list);
                else
                    roi = new PolylineROI(tmp_points_list, null);
            }
            
        }
        
        if (roi != null) {
            def annotation = new PathAnnotationObject(roi)

            //XXX Here we deal with lack of annotation name. Yup, identifying annotations by their colour is fine, I can do color :-(
            if (annotationColor == "#000000")
                annotationName = "ROI"
            else if (annotationColor == "#FF0000")
                annotationName = "ROI2"
                
            annotation.setName(annotationName)
            
            if (annotationClassName) {
                //TODO (validate) and add the new class if it doesn't already exist:
                //if (!PathClassFactory.classExists(annotationClassName))
                annotation.setPathClass(PathClassFactory.getPathClass(annotationClassName))
            }
            
            if (details) {          
                annotation.setDescription(details)
            }

            annotation.setLocked(true)
            hierarchy.addPathObject(annotation) //, false)
        }

    }

}

/* 
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

                              Intersect the regions of interest with the tissue region
    
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/

def shape_rois(imageData) {
    // need to add annotations to hierarchy so qupath sees them
    def hierarchy = imageData.getHierarchy()

    //https://gist.github.com/Svidro/5829ba53f927e79bb6e370a6a6747cfd#file-force-update-selected-annotation-groovy
    def tissues = hierarchy.getAnnotationObjects().findAll {it.getName() == "TISSUE_COMBINED"}
    def regions = hierarchy.getAnnotationObjects().findAll {it.getPathClass() == PathClassFactory.getPathClass("Region")}

    for (tissuePath in tissues) {
        for (regionPath in regions) {
            ROI roiNew = RoiTools.combineROIs(tissuePath.getROI(), regionPath.getROI(), CombineOp.INTERSECT);
            if (roiNew.getArea() > 0) {
                def annotation = new PathAnnotationObject(roiNew)
                annotation.setName(regionPath.getName())
                annotation.setPathClass(PathClassFactory.getPathClass("Region"))
                tissuePath.addPathObject(annotation) //, false) //An event is fired each time a new object is added
                hierarchy.fireHierarchyChangedEvent(this, annotation)
            } else {
                //println "Empty intersection betweeen "+tissuePath.getName()+" and "+regionPath.getName()
            }
        }
    }

    //Remove the original
    for (regionPath in regions) {
        hierarchy.removeObject(regionPath, true)
    }

    for (regionPath in hierarchy.getAnnotationObjects().findAll {it.getName().contains("ROI ")} ) {
        regionPath.setColorRGB(ColorTools.makeRGB(0, 0, 0))
    }

    for (regionPath in hierarchy.getAnnotationObjects().findAll {it.getName().contains("ROI2")} ) {
        regionPath.setColorRGB(ColorTools.makeRGB(255, 0, 0))
    }

}

/* 
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

                         Merge results for all the images in a project into a single CSV file

----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/

def result_merger(String outputName = 'Combined_results.csv', boolean addTissue = true, boolean addHotspot = true, boolean addROI = false, boolean addROI2 = false) {
    // Image	Name	Class	Parent	ROI	Centroid X µm	Centroid Y µm	Num Detections	Num Negative	Num Positive	Positive %	Num Positive per mm^2	Area µm^2	Perimeter µm

    String ext = '.txt' // Result files have a .txt extension
    String delimiter = '\t' // tab-delimitation
    String outDelimiter = ','

    def dirResults = new File(buildFilePath(PROJECT_BASE_DIR, 'annotation_results'))
    if(!dirResults.exists()) {
        dirResults.mkdirs()
    }

    def fileResults = new File(dirResults, outputName)    //création du fichier contenant la fusion des résultats 

    // Make a list of all the files to be merged
    def files = dirResults.listFiles({
        File f -> f.isFile() &&
                f.getName().toLowerCase().endsWith(ext) &&
                f.getName() != outputName} as FileFilter)
    if (files.size() < 1) {
        println 'At least one results file needed to merge!'
        return
    } else
        println 'Will try to merge ' + files.size() + ' files'

    def results = [:]
    def outputColumns = new LinkedHashSet<String>()

    // Adding the object columns
    def objectNames = []
    if (addTissue) objectNames.add("Tissue")
    if (addROI) objectNames.add("ROI")
    if (addROI2) objectNames.add("ROI2")
    if (addHotspot) objectNames.add("HSROI")
    if (addHotspot) objectNames.add("HSTISSUE")

    objectNames.each() {
        outputColumns.add(it + ' Num Detections')
        outputColumns.add(it + ' Num Negative')
        outputColumns.add(it + ' Num Positive')
        outputColumns.add(it + ' Positive %')
        outputColumns.add(it + ' Area µm^2')
    }

    for (file in files) {
        // Check whether the file is empty
        def lines = file.readLines()
        if (lines.size() <= 1) {
            println 'No results found in ' + file
            continue
        }
        // Save the column headers
        def iter = lines.iterator()
        def columns = iter.next().split(delimiter)

        def map = [:]
        def imageName = ""

        while (iter.hasNext()) {
            def line = iter.next()
            if (line.isEmpty())
                continue

            line = line.replaceAll("ROI 2", "ROI2").replaceAll("ROI 1", "ROI")

            def values = line.split(delimiter)

            // Check the number of columns
            if (values.size() != columns.size()) {
                println String.format('Number of entries (%d) does not match the number of columns (%d)!', columns.size(), values.size())
                println 'I will stop processing ' + file.getName()
                break
            }

            // Set the image name (same throughout the file)
            imageName = values[0]

            // Name is 2nd column (could be XXX in tissue X, so need to split spaces)
            def name = values[1].split(" ")[0]

            // Fix names for Hotspot and Tissue
            if (name.startsWith("Hotspot")) name = "Hotspot";
            else if (name.startsWith("Tissue")) name = "Tissue";

            // Sort the results
            if (name == "Hotspot" || name == "HSROI" || name == "HSTISSUE") {
                def outputCol = name+" "+columns[10]
                curDensity = map[outputCol]
                newDensity =  Float.parseFloat(values[10])

                if (newDensity > curDensity) {
                    for (int i = 6; i < columns.size()-1; i++)
                    {
                        outputCol = name+" "+columns[i]
                        if (map[outputCol] == null) map[outputCol] = 0.0
                        map[outputCol] = Float.parseFloat(values[i])
                    }
                }
            } else {
                for (int i = 6; i < columns.size()-1; i++)
                {
                    def outputCol = name+" "+columns[i]
                    if (map[outputCol] == null) map[outputCol] = 0.0
                    map[outputCol] += Float.parseFloat(values[i])
                }
            }
        }

        // Fix these entries
        objectNames.each {
            def ntot = map[it+' Num Detections']
            def npos = map[it+' Num Positive'] 
            def nneg = map[it+' Num Negative'] 
            def area = map[it+' Area µm^2'] 
            if (area == null) {
                ntot = 0
                npos = 0
                nneg = 0
                area = 0
            }
            map[it+' Positive %'] = (ntot == 0)? 0:npos/ntot
            map[it+' Num Positive per mm^2'] = (area == 0)?0:1E6*npos/area
        }

        results[imageName] = map
    }

    int count = 0
    fileResults.withPrintWriter {
        def header = "Image"+outDelimiter+String.join(outDelimiter, outputColumns)
        it.println(header)

        // Add the results, blanks for missing values
        for (def key in results.keySet()) {
            // The image name
            def map = results[key]
            it.print(key+outDelimiter)

            for (column in outputColumns) {
                // Generate appropriate strings depending on the column
                def s = ""
                def v = map[column]
                if (v == null) v=0

                if (column.contains("Num") && !column.contains("per"))
                    s = Integer.toString((int)v)
                else
                    s = Double.toString(v)

                it.print(s)
                it.print(outDelimiter)
            }
            it.println()
            count++
        }
    }

    println 'Merging done! ' + count + ' result(s) written to ' + fileResults.getAbsolutePath()
}

/*
----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*

                         Main section -- Calling the pipeline functions

----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*----*
*/

def clearAll = true;
def addTissue = true;
def addROI = true;
def addHotspot = true;

String outputName = 'Combined_results.csv'
def imageData = getCurrentImageData()
def hierarchy = imageData.getHierarchy()

//In case the only thing tested is the hotspot, no need to clear everything
if (clearAll) {
    clearAllObjects();
    createSelectAllObject(true);

    //detect tissue and cells
    detect_tissue(imageData, 'BRIGHTFIELD_H_DAB', tissueThreshold);
    detect_positive(imageData);
} else {
    hierarchy.removeObjects(hierarchy.getAnnotationObjects().findAll{it.getROI() instanceof EllipseROI},true)
}

if (addROI) {
    hierarchy.removeObjects(hierarchy.getAnnotationObjects().findAll{it.getName().startsWith("ROI")}, true)
    hierarchy.removeObjects(hierarchy.getAnnotationObjects().findAll{it.getName().startsWith("TISSUE")}, true)
    read_ndpa(imageData);
    add_merged_rois(imageData, "ROI","Region")
    add_merged_rois(imageData, "Tissue","Tissue")
    shape_rois(imageData);
}

if (addHotspot) {
    add_hotspot(imageData, "ROI_COMBINED", "HSROI",radiusMicrons, minCells)
    add_hotspot(imageData, "TISSUE_COMBINED", "HSTISSUE",radiusMicrons, minCells)
}

// Saving the annotations
def namefile = QP.getProjectEntry().getImageName() + '.txt'
def path = buildFilePath(PROJECT_BASE_DIR, 'annotation_results')
mkdirs(path)

path = buildFilePath(path, namefile)
saveAnnotationMeasurements(path)
println 'Results exported to ' + path  

// Merge all the results from all the images in the project
result_merger(outputName, addTissue, addHotspot, addROI, addROI);

// https://github.com/qupath/qupath/issues/617
// Add this line to save your changes to the project
//getProjectEntry().saveImageData(getCurrentImageData())
